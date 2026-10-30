package com.ezra.pricepulse.alert;

import java.math.BigDecimal;
import java.time.*;

/** Immutable provenance of an opening comparison; execution price/time stay on the actual quote. */
public record OpeningGapContext(String kind, String referencePrice, Instant referenceTime,
        String referenceEpochNanos, String referenceSource, long referenceBarId, long referenceRevision,
        Instant referenceFetchedAt, LocalDate sessionDate, Instant sessionOpen, Instant ruleEffectiveAt) {
    public OpeningGapContext {
        if (!"OPENING_GAP".equals(kind) || new BigDecimal(referencePrice).signum()<=0
                || !"ALPACA/iex/raw/1Min/close".equals(referenceSource) || referenceBarId<=0 || referenceRevision<1
                || referenceTime==null || referenceFetchedAt==null || sessionDate==null || sessionOpen==null)
            throw new IllegalArgumentException("Invalid opening reference");
    }

    public boolean eligible(LocalDateTime effectiveAt) {
        return effectiveAt!=null && effectiveAt.toInstant(ZoneOffset.UTC).isBefore(sessionOpen);
    }

    public OpeningGapContext forRule(LocalDateTime effectiveAt) {
        if (!eligible(effectiveAt)) throw new IllegalArgumentException("Rule was not effective before opening");
        return new OpeningGapContext(kind,referencePrice,referenceTime,referenceEpochNanos,referenceSource,
                referenceBarId,referenceRevision,referenceFetchedAt,sessionDate,sessionOpen,effectiveAt.toInstant(ZoneOffset.UTC));
    }
}
