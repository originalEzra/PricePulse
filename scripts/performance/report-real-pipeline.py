#!/usr/bin/env python3
"""Validate ignored raw samples, publish compact synthetic summaries only."""
import argparse
import csv
import gzip
import hashlib
import json
import math
import re
from collections import Counter
from pathlib import Path


def primary_table(sql):
    text = re.sub(r'\s+', ' ', sql.replace('`', '').strip().lower())
    operation = text.split(' ', 1)[0]
    match = re.search(r'(?:^update |^insert into |^delete from |\bfrom )([a-z_]+)', text)
    return f'{operation}:{match[1]}' if match else None


def server_delta(before, after):
    def counts(snapshot):
        result = Counter()
        for row in snapshot['statements']:
            key = primary_table(row['DIGEST_TEXT'])
            if key and key.split(':', 1)[0] in ('select', 'insert', 'update', 'delete'):
                result[key] += int(row['COUNT_STAR'])
        return result
    start, end = counts(before), counts(after)
    return {k: end[k] - start[k] for k in sorted(set(start) | set(end)) if end[k] != start[k]}


def validate(directory):
    data = json.loads((directory / 'summary.json').read_text())
    assert data['complete'], f'incomplete run: {directory}'
    artifacts, cases = [], []
    for number, case in enumerate(data['cases'], 1):
        file = directory / f'samples-{number}.csv.gz'
        with gzip.open(file, 'rt') as handle:
            samples = list(csv.DictReader(handle))
        assert len(samples) == case['inputEvents']
        assert case['oracle']['mismatches'] == 0
        assert case['failedTasks'] == 0, case['id']
        finished = [s for s in samples if s['completed_nanos'] != '0']
        dropped = [s for s in samples if s['discarded']]
        assert len(finished) == case['processedEvents'] and len(dropped) == case['discardedTasks']
        assert len(finished) + len(dropped) == len(samples)
        for s in finished:
            received, enqueued, start, completed = (int(s[k]) for k in ('received_nanos', 'enqueued_nanos', 'started_nanos', 'completed_nanos'))
            assert received <= enqueued <= start <= completed
            assert int(s['queue_wait_nanos']) == start - enqueued
            assert int(s['trade_processing_lag_nanos']) == completed - received
            assert int(s['transaction_total_nanos']) >= int(s['transaction_body_nanos']) + int(s['transaction_commit_nanos'])
        for phase, stats in case['phases'].items():
            key = phase.removesuffix('_ms') + '_nanos'
            values = sorted(int(s[key]) for s in finished if int(s[key]) > 0)
            # A tiny pure decision can fall below nanoTime resolution. Its actual invocation count
            # is retained in the run's distribution; zero in CSV also represents an uninvoked phase.
            if len(values) < stats['samples']:
                assert phase == 'rule_decision_ms', (case['id'], phase, 'unexpected unresolved zero duration')
                values = [0] * (stats['samples'] - len(values)) + values
            assert len(values) == stats['samples'], (case['id'], phase)
            if values:
                for percentile in (50, 95, 99):
                    expected = values[math.ceil(len(values) * percentile / 100) - 1] / 1e6
                    assert abs(stats[f'p{percentile}Ms'] - expected) < 1e-8
                assert abs(stats['maxMs'] - values[-1] / 1e6) < 1e-8
        delta = server_delta(case['serverBefore'], case['serverAfter'])
        differences = {}
        if data['environment'].get('instrumentation', 'full') == 'full':
            for key, count in case['jdbcExecutions'].items():
                # The initial capture filters these six business tables. Calendar reads remain in JDBC totals.
                if key.split(':', 1)[1] not in ('quote_events', 'alert_rules', 'stocks', 'market_stream_state', 'alert_triggers', 'outbox_events'):
                    continue
                if delta.get(key, 0) != count:
                    differences[key] = {'jdbc': count, 'serverDigest': delta.get(key, 0)}
        assert case['tableRowDelta']['quote_events'] == case['processedEvents']
        assert case['tableRowDelta']['alert_triggers'] == case['triggerCount']
        assert case['tableRowDelta']['outbox_events'] == case['triggerCount']
        assert case['tableRowDelta']['notifications'] == 0
        assert case['tableRowDelta']['simulation_runs'] == case['tableRowDelta']['simulation_steps'] == case['tableRowDelta']['simulation_rule_events'] == 0
        size = file.stat().st_size
        artifacts.append({'name': file.name, 'bytes': size, 'sha256': hashlib.sha256(file.read_bytes()).hexdigest()})
        compact = {k: v for k, v in case.items() if k not in ('serverBefore', 'serverAfter', 'redisBefore', 'redisAfter', 'resources')}
        compact['serverSqlExecutions'] = delta
        compact['serverCounterDifferences'] = differences
        compact['comparisonEligible'] = case['firstError'] is None and case.get('warmupFailedTasks', 0) == 0 and case.get('warmupFirstError') is None
        if not compact['comparisonEligible']:
            compact['measurementCaveat'] = ('No measured task failed, but the run retained a warmup/telemetry error. '
                                             'Preserve this row; exclude it from clean before/after aggregates and repeat this workload.')
        compact['maxPoolAwaiting'] = max((r['poolAwaiting'] for r in case['resources']), default=0)
        compact['maxPoolActive'] = max((r['poolActive'] for r in case['resources']), default=0)
        compact['maxSampledOldestWaitMs'] = max((q['oldestWaitMs'] for r in case['resources'] for q in r['queues'].values()), default=0)
        compact['queueTimeline'] = [{'second': r['second'], 'maxDepth': max((q['depth'] for q in r['queues'].values()), default=0),
                                     'maxOldestWaitMs': max((q['oldestWaitMs'] for q in r['queues'].values()), default=0)} for r in case['resources']]
        cases.append(compact)
    return data, cases, artifacts


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--input', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    data, cases, artifacts = validate(args.input)
    args.output.mkdir(parents=True, exist_ok=True)
    (args.output / 'summary.json').write_text(json.dumps({'environment': data['environment'], 'cases': cases, 'checks': data['checks'], 'rawSamples': artifacts}, ensure_ascii=False, indent=2) + '\n')
    with (args.output / 'cases.csv').open('w', newline='') as handle:
        fields = ['id', 'scenario', 'stocks', 'ratePerStock', 'seconds', 'repeat', 'inputEvents', 'processedEvents', 'actualInputPerSecond',
                  'completedPerSecondIncludingDrain', 'drainSeconds', 'maxQueueDepth', 'queueOverflows', 'discardedTasks', 'failedTasks', 'triggerCount', 'rearmCount', 'maxPoolActive', 'maxPoolAwaiting']
        for phase in ('quote_process_total', 'queue_wait', 'trade_processing_lag', 'transaction_total', 'transaction_commit', 'stock_lock_wait', 'mysql_rule_fetch', 'redis_candidate', 'rule_transition', 'trigger_outbox_write'):
            fields.extend(f'{phase}_{p}' for p in ('samples', 'meanMs', 'p50Ms', 'p95Ms', 'p99Ms', 'maxMs'))
        writer = csv.DictWriter(handle, fieldnames=fields, lineterminator='\n'); writer.writeheader()
        for case in cases:
            row = {k: case.get(k) for k in fields if k in case}
            for phase, stats in case['phases'].items():
                for k, value in stats.items():
                    key = phase.removesuffix('_ms') + '_' + k
                    if key in fields: row[key] = value
            writer.writerow(row)
    differences = sum(len(c['serverCounterDifferences']) for c in cases)
    print(f'VALIDATED cases={len(cases)} rawArtifacts={len(artifacts)} raw latency/JDBC samples checked; database digest differences retained={differences}')


if __name__ == '__main__':
    main()
