package com.ezra.pricepulse.market;

public enum QuoteOutcome {
    PROCESSED,
    DUPLICATE,
    DISCARDED_STALE,
    DISCARDED_FUTURE,
    DISCARDED_OUT_OF_ORDER
}
