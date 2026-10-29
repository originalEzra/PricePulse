package com.ezra.pricepulse.market;

import com.ezra.pricepulse.common.PriceTicks;
import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.*;
import java.util.*;

/** Quotes/bars are not trades. This release matches regular-sale trades, not every sale condition. */
public final class AlpacaProtocol {
    private AlpacaProtocol() {}
    public record Trade(String symbol,String eventKey,BigDecimal price,Instant eventTime,Instant receivedAt) {}
    public static Trade trade(JsonNode item, Instant receivedAt, Set<String> symbols) {
        if (!"t".equals(item.path("T").asText())) return null;
        String symbol=item.required("S").asText();
        if (!symbols.contains(symbol)) throw new IllegalArgumentException("UNSUBSCRIBED_SYMBOL");
        JsonNode conditions=item.required("c");
        if (!conditions.isArray() || conditions.isEmpty()) throw new IllegalArgumentException("INVALID_CONDITIONS");
        for (JsonNode condition:conditions) {
            // Conservative explicit last-sale policy. Odd-lot/late/average-price trades are excluded.
            if (!Set.of("@","F").contains(condition.asText())) return null;
        }
        JsonNode rawPrice=item.required("p");
        if (!rawPrice.isNumber() || !item.required("s").isIntegralNumber() || item.get("s").asLong()<=0)
            throw new IllegalArgumentException("INVALID_TRADE");
        BigDecimal price=PriceTicks.normalize(rawPrice.decimalValue(),"trade price");
        Instant time=Instant.parse(item.required("t").asText());
        MarketTime.nanos(time); // Reject out-of-range timestamps before any queue or persistence.
        String exchange=item.required("x").asText(),id=item.required("i").asText();
        if (exchange.isBlank() || exchange.length()>8 || !id.matches("[0-9]{1,24}"))
            throw new IllegalArgumentException("INVALID_TRADE_ID");
        String identity="ALPACA|iex|"+symbol+"|"+time.atZone(MarketTime.NEW_YORK).toLocalDate()+"|"+exchange+"|"+id;
        try {
            String key="alpaca:iex:"+HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(identity.getBytes(StandardCharsets.UTF_8)));
            return new Trade(symbol,key,price,time,receivedAt);
        } catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
}
