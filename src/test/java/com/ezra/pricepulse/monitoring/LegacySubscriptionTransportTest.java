package com.ezra.pricepulse.monitoring;

import static org.assertj.core.api.Assertions.*;
import com.ezra.pricepulse.market.*;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class LegacySubscriptionTransportTest {
    @Test void globalConnectionNeverConfirmsAnUnacknowledgedSymbolAndDisconnectInvalidatesProof() {
        var properties=new AlpacaProperties();properties.setEnabled(true);properties.setBusinessWritesEnabled(true);properties.setSymbols(List.of("AAPL","MSFT"));
        var health=new MarketFeedHealth();var transport=new LegacySubscriptionTransport(properties,health);
        health.streamSubscribed(Instant.parse("2026-10-30T00:00:00Z"),List.of("AAPL"));health.connection("SUBSCRIBED");
        assertThat(transport.observe("AAPL").state()).isEqualTo("CONFIRMED");
        assertThat(transport.observe("MSFT").state()).isEqualTo("PENDING");
        assertThat(transport.observe("PLTR").state()).isEqualTo("FAILED");assertThat(transport.dynamicAvailable()).isFalse();
        assertThat(health.snapshot("PLTR",true,Instant.now(),null,true).connection()).isEqualTo("NOT_SUBSCRIBED");
        health.connection("RECONNECTING");assertThat(transport.observe("AAPL").state()).isEqualTo("PENDING");
        assertThat(transport.observe("AAPL").epoch()).isNull();
    }
}
