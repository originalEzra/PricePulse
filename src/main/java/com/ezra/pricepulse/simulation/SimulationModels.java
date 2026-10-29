package com.ezra.pricepulse.simulation;

import com.ezra.pricepulse.alert.AlertRuleStatus;
import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;

public final class SimulationModels {
    private SimulationModels() { }

    /** Server-prepared immutable input; no controller accepts this object in P1. */
    public record Seed(BigDecimal price, Instant time, JsonNode origin, JsonNode historyPrefix,
            JsonNode parameters, long seed, String generatorVersion, JsonNode calendar) { }
    public record Point(Instant eventTime, BigDecimal price) { }
    public record StoredPoint(long pointSeq, Instant eventTime, String price) { }

    public record Run(String id, long userId, long stockId, String originSnapshot,
            String historyPrefixSnapshot, String parameters, long seed, String generatorVersion,
            String calendarSnapshot, LocalDateTime simClock, long simClockNs, BigDecimal latestPrice,
            long nextPointSeq, long lastBatchNo, long version, String ruleIndexToken,
            String startRequestKey, String startRequestHash) { }

    public record Snapshot(long id, String runId, long sourceRuleId, long sourceVersion, String configJson,
            AlertRuleStatus sourceStatus, AlertRuleStatus initialState, AlertRuleStatus currentState,
            long stateVersion, LocalDateTime lastTriggeredAt) { }

    public record Step(long id, String runId, long batchNo, String requestKey, String requestHash,
            LocalDateTime fromClock, LocalDateTime toClock, long fromClockNs, long toClockNs,
            long firstPointSeq, long lastPointSeq, String pointsJson, String summaryJson) { }

    public record Event(long id, String runId, long stepId, long snapshotRuleId, long pointSeq,
            String eventType, LocalDateTime eventTime, long eventTimeNs, BigDecimal price,
            AlertRuleStatus fromState, AlertRuleStatus toState) { }

    public record BatchResult(String runId, long batchNo, long versionAfter,
            int triggered, int rearmed, List<StoredPoint> points) { }
}
