package com.ezra.pricepulse.simulation;

import com.ezra.pricepulse.common.PriceTicks;
import com.ezra.pricepulse.common.error.*;
import com.ezra.pricepulse.market.*;
import com.ezra.pricepulse.simulation.SimulationApi.*;
import com.ezra.pricepulse.simulation.SimulationModels.Seed;
import com.ezra.pricepulse.stock.Stock;
import com.fasterxml.jackson.databind.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import java.util.function.Function;

/** Experiment-only prices and metadata. No ingestion, real-rule state or delivery dependency. */
final class SimulationOpeningGap {
    private SimulationOpeningGap(){}
    static Seed prepare(MarketDataMapper data,ObjectMapper json,Clock clock,Stock stock,Start request,long seed,Function<Start,Seed> historical){
        var gap=request.openingGap();
        if(gap==null||gap.source()==null||gap.openingPrice()==null||gap.continuationMode()==null||gap.continuationMode()==SimulationGenerator.Mode.OPENING_GAP)
            throw invalid("Opening gap requires a source, opening price and ordinary continuation mode");
        BigDecimal opening=PriceTicks.normalize(gap.openingPrice(),"simulated opening price");
        Seed base;
        if(gap.source()==ReferenceSource.REAL_HISTORY){
            if(request.origin().kind()!=OriginKind.HISTORICAL_POINT||gap.referencePrice()!=null)throw invalid("Choose a real closing minute; its reference price cannot be supplied by the client");
            base=historical.apply(request);
            var calendar=json.convertValue(base.calendar(),SimulationCalendar.Snapshot.class);
            if(!base.origin().path("point").path("kind").asText().equals("BAR_CLOSE")||calendar.sessions().stream().noneMatch(s->s.close().equals(base.time())))
                throw invalid("Opening gap requires the actual closed final minute of a trading session");
        }else{
            if(request.origin().kind()!=OriginKind.SIMULATION_REFERENCE||request.origin().pointRef()!=null||request.origin().revision()!=null||gap.referencePrice()==null)
                throw invalid("Custom reference must be explicitly marked SIMULATION_REFERENCE");
            BigDecimal reference=PriceTicks.normalize(gap.referencePrice(),"simulated reference price");
            var all=data.sessions();var close=all.stream().filter(s->!s.close().isAfter(clock.instant())).reduce((a,b)->b)
                    .orElseThrow(()->new BusinessException(ErrorCode.SERVICE_UNAVAILABLE,"No closed reference session is cached"));
            var schedule=all.stream().filter(s->!s.sessionDate().isBefore(close.sessionDate())).map(s->new SimulationCalendar.Session(s.sessionDate().toString(),s.open(),s.close())).toList();
            String version=SimulationOriginResolver.hash(json.valueToTree(schedule).toString());
            var point=new MarketDataModels.Point("simulation-reference",close.close(),Long.toString(MarketTime.nanos(close.close())),reference.toPlainString(),"SIMULATION_REFERENCE",null,null,close.close(),"1",close.sessionDate().toString());
            var origin=json.createObjectNode().put("kind","SIMULATION_REFERENCE").put("stockId",stock.id()).put("stockSymbol",stock.symbol())
                    .put("provider","SIMULATION").put("feed","SIMULATION").put("prefixFrom",close.close().toString()).put("calendarVersion",version);
            origin.set("point",json.valueToTree(point));
            base=new Seed(reference,close.close(),origin,json.createArrayNode(),json.createObjectNode(),seed,SimulationGenerator.GAP_VERSION,json.valueToTree(new SimulationCalendar.Snapshot(version,schedule)));
        }
        var calendar=json.convertValue(base.calendar(),SimulationCalendar.Snapshot.class);
        Instant open=SimulationCalendar.nextOpening(base.time(),calendar);
        var metadata=json.createObjectNode().put("kind","SIMULATED_OPENING_GAP").put("scope","SIMULATION")
                .put("source",gap.source().name()).put("referencePrice",base.price().toPlainString()).put("referenceTime",base.time().toString())
                .put("openingPrice",opening.toPlainString()).put("openingTime",open.toString()).put("continuationMode",gap.continuationMode().name())
                .put("direction",opening.compareTo(base.price())>0?"UP":opening.compareTo(base.price())<0?"DOWN":"FLAT");
        var parameters=json.createObjectNode().put("mode","OPENING_GAP").put("stepRatio",request.stepRatio().stripTrailingZeros().toPlainString());
        parameters.set("openingGap",metadata);
        return new Seed(base.price(),base.time(),base.origin(),base.historyPrefix(),parameters,seed,SimulationGenerator.GAP_VERSION,base.calendar());
    }
    private static BusinessException invalid(String message){return new BusinessException(ErrorCode.BUSINESS_RULE_VIOLATION,message);}
}
