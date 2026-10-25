package com.ezra.pricepulse.market;

import com.ezra.pricepulse.common.api.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Positive;
import java.util.List;
import org.springframework.context.annotation.Profile;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Validated
@RestController
@RequestMapping("/api/market")
@Profile("!standalone & !mq-it")
public class MarketController {
    private final MarketService marketService;

    public MarketController(MarketService marketService) {
        this.marketService = marketService;
    }

    @PostMapping("/quotes")
    public ApiResponse<QuoteResult> ingest(@Valid @RequestBody QuoteInputRequest request) {
        return ApiResponse.success(marketService.ingestLive(request));
    }

    @PostMapping("/replay")
    public ApiResponse<BatchQuoteResponse> replay(@Valid @RequestBody ReplayRequest request) {
        return ApiResponse.success(marketService.replay(request));
    }

    @PostMapping("/simulate")
    public ApiResponse<BatchQuoteResponse> simulate(@Valid @RequestBody SimulationRequest request) {
        return ApiResponse.success(marketService.simulate(request));
    }

    @GetMapping("/stocks/{stockId}/quotes")
    public ApiResponse<List<QuoteHistoryResponse>> history(
            @PathVariable @Positive long stockId,
            @RequestParam(defaultValue = "100") @Min(1) @Max(500) int limit) {
        return ApiResponse.success(marketService.history(stockId, limit));
    }
}
