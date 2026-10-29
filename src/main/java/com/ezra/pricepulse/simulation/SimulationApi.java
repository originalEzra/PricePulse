package com.ezra.pricepulse.simulation;

import com.ezra.pricepulse.alert.AlertRuleStatus;
import com.ezra.pricepulse.market.MarketDataModels.Point;
import com.ezra.pricepulse.market.MarketDataModels.TradingSession;
import com.ezra.pricepulse.simulation.SimulationModels.StoredPoint;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public final class SimulationApi {
    private SimulationApi() {}
    public enum OriginKind { LATEST, HISTORICAL_POINT }
    public record Origin(@NotNull OriginKind kind,@Size(max=80) String pointRef,@Size(max=40) String revision) {}
    public record Start(@NotNull @Positive Long stockId,@NotNull @Valid Origin origin,
            @NotNull SimulationGenerator.Mode mode,
            @NotNull @DecimalMin(value="0",inclusive=false) @DecimalMax("0.003") @Digits(integer=1,fraction=6) BigDecimal stepRatio,
            Long seed,@JsonProperty(value="expectedCurrentRunId",required=true) @Size(max=36) String expectedCurrentRunId) {}
    public record Advance(@NotNull @PositiveOrZero Long expectedVersion,@NotNull @PositiveOrZero Long expectedBatchNo) {}
    public record Rules(long snapshotRuleId,long sourceRuleId,long sourceVersion,JsonNode config,
            AlertRuleStatus sourceStatus,AlertRuleStatus initialState,AlertRuleStatus currentState,long stateVersion) {}
    public record Stats(long triggered,long rearmed) {}
    public record Current(String runId,long stockId,long version,long lastBatchNo,Instant simClock,String latestPrice,
            long nextPointSeq,JsonNode origin,JsonNode parameters,String seed,String generatorVersion,
            boolean generatorSupported,List<Rules> ruleSnapshots,Stats stats) {}
    public record Batch(String runId,long batchNo,long versionAfter,Instant fromClock,Instant toClock,
            long firstPointSeq,long lastPointSeq,int triggered,int rearmed,List<StoredPoint> points) {}
    public record Range(Instant from,Instant to,String marketTimeZone) {}
    public record Timeline(String scope,String runId,long stockId,long version,Instant simClock,JsonNode origin,
            Range range,List<TradingSession> sessions,List<Point> realPrefix,List<StoredPoint> simulation,List<Rules> thresholds,
            Stats stats,String pointCursor,String watermark) {}
    public record Event(long id,long snapshotRuleId,long pointSeq,String eventType,Instant eventTime,String epochNanos,
            String price,AlertRuleStatus fromState,AlertRuleStatus toState,JsonNode ruleSnapshot) {}
    public record EventPage(List<Event> events,String eventCursor,String watermark) {}
}
