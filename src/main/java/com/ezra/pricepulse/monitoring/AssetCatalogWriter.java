package com.ezra.pricepulse.monitoring;

import com.ezra.pricepulse.monitoring.MonitoringModels.Asset;
import java.util.List;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Profile("!standalone & !mq-it")
public class AssetCatalogWriter {
    private final MonitoringMapper store;
    public AssetCatalogWriter(MonitoringMapper store){this.store=store;}
    @Transactional public void replace(List<Asset> assets){
        if(assets.isEmpty())throw new IllegalArgumentException("A complete catalog is required");
        for(int i=0;i<assets.size();i+=400)store.assets(assets.subList(i,Math.min(i+400,assets.size())));
        store.retireUnseen(assets.getFirst().revision());
    }
    @Transactional public void remember(Asset asset){store.assets(List.of(asset));}
}
