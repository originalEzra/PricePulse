package com.ezra.pricepulse.market;

import com.ezra.pricepulse.common.error.BusinessException;
import com.ezra.pricepulse.common.error.ErrorCode;
import com.ezra.pricepulse.common.PriceTicks;
import com.ezra.pricepulse.stock.Stock;
import com.ezra.pricepulse.stock.StockMapper;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Profile("!standalone & !mq-it")
public class MarketService {
    private static final BigDecimal MINIMUM_PRICE = new BigDecimal("0.0001");

    private final QuoteIngestionService ingestionService;
    private final QuoteEventMapper quoteEventMapper;
    private final StockMapper stockMapper;

    public MarketService(
            QuoteIngestionService ingestionService,
            QuoteEventMapper quoteEventMapper,
            StockMapper stockMapper) {
        this.ingestionService = ingestionService;
        this.quoteEventMapper = quoteEventMapper;
        this.stockMapper = stockMapper;
    }

    public QuoteResult ingestLive(QuoteInputRequest request) {
        return ingestionService.ingest(QuoteCandidate.live(request), QuoteIngestionPolicy.LIVE);
    }

    public BatchQuoteResponse replay(ReplayRequest request) {
        List<QuoteResult> results = request.events().stream()
                .map(event -> new QuoteCandidate(
                        event.eventKey().trim(),
                        event.stockId(),
                        event.price(),
                        event.eventTime(),
                        event.source().trim(),
                        event.sequenceNo()))
                .map(candidate -> ingestionService.ingest(candidate, QuoteIngestionPolicy.REPLAY))
                .toList();
        return BatchQuoteResponse.from(results);
    }

    public BatchQuoteResponse simulate(SimulationRequest request) {
        requireActiveStock(request.stockId());
        Random random = new Random(request.seed());
        BigDecimal price = PriceTicks.normalize(request.initialPrice(), "initialPrice");
        List<QuoteResult> results = new ArrayList<>(request.steps());

        for (int index = 1; index <= request.steps(); index++) {
            double magnitude = random.nextDouble() * request.stepRatio().doubleValue();
            double signedChange = switch (request.mode()) {
                case NORMAL -> (random.nextBoolean() ? 1 : -1) * magnitude;
                case RISE -> magnitude;
                case FALL -> -magnitude;
                case OSCILLATE -> (index % 2 == 1 ? 1 : -1) * magnitude;
            };
            price = price
                    .multiply(BigDecimal.valueOf(1 + signedChange))
                    .max(MINIMUM_PRICE)
                    .setScale(4, RoundingMode.HALF_UP);
            QuoteCandidate candidate = new QuoteCandidate(
                    "sim:" + request.runKey() + ":" + request.stockId() + ":" + index,
                    request.stockId(),
                    price,
                    request.startTime().plusNanos(index * 1_000L),
                    "SIMULATION",
                    (long) index);
            results.add(ingestionService.ingest(candidate, QuoteIngestionPolicy.SIMULATION));
        }
        return BatchQuoteResponse.from(results);
    }

    @Transactional(readOnly = true)
    public List<QuoteHistoryResponse> history(long stockId, int limit) {
        requireActiveStock(stockId);
        return quoteEventMapper.listByStock(stockId, limit).stream()
                .map(QuoteHistoryResponse::from)
                .toList();
    }

    private Stock requireActiveStock(long stockId) {
        Stock stock = stockMapper.findActiveById(stockId);
        if (stock == null) {
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "Stock was not found");
        }
        return stock;
    }

}
