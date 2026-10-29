package com.ezra.pricepulse.market;

import com.ezra.pricepulse.common.api.ApiResponse;
import com.ezra.pricepulse.market.MarketDataModels.*;
import com.ezra.pricepulse.user.CurrentUserId;
import jakarta.validation.constraints.Positive;
import java.time.Instant;
import org.springframework.context.annotation.Profile;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

@Validated
@RestController
@RequestMapping("/api/market/stocks/{stockId}/timeline")
@Profile("!standalone & !mq-it")
public class MarketTimelineController {
    private final MarketTimelineService service;
    public MarketTimelineController(MarketTimelineService service){this.service=service;}
    @GetMapping
    public ApiResponse<Timeline> timeline(@CurrentUserId Long userId,@PathVariable @Positive long stockId,
            @RequestParam(defaultValue="1D") String window,@RequestParam(required=false) Instant anchor) {
        return ApiResponse.success(service.timeline(userId,stockId,window,anchor));
    }
    @GetMapping("/events")
    public ApiResponse<EventPage> events(@CurrentUserId Long userId,@PathVariable @Positive long stockId,
            @RequestParam Instant from,@RequestParam Instant to,@RequestParam(required=false) String cursor) {
        return ApiResponse.success(service.events(userId,stockId,from,to,cursor));
    }
}
