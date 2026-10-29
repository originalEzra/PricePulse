package com.ezra.pricepulse.market;

import static org.assertj.core.api.Assertions.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;

class MarketQuoteLaneTest {
    @Test void overloadIsBoundedAndNextAcceptedWorkHasNewContinuityInsteadOfSilentlySampling()throws Exception {
        var started=new CountDownLatch(1);var release=new CountDownLatch(1);var done=new CountDownLatch(2);
        List<MarketQuoteLane.Work> accepted=new CopyOnWriteArrayList<>();List<String> faults=new CopyOnWriteArrayList<>();
        try(var lane=new MarketQuoteLane(1,1,work->{
            accepted.add(work);started.countDown();
            if(work.trade().eventKey().equals("first"))try{release.await(3,TimeUnit.SECONDS);}catch(InterruptedException e){Thread.currentThread().interrupt();}
            done.countDown();
        },faults::add)) {
            lane.offer(trade("first"));assertThat(started.await(1,TimeUnit.SECONDS)).isTrue();
            lane.offer(trade("queued"));lane.offer(trade("after-overflow"));release.countDown();
            assertThat(done.await(2,TimeUnit.SECONDS)).isTrue();
            assertThat(accepted).extracting(w->w.trade().eventKey()).containsExactly("first","after-overflow");
            assertThat(accepted.getLast().continuity()).isNotEqualTo(accepted.getFirst().continuity());
            assertThat(accepted.getLast().reason()).isEqualTo("QUEUE_OVERFLOW");assertThat(faults).containsExactly("QUEUE_OVERFLOW");
        }
    }
    @Test void failureClearsUncertainQueuedTailAndReconnectAlsoChangesBaseline()throws Exception {
        var failed=new CountDownLatch(1);var processed=new CountDownLatch(1);List<MarketQuoteLane.Work> seen=new CopyOnWriteArrayList<>();
        try(var lane=new MarketQuoteLane(1,3,work->{
            seen.add(work);if(work.trade().eventKey().equals("fail"))throw new IllegalStateException();processed.countDown();
        },code->failed.countDown())) {
            lane.offer(trade("fail"));assertThat(failed.await(1,TimeUnit.SECONDS)).isTrue();
            lane.gap("RECONNECT");lane.offer(trade("after"));assertThat(processed.await(1,TimeUnit.SECONDS)).isTrue();
            assertThat(seen.getLast().continuity()).isNotEqualTo(seen.getFirst().continuity());
        }
    }
    private AlpacaProtocol.Trade trade(String key){return new AlpacaProtocol.Trade("AAPL",key,BigDecimal.ONE,Instant.now(),Instant.now());}
}
