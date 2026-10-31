#!/usr/bin/env python3
"""A/B/A/B... isolated runs. Never checks out sources, starts Spring, or accepts a DB URL."""
import argparse
from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess


def utc():
    return datetime.now(timezone.utc).isoformat()


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--before', required=True)
    parser.add_argument('--after', required=True)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--pairs', type=int, default=5)
    parser.add_argument('--seconds', type=int, default=8)
    parser.add_argument('--warmup-seconds', type=int, default=2)
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[1]
    os.chdir(root)
    output = args.output.resolve()
    assert output.is_relative_to(root / 'target') and not output.exists(), 'Use a new directory under target/'
    assert 5 <= args.pairs <= 10 and 4 <= args.seconds <= 60 and 1 <= args.warmup_seconds <= 10
    for ref in (args.before, args.after):
        assert re.fullmatch('[0-9a-f]{7,40}', ref)
        subprocess.run(['git', 'cat-file', '-e', ref + '^{commit}'], check=True)
    output.mkdir(parents=True)
    paths = ['pom.xml', 'scripts/run-real-pipeline-experiment.sh',
             'src/test/java/com/ezra/pricepulse/market/RealPipelineExperiment.java',
             'src/test/java/com/ezra/pricepulse/market/PipelineProbe.java',
             'src/test/java/com/ezra/pricepulse/market/DedupExceptionPerformance.java']
    paths.extend(subprocess.check_output(['git', 'ls-files', 'src/main'], text=True).splitlines())
    def hashes():
        return {p: hashlib.sha256(Path(p).read_bytes()).hexdigest() for p in sorted(set(paths))}
    frozen = hashes()
    manifest = {'startedAtUtc': utc(), 'sequence': 'A,B repeated, never concurrent', 'before': args.before,
                'after': args.after, 'pairs': args.pairs, 'seconds': args.seconds,
                'warmupSeconds': args.warmup_seconds, 'sourceSha256': frozen, 'rounds': [], 'complete': False}
    def save():
        (output / 'manifest.json').write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + '\n')
    save()
    for pair in range(1, args.pairs + 1):
        for variant, reference in [('A', args.before), ('B', args.after)]:
            assert hashes() == frozen, 'Sources changed during comparison; do not mix versions'
            name = f'{variant}{pair}'
            directory, log = output / name, output / f'{name}.log'
            record = {'pair': pair, 'variant': variant, 'reference': reference, 'directory': str(directory.relative_to(root)),
                      'startedAtUtc': utc(), 'status': 'RUNNING'}
            manifest['rounds'].append(record); save()
            env = os.environ.copy()
            env.pop('PP_PIPELINE_EVALUATION_REF', None)
            env.pop('PP_PIPELINE_UNIT_TESTS', None)
            env['PP_PIPELINE_INGESTION_REF'] = reference
            command = ['bash', 'scripts/run-real-pipeline-experiment.sh', '--suite=dedup-benchmark',
                       f'--seconds={args.seconds}', f'--warmup-seconds={args.warmup_seconds}', '--repeats=1',
                       '--stocks=1', '--rates=50,100,150,200',
                       '--scenarios=ZERO_RULES,ZERO_HIT,FEW_HIT,MIXED_DUPLICATES',
                       '--capture-candidates=true', '--instrumentation=full', '--output=' + str(directory.relative_to(root))]
            record['command'] = command
            print(f'START {name} ingestion={reference}', flush=True)
            with log.open('w') as handle:
                result = subprocess.run(command, env=env, stdout=handle, stderr=subprocess.STDOUT)
            record.update({'endedAtUtc': utc(), 'exitCode': result.returncode})
            if result.returncode:
                record['status'] = 'FAILED'; save()
                raise SystemExit(f'{name} failed; preserve {log}, do not silently skip or replace it')
            summary = json.loads((directory / 'summary.json').read_text())
            direct = json.loads((directory / 'direct-paths.json').read_text())
            assert summary['complete'] and len(summary['cases']) == 16 and len(direct['paths']) == 3
            assert all(c['failedTasks'] == c.get('warmupFailedTasks', 0) == 0 and c['firstError'] is None
                       and c.get('warmupFirstError') is None and c['oracle']['mismatches'] == 0 for c in summary['cases'])
            record['status'] = 'COMPLETE'
            record['inputEvents'] = sum(c['inputEvents'] for c in summary['cases'])
            record['discardedTasks'] = sum(c['discardedTasks'] for c in summary['cases'])
            record['summarySha256'] = hashlib.sha256((directory / 'summary.json').read_bytes()).hexdigest()
            save(); print(f'COMPLETE {name} events={record["inputEvents"]} discarded={record["discardedTasks"]}', flush=True)
    manifest.update({'complete': True, 'endedAtUtc': utc()}); save()
    print('ALTERNATING_COMPARISON_COMPLETE', flush=True)


if __name__ == '__main__':
    main()
