package com.ezra.pricepulse.stock;

import com.ezra.pricepulse.common.error.BusinessException;
import com.ezra.pricepulse.common.error.ErrorCode;
import java.util.List;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Profile("!standalone & !mq-it")
public class StockService {
    private final StockMapper stockMapper;

    public StockService(StockMapper stockMapper) {
        this.stockMapper = stockMapper;
    }

    @Transactional(readOnly = true)
    public List<StockResponse> search(String query, int limit) {
        String normalized = query == null ? null : query.trim();
        return stockMapper.searchActive(normalized, limit).stream().map(StockResponse::from).toList();
    }

    @Transactional(readOnly = true)
    public StockResponse get(long stockId) {
        Stock stock = stockMapper.findActiveById(stockId);
        if (stock == null) {
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "Stock was not found");
        }
        return StockResponse.from(stock);
    }
}
