package com.ezra.pricepulse.monitoring;

import com.ezra.pricepulse.market.*;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

@Component
@Profile("!standalone & !mq-it")
@ConditionalOnProperty(name="price-pulse.monitoring.dynamic-subscriptions-enabled",havingValue="false",matchIfMissing=true)
public class LegacySubscriptionTransport implements SubscriptionTransport {
    private final AlpacaProperties properties;
    private final MarketFeedHealth health;
    public LegacySubscriptionTransport(AlpacaProperties properties,MarketFeedHealth health){this.properties=properties;this.health=health;}
    public boolean supports(String symbol){return properties.getSymbols().contains(symbol);}
    public Observation observe(String symbol){
        if(!supports(symbol))return new Observation("FAILED",null,"DYNAMIC_SUBSCRIPTION_UNAVAILABLE");
        if(!properties.isEnabled()||!properties.isBusinessWritesEnabled())return new Observation("FAILED",null,"FORMAL_FEED_DISABLED");
        var stream=health.streamSnapshot();
        if(health.confirms(symbol)&&stream.lastSubscribedAt()!=null)
            return new Observation("CONFIRMED",stream.lastSubscribedAt().toString(),null);
        if(health.connection().equals("REJECTED"))return new Observation("FAILED",null,"UPSTREAM_REJECTED");
        return new Observation("PENDING",null,"AWAITING_UPSTREAM_CONFIRMATION");
    }
}
