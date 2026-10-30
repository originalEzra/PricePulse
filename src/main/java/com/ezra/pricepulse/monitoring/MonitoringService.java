package com.ezra.pricepulse.monitoring;

import com.ezra.pricepulse.alert.*;
import com.ezra.pricepulse.common.error.*;
import com.ezra.pricepulse.market.MarketTime;
import com.ezra.pricepulse.matching.RuleIndex;
import com.ezra.pricepulse.monitoring.MonitoringModels.*;
import com.ezra.pricepulse.stock.*;
import com.ezra.pricepulse.user.UserMapper;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.*;
import java.util.*;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.*;
import org.springframework.transaction.support.TransactionTemplate;

@Service
@Profile("!standalone & !mq-it")
public class MonitoringService {
    private final MonitoringMapper store;
    private final StockMapper stocks;
    private final UserMapper users;
    private final AlertRuleMapper rules;
    private final RuleIndex index;
    private final AssetCatalogService assets;
    private final SubscriptionTransport transport;
    private final Clock clock;
    private final MonitoringProperties properties;
    private final TransactionTemplate transaction;
    public MonitoringService(MonitoringMapper store,StockMapper stocks,UserMapper users,AlertRuleMapper rules,RuleIndex index,
            AssetCatalogService assets,SubscriptionTransport transport,Clock clock,PlatformTransactionManager transactions,MonitoringProperties properties){
        this.store=store;this.stocks=stocks;this.users=users;this.rules=rules;this.index=index;this.assets=assets;this.transport=transport;this.clock=clock;
        this.properties=properties;
        transaction=new TransactionTemplate(transactions);transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public Page list(long user){
        requireUser(user);
        return new Page(store.rows(user,MarketTime.utc(clock.instant())).stream().map(this::entry).toList(),capacity());
    }
    public Capacity capacity(){return new Capacity(store.limit(),store.reserved(),transport.dynamicAvailable());}
    public void assertActiveUser(long user){requireUser(user);}
    public Entry add(long user,String assetId){
        requireUser(user);
        Asset asset=assets.validate(assetId); // Provider I/O never holds a business row/transaction lock.
        if(!asset.status().equals("active"))throw new BusinessException(ErrorCode.ASSET_UNAVAILABLE,"该股票已停用，无法请求监控");
        return transaction.execute(status->{
            store.importStock(asset);
            var matches=stocks.findActiveBySymbol(asset.symbol(),"US");
            if(matches.size()!=1)throw conflict("资产身份已变化，请重新搜索");
            Stock stock=lock(matches.getFirst().id());String existing=store.assetId(stock.id());
            if(existing!=null&&!existing.equals(asset.id()))throw conflict("资产身份与旧股票记录不一致");
            store.bindAsset(stock.id(),asset.id());store.lockBudget();
            var old=store.subscription(user,stock.id());if(old!=null&&old.desired())throw conflict("该股票已在个人监控列表中");
            store.target(stock.id());var target=store.getTarget(stock.id());
            if((!target.legacyHold()&&store.demand(stock.id())==0)||target.state().equals("FAILED")||!target.slotReserved())
                store.newIntent(stock.id(),"PENDING");
            reserve(store.getTarget(stock.id()));store.subscribe(user,stock.id());
            settle(stock,store.getTarget(stock.id()));return ownEntry(user,stock.id());
        });
    }
    public Entry retry(long user,long stockId,long version){
        requireUser(user);
        return transaction.execute(status->{
            Stock stock=lock(stockId);store.lockBudget();var current=requireSubscription(user,stockId);
            if(current.version()!=version)throw conflict("监控请求已变化，请刷新后重试");
            var target=store.getTarget(stockId);
            if(transport.dynamicAvailable()){
                if(!target.state().equals("FAILED")&&!transport.observe(stock.symbol(),target.intentVersion()).state().equals("FAILED"))
                    throw conflict("当前请求无需重试，请等待行情源确认");
                store.newIntent(stockId,"PENDING");
            }
            reserve(store.getTarget(stockId));store.subscribe(user,stockId);settle(stock,store.getTarget(stockId));return ownEntry(user,stockId);
        });
    }
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public Cancellation cancellation(long user,long stockId){
        requireUser(user);var request=requireSubscription(user,stockId);Stock stock=stocks.findActiveById(stockId);
        if(stock==null)throw missing();var all=rules.listSnapshotSource(user,stockId);
        return new Cancellation(stockId,stock.symbol(),request.version(),all.size(),digest(all));
    }
    public Cancelled cancel(long user,long stockId,Cancel request){
        requireUser(user);if(!request.confirmed())throw conflict("请明确确认取消监控并停用相关提醒");
        return transaction.execute(status->{
            Stock stock=lock(stockId);store.lockBudget();var subscription=requireSubscription(user,stockId);
            if(subscription.version()!=request.version())throw conflict("监控请求已变化，请重新确认");
            var all=rules.listSnapshotSource(user,stockId);
            if(!digest(all).equals(request.rulesDigest()))throw conflict("提醒规则已发生变化，请重新确认取消");
            if(store.cancel(user,stockId,request.version())!=1)throw conflict("监控请求已变化");
            for(AlertRule rule:all){
                if(rules.softDelete(rule.getId(),user,rule.getVersion())!=1)throw conflict("提醒规则已发生变化");
                rule.setStatus(AlertRuleStatus.DELETED);rule.setVersion(rule.getVersion()+1);
            }
            if(!all.isEmpty())index.changed(stock,all);
            var target=store.getTarget(stockId);boolean remaining=store.demand(stockId)>0;
            if(!remaining&&!target.legacyHold()){
                if(transport.dynamicAvailable()){
                    store.newIntent(stockId,"CANCELLED");target=store.getTarget(stockId);
                    update(target,"CANCELLED",target.slotReserved(),null,null); // The slot remains until absence is proved.
                }else update(target,"CANCELLED",false,null,null);
            }
            return new Cancelled(stockId,all.size(),remaining,target.legacyHold());
        });
    }
    /** Network effects are outside MySQL transactions; each observation is checked under the stock/budget locks. */
    @Transactional(propagation=Propagation.NOT_SUPPORTED)
    public void reconcile(){
        transport.reconcile();
        for(long id:store.targetIds())transaction.execute(status->{
        Stock stock=stocks.findActiveByIdForUpdate(id);if(stock==null)return null;store.lockBudget();var target=store.getTarget(id);
        if(transport.dynamicAvailable()&&properties.isAdoptLegacyHolds()&&target.legacyHold()
                &&store.orphanedRules(id)==0&&transport.observe(stock.symbol(),target.intentVersion()).state().equals("CONFIRMED")){
            store.adopt(id);target=store.getTarget(id);
            if(store.demand(id)==0){store.newIntent(id,"CANCELLED");target=store.getTarget(id);}
        }
        if(store.demand(id)==0&&!target.legacyHold()){
            boolean reserved=target.slotReserved()&&!transport.removalConfirmed(stock.symbol());
            if(!target.state().equals("CANCELLED")||target.slotReserved()!=reserved)update(target,"CANCELLED",reserved,null,null);
        }else if(target.slotReserved())settle(stock,target);
        return null;
        });
        transport.reconcile();
    }
    private void reserve(Target target){
        if(target==null)throw missing();
        if(!target.slotReserved()){
            if(store.reserved()>=store.limit())throw new BusinessException(ErrorCode.MONITORING_LIMIT_REACHED,"全局去重股票监控名额已满；取消后的名额需等待上游确认释放");
            update(target,"PENDING",true,null,null);
        }
    }
    private void settle(Stock stock,Target target){
        var observed=transport.observe(stock.symbol(),target.intentVersion());
        boolean rejected=observed.state().equals("FAILED");
        boolean reserved=target.legacyHold()||(transport.dynamicAvailable()
                ?!rejected||!transport.failureReleaseConfirmed(stock.symbol()):!rejected);
        if(!target.state().equals(observed.state())||target.slotReserved()!=reserved||!Objects.equals(target.confirmedEpoch(),observed.epoch())||!Objects.equals(target.errorCode(),observed.errorCode()))
            update(target,observed.state(),reserved,observed.epoch(),observed.errorCode());
    }
    private void update(Target target,String state,boolean reserved,String epoch,String error){
        if(store.targetState(target.stockId(),target.version(),state,reserved,epoch,error)!=1)throw conflict("上游订阅期望已变化，请重试");
    }
    private Entry ownEntry(long user,long stock){return store.rows(user,MarketTime.utc(clock.instant())).stream().filter(r->r.stockId()==stock).map(this::entry).findFirst().orElseThrow(MonitoringService::missing);}
    private Entry entry(Row row){
        var observed=row.slotReserved()?transport.observe(row.symbol(),row.intentVersion()):new SubscriptionTransport.Observation("FAILED",null,row.errorCode()==null?"AWAITING_CAPACITY":row.errorCode());
        Instant accepted=row.acceptedTimeNs()==null||row.acceptedPrice()==null?null:MarketTime.instant(row.acceptedTimeNs());
        Instant bar=row.barTime()==null?null:MarketTime.instant(row.barTime());
        boolean useBar=bar!=null&&(accepted==null||bar.isAfter(accepted));
        var price=useBar?row.barPrice():row.acceptedPrice();Instant time=useBar?bar:accepted;
        return new Entry(new Quote(row.stockId(),row.symbol(),row.name(),"US",row.currency(),price==null?null:price.toPlainString(),
                time,price==null?"NO_DATA":useBar?"HISTORICAL_BAR_CLOSE":"ACCEPTED_REAL_TRADE"),row.version(),observed.state(),observed.errorCode(),row.legacyHold(),row.ruleCount());
    }
    private void requireUser(long user){if(!users.existsActive(user))throw new BusinessException(ErrorCode.UNAUTHORIZED,"User is not active");}
    private Stock lock(long stock){Stock value=stocks.findActiveByIdForUpdate(stock);if(value==null)throw missing();return value;}
    private Subscription requireSubscription(long user,long stock){var value=store.subscription(user,stock);if(value==null||!value.desired())throw missing();return value;}
    private static String digest(List<AlertRule> rules){
        String value=rules.stream().map(r->r.getId()+":"+r.getVersion()+":"+r.getStatus()).collect(java.util.stream.Collectors.joining("|"));
        try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}
        catch(NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}
    }
    private static BusinessException conflict(String message){return new BusinessException(ErrorCode.CONFLICT,message);}
    private static BusinessException missing(){return new BusinessException(ErrorCode.RESOURCE_NOT_FOUND,"个人监控订阅不存在");}
}
