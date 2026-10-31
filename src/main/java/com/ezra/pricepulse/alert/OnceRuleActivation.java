package com.ezra.pricepulse.alert;

import com.ezra.pricepulse.market.*;
import com.ezra.pricepulse.stock.Stock;
import java.time.*;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

/** Reuses an accepted quote, never inserts a synthetic quote. Caller already holds the stock lock. */
@Service @Profile("!standalone & !mq-it")
public class OnceRuleActivation {
    private final QuoteEventMapper quotes;
    private final MarketDataMapper data;
    private final QuoteReadiness readiness;
    private final MarketProperties properties;
    private final Clock clock;
    private final AlertEvaluationService evaluation;
    public OnceRuleActivation(QuoteEventMapper quotes,MarketDataMapper data,QuoteReadiness readiness,MarketProperties properties,Clock clock,AlertEvaluationService evaluation){
        this.quotes=quotes;this.data=data;this.readiness=readiness;this.properties=properties;this.clock=clock;this.evaluation=evaluation;
    }
    @Transactional(propagation=Propagation.MANDATORY)
    public void confirm(Stock stock,AlertRule rule,boolean created){
        if(rule.getStatus()!=AlertRuleStatus.ACTIVE||rule.getTriggerMode()!=AlertTriggerMode.ONCE)return;
        var state=data.streamState(stock.id());
        if(state==null||!"ALPACA".equals(state.provider())||!"iex".equals(state.feed())||!readiness.canConfirmLatest(stock,state))return;
        var quote=quotes.latestAcceptedReal(stock.id());var now=clock.instant();
        if(rule.getEffectiveAt()==null||now.isBefore(MarketTime.instant(rule.getEffectiveAt())))return;
        if(quote==null||quote.getEventTimeNs()==null||quote.getReceivedAt()==null||quote.getProcessedAt()==null
                ||stock.latestPrice()==null||quote.getPrice().compareTo(stock.latestPrice())!=0)return;
        var time=MarketTime.instant(quote.getEventTimeNs());var oldest=now.minus(properties.liveMaxAge());
        if(time.isBefore(oldest)||time.isAfter(now)||MarketTime.instant(quote.getReceivedAt()).isBefore(oldest)
                ||MarketTime.instant(quote.getProcessedAt()).isBefore(oldest)||MarketTime.instant(quote.getReceivedAt()).isAfter(now)
                ||MarketTime.instant(quote.getProcessedAt()).isAfter(now))return;
        evaluation.confirmOnceRule(stock,rule,quote.getId(),quote.getPrice(),MarketTime.utc(now),
                new TargetReachContext("TARGET_REACHED",created?"RULE_CREATED":"RULE_ACTIVATED",now,time,null));
    }
}
