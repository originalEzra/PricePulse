-- EXPERIMENT ONLY. Never execute against the running PricePulse database.
-- Secondary InnoDB indexes already contain the primary key id.
CREATE INDEX idx_bench_trigger ON bench_rules(stock_id,status,direction,threshold_price);
CREATE INDEX idx_bench_rearm ON bench_rules(stock_id,status,direction,rearm_price);
ANALYZE TABLE bench_rules;

-- Upward interval: previous exclusive, current inclusive; disjoint states.
EXPLAIN ANALYZE
SELECT id FROM bench_rules
 WHERE stock_id=1 AND status='ACTIVE' AND direction='ABOVE'
   AND threshold_price>100.0000 AND threshold_price<=100.0100
UNION ALL
SELECT id FROM bench_rules
 WHERE stock_id=1 AND status='COOLDOWN' AND direction='BELOW'
   AND rearm_price>100.0000 AND rearm_price<=100.0100;

-- Downward interval: current inclusive, previous exclusive.
EXPLAIN ANALYZE
SELECT id FROM bench_rules
 WHERE stock_id=1 AND status='ACTIVE' AND direction='BELOW'
   AND threshold_price>=99.9900 AND threshold_price<100.0000
UNION ALL
SELECT id FROM bench_rules
 WHERE stock_id=1 AND status='COOLDOWN' AND direction='ABOVE'
   AND rearm_price>=99.9900 AND rearm_price<100.0000;
