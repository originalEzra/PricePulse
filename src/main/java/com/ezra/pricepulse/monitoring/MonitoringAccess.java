package com.ezra.pricepulse.monitoring;

import com.ezra.pricepulse.common.error.*;
import com.ezra.pricepulse.stock.Stock;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile("!standalone & !mq-it")
public class MonitoringAccess {
    private final MonitoringProperties properties;
    private final MonitoringMapper store;
    private final SubscriptionTransport transport;
    public MonitoringAccess(MonitoringProperties properties,MonitoringMapper store,SubscriptionTransport transport){this.properties=properties;this.store=store;this.transport=transport;}
    public void requireConfirmed(long user,Stock stock){
        if(!stock.market().equals("US"))return;
        if(!properties.isEnabled()){
            if(!com.ezra.pricepulse.market.MarketUniverse.supports(stock.symbol())&&store.assetId(stock.id())!=null)
                throw new BusinessException(ErrorCode.MONITORING_REQUIRED,"个人监控管理尚未启用，无法启用该股票的正式提醒");
            return;
        }
        var request=store.subscription(user,stock.id());var target=store.getTarget(stock.id());
        if(request==null||!request.desired()||target==null||!target.slotReserved()
                ||!transport.observe(stock.symbol(),target.intentVersion()).state().equals("CONFIRMED"))
            throw new BusinessException(ErrorCode.MONITORING_REQUIRED,"请先监控该股票并等待行情订阅确认");
    }
}
