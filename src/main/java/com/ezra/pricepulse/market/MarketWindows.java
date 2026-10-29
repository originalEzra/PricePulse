package com.ezra.pricepulse.market;

import com.ezra.pricepulse.market.MarketDataModels.*;
import com.ezra.pricepulse.common.error.*;
import java.time.*;
import java.util.*;

/** No calendar guessing; ranges use UTC elapsed time, with NY calendar arithmetic for 1M. */
public final class MarketWindows {
    private MarketWindows() {}
    public static Range resolve(String window, Instant anchor, Instant now, List<Session> sessions) {
        if (!Set.of("1H","1D","1W","1M").contains(window))
            throw new BusinessException(ErrorCode.VALIDATION_ERROR,"window must be 1H, 1D, 1W or 1M");
        if (anchor.isAfter(now)) throw new BusinessException(ErrorCode.VALIDATION_ERROR,"anchor cannot be in the future");
        LocalDate date = anchor.atZone(MarketTime.NEW_YORK).toLocalDate();
        if (sessions.isEmpty() || date.isBefore(sessions.getFirst().sessionDate()) || date.isAfter(sessions.getLast().sessionDate()))
            throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE,"Trading calendar is not cached for this date");
        Session session = sessions.stream().filter(s -> !s.open().isAfter(anchor)).reduce((a,b) -> b)
                .orElseThrow(() -> new BusinessException(ErrorCode.SERVICE_UNAVAILABLE,"Previous trading session is unavailable"));
        Instant asOf = anchor.isAfter(session.close()) ? session.close() : anchor;
        Instant from = switch(window) {
            case "1H" -> asOf.minus(Duration.ofHours(1));
            case "1D" -> session.open();
            case "1W" -> asOf.atZone(MarketTime.NEW_YORK).minusDays(7).toInstant();
            default -> asOf.atZone(MarketTime.NEW_YORK).minusMonths(1).toInstant();
        };
        Instant to = window.equals("1D") ? session.close() : asOf;
        return new Range(from,to,asOf,MarketTime.NEW_YORK.getId());
    }
}
