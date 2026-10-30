package com.ezra.pricepulse.monitoring;

import com.ezra.pricepulse.market.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile("!standalone & !mq-it")
@ConditionalOnProperty(name="price-pulse.monitoring.dynamic-subscriptions-enabled",havingValue="true")
public class DynamicSubscriptionTransport implements SubscriptionTransport {
    private final MonitoringMapper store;
    private final MonitoringProperties monitoring;
    private final AlpacaProperties properties;
    private final RealMarketRuntime runtime;
    public DynamicSubscriptionTransport(MonitoringMapper store,MonitoringProperties monitoring,AlpacaProperties properties,RealMarketRuntime runtime){
        this.store=store;this.monitoring=monitoring;this.properties=properties;this.runtime=runtime;
    }
    public boolean dynamicAvailable(){return monitoring.isDynamicSubscriptionsEnabled();}
    public boolean supports(String symbol){return dynamicAvailable()&&!symbol.isBlank()&&!symbol.contains("*");}
    public Observation observe(String symbol){
        if(!dynamicAvailable())return new Observation("FAILED",null,"DYNAMIC_SUBSCRIPTION_UNAVAILABLE");
        if(!properties.isEnabled()||!properties.isBusinessWritesEnabled())return new Observation("FAILED",null,"FORMAL_FEED_DISABLED");
        var value=runtime.subscription(symbol);
        return new Observation(value.state(),value.epoch(),value.errorCode(),value.intentVersion());
    }
    // Serialize the read + apply together: an older MySQL snapshot cannot overwrite a newer one.
    public synchronized void reconcile(){if(dynamicAvailable())runtime.applySubscriptions(store.liveDemand());}
    public boolean removalConfirmed(String symbol){return runtime.subscription(symbol).absent();}
    public boolean failureReleaseConfirmed(String symbol){return runtime.subscription(symbol).absent();}
}
