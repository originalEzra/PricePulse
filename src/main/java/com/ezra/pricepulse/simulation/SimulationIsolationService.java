package com.ezra.pricepulse.simulation;

import com.ezra.pricepulse.alert.*;
import com.ezra.pricepulse.alert.RuleTransitionEngine.Definition;
import com.ezra.pricepulse.common.PriceTicks;
import com.ezra.pricepulse.common.error.BusinessException;
import com.ezra.pricepulse.common.error.ErrorCode;
import com.ezra.pricepulse.matching.RuleIndexEntry;
import com.ezra.pricepulse.matching.ThresholdIndex;
import com.ezra.pricepulse.simulation.SimulationModels.*;
import com.ezra.pricepulse.stock.StockMapper;
import com.ezra.pricepulse.stock.Stock;
import com.ezra.pricepulse.user.UserMapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import java.util.function.Function;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Internal transaction boundary. Public APIs must supply server-validated origin and generated points. */
@Service
@Profile("!standalone & !mq-it")
public class SimulationIsolationService {
    private final SimulationMapper store;
    private final AlertRuleMapper realRules;
    private final StockMapper stocks;
    private final UserMapper users;
    private final ThresholdIndex index;
    private final ObjectMapper json;

    public SimulationIsolationService(SimulationMapper store, AlertRuleMapper realRules, StockMapper stocks,
            UserMapper users, ThresholdIndex index, ObjectMapper json) {
        this.store = store; this.realRules = realRules; this.stocks = stocks;
        this.users = users; this.index = index; this.json = json;
    }

    @Transactional(isolation = Isolation.REPEATABLE_READ)
    public Run begin(long userId, long stockId, String expectedRunId, String requestKey, String requestHash, Seed seed) {
        return begin(userId, stockId, expectedRunId, requestKey, requestHash, ignored -> seed);
    }

