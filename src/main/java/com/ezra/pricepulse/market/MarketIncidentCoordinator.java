package com.ezra.pricepulse.market;

import java.time.Clock;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** WS/lane callbacks collect facts only; one periodic task performs retryable persistence. */
@Component @Profile("!standalone & !mq-it") @org.springframework.scheduling.annotation.EnableScheduling
public class MarketIncidentCoordinator {
    private final MonitorIncidentMapper store;
    private final MonitorIncidentService service;
    private final MarketFeedHealth health;
    private final AlpacaProperties properties;
    private final ObjectProvider<RealMarketRuntime> runtime;
    private final Clock clock;
    private boolean initialized;
    private final java.util.concurrent.atomic.AtomicBoolean reconciling=new java.util.concurrent.atomic.AtomicBoolean();
    public MarketIncidentCoordinator(MonitorIncidentMapper store,MonitorIncidentService service,MarketFeedHealth health,
            AlpacaProperties properties,ObjectProvider<RealMarketRuntime> runtime,Clock clock){
        this.store=store;this.service=service;this.health=health;this.properties=properties;this.runtime=runtime;this.clock=clock;
    }
    public synchronized void bootstrap(){
        if(initialized)return;
        store.open().forEach(i->health.restoreFault(i.restore()));initialized=true;
    }
    @Scheduled(fixedDelayString="${price-pulse.market.incident-check-delay-ms:1000}")
    public void poll(){
        if(!properties.isEnabled()||!properties.isBusinessWritesEnabled())return;
        try{reconcile();}catch(RuntimeException error){health.error("MONITOR_INCIDENT_PERSIST_RETRY");}
    }
    public void reconcile(){
        if(!reconciling.compareAndSet(false,true))return;
        try{
            bootstrap();var live=runtime.getIfAvailable();
            if(live!=null&&live.monitoringRunning()&&health.connectionFaultId()!=null)service.confirmRecovery(live,health,clock.instant());
            for(var fault:health.faults()){
                service.record(fault,clock.instant()); // proxy returns only after the actual MySQL commit
                health.acknowledgeFault(fault.id(),fault.revision());
            }
        }finally{reconciling.set(false);}
    }
}
