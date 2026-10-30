package com.ezra.pricepulse.market;

import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.time.*;
import java.util.List;
import java.util.Comparator;

public final class MarketDataModels {
    private MarketDataModels() {}
    public record Session(LocalDate sessionDate, LocalDateTime opensAt, LocalDateTime closesAt) {
        public Instant open() { return MarketTime.instant(opensAt); }
        public Instant close() { return MarketTime.instant(closesAt); }
        public boolean contains(Instant time) { return !time.isBefore(open()) && time.isBefore(close()); }
    }
    public record Bar(Long id, long stockId, LocalDateTime intervalStart, LocalDateTime intervalEnd,
            BigDecimal openPrice, BigDecimal highPrice, BigDecimal lowPrice, BigDecimal closePrice,
            long revision, LocalDateTime fetchedAt) {}
    public record StreamState(long stockId, String provider, String feed, long eventTimeNs,
            long acceptedSequence, String continuityId, LocalDateTime receivedAt, LocalDateTime processedAt) {}
    public record Coverage(LocalDateTime requestedFrom, LocalDateTime requestedTo, LocalDateTime syncedAt) {}
    public record EventRow(long triggerId, long quoteId, long eventTimeNs, BigDecimal price,
            String ruleSnapshot, String notificationStatus, String triggerContext) {}
    public record Point(String pointRef, Instant eventTime, String epochNanos, String price, String kind,
            Instant intervalStart, Instant intervalEnd, Instant availableAt, String revision, String session) {}
    public record TimelineEvent(long triggerId, long quoteId, Instant eventTime, String epochNanos, String price,
            JsonNode ruleSnapshot, String notificationStatus, JsonNode triggerContext) {}
    public record EventPage(List<TimelineEvent> events, String eventCursor, String watermark) {}
    public record Range(Instant from, Instant to, Instant asOf, String marketTimeZone) {}
    public record Gap(Instant from, Instant to, String reason) {}
    public record TradingSession(String date, Instant open, Instant close) {
        /** Include adjacent sessions so a clipped overnight/weekend edge is still calendar-backed. */
        public static List<TradingSession> forRange(List<TradingSession> sessions,Instant from,Instant to) {
            var ordered=sessions.stream().sorted(Comparator.comparing(TradingSession::open)).toList();
            var previous=ordered.stream().filter(s->s.close().isBefore(from)).reduce((a,b)->b).orElse(null);
            var next=ordered.stream().filter(s->s.open().isAfter(to)).findFirst().orElse(null);
            return ordered.stream().filter(s->s==previous || s==next || (!s.close().isBefore(from) && !s.open().isAfter(to))).toList();
        }
    }
    public record DataQuality(long availableBars,long displayedBars,long expectedMinutes,long unavailableMinutes,
            boolean historyCoverageComplete,boolean calendarCoverageComplete,boolean barSamplingApplied,long tradeTailPoints) {}
    public record StreamFailure(Instant at,String code,long connectionAttempt,Instant lastFrameAt,Instant lastPingAt,
            Instant lastPongAt,Long schedulerDelayMillis,Long receiveSilenceMillis,Long clockDriftMillis) {}
    public record StreamDiagnostics(long connectionAttempts, long subscriptions, long receivedFrames,
            long sentPings, long receivedPongs, long heartbeatTimeouts,
            Instant lastConnectAttemptAt, Instant lastSubscribedAt, Instant lastFrameAt,
            Instant lastPingAt, Instant lastPongAt, Instant lastFailureAt, String lastFailureCode,
            Instant lastCloseAt, Integer lastCloseCode,long clientTimingGaps,
            Instant lastCloseRequestedAt,Instant lastCloseSentAt,List<StreamFailure> recentFailures) {}
    public record Health(String connection, String market, String freshness, boolean businessWritesEnabled,
            Instant lastObservedAt, Instant lastAcceptedAt, long observedTrades, long rejectedTrades,
            long queueOverflows, String lastError, StreamDiagnostics stream) {}
    public record Timeline(String scope, Object stock, String provider, String feed, String adjustment,
            String resolution, Range range, Object coverage, Health health, List<Gap> gaps,
            List<TradingSession> sessions,DataQuality dataQuality,
            List<Point> points, List<?> thresholds, String thresholdSemantics,
            List<TimelineEvent> events, String eventCursor, String watermark) {}
}
