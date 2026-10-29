package com.ezra.pricepulse.market;

import java.time.*;
import java.util.Collection;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Independent cache writer: deliberately has no ingestion, matching, Outbox or notification dependencies. */
@Service
@Profile("!standalone & !mq-it")
public class MarketHistoryCache {
    private final MarketDataMapper data;
    public MarketHistoryCache(MarketDataMapper data) { this.data = data; }
    @Transactional
    public void store(AlpacaDataClient.Download result, Collection<Long> stockIds, Instant from, Instant to) {
        if (result.bars().stream().anyMatch(bar -> !stockIds.contains(bar.stockId())))
            throw new IllegalArgumentException("History cache contains an unexpected catalog stock");
        var now = MarketTime.utc(to);
        result.sessions().forEach(s -> data.saveSession(s,now));
        var bars = result.bars();
        for (int offset=0;offset<bars.size();offset+=400)
            data.saveBars(bars.subList(offset,Math.min(offset+400,bars.size())));
        stockIds.forEach(id -> data.saveCoverage(id,MarketTime.utc(from),MarketTime.utc(to),now));
    }
}
