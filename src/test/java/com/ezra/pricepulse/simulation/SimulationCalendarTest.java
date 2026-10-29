package com.ezra.pricepulse.simulation;

import static org.assertj.core.api.Assertions.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class SimulationCalendarTest {
    SimulationCalendar.Session session(String date,String open,String close) {
        var zone=ZoneId.of("America/New_York");var d=LocalDate.parse(date);
        return new SimulationCalendar.Session(date,ZonedDateTime.of(d,LocalTime.parse(open),zone).toInstant(),ZonedDateTime.of(d,LocalTime.parse(close),zone).toInstant());
    }
    SimulationCalendar.Snapshot calendar(SimulationCalendar.Session...sessions){return new SimulationCalendar.Snapshot("fixture-v1",List.of(sessions));}
    @Test void skipsWeekendAndDstWhilePreservingFractionalMinuteRemainder() {
        var friday=session("2026-10-30","09:30","16:00");var monday=session("2026-11-02","09:30","16:00");
        var origin=friday.close().minusSeconds(30).plusNanos(123456789);
        var points=SimulationCalendar.minutes(origin,60,calendar(friday,monday));
        assertThat(points).hasSize(60);assertThat(points.getFirst()).isEqualTo(monday.open().plusSeconds(30).plusNanos(123456789));
        assertThat(points.getLast()).isEqualTo(monday.open().plusSeconds(59*60+30).plusNanos(123456789));
        assertThat(monday.open()).isEqualTo(Instant.parse("2026-11-02T14:30:00Z"));
    }
    @Test void earlyCloseAndHolidayUseOnlyFrozenScheduleAndTotalSixtyTradingMinutes() {
        var early=session("2026-11-27","09:30","13:00");var next=session("2026-11-30","09:30","16:00");
        var points=SimulationCalendar.minutes(early.close().minusSeconds(20*60),60,calendar(early,next));
        assertThat(points.get(19)).isEqualTo(early.close());
        assertThat(points.get(20)).isEqualTo(next.open().plusSeconds(60));
        assertThat(points.getLast()).isEqualTo(next.open().plusSeconds(40*60));
    }
    @Test void originAtCloseOrDuringRestStartsCountingAtNextOpen() {
        var a=session("2026-07-02","09:30","16:00");var b=session("2026-07-06","09:30","16:00");
        assertThat(SimulationCalendar.minutes(a.close(),1,calendar(a,b))).containsExactly(b.open().plusSeconds(60));
        assertThat(SimulationCalendar.minutes(a.close().plusSeconds(100),1,calendar(a,b))).containsExactly(b.open().plusSeconds(60));
    }
    @Test void exhaustedInvalidOrInsufficientCalendarFailsInsteadOfReturningPartialHour() {
        var a=session("2026-10-28","09:30","16:00");
        assertThatThrownBy(()->SimulationCalendar.minutes(a.close().minusSeconds(60),60,calendar(a))).hasMessageContaining("calendar");
        assertThatThrownBy(()->SimulationCalendar.minutes(a.open().minusSeconds(1),1,calendar(a))).hasMessageContaining("calendar");
        assertThatThrownBy(()->SimulationCalendar.minutes(a.open(),1,calendar(a,a))).hasMessageContaining("calendar");
    }
}
