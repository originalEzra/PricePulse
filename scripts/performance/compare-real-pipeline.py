#!/usr/bin/env python3
"""Strict, loss-free functional comparison using actual candidate IDs and persisted rule states."""
import argparse
import csv
import gzip
import hashlib
import json
from pathlib import Path


def main():
    p = argparse.ArgumentParser()
    p.add_argument('--before', required=True, type=Path)
    p.add_argument('--after', required=True, type=Path)
    p.add_argument('--output', required=True, type=Path)
    args = p.parse_args()
    runs = [json.loads((d / 'summary.json').read_text()) for d in (args.before, args.after)]
    assert all(r['complete'] and r['environment']['candidateIdsCaptured'] for r in runs)
    assert len(runs[0]['cases']) == len(runs[1]['cases'])
    records = []
    for i, (before, after) in enumerate(zip(runs[0]['cases'], runs[1]['cases']), 1):
        assert before['id'] == after['id']
        for c in (before, after):
            assert c['failedTasks'] == c['discardedTasks'] == c.get('warmupFailedTasks', 0) == 0
            assert c['firstError'] is None and c.get('warmupFirstError') is None
        for field in ('inputSha256', 'initialRulesSha256', 'finalRulesSha256', 'triggerCount', 'rearmCount'):
            assert before[field] == after[field], (before['id'], field)
        fields = ('key', 'stock', 'number', 'event_time_utc', 'price', 'outcome', 'candidates', 'triggers', 'rearms', 'candidate_ids_sha256')
        traces = []
        for directory in (args.before, args.after):
            with gzip.open(directory / f'samples-{i}.csv.gz', 'rt') as handle:
                trace = [tuple(row[f] for f in fields) for row in csv.DictReader(handle)]
            assert all(row[-1] for row in trace)
            traces.append(trace)
        assert traces[0] == traces[1], before['id']
        for table in ('alert_triggers', 'outbox_events', 'notifications', 'simulation_runs', 'simulation_steps', 'simulation_rule_events'):
            assert before['tableRowDelta'][table] == after['tableRowDelta'][table]
        records.append({'case': before['id'], 'events': len(traces[0]), 'mismatches': 0,
                        'traceSha256': hashlib.sha256(repr(traces[0]).encode()).hexdigest(),
                        'inputSha256': before['inputSha256'], 'finalRulesSha256': before['finalRulesSha256'],
                        'triggers': before['triggerCount'],
                        'outboxStatementsBefore': before['jdbcExecutions'].get('insert:outbox_events', 0),
                        'outboxStatementsAfter': after['jdbcExecutions'].get('insert:outbox_events', 0)})
    result = {'candidateAndOutcomeMismatches': 0, 'cases': records, 'totalEvents': sum(r['events'] for r in records)}
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(result, ensure_ascii=False, indent=2) + '\n')
    print(f'FUNCTIONAL_EQUAL cases={len(records)} events={result["totalEvents"]} candidate IDs, outcomes, final rule states and rows agree')


if __name__ == '__main__':
    main()