    /** Resolve cached origin data inside the same MVCC snapshot; never perform provider I/O here. */
    @Transactional(isolation = Isolation.REPEATABLE_READ)
    public Run begin(long userId, long stockId, String expectedRunId, String requestKey, String requestHash,
            Function<Stock, Seed> prepare) {
        validateKey(requestKey, requestHash);
        if (users.lockActive(userId) == null) throw missing();
        Run old = store.current(userId);
        if (old != null) {
            old = store.lockOwned(old.id(), userId);
            if (old.startRequestKey().equals(requestKey)) {
                if (old.stockId() != stockId || !old.startRequestHash().equals(requestHash)) throw conflict();
                return old;
            }
        }
        if (!Objects.equals(expectedRunId, old == null ? null : old.id())) throw conflict();
        Stock stock = stocks.findActiveById(stockId);
        if (stock == null) throw missing();
        Seed seed = prepare.apply(stock);
        BigDecimal price = PriceTicks.normalize(seed.price(), "originPrice");
        long time = nanos(seed.time());
        String id = UUID.randomUUID().toString();
        Run run = new Run(id, userId, stockId, write(seed.origin()), write(seed.historyPrefix()),
                write(seed.parameters()), seed.seed(), Objects.requireNonNull(seed.generatorVersion()),
                write(seed.calendar()), utc(seed.time()), time, price, 1, 0, 0,
                UUID.randomUUID().toString(), requestKey, requestHash);
        List<AlertRule> sources = realRules.listSnapshotSource(userId, stockId);
        if (old != null && store.deleteOwned(old.id(), userId) != 1) throw conflict();
        store.insertRun(run);
        for (AlertRule source : sources) {
            AlertRuleStatus initial = source.getStatus() == AlertRuleStatus.PAUSED
                    ? AlertRuleStatus.PAUSED : AlertRuleStatus.ACTIVE;
            store.insertSnapshot(new Snapshot(0, id, source.getId(), source.getVersion(),
                    write(AlertRuleResponse.from(source)), source.getStatus(), initial, initial, 0, null));
        }
        if (old != null) {
            String oldKey = key(old.id());
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCommit() { index.retire(oldKey); }
            });
        }
        return store.current(userId);
    }

    @Transactional
    public BatchResult advance(long userId, String runId, long expectedVersion, long expectedBatch,
            String requestKey, String requestHash, Function<Run, List<Point>> prepare) {
        validateKey(requestKey, requestHash);
        Run run = requireLocked(userId, runId);
        Step prior = store.byRequest(runId, requestKey);
        if (prior != null) {
            if (!prior.requestHash().equals(requestHash)) throw conflict();
            return read(prior.summaryJson(), BatchResult.class);
        }
        if (run.version() != expectedVersion || run.lastBatchNo() != expectedBatch) throw conflict();
        List<Point> points = List.copyOf(prepare.apply(run));
        if (points.isEmpty() || points.size() > 60) {
            throw new BusinessException(ErrorCode.BUSINESS_RULE_VIOLATION, "Prepared batch must contain 1–60 points");
        }
        List<StoredPoint> storedPoints = new ArrayList<>();
        long previousTime = run.simClockNs(), seq = run.nextPointSeq();
        for (Point point : points) {
            long time = nanos(point.eventTime());
            if (time <= previousTime) throw new BusinessException(ErrorCode.CONFLICT, "Simulation time must increase");
            storedPoints.add(new StoredPoint(seq++, point.eventTime(),
                    PriceTicks.normalize(point.price(), "price").toPlainString()));
            previousTime = time;
        }
        StoredPoint last = storedPoints.getLast();
        Step step = new Step(0, runId, expectedBatch + 1, requestKey, requestHash, run.simClock(),
                utc(last.eventTime()), run.simClockNs(), nanos(last.eventTime()), run.nextPointSeq(), seq - 1,
                write(storedPoints), "{}");
        store.insertStep(step);
        long stepId = store.lastInsertId();
        String token = run.ruleIndexToken();
        BigDecimal previous = run.latestPrice();
        int triggered = 0, rearmed = 0;
        for (StoredPoint point : storedPoints) {
            BigDecimal price = new BigDecimal(point.price());
            List<Long> ids = index.candidates(key(runId), token, previous, price,
                    () -> store.indexable(runId).stream().map(this::entry).toList());
            List<RuleIndexEntry> changed = new ArrayList<>();
            for (int offset = 0; offset < ids.size(); offset += 500) {
                for (Snapshot snapshot : store.candidates(runId, ids.subList(offset, Math.min(offset + 500, ids.size())))) {
                    Definition definition = definition(snapshot);
                    var decision = RuleTransitionEngine.evaluate(definition, snapshot.currentState(), previous, price);
                    if (decision.kind() == RuleTransitionEngine.Kind.NONE) continue;
                    boolean trigger = decision.kind() == RuleTransitionEngine.Kind.TRIGGERED;
                    if (store.transition(snapshot, decision.target(), trigger ? utc(point.eventTime()) : null) != 1) {
                        throw conflict();
                    }
                    store.insertEvent(new Event(0, runId, stepId, snapshot.id(), point.pointSeq(),
                            decision.kind().name(), utc(point.eventTime()), nanos(point.eventTime()), price,
                            snapshot.currentState(), decision.target()));
                    changed.add(new RuleIndexEntry(snapshot.id(), definition, decision.target()));
                    if (trigger) triggered++; else rearmed++;
                }
            }
            if (!changed.isEmpty()) {
                String next = UUID.randomUUID().toString();
                if (store.indexToken(runId, next) != 1) throw conflict();
                index.changed(key(runId), token, next, changed);
                token = next;
            }
            previous = price;
        }
        BatchResult result = new BatchResult(runId, step.batchNo(), expectedVersion + 1,
                triggered, rearmed, List.copyOf(storedPoints));
        store.summary(stepId, write(result));
        if (store.finish(step, previous, expectedVersion) != 1) throw conflict();
        return result;
    }

    @Transactional(readOnly = true)
    public Run current(long userId) { return store.current(userId); }

    @Transactional(readOnly = true)
    public List<Snapshot> snapshots(long userId, String runId) {
        requireOwned(userId, runId); return store.snapshots(runId);
    }

    @Transactional(readOnly = true)
    public List<Event> events(long userId, String runId) {
        requireOwned(userId, runId); return store.events(runId);
    }

    private Run requireLocked(long userId, String runId) {
        Run run = store.lockOwned(runId, userId);
        if (run == null) throw missing();
        return run;
    }

    private void requireOwned(long userId, String id) {
        Run current = store.current(userId);
        if (current == null || !current.id().equals(id)) throw missing();
    }

    private RuleIndexEntry entry(Snapshot snapshot) {
        return new RuleIndexEntry(snapshot.id(), definition(snapshot), snapshot.currentState());
    }

    private Definition definition(Snapshot snapshot) {
        var node = read(snapshot.configJson(), com.fasterxml.jackson.databind.JsonNode.class);
        return new Definition(AlertDirection.valueOf(node.path("direction").asText()),
                node.path("thresholdPrice").decimalValue(), AlertTriggerMode.valueOf(node.path("triggerMode").asText()),
                node.path("rearmPrice").isNull() ? null : node.path("rearmPrice").decimalValue());
    }

    private String write(Object value) {
        if (value == null) throw new BusinessException(ErrorCode.BUSINESS_RULE_VIOLATION, "Missing prepared input");
        try { return json.writeValueAsString(value); }
        catch (JsonProcessingException e) { throw new IllegalArgumentException("Cannot serialize simulation state", e); }
    }

    private <T> T read(String value, Class<T> type) {
        try { return json.readValue(value, type); }
        catch (JsonProcessingException e) { throw new IllegalStateException("Cannot restore simulation state", e); }
    }

    private void validateKey(String key, String hash) {
        if (key == null || key.isBlank() || key.length() > 128 || hash == null || !hash.matches("[0-9a-f]{64}")) {
            throw new BusinessException(ErrorCode.BUSINESS_RULE_VIOLATION, "Invalid internal idempotency metadata");
        }
    }

    static long nanos(Instant instant) {
        try { return Math.addExact(Math.multiplyExact(instant.getEpochSecond(), 1_000_000_000L), instant.getNano()); }
        catch (ArithmeticException e) { throw new BusinessException(ErrorCode.BUSINESS_RULE_VIOLATION, "Timestamp out of range"); }
    }

    private static LocalDateTime utc(Instant instant) { return LocalDateTime.ofInstant(instant, ZoneOffset.UTC).withNano(instant.getNano() / 1000 * 1000); }
    public static String key(String runId) { return "pricepulse:v2:sim:rules:{run:" + runId + "}"; }
    private BusinessException missing() { return new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "Simulation was not found"); }
    private BusinessException conflict() { return new BusinessException(ErrorCode.CONFLICT, "Simulation state or request changed"); }
}
