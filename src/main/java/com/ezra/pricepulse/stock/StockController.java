package com.ezra.pricepulse.stock;

import com.ezra.pricepulse.common.api.ApiResponse;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import java.util.List;
import org.springframework.context.annotation.Profile;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Validated
@RestController
@RequestMapping("/api/stocks")
@Profile("!standalone & !mq-it")
public class StockController {
    private final StockService stockService;

    public StockController(StockService stockService) {
        this.stockService = stockService;
    }

    @GetMapping
    public ApiResponse<List<StockResponse>> search(
            @RequestParam(required = false) @Size(max = 64) String query,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int limit) {
        return ApiResponse.success(stockService.search(query, limit));
    }

    @GetMapping("/{stockId}")
    public ApiResponse<StockResponse> get(@PathVariable @Min(1) long stockId) {
        return ApiResponse.success(stockService.get(stockId));
    }
}
