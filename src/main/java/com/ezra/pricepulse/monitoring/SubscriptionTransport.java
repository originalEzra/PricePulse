package com.ezra.pricepulse.monitoring;

/** Provider I/O runs outside business transactions; observations are immutable in-memory facts. */
public interface SubscriptionTransport {
    record Observation(String state,String epoch,String errorCode,long intentVersion){
        public Observation(String state,String epoch,String errorCode){this(state,epoch,errorCode,-1);}
    }
    Observation observe(String symbol);
    default Observation observe(String symbol,long intentVersion){
        var value=observe(symbol);
        return dynamicAvailable()&&value.intentVersion()!=intentVersion
            ?new Observation("PENDING",null,"AWAITING_UPSTREAM_CONFIRMATION",intentVersion):value;
    }
    boolean supports(String symbol);
    default boolean dynamicAvailable(){return false;}
    default void reconcile(){}
    default boolean removalConfirmed(String symbol){return !dynamicAvailable();}
    default boolean failureReleaseConfirmed(String symbol){return !dynamicAvailable();}
}
