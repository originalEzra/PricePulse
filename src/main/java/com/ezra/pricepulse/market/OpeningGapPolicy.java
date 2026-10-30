package com.ezra.pricepulse.market;

import com.ezra.pricepulse.alert.OpeningGapContext;
import com.ezra.pricepulse.market.MarketDataModels.*;
import java.time.*;
import java.util.*;

/** A calendar boundary is eligible only while the same live lane generation remains intact. */
final class OpeningGapPolicy {
    private OpeningGapPolicy() {}
    record Transition(Session previous, Session current) {}
    private record Continuity(UUID generation, LocalDate date) {}

    static Transition transition(QuoteCandidate candidate, StreamState state, List<Session> sessions) {
        if (state==null || !"ALPACA".equals(candidate.provider()) || !"iex".equals(candidate.feed())
                || !"ALPACA".equals(state.provider()) || !"iex".equals(state.feed())) return null;
        Continuity previous=parse(state.continuityId()),current=parse(candidate.continuityId());
        if (previous==null || current==null || !previous.generation().equals(current.generation())
                || !current.date().isAfter(previous.date())) return null;
        Instant at=candidate.eventTime().toInstant(ZoneOffset.UTC),before=MarketTime.instant(state.eventTimeNs());
        if (!current.date().equals(at.atZone(MarketTime.NEW_YORK).toLocalDate())
                || !previous.date().equals(before.atZone(MarketTime.NEW_YORK).toLocalDate())) return null;
        Session session=sessions.stream().filter(s->s.sessionDate().equals(current.date()) && s.contains(at)).findFirst().orElse(null);
        if (session==null) return null;
        Session prior=sessions.stream().filter(s->s.sessionDate().isBefore(session.sessionDate()))
                .max(Comparator.comparing(Session::sessionDate)).orElse(null);
        if (prior==null || !prior.sessionDate().equals(previous.date()) || !prior.contains(before)
                || !prior.close().isBefore(session.open())) return null;
        return new Transition(prior,session);
    }

    static OpeningGapContext reference(long stockId, Transition transition, Bar bar, Coverage coverage, Instant now) {
        if (transition==null || bar==null || coverage==null || bar.id()==null || bar.id()<=0
                || bar.stockId()!=stockId || bar.revision()<1 || bar.closePrice()==null || bar.closePrice().signum()<=0
                || bar.fetchedAt()==null || coverage.syncedAt()==null) return null;
        Session prior=transition.previous();
        LocalDateTime close=prior.closesAt();
        if (!bar.intervalEnd().equals(close) || !bar.intervalStart().equals(close.minusMinutes(1))
                || bar.fetchedAt().isBefore(close) || bar.fetchedAt().isAfter(MarketTime.utc(now))
                || coverage.requestedFrom().isAfter(prior.opensAt()) || coverage.requestedTo().isBefore(close)
                || coverage.syncedAt().isBefore(bar.fetchedAt()) || coverage.syncedAt().isAfter(MarketTime.utc(now))) return null;
        return new OpeningGapContext("OPENING_GAP",bar.closePrice().toPlainString(),prior.close(),
                Long.toString(MarketTime.nanos(prior.close())),"ALPACA/iex/raw/1Min/close",bar.id(),bar.revision(),
                MarketTime.instant(bar.fetchedAt()),transition.current().sessionDate(),transition.current().open(),null);
    }

    private static Continuity parse(String text) {
        if (text==null) return null;
        int separator=text.lastIndexOf(':');
        if (separator<1) return null;
        try { return new Continuity(UUID.fromString(text.substring(0,separator)),LocalDate.parse(text.substring(separator+1))); }
        catch (IllegalArgumentException | DateTimeException ignored) { return null; }
    }
}
