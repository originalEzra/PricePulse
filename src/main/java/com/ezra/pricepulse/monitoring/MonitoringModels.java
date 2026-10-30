package com.ezra.pricepulse.monitoring;

import java.math.BigDecimal;
import java.time.*;
import java.util.List;
import jakarta.validation.constraints.*;

public final class MonitoringModels {
    private MonitoringModels(){}
    public record Asset(String id,String symbol,String name,String assetClass,String exchange,String status,
            boolean tradable,String revision,LocalDateTime fetchedAt){}
    public record AssetResult(Asset asset,boolean subscribed,boolean requestSupported){}
    public record Search(List<AssetResult> assets,boolean stale,String errorCode){}
    public record Subscription(long userId,long stockId,boolean desired,long version){}
    public record Target(long stockId,String state,boolean slotReserved,boolean legacyHold,long version,
            String confirmedEpoch,String errorCode,long intentVersion){}
    public record Demand(long stockId,String symbol,long intentVersion){}
    public record Row(long stockId,String symbol,String name,String currency,BigDecimal acceptedPrice,Long acceptedTimeNs,
            BigDecimal barPrice,LocalDateTime barTime,long version,String state,boolean slotReserved,boolean legacyHold,
            String confirmedEpoch,String errorCode,long ruleCount,long intentVersion){}
    public record Quote(long id,String symbol,String name,String market,String currency,String latestPrice,
            Instant latestQuoteAt,String priceKind){}
    public record Entry(Quote stock,long version,String state,String errorCode,boolean legacyHeld,long ruleCount){}
    public record Capacity(int limit,int reserved,boolean dynamicSubscriptions){}
    public record Page(List<Entry> subscriptions,Capacity capacity){}
    public record Add(@NotBlank @Pattern(regexp="[0-9a-fA-F-]{36}") String assetId){}
    public record Version(@Min(0) long version){}
    public record Cancellation(long stockId,String symbol,long version,long ruleCount,String rulesDigest){}
    public record Cancel(@Min(0) long version,@NotBlank @Pattern(regexp="[0-9a-f]{64}") String rulesDigest,
            @AssertTrue boolean confirmed){}
    public record Cancelled(long stockId,long deletedRules,boolean globalDemandRemaining,boolean legacyHeld){}
}
