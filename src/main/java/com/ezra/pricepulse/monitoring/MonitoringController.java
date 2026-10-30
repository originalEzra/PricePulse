package com.ezra.pricepulse.monitoring;

import com.ezra.pricepulse.common.api.ApiResponse;
import com.ezra.pricepulse.monitoring.MonitoringModels.*;
import com.ezra.pricepulse.user.CurrentUserId;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

@RestController
@Validated
@Profile("!standalone & !mq-it")
@ConditionalOnProperty(name="price-pulse.monitoring.enabled",havingValue="true")
public class MonitoringController {
    private final MonitoringService service;
    private final AssetCatalogService assets;
    private final MonitoringHistoryService history;
    public MonitoringController(MonitoringService service,AssetCatalogService assets,MonitoringHistoryService history){this.service=service;this.assets=assets;this.history=history;}
    @GetMapping("/api/monitoring/capabilities") public ApiResponse<Map<String,Object>> capabilities(){
        return ApiResponse.success(Map.of("managementEnabled",true,"dynamicSubscriptions",service.capacity().dynamicSubscriptions(),"capacity",service.capacity()));
    }
    @GetMapping("/api/assets") public ApiResponse<Search> search(@CurrentUserId Long user,@RequestParam @Size(min=1,max=64)String query,
            @RequestParam(defaultValue="20") @Min(1) @Max(50)int limit){service.assertActiveUser(user);return ApiResponse.success(assets.search(user,query,limit));}
    @GetMapping("/api/monitoring/subscriptions") public ApiResponse<Page> list(@CurrentUserId Long user){return ApiResponse.success(service.list(user));}
    @PostMapping("/api/monitoring/subscriptions") public ApiResponse<Entry> add(@CurrentUserId Long user,@RequestBody @Valid Add request){return ApiResponse.success(service.add(user,request.assetId()));}
    @GetMapping("/api/monitoring/subscriptions/{stockId}/cancellation") public ApiResponse<Cancellation> cancellation(@CurrentUserId Long user,@PathVariable @Positive long stockId){return ApiResponse.success(service.cancellation(user,stockId));}
    @PostMapping("/api/monitoring/subscriptions/{stockId}/cancel") public ApiResponse<Cancelled> cancel(@CurrentUserId Long user,@PathVariable @Positive long stockId,@RequestBody @Valid Cancel request){return ApiResponse.success(service.cancel(user,stockId,request));}
    @PostMapping("/api/monitoring/subscriptions/{stockId}/retry") public ApiResponse<Entry> retry(@CurrentUserId Long user,@PathVariable @Positive long stockId,@RequestBody @Valid Version request){return ApiResponse.success(service.retry(user,stockId,request.version()));}
    @PostMapping("/api/monitoring/subscriptions/{stockId}/history-requests") public ApiResponse<MonitoringHistoryService.Status> history(@CurrentUserId Long user,@PathVariable @Positive long stockId){service.assertActiveUser(user);return ApiResponse.success(history.request(user,stockId));}
    @GetMapping("/api/monitoring/history-requests/{requestId}") public ApiResponse<MonitoringHistoryService.Status> historyStatus(@CurrentUserId Long user,@PathVariable String requestId){service.assertActiveUser(user);return ApiResponse.success(history.status(user,requestId));}
}
