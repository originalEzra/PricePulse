package com.ezra.pricepulse.market;

import java.time.*;
import java.time.temporal.ChronoUnit;

public final class MarketTime {
    public static final ZoneId NEW_YORK = ZoneId.of("America/New_York");
    private MarketTime() {}
    public static long nanos(Instant instant) {
        return Math.addExact(Math.multiplyExact(instant.getEpochSecond(), 1_000_000_000L), instant.getNano());
    }
    public static Instant instant(long nanos) {
        return Instant.ofEpochSecond(Math.floorDiv(nanos, 1_000_000_000L), Math.floorMod(nanos, 1_000_000_000L));
    }
    public static LocalDateTime utc(Instant instant) {
        return LocalDateTime.ofInstant(instant, ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS);
    }
    public static Instant instant(LocalDateTime time) { return time.toInstant(ZoneOffset.UTC); }
}
