package com.ezra.pricepulse.simulation;

import com.ezra.pricepulse.common.error.*;
import com.ezra.pricepulse.market.*;
import com.ezra.pricepulse.market.MarketDataModels.*;
import com.ezra.pricepulse.simulation.SimulationModels.Seed;
import com.ezra.pricepulse.stock.Stock;
import com.fasterxml.jackson.databind.*;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Component;
import org.springframework.context.annotation.Profile;

/** Only reads trusted cached rows inside begin's MVCC transaction; never calls the provider. */
@Component
@Profile("!standalone & !mq-it")
public class SimulationOriginResolver {
    private final MarketDataMapper data;
    private final ObjectMapper json;
    private final Clock clock;
    private final com.ezra.pricepulse.monitoring.MonitoringMapper assets;
    public SimulationOriginResolver(MarketDataMapper data,ObjectMapper json,Clock clock,com.ezra.pricepulse.monitoring.MonitoringMapper assets){this.data=data;this.json=json;this.clock=clock;this.assets=assets;}
    public Seed prepare(Stock stock,SimulationApi.Start request,long seed) {
        if(!stock.market().equals("US") || !stock.currency().equals("USD")
                || (!MarketUniverse.supports(stock.symbol())&&assets.assetId(stock.id())==null))throw missing();
        if(request.mode()==SimulationGenerator.Mode.OPENING_GAP)
            return SimulationOpeningGap.prepare(data,json,clock,stock,request,seed,r->standard(stock,r,seed));
        if(request.openingGap()!=null||request.origin().kind()==SimulationApi.OriginKind.SIMULATION_REFERENCE)throw invalid("Simulation reference requires opening-gap mode");
        return standard(stock,request,seed);
    }
    private Seed standard(Stock stock,SimulationApi.Start request,long seed){
        Instant now=clock.instant();List<Session> sessions=data.sessions();
        if(sessions.isEmpty())throw unavailable("Trading calendar is not cached");
        Point point;
        if(request.origin().kind()==SimulationApi.OriginKind.LATEST) {
            if(request.origin().pointRef()!=null || request.origin().revision()!=null)throw invalid("LATEST does not accept a client point");
            var latest=data.latestSimulationTrade(stock.id());
            if(latest==null)throw unavailable("No accepted real-time trade is available; choose a closed historical minute");
            point=trade(latest);
            if(now.atZone(MarketTime.NEW_YORK).toLocalDate().isAfter(sessions.getLast().sessionDate()))throw unavailable("Current calendar coverage is unavailable");
            var recent=sessions.stream().filter(s->!s.open().isAfter(now)).reduce((a,b)->b)
                    .orElseThrow(()->unavailable("Recent trading session is unavailable"));
            Instant freshest=now.isBefore(recent.close())?now:recent.close();
            if(point.eventTime().isBefore(freshest.minusSeconds(120)))throw unavailable("Latest real-time trade is too old for this session");
        } else {
            String ref=request.origin().pointRef(),revision=request.origin().revision();
            if(ref==null || !ref.matches("(bar|trade):[1-9][0-9]{0,18}") || revision==null || revision.isBlank())throw invalid("Choose a server-provided point and revision");
            String[] fields=ref.split(":");long id;
            try{id=Long.parseLong(fields[1]);}catch(NumberFormatException e){throw invalid("Invalid point reference");}
            if(fields[0].equals("bar")) {
                var bar=data.simulationBar(stock.id(),id);if(bar==null)throw missing();point=bar(bar);
                if(!sessions.stream().anyMatch(s->!MarketTime.instant(bar.intervalStart()).isBefore(s.open())
                        && !MarketTime.instant(bar.intervalEnd()).isAfter(s.close())))throw invalid("Historical minute is outside the normal session");
            } else {
                var candidate=data.simulationTrade(stock.id(),id);if(candidate==null)throw missing();point=trade(candidate);
            }
            if(!point.revision().equals(revision))throw new BusinessException(ErrorCode.CONFLICT,"Selected point was revised; reload and choose it again");
        }
        if(point.availableAt().isAfter(now))throw invalid("Selected point is not available yet");
        Session originSession=sessions.stream().filter(s->!point.eventTime().isBefore(s.open())
                && (point.kind().equals("BAR_CLOSE")?!point.eventTime().isAfter(s.close()):point.eventTime().isBefore(s.close())))
                .findFirst().orElseThrow(()->invalid("Selected point is outside a normal trading session"));
        Session previous=sessions.stream().filter(s->s.sessionDate().isBefore(originSession.sessionDate())).reduce((a,b)->b)
                .orElseThrow(()->unavailable("Previous trading day's context is not cached"));
        Coverage coverage=data.coverage(stock.id());
        if(coverage==null || MarketTime.instant(coverage.requestedFrom()).isAfter(previous.open())
                || MarketTime.instant(coverage.requestedTo()).isBefore(point.eventTime()))throw unavailable("Historical prefix coverage is unavailable");
        List<Bar> bars=data.bars(stock.id(),previous.opensAt(),MarketTime.utc(point.eventTime()));
        if(bars.stream().noneMatch(b->!MarketTime.instant(b.intervalStart()).isBefore(previous.open())
                && !MarketTime.instant(b.intervalEnd()).isAfter(previous.close())))throw unavailable("Previous trading day's price context is missing");
        var prefix=new ArrayList<Point>();
        for(Bar bar:bars) {
            Point p=bar(bar);
            if(p.eventTime().isBefore(point.eventTime()) && sessions.stream().anyMatch(s->!p.intervalStart().isBefore(s.open()) && !p.intervalEnd().isAfter(s.close())))prefix.add(p);
        }
        prefix.add(point); // The selected origin occurs once, including trade-vs-bar timestamp ties.
        var schedule=sessions.stream().filter(s->!s.sessionDate().isBefore(previous.sessionDate()))
                .map(s->new SimulationCalendar.Session(s.sessionDate().toString(),s.open(),s.close())).toList();
        String calendarVersion=hash(json.valueToTree(schedule).toString());
        var origin=json.createObjectNode();origin.put("kind",request.origin().kind().name());origin.put("stockId",stock.id());
        origin.put("stockSymbol",stock.symbol());origin.put("provider","ALPACA");origin.put("feed","iex");origin.put("adjustment","raw");
        origin.set("point",json.valueToTree(point));origin.put("prefixFrom",previous.open().toString());origin.put("calendarVersion",calendarVersion);
        var parameters=json.createObjectNode();parameters.put("mode",request.mode().name());parameters.put("stepRatio",request.stepRatio().stripTrailingZeros().toPlainString());
        return new Seed(new BigDecimal(point.price()),point.eventTime(),origin,json.valueToTree(prefix),parameters,seed,
                SimulationGenerator.VERSION,json.valueToTree(new SimulationCalendar.Snapshot(calendarVersion,schedule)));
    }
    private Point bar(Bar bar) {
        Instant start=MarketTime.instant(bar.intervalStart()),end=MarketTime.instant(bar.intervalEnd());
        if(!end.equals(start.plusSeconds(60)))throw invalid("Selected row is not a complete one-minute bar");
        return new Point("bar:"+bar.id(),end,Long.toString(MarketTime.nanos(end)),bar.closePrice().toPlainString(),"BAR_CLOSE",
                start,end,end,Long.toString(bar.revision()),start.atZone(MarketTime.NEW_YORK).toLocalDate().toString());
    }
    private Point trade(MarketDataMapper.TradePoint trade) {
        Instant time=MarketTime.instant(trade.eventTimeNs());
        return new Point("trade:"+trade.id(),time,Long.toString(trade.eventTimeNs()),trade.price().toPlainString(),"TRADE",null,null,time,"1",
                time.atZone(MarketTime.NEW_YORK).toLocalDate().toString());
    }
    static String hash(String value) {
        try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}
        catch(NoSuchAlgorithmException e){throw new IllegalStateException(e);}
    }
    private BusinessException unavailable(String text){return new BusinessException(ErrorCode.SERVICE_UNAVAILABLE,text);}
    private BusinessException invalid(String text){return new BusinessException(ErrorCode.BUSINESS_RULE_VIOLATION,text);}
    private BusinessException missing(){return new BusinessException(ErrorCode.RESOURCE_NOT_FOUND,"Trusted market point was not found for this stock");}
}
