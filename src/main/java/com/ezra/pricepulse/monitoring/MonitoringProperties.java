package com.ezra.pricepulse.monitoring;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties("price-pulse.monitoring")
public class MonitoringProperties {
    private boolean enabled;
    private boolean dynamicSubscriptionsEnabled;
    private boolean adoptLegacyHolds;
    public boolean isEnabled(){return enabled;}
    public void setEnabled(boolean enabled){this.enabled=enabled;}
    public boolean isDynamicSubscriptionsEnabled(){return enabled&&dynamicSubscriptionsEnabled;}
    public void setDynamicSubscriptionsEnabled(boolean value){dynamicSubscriptionsEnabled=value;}
    public boolean isAdoptLegacyHolds(){return adoptLegacyHolds;}
    public void setAdoptLegacyHolds(boolean value){adoptLegacyHolds=value;}
}
