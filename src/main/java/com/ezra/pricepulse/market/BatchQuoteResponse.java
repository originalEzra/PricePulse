package com.ezra.pricepulse.market;

import java.util.List;

public record BatchQuoteResponse(
        int requested,
        long processed,
        long duplicates,
        long discarded,
        List<QuoteResult> results) {

    static BatchQuoteResponse from(List<QuoteResult> results) {
        long processed = results.stream()
                .filter(result -> result.outcome() == QuoteOutcome.PROCESSED)
                .count();
        long duplicates = results.stream()
                .filter(result -> result.outcome() == QuoteOutcome.DUPLICATE)
                .count();
        return new BatchQuoteResponse(
                results.size(),
                processed,
                duplicates,
                results.size() - processed - duplicates,
                List.copyOf(results));
    }
}
