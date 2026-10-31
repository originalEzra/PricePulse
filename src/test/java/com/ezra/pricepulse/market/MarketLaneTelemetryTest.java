package com.ezra.pricepulse.market;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.ezra.pricepulse.matching.MatchingResult;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.math.BigDecimal;
import java.time.*;
import org.junit.jupiter.api.Test;

class MarketLaneTelemetryTest {
    static final Instant NOW=Instant.parse("2026-10-30T14:00:00Z");
    QuoteResult result(QuoteOutcome outcome){return new QuoteResult(1,"synthetic",1,null,BigDecimal.ONE,LocalDateTime.ofInstant(NOW,ZoneOffset.UTC),outcome,null,MatchingResult.empty());}
    @Test void recordsOnlyRealProcessedResultsRestoresTimingScopeAndRetiresStockLabels() {
        var registry=new SimpleMeterRegistry();
        try(var telemetry=new MarketLaneTelemetry(registry,Clock.fixed(NOW,ZoneOffset.UTC))){
            var observer=telemetry.observer(1);telemetry.attach(1,()->new MarketQuoteLane.QueueSample(3,2_000_000,1));
            var trade=new AlpacaProtocol.Trade("PPTEST0","synthetic",BigDecimal.ONE,NOW,NOW.minusMillis(5));
            var work=new MarketQuoteLane.Work(trade,"a","STARTUP");long start=System.nanoTime();
            observer.offered(trade);observer.started(work,start-1_000_000,start);
            QuoteTiming.finish("rule_decision",QuoteTiming.start());
            assertThat(telemetry.ingest(1,()->result(QuoteOutcome.PROCESSED)).outcome()).isEqualTo(QuoteOutcome.PROCESSED);
            observer.completed(work,start,true);
            telemetry.ingest(1,()->result(QuoteOutcome.DUPLICATE));telemetry.fault(1,"QUEUE_OVERFLOW");observer.discarded(work,"QUEUE_OVERFLOW");
            assertThat(QuoteTiming.start()).isZero();
            assertThat(registry.get("pricepulse.market.input").counter().count()).isEqualTo(1);
            assertThat(registry.get("pricepulse.market.processed").counter().count()).isEqualTo(1);
            assertThat(registry.get("pricepulse.market.rejected").counter().count()).isEqualTo(1);
            assertThat(registry.get("pricepulse.market.transaction").timer().count()).isEqualTo(2);
            assertThat(registry.get("pricepulse.market.queue.depth").gauge().value()).isEqualTo(3);
            assertThat(registry.get("pricepulse.market.queue.oldest.wait").gauge().value()).isEqualTo(2);
            telemetry.detach(1);assertThat(registry.getMeters()).isEmpty();
        }finally{registry.close();}
    }
    @Test void aMetricsFailureCannotChangeTheBusinessResultOrException() {
        var telemetry=new MarketLaneTelemetry(mock(MeterRegistry.class),Clock.fixed(NOW,ZoneOffset.UTC));
        var expected=result(QuoteOutcome.PROCESSED);
        assertThat(telemetry.ingest(1,()->expected)).isSameAs(expected);
        var failure=new IllegalStateException("business failure");
        assertThatThrownBy(()->telemetry.ingest(1,()->{throw failure;})).isSameAs(failure);
    }
}
