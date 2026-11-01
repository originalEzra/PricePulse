#!/usr/bin/env python3
"""Validate every scored raw run and publish paired, non-pooled comparisons."""
import argparse
from collections import Counter
import csv
import gzip
import hashlib
import importlib.util
import json
import math
from pathlib import Path
import statistics
import subprocess

spec = importlib.util.spec_from_file_location('pipeline_report', Path(__file__).with_name('report-real-pipeline.py'))
base = importlib.util.module_from_spec(spec)
spec.loader.exec_module(base)


def rows(path):
    with gzip.open(path, 'rt') as handle:
        return list(csv.DictReader(handle))


def validate_direct(directory):
    data = json.loads((directory / 'direct-paths.json').read_text())
    assert data['callerRetries'] == 0 and len(data['paths']) == 3
    for path in data['paths']:
        raw = directory / path['raw']['name']
        assert hashlib.sha256(raw.read_bytes()).hexdigest() == path['raw']['sha256']
        samples = rows(raw)
        assert len(samples) == path['attempts'] == 200
        assert dict(Counter(s['outcome'] for s in samples)) == path['outcomes']
        assert sum(int(s['business_sql_attempts']) for s in samples) == sum(path['jdbcExecutions'].values())
        assert sum(int(s['duplicate_key_exceptions']) for s in samples) == sum(path['sqlErrors'].values())
        for phase, stats in path['phases'].items():
            key = phase.removesuffix('_ms') + '_nanos'
            values = sorted(int(s[key]) for s in samples if int(s[key]) > 0)
            assert len(values) == stats['samples'], (path['name'], phase)
            if values:
                for p in (50, 95, 99):
                    assert abs(values[math.ceil(len(values)*p/100)-1]/1e6 - stats[f'p{p}Ms']) < 1e-8
        assert path['callerRetries'] == 0
    return data['paths']


def metric(case, field):
    if '.' in field:
        phase, stat = field.split('.')
        return case['phases'][phase + '_ms'][stat]
    handled = case['processedEvents'] + case.get('businessRejectedEvents', 0)
    if field == 'handledPerSecond':
        return handled / case['observationSeconds']
    if field == 'sqlPerHandled':
        return sum(case['jdbcExecutions'].values()) / handled
    return case[field]


def group(cases):
    fields = ['completedPerSecondIncludingDrain', 'handledPerSecond', 'sqlPerHandled', 'maxQueueDepth',
              'transaction_total.meanMs', 'transaction_total.p50Ms', 'transaction_total.p95Ms', 'transaction_total.p99Ms',
              'quote_process_total.meanMs', 'quote_process_total.p50Ms', 'quote_process_total.p95Ms', 'quote_process_total.p99Ms',
              'queue_wait.p95Ms', 'queue_wait.p99Ms']
    result = {'runs': len(cases), 'inputEvents': sum(c['inputEvents'] for c in cases),
              'processedEvents': sum(c['processedEvents'] for c in cases),
              'duplicates': sum(c['outcomeCounts'].get('DUPLICATE', 0) for c in cases),
              'overflows': sum(c['queueOverflows'] for c in cases), 'discarded': sum(c['discardedTasks'] for c in cases),
              'duplicateKeyExceptions': sum(c['duplicateKeyExceptions'] for c in cases),
              'callerRetries': sum(c['callerRetries'] for c in cases),
              'maxQueueDepthPeak': max(c['maxQueueDepth'] for c in cases),
              'lossFreeRuns': sum(c['discardedTasks'] == 0 for c in cases)}
    for field in fields:
        values = [metric(c, field) for c in cases]
        result[field] = {'median': statistics.median(values), 'min': min(values), 'max': max(values), 'values': values}
    return result


