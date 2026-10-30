package com.ezra.pricepulse.market;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Never use record-generated toString for credentials. Only IEX market-data endpoints are supported. */
@ConfigurationProperties("price-pulse.alpaca")
public class AlpacaProperties {
    private boolean enabled;
    private boolean businessWritesEnabled;
    private String keyId = "";
    private String secretKey = "";
    private String environment = "paper";
    private List<String> symbols = MarketUniverse.SYMBOLS;
    private int queueCapacity = 1024;
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean v) { enabled = v; }
    public boolean isBusinessWritesEnabled() { return businessWritesEnabled; }
    public void setBusinessWritesEnabled(boolean v) { businessWritesEnabled = v; }
    public String getKeyId() { return keyId; }
    public void setKeyId(String v) { keyId = v; }
    public String getSecretKey() { return secretKey; }
    public void setSecretKey(String v) { secretKey = v; }
    public String getEnvironment() { return environment; }
    public void setEnvironment(String v) { environment = v; }
    public List<String> getSymbols() { return symbols; }
    public void setSymbols(List<String> v) { symbols = List.copyOf(v); }
    public int getQueueCapacity() { return queueCapacity; }
    public void setQueueCapacity(int v) { queueCapacity = v; }
    public void validate() {
        if (!enabled) return;
        if (keyId.isBlank() || secretKey.isBlank()) throw new IllegalStateException("Alpaca credentials are not configured");
        if (!List.of("paper", "live").contains(environment)) throw new IllegalStateException("Unsupported Alpaca environment");
        if (symbols.isEmpty() || symbols.size() > MarketUniverse.MAX_SYMBOLS
                || new java.util.HashSet<>(symbols).size()!=symbols.size() || symbols.stream().anyMatch(s -> !MarketUniverse.supports(s)))
            throw new IllegalStateException("Configure 1–15 distinct supported US symbols");
        if (queueCapacity < 1 || queueCapacity > 10000) throw new IllegalStateException("Invalid market queue capacity");
    }
    @Override public String toString() { return "AlpacaProperties[credentials=REDACTED]"; }
}
