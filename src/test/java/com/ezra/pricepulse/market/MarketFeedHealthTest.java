package com.ezra.pricepulse.market;

import static org.assertj.core.api.Assertions.*;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class MarketFeedHealthTest {
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
