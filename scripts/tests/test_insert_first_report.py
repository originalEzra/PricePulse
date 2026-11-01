import csv
import gzip
import importlib.util
from pathlib import Path
import tempfile
import unittest

spec = importlib.util.spec_from_file_location('dedup_report', Path(__file__).resolve().parents[1] / 'performance/report-insert-first.py')
report = importlib.util.module_from_spec(spec)
spec.loader.exec_module(report)


def case(latency=1, dropped=0):
    return {'id': 'mixed', 'inputEvents': 100, 'processedEvents': 95-dropped, 'businessRejectedEvents': 5,
            'discardedTasks': dropped, 'queueOverflows': int(dropped > 0), 'maxQueueDepth': 2,
            'completedPerSecondIncludingDrain': 95-dropped, 'observationSeconds': 1,
            'duplicateKeyExceptions': 5, 'callerRetries': 0, 'jdbcExecutions': {'select:stocks': 100},
            'outcomeCounts': {'PROCESSED': 95-dropped, 'DUPLICATE': 5},
            'phases': {phase + '_ms': {stat: latency for stat in ('meanMs', 'p50Ms', 'p95Ms', 'p99Ms')}
                       for phase in ('transaction_total', 'quote_process_total', 'queue_wait')},
            'inputSha256': 'same-input', 'initialRulesSha256': 'same-initial', 'finalRulesSha256': 'same-final',
            'triggerCount': 1, 'rearmCount': 1, 'tableRowDelta': {'quote_events': 95-dropped}, 'oracle': {'sha256': 'same-events'}}


class InsertFirstReportTest(unittest.TestCase):
    def test_duplicates_count_as_handled_without_inflating_processed_throughput(self):
        result = report.group([case() for _ in range(5)])
        self.assertEqual(result['handledPerSecond']['median'], 100)
        self.assertEqual(result['completedPerSecondIncludingDrain']['median'], 95)
        self.assertEqual(result['sqlPerHandled']['median'], 1)

    def test_independent_run_statistics_are_not_pooled_or_averaged(self):
        result = report.group([case(value) for value in [10, 11, 100, 12, 13]])
        self.assertEqual(result['quote_process_total.p95Ms']['median'], 12)
        self.assertEqual(result['quote_process_total.p95Ms']['max'], 100)

    def test_overload_is_retained_but_never_claimed_as_zero_difference(self):
        result = report.compare_trace(None, None, case(dropped=1), case(), 1)
        self.assertFalse(result['strictLossFreeComparison'])
        self.assertNotIn('mismatches', result)
        changed = case(dropped=1); changed['initialRulesSha256'] = 'different'
        with self.assertRaises(AssertionError):
            report.compare_trace(None, None, changed, case(), 1)

    def test_an_actual_candidate_id_digest_difference_rejects_the_comparison(self):
        fields = ('key', 'event_key', 'stock', 'number', 'event_time_utc', 'price', 'outcome', 'candidates', 'triggers', 'rearms', 'candidate_ids_sha256')
        with tempfile.TemporaryDirectory() as folder:
            directories = [Path(folder)/'A', Path(folder)/'B']
            for directory, digest in zip(directories, ['candidate-a', 'candidate-b']):
                directory.mkdir()
                with gzip.open(directory/'samples-1.csv.gz', 'wt') as handle:
                    writer = csv.DictWriter(handle, fieldnames=fields); writer.writeheader()
                    row = {field: 'same' for field in fields}; row['candidate_ids_sha256'] = digest; writer.writerow(row)
            with self.assertRaises(AssertionError):
                report.compare_trace(*directories, case(), case(), 1)


if __name__ == '__main__':
    unittest.main()
