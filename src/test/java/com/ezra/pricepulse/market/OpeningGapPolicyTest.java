package com.ezra.pricepulse.market;

import static org.assertj.core.api.Assertions.*;
import com.ezra.pricepulse.market.MarketDataModels.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class OpeningGapPolicyTest {
    private final String generation="00000000-0000-4000-8000-000000000001";
    private final Session prior=session("2026-10-28","13:30","20:00"),current=session("2026-10-29","13:30","20:00");

    @Test void uninterruptedLaneAcrossTheVerifiedTradingDayBoundaryUsesAnExactClosedMinuteReference() {
        var transition=transition(prior,current,generation);
        assertThat(transition).isNotNull();
        var context=OpeningGapPolicy.reference(1,transition,bar(prior,"100.1234"),coverage(prior,current),current.open());
        assertThat(context).isNotNull();assertThat(context.kind()).isEqualTo("OPENING_GAP");
        assertThat(context.referencePrice()).isEqualTo("100.1234");assertThat(context.referenceTime()).isEqualTo(prior.close());
        assertThat(context.referenceEpochNanos()).isEqualTo(Long.toString(MarketTime.nanos(prior.close())));
        assertThat(context.referenceSource()).isEqualTo("ALPACA/iex/raw/1Min/close");assertThat(context.referenceRevision()).isEqualTo(2);
    }

    @Test void startupOrAnOvernightReconnectCannotBeMistakenForNormalOpening() {
        assertThat(transition(prior,current,"00000000-0000-4000-8000-000000000002")).isNull();
        assertThat(OpeningGapPolicy.transition(candidate(current,generation),null,List.of(prior,current))).isNull();
    }

    @Test void aDateChangeAloneIsNotEvidenceOfContinuity() {
        var state=state(prior,"startup");
        assertThat(OpeningGapPolicy.transition(candidate(current,"startup"),state,List.of(prior,current))).isNull();
        var malformed=new QuoteCandidate("x",1,new BigDecimal("110"),current.opensAt(),"ALPACA_IEX",null,current.open(),
                "ALPACA","iex",generation+":2026-10-30",null);
        assertThat(OpeningGapPolicy.transition(malformed,state(prior,generation),List.of(prior,current))).isNull();
    }

    @Test void weekendsHolidaysDstAndEarlyClosesUseAdjacentCalendarSessions() {
        for(var pair:List.of(
                List.of(session("2026-10-23","13:30","20:00"),session("2026-10-26","13:30","20:00")),
                List.of(session("2026-10-30","13:30","20:00"),session("2026-11-02","14:30","21:00")),
                List.of(session("2026-11-25","14:30","21:00"),session("2026-11-27","14:30","18:00")),
                List.of(session("2026-11-27","14:30","18:00"),session("2026-11-30","14:30","21:00")))) {
            var previous=pair.getFirst();var next=pair.getLast();
            var transition=transition(previous,next,generation);assertThat(transition).isNotNull();
            assertThat(OpeningGapPolicy.reference(1,transition,bar(previous,"100"),coverage(previous,next),next.open())).isNotNull();
        }
    }

    @Test void missingAnInterveningTradingDayOrTheCurrentCalendarFailsClosed() {
        var older=session("2026-10-27","13:30","20:00");
        assertThat(OpeningGapPolicy.transition(candidate(current,generation),state(older,generation),List.of(older,prior,current))).isNull();
        assertThat(OpeningGapPolicy.transition(candidate(current,generation),state(prior,generation),List.of(prior))).isNull();
    }

    @Test void intradayAndExtendedHoursDoNotBecomeOpeningComparisons() {
        var sameDate=new QuoteCandidate("same",1,new BigDecimal("110"),prior.opensAt().plusHours(1),"ALPACA_IEX",null,prior.open().plusSeconds(3600),
                "ALPACA","iex",generation+":"+prior.sessionDate(),null);
        assertThat(OpeningGapPolicy.transition(sameDate,state(prior,generation),List.of(prior,current))).isNull();
        for(var at:List.of(current.open().minusNanos(1),current.close())) {
            var extended=new QuoteCandidate("extended",1,new BigDecimal("110"),LocalDateTime.ofInstant(at,ZoneOffset.UTC),"ALPACA_IEX",null,at,
                    "ALPACA","iex",generation+":"+current.sessionDate(),null);
            assertThat(OpeningGapPolicy.transition(extended,state(prior,generation),List.of(prior,current))).isNull();
        }
    }

    @Test void noReferenceOrIncompleteHistoryDoesNotSubstituteAnArbitraryOldPrice() {
        var transition=transition(prior,current,generation);var bar=bar(prior,"100");var coverage=coverage(prior,current);
        assertThat(OpeningGapPolicy.reference(1,transition,null,coverage,current.open())).isNull();
        assertThat(OpeningGapPolicy.reference(1,transition,bar,null,current.open())).isNull();
        assertThat(OpeningGapPolicy.reference(1,transition,bar,new Coverage(prior.opensAt(),prior.closesAt().minusSeconds(1),coverage.syncedAt()),current.open())).isNull();
        assertThat(OpeningGapPolicy.reference(1,transition,bar,new Coverage(prior.opensAt().plusSeconds(1),coverage.requestedTo(),coverage.syncedAt()),current.open())).isNull();
    }

    @Test void wrongStockStaleMinuteUnclosedOrFutureReferenceIsRejected() {
        var transition=transition(prior,current,generation);var good=bar(prior,"100");var coverage=coverage(prior,current);
        for(var bad:List.of(
                new Bar(1L,2,good.intervalStart(),good.intervalEnd(),good.openPrice(),good.highPrice(),good.lowPrice(),good.closePrice(),2,good.fetchedAt()),
                new Bar(1L,1,good.intervalStart().minusMinutes(1),good.intervalEnd().minusMinutes(1),good.openPrice(),good.highPrice(),good.lowPrice(),good.closePrice(),2,good.fetchedAt()),
                new Bar(1L,1,good.intervalStart(),good.intervalEnd(),good.openPrice(),good.highPrice(),good.lowPrice(),good.closePrice(),2,prior.closesAt().minusSeconds(1)),
                new Bar(1L,1,good.intervalStart(),good.intervalEnd(),good.openPrice(),good.highPrice(),good.lowPrice(),good.closePrice(),2,current.opensAt().plusSeconds(1))))
            assertThat(OpeningGapPolicy.reference(1,transition,bad,coverage,current.open())).isNull();
    }

    @Test void rulesMustHaveBecomeEffectiveStrictlyBeforeTheScheduledOpenEvenForALateFirstTrade() {
        var context=OpeningGapPolicy.reference(1,transition(prior,current,generation),bar(prior,"100"),coverage(prior,current),current.open());
        assertThat(context.eligible(current.opensAt().minusNanos(1))).isTrue();
        assertThat(context.eligible(current.opensAt())).isFalse();assertThat(context.eligible(current.opensAt().plusMinutes(1))).isFalse();
        assertThat(context.eligible(null)).isFalse();
        assertThat(context.forRule(current.opensAt().minusMinutes(5)).ruleEffectiveAt()).isEqualTo(current.open().minusSeconds(300));
    }

    private OpeningGapPolicy.Transition transition(Session previous,Session next,String nextGeneration) {
        return OpeningGapPolicy.transition(candidate(next,nextGeneration),state(previous,generation),List.of(previous,next));
    }
    private QuoteCandidate candidate(Session session,String lane) {
        return new QuoteCandidate("open",1,new BigDecimal("110.4567"),session.opensAt(),"ALPACA_IEX",null,session.open(),
                "ALPACA","iex",lane+":"+session.sessionDate(),"STARTUP");
    }
    private StreamState state(Session session,String lane) {
        var at=session.close().minusNanos(1);
        return new StreamState(1,"ALPACA","iex",MarketTime.nanos(at),10,lane+":"+session.sessionDate(),MarketTime.utc(at),MarketTime.utc(at));
    }
    private Bar bar(Session session,String price) {
        return new Bar(1L,1,session.closesAt().minusMinutes(1),session.closesAt(),new BigDecimal("100"),new BigDecimal("101"),new BigDecimal("99"),new BigDecimal(price),2,session.closesAt().plusMinutes(5));
    }
    private Coverage coverage(Session previous,Session next) {
        return new Coverage(previous.opensAt(),next.opensAt().minusMinutes(1),next.opensAt().minusMinutes(1));
    }
    private Session session(String date,String open,String close) {
        return new Session(LocalDate.parse(date),LocalDateTime.parse(date+"T"+open+":00"),LocalDateTime.parse(date+"T"+close+":00"));
    }
}
