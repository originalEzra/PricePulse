package com.ezra.pricepulse.simulation;

import com.ezra.pricepulse.common.error.*;
import com.ezra.pricepulse.market.*;
import com.ezra.pricepulse.simulation.SimulationApi.*;
import com.ezra.pricepulse.simulation.SimulationModels.*;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.*;
import java.security.SecureRandom;
import java.time.*;
import java.util.*;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

@Service
@Profile("!standalone & !mq-it")
public class SimulationService {
    private final SimulationIsolationService isolation;
    private final SimulationOriginResolver origins;
    private final SimulationMapper store;
    private final SimulationCursor cursors;
    private final ObjectMapper json;
    private final SecureRandom random=new SecureRandom();
    public SimulationService(SimulationIsolationService isolation,SimulationOriginResolver origins,SimulationMapper store,
            SimulationCursor cursors,ObjectMapper json){this.isolation=isolation;this.origins=origins;this.store=store;this.cursors=cursors;this.json=json;}

    @Transactional(isolation=Isolation.REPEATABLE_READ)
    public Current start(long user,Start request,String key) {
        String requestKey=uuid(key),expected=request.expectedCurrentRunId()==null?null:uuid(request.expectedCurrentRunId());
        var normalized=new Start(request.stockId(),request.origin(),request.mode(),request.stepRatio().stripTrailingZeros(),request.seed(),expected);
        String hash=SimulationOriginResolver.hash(write(normalized));
        Run run=isolation.begin(user,request.stockId(),expected,requestKey,hash,
                stock->origins.prepare(stock,normalized,request.seed()==null?random.nextLong():request.seed()));
        return current(run);
    }
    @Transactional
    public Batch advance(long user,String runId,Advance request,String key) {
        String id=uuid(runId),requestKey=uuid(key),hash=SimulationOriginResolver.hash(id+"|"+write(request));
        BatchResult result=isolation.advance(user,id,request.expectedVersion(),request.expectedBatchNo(),requestKey,hash,run->{
            if(!SimulationGenerator.VERSION.equals(run.generatorVersion()))throw new BusinessException(ErrorCode.CONFLICT,"Generator version is unsupported; saved results remain readable");
            var calendar=read(run.calendarSnapshot(),SimulationCalendar.Snapshot.class);
            var parameters=read(run.parameters(),JsonNode.class);
            var times=SimulationCalendar.minutes(MarketTime.instant(run.simClockNs()),60,calendar);
            return SimulationGenerator.generate(run.generatorVersion(),run.seed(),run.nextPointSeq(),run.latestPrice(),
                    SimulationGenerator.Mode.valueOf(parameters.required("mode").asText()),
                    new java.math.BigDecimal(parameters.required("stepRatio").asText()),times);
        });
        Step step=store.byRequest(id,requestKey);
        return new Batch(id,result.batchNo(),result.versionAfter(),MarketTime.instant(step.fromClockNs()),MarketTime.instant(step.toClockNs()),
                step.firstPointSeq(),step.lastPointSeq(),result.triggered(),result.rearmed(),result.points());
    }
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public Current current(long user){Run run=store.current(user);return run==null?null:current(run);}

    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public Timeline timeline(long user,String runId,Instant from,Instant to,String cursor) {
        Run run=owned(user,runId);var prefix=list(run.historyPrefixSnapshot(),MarketDataModels.Point.class);
        Instant lower=from==null?Instant.parse(read(run.originSnapshot(),JsonNode.class).required("prefixFrom").asText()):from,upper=to==null?MarketTime.instant(run.simClockNs()):to;
        long a=MarketTime.nanos(lower),b=MarketTime.nanos(upper);range(a,b);
        var position=cursor==null?new SimulationCursor.Position(0,run.lastBatchNo()):cursors.decode(cursor,user,run.id(),"POINTS",a,b);
        if(position.watermark()>run.lastBatchNo())throw new BusinessException(ErrorCode.VALIDATION_ERROR,"Invalid simulation watermark");
        var rows=store.pageSteps(run.id(),position.after(),position.watermark(),a,b);boolean more=rows.size()>10;
        var page=more?rows.subList(0,10):rows;
        var points=new ArrayList<StoredPoint>();
        for(Step step:page)for(StoredPoint point:list(step.pointsJson(),StoredPoint.class))
            if(!point.eventTime().isBefore(lower) && !point.eventTime().isAfter(upper))points.add(point);
        String next=more?cursors.encode(user,run.id(),"POINTS",a,b,page.getLast().batchNo(),position.watermark()):null;
        Step last=position.watermark()==0?null:store.step(run.id(),position.watermark());
        Instant clock=last==null?json.convertValue(read(run.originSnapshot(),JsonNode.class).required("point"),MarketDataModels.Point.class).eventTime():MarketTime.instant(last.toClockNs());
        var calendar=read(run.calendarSnapshot(),SimulationCalendar.Snapshot.class);
        var chartSessions=MarketDataModels.TradingSession.forRange(calendar.sessions().stream()
                .map(s->new MarketDataModels.TradingSession(s.date(),s.open(),s.close())).toList(),lower,upper);
        return new Timeline("SIMULATION",run.id(),run.stockId(),position.watermark(),clock,read(run.originSnapshot(),JsonNode.class),
                new Range(lower,upper,MarketTime.NEW_YORK.getId()),chartSessions,prefix.stream().filter(p->!p.eventTime().isBefore(lower)&&!p.eventTime().isAfter(upper)).toList(),
                List.copyOf(points),rules(run.id()),stats(store.eventCountsAt(run.id(),position.watermark())),next,Long.toString(position.watermark()));
    }
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public EventPage events(long user,String runId,Instant from,Instant to,String cursor) {
        Run run=owned(user,runId);long a=MarketTime.nanos(from),b=MarketTime.nanos(to);range(a,b);
        var position=cursor==null?new SimulationCursor.Position(0,store.eventWatermark(run.id())):cursors.decode(cursor,user,run.id(),"EVENTS",a,b);
        var rows=store.pageEvents(run.id(),position.after(),position.watermark(),a,b);boolean more=rows.size()>100;
        var page=more?rows.subList(0,100):rows;
        Map<Long,JsonNode> configs=new HashMap<>();store.snapshots(run.id()).forEach(s->configs.put(s.id(),read(s.configJson(),JsonNode.class)));
        var events=page.stream().map(e->new SimulationApi.Event(e.id(),e.snapshotRuleId(),e.pointSeq(),e.eventType(),
                MarketTime.instant(e.eventTimeNs()),Long.toString(e.eventTimeNs()),e.price().toPlainString(),e.fromState(),e.toState(),configs.get(e.snapshotRuleId()))).toList();
        String next=more?cursors.encode(user,run.id(),"EVENTS",a,b,page.getLast().id(),position.watermark()):null;
        return new EventPage(events,next,Long.toString(position.watermark()));
    }
    private Current current(Run run) {
        return new Current(run.id(),run.stockId(),run.version(),run.lastBatchNo(),MarketTime.instant(run.simClockNs()),run.latestPrice().toPlainString(),
                run.nextPointSeq(),read(run.originSnapshot(),JsonNode.class),read(run.parameters(),JsonNode.class),Long.toString(run.seed()),
                run.generatorVersion(),SimulationGenerator.VERSION.equals(run.generatorVersion()),rules(run.id()),stats(store.eventCounts(run.id())));
    }
    private List<Rules> rules(String id){return store.snapshots(id).stream().map(s->new Rules(s.id(),s.sourceRuleId(),s.sourceVersion(),read(s.configJson(),JsonNode.class),
            s.sourceStatus(),s.initialState(),s.currentState(),s.stateVersion())).toList();}
    private Stats stats(List<SimulationMapper.EventCount> rows) {
        long triggers=0,rearms=0;for(var row:rows){if(row.eventType().equals("TRIGGERED"))triggers=row.total();else if(row.eventType().equals("REARMED"))rearms=row.total();}
        return new Stats(triggers,rearms);
    }
    private Run owned(long user,String id){Run run=store.current(user);if(run==null || !run.id().equals(uuid(id)))throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND,"Simulation was not found");return run;}
    private void range(long from,long to){if(to<from)throw new BusinessException(ErrorCode.VALIDATION_ERROR,"Simulation range is reversed");}
    private static String uuid(String text) {
        try {String canonical=UUID.fromString(text).toString();if(!canonical.equalsIgnoreCase(text))throw new IllegalArgumentException();return canonical;}
        catch(RuntimeException e){throw new BusinessException(ErrorCode.VALIDATION_ERROR,"A UUID idempotency key or run reference is required");}
    }
    private String write(Object value){try{return json.writeValueAsString(value);}catch(JsonProcessingException e){throw new IllegalStateException("Cannot encode simulation request");}}
    private <T>T read(String text,Class<T> type){try{return json.readValue(text,type);}catch(JsonProcessingException e){throw new IllegalStateException("Cannot restore simulation state");}}
    private <T>List<T> list(String text,Class<T> type){try{return json.readValue(text,json.getTypeFactory().constructCollectionType(List.class,type));}catch(JsonProcessingException e){throw new IllegalStateException("Cannot restore simulation points");}}
}
