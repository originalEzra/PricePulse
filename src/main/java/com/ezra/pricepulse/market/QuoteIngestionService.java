package com.ezra.pricepulse.market;

import com.ezra.pricepulse.alert.AlertEvaluationService;
import com.ezra.pricepulse.common.PriceTicks;
import com.ezra.pricepulse.matching.MatchingResult;
import com.ezra.pricepulse.common.error.BusinessException;
import com.ezra.pricepulse.common.error.ErrorCode;
import com.ezra.pricepulse.stock.Stock;
import com.ezra.pricepulse.stock.StockMapper;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Isolation;

@Service
@Profile("!standalone & !mq-it")
public class QuoteIngestionService {
    private static final String STALE = "STALE";
    private static final String FUTURE = "FUTURE";
    private static final String OUT_OF_ORDER = "OUT_OF_ORDER";

    private final QuoteEventMapper quoteEventMapper;
    private final StockMapper stockMapper;
    private final MarketProperties properties;
    private final Clock clock;
    private final AlertEvaluationService evaluation;

    public QuoteIngestionService(
            QuoteEventMapper quoteEventMapper,
            StockMapper stockMapper,
            MarketProperties properties,
            Clock clock,
            AlertEvaluationService evaluation) {
        this.quoteEventMapper = quoteEventMapper;
        this.stockMapper = stockMapper;
        this.properties = properties;
        this.clock = clock;
        this.evaluation = evaluation;
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public QuoteResult ingest(QuoteCandidate candidate, QuoteIngestionPolicy policy) {
        candidate = new QuoteCandidate(candidate.eventKey(), candidate.stockId(), candidate.price(),
                candidate.eventTime().truncatedTo(ChronoUnit.MICROS), candidate.source(), candidate.sequenceNo());
        Stock stock = stockMapper.findActiveByIdForUpdate(candidate.stockId());
        if (stock == null) {
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "Stock was not found");
        }

        BigDecimal price = PriceTicks.normalize(candidate.price(), "price");
        QuoteEvent existing = quoteEventMapper.findByEventKey(candidate.eventKey());
        if (existing != null) {
            return duplicate(existing, candidate, price, stock.latestPrice());
        }

        QuoteEvent event = buildEvent(candidate, price);
        try {
            quoteEventMapper.insert(event);
        } catch (DuplicateKeyException exception) {
            QuoteEvent concurrent = quoteEventMapper.findByEventKey(candidate.eventKey());
            if (concurrent != null) {
                return duplicate(concurrent, candidate, price, stock.latestPrice());
            }
            throw exception;
        }

        if (policy == QuoteIngestionPolicy.LIVE) {
            LocalDateTime now = LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
            if (candidate.eventTime().isBefore(now.minus(properties.liveMaxAge()))) {
                return discard(event, stock.latestPrice(), QuoteOutcome.DISCARDED_STALE, STALE);
            }
            if (candidate.eventTime().isAfter(now.plus(properties.maxFutureSkew()))) {
                return discard(event, stock.latestPrice(), QuoteOutcome.DISCARDED_FUTURE, FUTURE);
            }
        }

        if (stock.latestQuoteAt() != null && !candidate.eventTime().isAfter(stock.latestQuoteAt())) {
            return discard(
                    event, stock.latestPrice(), QuoteOutcome.DISCARDED_OUT_OF_ORDER, OUT_OF_ORDER);
        }

        MatchingResult matched = evaluation.evaluate(stock, event.getId(), price, candidate.eventTime());
        if (stockMapper.updateLatestQuote(stock.id(), price, candidate.eventTime()) != 1
                || quoteEventMapper.markProcessed(event.getId()) != 1) {
            throw new BusinessException(ErrorCode.CONFLICT, "Quote state changed concurrently");
        }
        return new QuoteResult(event.getId(), event.getEventKey(), stock.id(), stock.latestPrice(),
                price, candidate.eventTime(), QuoteOutcome.PROCESSED, null, matched);
    }

    private QuoteEvent buildEvent(QuoteCandidate candidate, BigDecimal price) {
        var event = new QuoteEvent();
        event.setEventKey(candidate.eventKey());
        event.setStockId(candidate.stockId());
        event.setPrice(price);
        event.setEventTime(candidate.eventTime());
        event.setSource(candidate.source());
        event.setSequenceNo(candidate.sequenceNo());
        return event;
    }

    private QuoteResult discard(
            QuoteEvent event,
            BigDecimal previousPrice,
            QuoteOutcome outcome,
            String reason) {
        if (quoteEventMapper.markDiscarded(event.getId(), reason) != 1) {
            throw new BusinessException(ErrorCode.CONFLICT, "Quote state changed concurrently");
        }
        return result(event, previousPrice, outcome, reason);
    }

    private QuoteResult duplicate(
            QuoteEvent event,
            QuoteCandidate candidate,
            BigDecimal normalizedPrice,
            BigDecimal previousPrice) {
        boolean samePayload = event.getStockId() == candidate.stockId()
                && event.getPrice().compareTo(normalizedPrice) == 0
                && event.getEventTime().equals(candidate.eventTime())
                && event.getSource().equals(candidate.source())
                && Objects.equals(event.getSequenceNo(), candidate.sequenceNo());
        if (!samePayload) {
            throw new BusinessException(
                    ErrorCode.CONFLICT,
                    "eventKey already belongs to a different quote payload");
        }
        return result(event, previousPrice, QuoteOutcome.DUPLICATE, "EVENT_KEY_ALREADY_EXISTS");
    }

    private QuoteResult result(
            QuoteEvent event,
            BigDecimal previousPrice,
            QuoteOutcome outcome,
            String reason) {
        return new QuoteResult(
                event.getId(),
                event.getEventKey(),
                event.getStockId(),
                previousPrice,
                event.getPrice(),
                event.getEventTime(),
                outcome,
                reason,
                null);
    }

}
