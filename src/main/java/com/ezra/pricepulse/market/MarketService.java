package com.ezra.pricepulse.market;

import com.ezra.pricepulse.common.error.BusinessException;
import com.ezra.pricepulse.common.error.ErrorCode;
import com.ezra.pricepulse.stock.Stock;
import com.ezra.pricepulse.stock.StockMapper;
import java.util.List;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Profile("!standalone & !mq-it")
public class MarketService {
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
        throw new BusinessException(ErrorCode.FORBIDDEN, "Legacy replay write path is retired in V2");
    }

    public BatchQuoteResponse replay(Long userId, ReplayRequest request) { return replay(request); }

    public BatchQuoteResponse simulate(SimulationRequest request) {
        throw new BusinessException(ErrorCode.FORBIDDEN, "Use isolated simulations after the V2 upgrade");
    }

    public BatchQuoteResponse simulate(Long userId, SimulationRequest request) { return simulate(request); }

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
