package com.ezra.pricepulse.monitoring;

import com.ezra.pricepulse.monitoring.MonitoringModels.*;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.*;

@Mapper
public interface MonitoringMapper {
    @Select("SELECT id,symbol,name,asset_class,exchange,status,tradable,revision,fetched_at FROM alpaca_assets WHERE symbol LIKE #{pattern} ESCAPE '!' OR name LIKE #{pattern} ESCAPE '!' ORDER BY CASE WHEN symbol=#{query} THEN 0 ELSE 1 END,symbol LIMIT #{limit}")
    List<Asset> search(@Param("query")String query,@Param("pattern")String pattern,@Param("limit")int limit);
    @Select("SELECT id,symbol,name,asset_class,exchange,status,tradable,revision,fetched_at FROM alpaca_assets WHERE id=#{id}")
    Asset asset(String id);
    @Select("SELECT MAX(fetched_at) FROM alpaca_assets") LocalDateTime catalogTime();
    @Insert("""
        <script>INSERT INTO alpaca_assets(id,symbol,name,asset_class,exchange,status,tradable,revision,fetched_at) VALUES
        <foreach collection="assets" item="a" separator=",">(#{a.id},#{a.symbol},#{a.name},#{a.assetClass},#{a.exchange},#{a.status},#{a.tradable},#{a.revision},#{a.fetchedAt})</foreach>
        AS v ON DUPLICATE KEY UPDATE id=v.id,symbol=v.symbol,name=v.name,asset_class=v.asset_class,exchange=v.exchange,status=v.status,
        tradable=v.tradable,revision=v.revision,fetched_at=v.fetched_at</script>
        """) void assets(@Param("assets")List<Asset> assets);
    @Update("UPDATE alpaca_assets SET status='inactive' WHERE revision<>#{revision}")
    void retireUnseen(String revision);
    @Insert("INSERT INTO stocks(symbol,name,market,currency,alpaca_asset_id) VALUES(#{a.symbol},#{a.name},'US','USD',#{a.id}) ON DUPLICATE KEY UPDATE id=LAST_INSERT_ID(id)")
    void importStock(@Param("a")Asset asset);
    @Select("SELECT alpaca_asset_id FROM stocks WHERE id=#{stockId}") String assetId(long stockId);
    @Update("UPDATE stocks SET alpaca_asset_id=#{assetId} WHERE id=#{stockId} AND alpaca_asset_id IS NULL")
    void bindAsset(@Param("stockId")long stockId,@Param("assetId")String assetId);
    @Select("SELECT symbol_limit FROM market_subscription_control WHERE id=1 FOR UPDATE") int lockBudget();
    @Select("SELECT symbol_limit FROM market_subscription_control WHERE id=1") int limit();
    @Select("SELECT COUNT(*) FROM market_subscription_targets WHERE slot_reserved=TRUE") int reserved();
    @Insert("INSERT IGNORE INTO market_subscription_targets(stock_id) VALUES(#{stockId})") void target(long stockId);
    @Select("SELECT stock_id,state,slot_reserved,legacy_hold,version,confirmed_epoch,error_code,intent_version FROM market_subscription_targets WHERE stock_id=#{stockId}") Target getTarget(long stockId);
    @Select("SELECT stock_id FROM market_subscription_targets ORDER BY stock_id") List<Long> targetIds();
    @Select("SELECT t.stock_id,s.symbol,t.intent_version FROM market_subscription_targets t JOIN stocks s ON s.id=t.stock_id WHERE s.status='ACTIVE' AND t.slot_reserved=TRUE AND (t.legacy_hold=TRUE OR EXISTS(SELECT 1 FROM user_stock_subscriptions u WHERE u.stock_id=t.stock_id AND u.desired=TRUE)) ORDER BY t.stock_id")
    List<Demand> liveDemand();
    @Update("UPDATE market_subscription_targets SET intent_version=intent_version+1,version=version+1,state=#{state},confirmed_epoch=NULL,error_code=NULL WHERE stock_id=#{stockId}")
    void newIntent(@Param("stockId")long stockId,@Param("state")String state);
    @Update("UPDATE market_subscription_targets SET legacy_hold=FALSE,version=version+1 WHERE stock_id=#{stockId} AND legacy_hold=TRUE")
    void adopt(long stockId);
    @Select("SELECT COUNT(*) FROM alert_rules r WHERE r.stock_id=#{stockId} AND r.status IN ('ACTIVE','COOLDOWN') AND NOT EXISTS(SELECT 1 FROM user_stock_subscriptions u WHERE u.stock_id=r.stock_id AND u.user_id=r.user_id AND u.desired=TRUE)")
    long orphanedRules(long stockId);
    @Update("UPDATE market_subscription_targets SET state=#{state},slot_reserved=#{reserved},confirmed_epoch=#{epoch},error_code=#{error},version=version+1 WHERE stock_id=#{stockId} AND version=#{version}")
    int targetState(@Param("stockId")long stockId,@Param("version")long version,@Param("state")String state,
            @Param("reserved")boolean reserved,@Param("epoch")String epoch,@Param("error")String error);
    @Select("SELECT user_id,stock_id,desired,version FROM user_stock_subscriptions WHERE user_id=#{user} AND stock_id=#{stock}")
    Subscription subscription(@Param("user")long user,@Param("stock")long stock);
    @Select("SELECT COUNT(*) FROM user_stock_subscriptions WHERE stock_id=#{stock} AND desired=TRUE") long demand(long stock);
    @Insert("INSERT INTO user_stock_subscriptions(user_id,stock_id) VALUES(#{user},#{stock}) ON DUPLICATE KEY UPDATE desired=TRUE,version=version+1")
    void subscribe(@Param("user")long user,@Param("stock")long stock);
    @Update("UPDATE user_stock_subscriptions SET desired=FALSE,version=version+1 WHERE user_id=#{user} AND stock_id=#{stock} AND desired=TRUE AND version=#{version}")
    int cancel(@Param("user")long user,@Param("stock")long stock,@Param("version")long version);
    @Select("""
        SELECT s.id stock_id,s.symbol,s.name,s.currency,
          CASE WHEN m.provider='ALPACA' AND m.feed='iex' THEN s.latest_price END accepted_price,
          CASE WHEN m.provider='ALPACA' AND m.feed='iex' THEN m.event_time_ns END accepted_time_ns,
          b.close_price bar_price,b.interval_end bar_time,u.version,t.state,t.slot_reserved,t.legacy_hold,t.confirmed_epoch,t.error_code,
          (SELECT COUNT(*) FROM alert_rules r WHERE r.user_id=u.user_id AND r.stock_id=s.id AND r.status!='DELETED') rule_count,t.intent_version
        FROM user_stock_subscriptions u JOIN stocks s ON s.id=u.stock_id
        JOIN market_subscription_targets t ON t.stock_id=s.id LEFT JOIN market_stream_state m ON m.stock_id=s.id
        LEFT JOIN market_price_bars b ON b.id=(SELECT z.id FROM market_price_bars z WHERE z.stock_id=s.id
          AND z.provider='ALPACA' AND z.feed='iex' AND z.resolution='1Min' AND z.adjustment='raw'
          AND z.interval_end<=#{now} ORDER BY z.interval_end DESC,z.id DESC LIMIT 1)
        WHERE u.user_id=#{user} AND u.desired=TRUE ORDER BY s.symbol,s.id
        """) List<Row> rows(@Param("user")long user,@Param("now")LocalDateTime now);
}
