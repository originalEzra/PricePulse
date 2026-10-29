package com.ezra.pricepulse.market;

import com.ezra.pricepulse.alert.*;
import com.ezra.pricepulse.common.error.*;
import com.ezra.pricepulse.market.MarketDataModels.*;
import com.ezra.pricepulse.stock.*;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.*;
import java.util.*;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

/** Every public method is a read-only snapshot. No upstream fetches or business writers are injected. */
@Service
@Profile("!standalone & !mq-it")
@Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
public class MarketTimelineService {
    private final MarketDataMapper data;
    private final StockMapper stocks;
    private final AlertRuleMapper rules;
    private final MarketFeedHealth health;
    private final AlpacaProperties properties;
    private final TimelineCursor cursors;
    private final ObjectMapper json;
    private final Clock clock;
    public MarketTimelineService(MarketDataMapper data,StockMapper stocks,AlertRuleMapper rules,MarketFeedHealth health,
            AlpacaProperties properties,TimelineCursor cursors,ObjectMapper json,Clock clock) {
        this.data=data;this.stocks=stocks;this.rules=rules;this.health=health;this.properties=properties;
        this.cursors=cursors;this.json=json;this.clock=clock;
    }
    public record TimelineStock(long id,String symbol,String name,String market,String currency,
            String latestPrice,Instant latestQuoteAt,String priceKind) {}
    public record Threshold(long id,String name,AlertDirection direction,String thresholdPrice,String rearmPrice,
            AlertRuleStatus status,long version) {}
    public Timeline timeline(long userId,long stockId,String window,Instant anchor) {
        Stock stock=requireStock(stockId);
        Instant now=clock.instant();
        List<Session> sessions=data.sessions();
        Range range=MarketWindows.resolve(window,anchor==null?now:anchor,now,sessions);
        List<Bar> bars=data.bars(stockId,MarketTime.utc(range.from()),MarketTime.utc(range.asOf()));
        List<Point> points=new ArrayList<>();
        for(Bar bar:bars) {
            Instant at=MarketTime.instant(bar.intervalEnd());
            points.add(new Point("bar:"+bar.id(),at,Long.toString(MarketTime.nanos(at)),bar.closePrice().toPlainString(),
                    "BAR_CLOSE",MarketTime.instant(bar.intervalStart()),at,at,Long.toString(bar.revision()),
                    MarketTime.instant(bar.intervalStart()).atZone(MarketTime.NEW_YORK).toLocalDate().toString()));
        }
        // Completed cached minutes supply the historical line. Accepted trades supply only the later tail.
        Instant tail=bars.isEmpty()?range.from():MarketTime.instant(bars.getLast().intervalEnd()).plusNanos(1);
        long bucket=Math.max(1,(MarketTime.nanos(range.asOf())-MarketTime.nanos(tail))/1000);
        if(!tail.isAfter(range.asOf())) for(var trade:data.tradePoints(stockId,MarketTime.nanos(tail),MarketTime.nanos(range.asOf()),bucket)) {
            Instant at=MarketTime.instant(trade.eventTimeNs());
            Session session=sessions.stream().filter(s->s.contains(at)).findFirst().orElse(null);
            if(session==null)continue;
            points.add(new Point("trade:"+trade.id(),at,Long.toString(trade.eventTimeNs()),trade.price().toPlainString(),
                    "TRADE",null,null,at,"1",session.sessionDate().toString()));
        }
        points.sort(Comparator.comparing(Point::eventTime));
        List<Gap> gaps=new ArrayList<>();
        for(Session session:sessions) {
            Instant start=max(session.open(),range.from()),end=min(session.close(),range.asOf());
            if(!end.isAfter(start))continue;
            Instant cursor=start;
            for(Bar bar:bars) {
                Instant begin=MarketTime.instant(bar.intervalStart()),finish=MarketTime.instant(bar.intervalEnd());
                if(finish.isBefore(start)||begin.isAfter(end))continue;
                if(begin.isAfter(cursor))gaps.add(new Gap(cursor,min(begin,end),"SPARSE_IEX_OR_CACHE_PENDING"));
                cursor=max(cursor,finish);
            }
            if(cursor.isBefore(end))gaps.add(new Gap(cursor,end,"SPARSE_IEX_OR_CACHE_PENDING"));
        }
        data.baselineTimes(stockId,MarketTime.nanos(range.from()),MarketTime.nanos(range.asOf()))
                .forEach(t->gaps.add(new Gap(MarketTime.instant(t),MarketTime.instant(t),"LIVE_BASELINE_AFTER_GAP")));
        EventPage events=events(userId,stockId,range.from(),range.asOf(),null);
        var state=data.streamState(stockId);
        boolean validState=state!=null && state.provider().equals("ALPACA") && state.feed().equals("iex");
        Point last=points.isEmpty()?null:points.getLast();
        TimelineStock view=new TimelineStock(stock.id(),stock.symbol(),stock.name(),stock.market(),stock.currency(),
                validState&&stock.latestPrice()!=null?stock.latestPrice().toPlainString():last==null?null:last.price(),
                validState?MarketTime.instant(state.eventTimeNs()):last==null?null:last.eventTime(),
                validState?"ACCEPTED_REAL_TRADE":last==null?"NO_DATA":"HISTORICAL_BAR_CLOSE");
        var coverage=data.coverage(stockId);
        Object coverageView=coverage==null?null:Map.of("requestedFrom",MarketTime.instant(coverage.requestedFrom()),
                "requestedTo",MarketTime.instant(coverage.requestedTo()),"syncedAt",MarketTime.instant(coverage.syncedAt()));
        long expected=expectedMinutes(sessions,range.from(),range.asOf());
        var quality=new DataQuality(bars.size(),bars.size(),expected,Math.max(0,expected-bars.size()),
                coverage!=null && !MarketTime.instant(coverage.requestedFrom()).isAfter(range.from())
                    && !MarketTime.instant(coverage.requestedTo()).isBefore(range.asOf()),
                !sessions.isEmpty() && !range.from().atZone(MarketTime.NEW_YORK).toLocalDate().isBefore(sessions.getFirst().sessionDate())
                    && !range.asOf().atZone(MarketTime.NEW_YORK).toLocalDate().isAfter(sessions.getLast().sessionDate()),
                false,points.size()-bars.size());
        var chartSessions=TradingSession.forRange(sessions.stream().map(s->new TradingSession(s.sessionDate().toString(),s.open(),s.close())).toList(),range.from(),range.to());
        var thresholds=rules.listSnapshotSource(userId,stockId).stream().map(r->new Threshold(r.getId(),r.getName(),r.getDirection(),
                r.getThresholdPrice().toPlainString(),r.getRearmPrice()==null?null:r.getRearmPrice().toPlainString(),r.getStatus(),r.getVersion())).toList();
        return new Timeline("REAL",view,"ALPACA","iex","raw","1Min + sampled trade tail",range,coverageView,
                health.snapshot(stock.symbol(),sessions.stream().anyMatch(s->s.contains(now)),now,validState?state:null,properties.isBusinessWritesEnabled()),
                gaps,chartSessions,quality,List.copyOf(points),thresholds,"CURRENT_CONFIGURATION",events.events(),events.eventCursor(),events.watermark());
    }
    public EventPage events(long userId,long stockId,Instant from,Instant to,String cursor) {
        requireStock(stockId);
        if(to.isBefore(from)||Duration.between(from,to).compareTo(Duration.ofDays(40))>0)
            throw new BusinessException(ErrorCode.VALIDATION_ERROR,"Event range must be ordered and no longer than 40 days");
        long fromNs=MarketTime.nanos(from),toNs=MarketTime.nanos(to);
        var pos=cursor==null?new TimelineCursor.Position(0,data.eventWatermark(userId,stockId))
                :cursors.decode(cursor,userId,stockId,fromNs,toNs);
        var rows=data.events(userId,stockId,fromNs,toNs,pos.after(),pos.watermark());
        var page=rows.subList(0,Math.min(100,rows.size())).stream().map(row->{
            try { return new TimelineEvent(row.triggerId(),row.quoteId(),MarketTime.instant(row.eventTimeNs()),
                    Long.toString(row.eventTimeNs()),row.price().toPlainString(),json.readTree(row.ruleSnapshot()),row.notificationStatus()); }
            catch(JsonProcessingException e){throw new IllegalStateException("Unreadable trigger snapshot",e);}
        }).toList();
        String next=rows.size()>100?cursors.encode(userId,stockId,fromNs,toNs,page.getLast().triggerId(),pos.watermark()):null;
        return new EventPage(page,next,Long.toString(pos.watermark()));
    }
    static long expectedMinutes(List<Session> sessions,Instant from,Instant to) {
        long count=0,minute=Duration.ofMinutes(1).toNanos();
        for(Session session:sessions) {
            long first=Math.max(1,-Math.floorDiv(-Duration.between(session.open(),from).toNanos(),minute));
            long last=Math.min(Duration.between(session.open(),session.close()).toMinutes(),
                    Math.floorDiv(Duration.between(session.open(),to).toNanos(),minute));
            count+=Math.max(0,last-first+1);
        }
        return count;
    }
    private Stock requireStock(long id) {
        Stock stock=stocks.findActiveById(id);
        if(stock==null||!stock.market().equals("US"))throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND,"US stock was not found");
        return stock;
    }
    private static Instant min(Instant a,Instant b){return a.isBefore(b)?a:b;}
    private static Instant max(Instant a,Instant b){return a.isAfter(b)?a:b;}
}
