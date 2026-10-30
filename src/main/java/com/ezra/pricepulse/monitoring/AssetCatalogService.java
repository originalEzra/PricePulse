package com.ezra.pricepulse.monitoring;

import com.ezra.pricepulse.monitoring.MonitoringModels.*;
import com.ezra.pricepulse.common.error.BusinessException;
import java.time.*;
import java.util.*;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

@Service
@Profile("!standalone & !mq-it")
public class AssetCatalogService {
    private final AlpacaAssetClient client;
    private final AssetCatalogWriter writer;
    private final MonitoringMapper store;
    private final SubscriptionTransport transport;
    private final Clock clock;
    private Instant loadedAt,retryAt=Instant.MIN;
    public AssetCatalogService(AlpacaAssetClient client,AssetCatalogWriter writer,MonitoringMapper store,SubscriptionTransport transport,Clock clock){
        this.client=client;this.writer=writer;this.store=store;this.transport=transport;this.clock=clock;
    }
    public synchronized Search search(long user,String text,int limit){
        Instant now=clock.instant();boolean stale=loadedAt==null||loadedAt.isBefore(now.minusSeconds(3600));String error=null;
        if(stale&&!now.isBefore(retryAt))try{writer.replace(client.catalog());loadedAt=now;stale=false;}
        catch(RuntimeException ignored){retryAt=now.plusSeconds(30);error="ASSET_CATALOG_UNAVAILABLE";}
        if(stale)error="ASSET_CATALOG_UNAVAILABLE";
        String query=text.trim().toUpperCase(Locale.ROOT),pattern="%"+query.replace("!","!!").replace("%","!%").replace("_","!_")+"%";
        var subscribed=store.rows(user,com.ezra.pricepulse.market.MarketTime.utc(now)).stream().map(Row::symbol).collect(java.util.stream.Collectors.toSet());
        var matches=store.search(query,pattern,limit).stream().map(a->new AssetResult(a,subscribed.contains(a.symbol()),transport.supports(a.symbol()))).toList();
        return new Search(matches,stale,error);
    }
    public Asset validate(String id){Asset a=client.get(id);writer.remember(a);return a;}
}
