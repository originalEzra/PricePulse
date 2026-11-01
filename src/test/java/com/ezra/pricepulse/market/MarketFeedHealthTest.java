package com.ezra.pricepulse.market;

import static org.assertj.core.api.Assertions.*;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class MarketFeedHealthTest {
    @Test void retriesAndSubscriptionStateDoNotResetOrPrematurelyCloseTheFault(){
        var health=new MarketFeedHealth();var at=Instant.parse("2026-11-02T15:00:00Z");
        health.streamFailure("SOCKET_CLOSED_1006",at);String id=health.connectionFaultId();
        health.streamFailure("CONNECT_FAILED",at.plusSeconds(10));health.streamFailure("HANDSHAKE_TIMEOUT",at.plusSeconds(25));
        assertThat(health.faults()).hasSize(1);assertThat(health.faults().getFirst().startedAt()).isEqualTo(at);
        health.connection("SUBSCRIBED");health.subscriptionSymbols(java.util.List.of("AAPL"));
        assertThat(health.connectionFaultId()).isEqualTo(id);
        assertThat(health.recoverConnection("older-generation",at.plusSeconds(31))).isFalse();
        assertThat(health.recoverConnection(id,at.plusSeconds(31))).isTrue();
        assertThat(health.recoverConnection(id,at.plusSeconds(32))).isFalse();
        var closed=health.faults().getFirst();health.acknowledgeFault(id,closed.revision()-1);assertThat(health.faults()).hasSize(1);
        health.acknowledgeFault(id,closed.revision());assertThat(health.faults()).isEmpty();
        health.streamFailure("CONNECT_FAILED",at.plusSeconds(40));assertThat(health.connectionFaultId()).isNotEqualTo(id);
    }
    @Test void queueLossWaitsForTheNewContinuityCommitAndAZeroLossRaceDoesNotInventDataLoss(){
        var health=new MarketFeedHealth();var at=Instant.parse("2026-11-02T15:00:00Z");
        health.queueGap(7,"QUEUE_OVERFLOW",0,"a",at);assertThat(health.faults()).isEmpty();
        health.queueGap(7,"QUEUE_OVERFLOW",1024,"b",at);health.queueGap(7,"QUEUE_OVERFLOW",5,"c",at.plusSeconds(1));
        assertThat(health.faults()).hasSize(1);assertThat(health.faults().getFirst().discarded()).isEqualTo(1029);
        health.queueAccepted(7,"b",at.plusSeconds(2));assertThat(health.queueFaultOpen(7)).isTrue();
        health.queueAccepted(7,"c",at.plusSeconds(3));assertThat(health.queueFaultOpen(7)).isFalse();
        assertThat(health.faults().getFirst().recoveredAt()).isEqualTo(at.plusSeconds(3));
    }
    @Test void quietMarketsAndPlannedShutdownDoNotOpenFaults(){
        var health=new MarketFeedHealth();var at=Instant.parse("2026-11-02T15:00:00Z");
        health.connection("SUBSCRIBED");health.streamSubscribed(at,java.util.List.of("AAPL"));health.streamPong(at.plusSeconds(60));
        health.snapshot("AAPL",false,at.plusSeconds(600),null,true);health.snapshot("AAPL",true,at.plusSeconds(600),null,true);
        health.streamFailure("STOP_CLOSE_TIMEOUT",at.plusSeconds(600));assertThat(health.faults()).isEmpty();
    }
    @Test void aRecoveredHeartbeatRetainsHistoricalFailureWithoutInventingTradeFreshness() {
        var health=new MarketFeedHealth();
        Instant failed=Instant.parse("2026-10-29T02:00:00Z"), recovered=failed.plusSeconds(60);
        health.streamFailure("HEARTBEAT_TIMEOUT",failed);
        health.streamAttempt(recovered);health.streamSubscribed(recovered,java.util.List.of("AAPL"));health.connection("SUBSCRIBED");
        health.streamPing(recovered);health.streamPong(recovered.plusSeconds(1));
        var view=health.snapshot("AAPL",false,recovered.plusSeconds(2),null,false);
        assertThat(view.connection()).isEqualTo("SUBSCRIBED");assertThat(view.lastError()).isEqualTo("HEARTBEAT_TIMEOUT");
        assertThat(view.stream().lastFailureAt()).isEqualTo(failed);
        assertThat(view.stream().lastPongAt()).isAfter(view.stream().lastFailureAt());
        assertThat(view.stream().heartbeatTimeouts()).isEqualTo(1);
        assertThat(view.freshness()).isEqualTo("MARKET_CLOSED");assertThat(view.lastObservedAt()).isNull();
        assertThat(view.lastAcceptedAt()).isNull();assertThat(view.observedTrades()).isZero();
        assertThat(view.businessWritesEnabled()).isFalse();
    }
    @Test void boundedFailureHistorySeparatesClientPausesAndPreservesPriorSnapshots() {
        var health=new MarketFeedHealth();var at=Instant.parse("2026-10-30T00:00:00Z");
        health.streamAttempt(at);health.streamPong(at);
        health.streamFailure("HEARTBEAT_TIMEOUT",at.plusSeconds(46),1000L,46000L,0L);
        var prior=health.streamSnapshot();
        for(int i=1;i<=25;i++)health.streamFailure("CLIENT_TIMING_GAP",at.plusSeconds(60L*i),60000L,60000L,0L);
        var latest=health.streamSnapshot();
        assertThat(prior.recentFailures()).hasSize(1);assertThat(latest.recentFailures()).hasSize(20);
        assertThat(latest.recentFailures().getFirst().at()).isEqualTo(at.plusSeconds(360));
        assertThat(latest.recentFailures().getLast().lastPongAt()).isEqualTo(at);
        assertThat(latest.heartbeatTimeouts()).isEqualTo(1);assertThat(latest.clientTimingGaps()).isEqualTo(25);
        assertThat(latest.lastFailureCode()).isEqualTo("CLIENT_TIMING_GAP");
    }
}
