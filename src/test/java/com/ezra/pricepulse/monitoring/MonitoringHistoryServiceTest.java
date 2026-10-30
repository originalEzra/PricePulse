package com.ezra.pricepulse.monitoring;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.ezra.pricepulse.market.*;
import com.ezra.pricepulse.stock.*;
import com.ezra.pricepulse.common.error.BusinessException;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class MonitoringHistoryServiceTest {
    @Test void preparationIsSharedOwnedAndOnlyWritesThroughTheIndependentCache()throws Exception {
        var store=mock(MonitoringMapper.class);var stocks=mock(StockMapper.class);var data=mock(MarketDataMapper.class);var cache=mock(MarketHistoryCache.class);
        when(store.subscription(1,7)).thenReturn(new MonitoringModels.Subscription(1,7,true,0));
        when(store.subscription(2,7)).thenReturn(new MonitoringModels.Subscription(2,7,true,0));
        when(stocks.findActiveById(7)).thenReturn(new Stock(7,"PLTR","Palantir","US","USD",null,null,"ACTIVE",null,null,"fixture"));
        var gate=new CountDownLatch(1);var reads=new AtomicInteger();
        var service=new MonitoringHistoryService(store,stocks,data,cache,(ids,from,to)->{reads.incrementAndGet();gate.await(3,TimeUnit.SECONDS);return new AlpacaDataClient.Download(List.of(),List.of(),1);},Clock.systemUTC());
        try {
            var first=service.request(1,7);var second=service.request(2,7);
            assertThat(first.requestId()).isEqualTo(second.requestId());assertThat(second.state()).isEqualTo("PENDING");
            assertThatThrownBy(()->service.status(3,first.requestId())).isInstanceOf(BusinessException.class);
            gate.countDown();long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(3);
            while(service.status(1,first.requestId()).state().equals("PENDING")&&System.nanoTime()<deadline)Thread.sleep(10);
            assertThat(service.status(2,first.requestId()).state()).isEqualTo("READY");assertThat(reads).hasValue(1);
            verify(cache).store(any(),eq(List.of(7L)),any(),any());
            verify(stocks,never()).updateLatestQuote(anyLong(),any(),any());
        }finally{gate.countDown();service.close();}
    }
}
