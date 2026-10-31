import importlib.util
from pathlib import Path
import unittest


spec = importlib.util.spec_from_file_location('pipeline_report', Path(__file__).resolve().parents[1] / 'performance' / 'report-real-pipeline.py')
report = importlib.util.module_from_spec(spec)
spec.loader.exec_module(report)


class PipelineReportTest(unittest.TestCase):
    def test_redis_failed_and_rejected_calls_do_not_replace_the_actual_command_count(self):
        before = {'cmdstat_evalsha': 'calls=100,usec=400,usec_per_call=4.0,rejected_calls=0,failed_calls=0'}
        after = {'cmdstat_evalsha': 'calls=110,usec=460,usec_per_call=4.1,rejected_calls=2,failed_calls=1'}
        result = report.redis_delta(before, after)['cmdstat_evalsha']
        self.assertEqual(result['calls'], 10)
        self.assertEqual(result['serverTotalMicros'], 60)
        self.assertEqual(result['serverMeanMicros'], 6)

    def test_insert_select_is_counted_for_its_destination_table(self):
        sql = 'INSERT INTO `outbox_events` SELECT t.id FROM `alert_triggers` t JOIN `quote_events` q ON q.id=t.quote_event_id'
        self.assertEqual(report.primary_table(sql), 'insert:outbox_events')

    def test_rule_select_is_distinct_from_the_stock_join(self):
        self.assertEqual(report.primary_table('SELECT r.id FROM `alert_rules` r JOIN `stocks` s ON s.id=r.stock_id'), 'select:alert_rules')
        self.assertEqual(report.primary_table('UPDATE `stocks` SET rule_index_token = ? WHERE id = ?'), 'update:stocks')

    def test_server_digest_difference_is_retained_without_correcting_it_to_expected_work(self):
        before = {'statements': [{'DIGEST_TEXT': 'SELECT id FROM alert_rules', 'COUNT_STAR': 10}]}
        after = {'statements': [{'DIGEST_TEXT': 'SELECT id FROM alert_rules', 'COUNT_STAR': 19}]}
        self.assertEqual(report.server_delta(before, after), {'select:alert_rules': 9})


if __name__ == '__main__':
    unittest.main()
