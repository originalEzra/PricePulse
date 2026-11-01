package com.ezra.pricepulse.market;

import com.ezra.pricepulse.monitoring.MonitoringMapper;
import com.ezra.pricepulse.outbox.OutboxWriter;
import java.time.*;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

/** Serializes incident/recipient/Outbox changes using the existing subscription control row. */
@Service @Profile("!standalone & !mq-it")
public class MonitorIncidentService {
    private final MonitorIncidentMapper store;
    private final MonitoringMapper subscriptions;
    private final OutboxWriter outbox;
    public MonitorIncidentService(MonitorIncidentMapper store,MonitoringMapper subscriptions,OutboxWriter outbox){this.store=store;this.subscriptions=subscriptions;this.outbox=outbox;}
    @Transactional(isolation=Isolation.READ_COMMITTED)
    public void confirmRecovery(RealMarketRuntime runtime,MarketFeedHealth health,Instant now){
        subscriptions.lockBudget();
        runtime.recoverMonitorConnection(store.expected(),health.connectionFaultId(),now);
    }
    @Transactional(isolation=Isolation.READ_COMMITTED)
    public void record(MonitorFault fault,Instant now){
        subscriptions.lockBudget();
        store.insert(fault,MarketTime.utc(fault.startedAt()));
        var old=store.find(fault.id());
        if(old==null)throw new IllegalStateException("A different incident still owns the active scope");
        store.update(fault.id(),fault.discarded(),fault.recoveredAt()==null?null:MarketTime.utc(fault.recoveredAt()));
        Instant end=fault.recoveredAt()==null?now:fault.recoveredAt();
        boolean alert=fault.scope().equals("QUEUE")?fault.discarded()>0:!end.isBefore(fault.startedAt().plusSeconds(30));
        if(alert){
            store.alerted(fault.id(),MarketTime.utc(now));
            var at=fault.scope().equals("QUEUE")?fault.startedAt():fault.startedAt().plusSeconds(30);
            for(var user:store.audience(fault.stockId()))deliver(fault.id(),user,"INTERRUPTED",at);
        }
        if(fault.recoveredAt()!=null){
            // Only users with a persisted alarm get a recovery notice, including users who have since cancelled.
            for(var user:store.interruptedAudience(fault.id()))deliver(fault.id(),user,"RECOVERED",fault.recoveredAt());
        }
    }
    private void deliver(String id,MonitorIncidentMapper.Audience user,String kind,Instant at){
        if(store.deliveryId(id,user.userId(),kind)!=null)return;
        store.insertDelivery(id,user.userId(),kind,MarketTime.utc(at),user.symbols());
        outbox.monitoring(store.deliveryId(id,user.userId(),kind),user.userId(),id);
    }
}