def compare_trace(before, after, first, second, number):
    assert first['id'] == second['id']
    assert first['inputSha256'] == second['inputSha256']
    assert first['initialRulesSha256'] == second['initialRulesSha256']
    record = {'case': first['id'], 'inputEvents': first['inputEvents'], 'inputSha256': first['inputSha256']}
    if first['discardedTasks'] or second['discardedTasks']:
        record.update({'strictLossFreeComparison': False, 'reason': 'Different accepted sequences possible after overflow; both retained and checked against own oracle'})
        return record
    for field in ('finalRulesSha256', 'triggerCount', 'rearmCount', 'tableRowDelta'):
        assert first[field] == second[field], (first['id'], field)
    assert first['oracle']['sha256'] == second['oracle']['sha256']
    fields = ('key', 'event_key', 'stock', 'number', 'event_time_utc', 'price', 'outcome', 'candidates', 'triggers', 'rearms', 'candidate_ids_sha256')
    traces = [[tuple(row[f] for f in fields) for row in rows(directory / f'samples-{number}.csv.gz')] for directory in (before, after)]
    assert traces[0] == traces[1], first['id']
    record.update({'strictLossFreeComparison': True, 'mismatches': 0, 'eventsCompared': len(traces[0]),
                   'traceSha256': hashlib.sha256(repr(traces[0]).encode()).hexdigest(), 'finalRulesSha256': first['finalRulesSha256'],
                   'persistedTriggerIdentitySha256': first['oracle']['sha256']})
    return record


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--input', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    assert args.output.name.startswith('dedup-'), 'Publish in an independent dedup-* directory'
    manifest = json.loads((args.input / 'manifest.json').read_text())
    assert manifest['complete'] and manifest['pairs'] >= 5
    assert [(r['variant'], r['pair']) for r in manifest['rounds']] == [(v, n) for n in range(1, manifest['pairs']+1) for v in ('A', 'B')]
    args.output.mkdir(parents=True, exist_ok=True)
    (args.output / 'runs').mkdir(exist_ok=True)
    runs = {}
    for record in manifest['rounds']:
        name = record['variant'] + str(record['pair'])
        directory = args.input / name
        assert record['status'] == 'COMPLETE'
        assert hashlib.sha256((directory / 'summary.json').read_bytes()).hexdigest() == record['summarySha256']
        data, cases, artifacts = base.validate(directory)
        assert len(cases) == 16 and all(c['comparisonEligible'] for c in cases)
        assert all(c['callerRetries'] == 0 for c in cases)
        for number, case in enumerate(cases, 1):
            outcomes = Counter(s['outcome'] for s in rows(directory / f'samples-{number}.csv.gz') if s['completed_nanos'] != '0')
            case['outcomeCounts'] = dict(outcomes)
            assert set(outcomes) <= {'PROCESSED', 'DUPLICATE'}, (name, case['id'], 'unexpected outcome: retain run and investigate', outcomes)
            assert outcomes['DUPLICATE'] == case.get('businessRejectedEvents', 0)
        source = subprocess.check_output(['git', 'show', record['reference'] + ':src/main/java/com/ezra/pricepulse/market/QuoteIngestionService.java'])
        assert hashlib.sha256(source).hexdigest() == data['environment']['sources']['src/main/java/com/ezra/pricepulse/market/QuoteIngestionService.java']
        direct = validate_direct(directory)
        runs[name] = {'cases': cases, 'direct': direct, 'directory': directory}
        payload = {'environment': data['environment'], 'cases': cases, 'directPaths': direct, 'rawSamples': artifacts}
        (args.output / 'runs' / (name + '.json')).write_text(base.summary_text(payload))
    comparisons, functional, csv_rows = [], [], []
    ordered_ids = [c['id'] for c in runs['A1']['cases']]
    for number, case_id in enumerate(ordered_ids, 1):
        groups = {v: [runs[v+str(n)]['cases'][number-1] for n in range(1, manifest['pairs']+1)] for v in ('A', 'B')}
        result = {'case': case_id, 'scenario': groups['A'][0]['scenario'], 'ratePerStock': groups['A'][0]['ratePerStock']}
        for v, cases in groups.items():
            result[v] = group(cases)
            row = {'case': case_id, 'variant': v, **{k: value for k, value in result[v].items() if not isinstance(value, dict)}}
            for key, stats in result[v].items():
                if isinstance(stats, dict):
                    row.update({key + '_' + stat: stats[stat] for stat in ('median', 'min', 'max')})
            csv_rows.append(row)
        changes = [(metric(a, 'quote_process_total.meanMs')-metric(b, 'quote_process_total.meanMs'))/metric(a, 'quote_process_total.meanMs')*100 for a, b in zip(groups['A'], groups['B'])]
        result['pairedProcessMeanReductionPercent'] = changes
        result['pairedProcessMeanReductionMedianPercent'] = statistics.median(changes)
        result['pairedProcessMeanWins'] = sum(value > 0 for value in changes)
        comparisons.append(result)
        for n in range(1, manifest['pairs']+1):
            a, b = runs['A'+str(n)], runs['B'+str(n)]
            functional.append({'pair': n, **compare_trace(a['directory'], b['directory'], a['cases'][number-1], b['cases'][number-1], number)})
    direct_comparisons = []
    for number in range(3):
        per_variant = {v: [runs[v+str(n)]['direct'][number] for n in range(1, manifest['pairs']+1)] for v in ('A', 'B')}
        assert len({p['normalizedFinalFactsSha256'] for paths in per_variant.values() for p in paths}) == 1
        item = {'name': per_variant['A'][0]['name'], 'normalizedFinalFactsSha256': per_variant['A'][0]['normalizedFinalFactsSha256']}
        for v, paths in per_variant.items():
            item[v] = {'runs': len(paths), 'attempts': sum(p['attempts'] for p in paths),
                       'sqlPerAttempt': [sum(p['jdbcExecutions'].values())/p['attempts'] for p in paths],
                       'sqlErrors': [p['sqlErrors'] for p in paths], 'callerRetries': sum(p['callerRetries'] for p in paths),
                       'outcomes': [p['outcomes'] for p in paths]}
            for stat in ('meanMs', 'p50Ms', 'p95Ms', 'p99Ms', 'maxMs'):
                for phase in ('transaction_total_ms', 'quote_process_total_ms', 'stock_lock_wait_ms'):
                    values = [p['phases'][phase][stat] for p in paths]
                    item[v][phase + '_' + stat] = {'median': statistics.median(values), 'min': min(values), 'max': max(values), 'values': values}
        direct_comparisons.append(item)
    result = {'method': 'Median/range of independent run statistics; never pooled per-event pseudo-replicates',
              'manifest': manifest, 'cases': comparisons, 'directPaths': direct_comparisons}
    (args.output / 'comparison.json').write_text(base.summary_text(result))
    (args.output / 'functional.json').write_text(base.summary_text({'comparisons': functional,
        'eventsCompared': sum(r.get('eventsCompared', 0) for r in functional),
        'strictComparisons': sum(r['strictLossFreeComparison'] for r in functional), 'totalComparisons': len(functional)}))
    with (args.output / 'comparison.csv').open('w', newline='') as handle:
        writer = csv.DictWriter(handle, fieldnames=list(csv_rows[0]), lineterminator='\n'); writer.writeheader(); writer.writerows(csv_rows)
    markdown = ['# 五组交替实验汇总', '', '每格为五次独立运行分位数的中位数；详细范围及每次值在 comparison.json，非合并样本分位数。单位 ms。', '',
                '| 场景 | 输入/s | 处理 P95 A→B | 事务 P95 A→B | 排队 P95 A→B | 吞吐 A→B（含排空） | 均值改善重复数 | 舍弃 A/B |',
                '| --- | ---: | --- | --- | --- | --- | ---: | --- |']
    for c in comparisons:
        def m(v, field): return c[v][field]['median']
        markdown.append(f"| {c['scenario']} | {c['ratePerStock']} | {m('A','quote_process_total.p95Ms'):.3f}→{m('B','quote_process_total.p95Ms'):.3f} | {m('A','transaction_total.p95Ms'):.3f}→{m('B','transaction_total.p95Ms'):.3f} | {m('A','queue_wait.p95Ms'):.3f}→{m('B','queue_wait.p95Ms'):.3f} | {m('A','completedPerSecondIncludingDrain'):.1f}→{m('B','completedPerSecondIncludingDrain'):.1f} | {c['pairedProcessMeanWins']}/{manifest['pairs']} | {c['A']['discarded']}/{c['B']['discarded']} |")
    markdown += ['', '## 单独异常路径', '', '这里是直接事务调用，无队列；含重复／冲突时的整个提交或回滚，不是持续行情吞吐。', '',
                 '| 路径 | 每次 SQL A→B | 完整处理 P50 A→B | P95 A→B | P99 A→B |', '| --- | --- | --- | --- | --- |']
    for p in direct_comparisons:
        def t(v, stat):return p[v]['quote_process_total_ms_'+stat]['median']
        markdown.append(f"| {p['name']} | {statistics.median(p['A']['sqlPerAttempt']):.1f}→{statistics.median(p['B']['sqlPerAttempt']):.1f} | {t('A','p50Ms'):.3f}→{t('B','p50Ms'):.3f} | {t('A','p95Ms'):.3f}→{t('B','p95Ms'):.3f} | {t('A','p99Ms'):.3f}→{t('B','p99Ms'):.3f} |")
    (args.output / 'tables.md').write_text('\n'.join(markdown)+'\n')
    print(f'Validated {len(runs)} runs, {len(comparisons)} case groups; strict trace comparisons {sum(r["strictLossFreeComparison"] for r in functional)}/{len(functional)}')


if __name__ == '__main__':
    main()
