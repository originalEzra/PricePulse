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
    private final MarketDataMapper data;

    public QuoteIngestionService(
            QuoteEventMapper quoteEventMapper,
            StockMapper stockMapper,
            MarketProperties properties,
            Clock clock,
            AlertEvaluationService evaluation,
            MarketDataMapper data) {
        this.quoteEventMapper = quoteEventMapper;
        this.stockMapper = stockMapper;
        this.properties = properties;
        this.clock = clock;
        this.evaluation = evaluation;
        this.data = data;
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public QuoteResult ingest(QuoteCandidate candidate, QuoteIngestionPolicy policy) {
        return ingest(candidate, policy, null);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public QuoteResult ingest(QuoteCandidate candidate, QuoteIngestionPolicy policy, RuleTraceSelection trace) {
        if (policy != QuoteIngestionPolicy.LIVE) {
            throw new BusinessException(ErrorCode.FORBIDDEN,
                    "Legacy replay and simulation cannot enter the real-market write pipeline");
        }
        java.time.Instant received = candidate.receivedAt() == null ? clock.instant() : candidate.receivedAt();
        LocalDateTime receivedAt = MarketTime.utc(received);
        long eventNs = MarketTime.nanos(candidate.eventTime().toInstant(ZoneOffset.UTC));
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
        event.setReceivedAt(receivedAt);
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

        var state = data.streamState(stock.id());
        boolean sameSource = state != null && state.provider().equals(candidate.provider()) && state.feed().equals(candidate.feed());
        Long watermark = null;
        if (sameSource) watermark = state.eventTimeNs();
        else if ("INTERNAL".equals(candidate.provider()) && stock.latestQuoteAt()!=null)
            watermark = MarketTime.nanos(stock.latestQuoteAt().toInstant(ZoneOffset.UTC));
        // Equal event timestamps can contain distinct trades; identity dedup happens before ordering.
        if (watermark != null && eventNs < watermark) {
            return discard(
                    event, stock.latestPrice(), QuoteOutcome.DISCARDED_OUT_OF_ORDER, OUT_OF_ORDER);
        }

        var before = trace == null ? null : evaluation.observeRule(trace.userId(), trace.ruleId(), stock.id());
        boolean baseline = "ALPACA".equals(candidate.provider()) && (!sameSource
                || !state.continuityId().equals(candidate.continuityId()));
        event.setAcceptedSequence(state == null ? 1L : state.acceptedSequence()+1);
        event.setProcessedAt(MarketTime.utc(clock.instant()));
        event.setBaselineReason(baseline ? (candidate.gapReason()==null ? "STARTUP_OR_RECONNECT" : candidate.gapReason()) : null);
        MatchingResult matched = evaluation.evaluate(stock, event.getId(), price, event.getEventTime(), baseline);
        data.saveStreamState(new MarketDataModels.StreamState(stock.id(),candidate.provider(),candidate.feed(),eventNs,
                event.getAcceptedSequence(),candidate.continuityId(),receivedAt,event.getProcessedAt()));
        if (stockMapper.updateLatestQuote(stock.id(), price, event.getEventTime()) != 1
                || quoteEventMapper.complete(event) != 1) {
            throw new BusinessException(ErrorCode.CONFLICT, "Quote state changed concurrently");
        }
        return new QuoteResult(event.getId(), event.getEventKey(), stock.id(), stock.latestPrice(),
                price, candidate.eventTime(), QuoteOutcome.PROCESSED, null, matched,
                evaluation.observation(before, event.getId()));
    }

    private QuoteEvent buildEvent(QuoteCandidate candidate, BigDecimal price) {
        var event = new QuoteEvent();
        event.setEventKey(candidate.eventKey());
        event.setStockId(candidate.stockId());
        event.setPrice(price);
        event.setEventTime(MarketTime.utc(candidate.eventTime().toInstant(ZoneOffset.UTC)));
        event.setEventTimeNs(MarketTime.nanos(candidate.eventTime().toInstant(ZoneOffset.UTC)));
        event.setProvider(candidate.provider());
        event.setFeed(candidate.feed());
        event.setSource(candidate.source());
        event.setSequenceNo(candidate.sequenceNo());
        event.setOriginType("REAL");
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
                && "REAL".equals(event.getOriginType())
                && event.getPrice().compareTo(normalizedPrice) == 0
                && (event.getEventTimeNs() == null ? event.getEventTime().equals(MarketTime.utc(candidate.eventTime().toInstant(ZoneOffset.UTC)))
                    : event.getEventTimeNs() == MarketTime.nanos(candidate.eventTime().toInstant(ZoneOffset.UTC)))
                && (event.getProvider() == null || event.getProvider().equals(candidate.provider()))
                && (event.getFeed() == null || event.getFeed().equals(candidate.feed()))
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
