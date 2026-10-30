package com.ezra.pricepulse.market;

import java.util.List;
import java.util.Set;

/** One bounded catalog shared by real ingestion and independent simulation origins. */
public final class MarketUniverse {
    private MarketUniverse() {}
    public static final int MAX_SYMBOLS=15;
    public static final List<String> SYMBOLS=List.of("AAPL","MSFT","NVDA","AMZN","GOOGL",
            "ORCL","SMCI","SOFI","AMD","TSLA","CRWD","MU","INTC","BA","HOOD");
    private static final Set<String> SUPPORTED=Set.copyOf(SYMBOLS);
    public static boolean supports(String symbol){return SUPPORTED.contains(symbol);}
}
