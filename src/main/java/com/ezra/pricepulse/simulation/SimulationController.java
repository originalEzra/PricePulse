package com.ezra.pricepulse.simulation;

import com.ezra.pricepulse.common.api.ApiResponse;
import com.ezra.pricepulse.simulation.SimulationApi.*;
import com.ezra.pricepulse.user.CurrentUserId;
import jakarta.validation.Valid;
import java.time.Instant;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.*;

@RestController
@Profile("!standalone & !mq-it")
@RequestMapping("/api/simulations")
public class SimulationController {
    private final SimulationService service;
    public SimulationController(SimulationService service){this.service=service;}
    @GetMapping("/capabilities") public ApiResponse<java.util.Map<String,Object>> capabilities(){return ApiResponse.success(java.util.Map.of("openingGap",true,"scope","SIMULATION"));}
    @GetMapping("/current") public ApiResponse<Current> current(@CurrentUserId Long user){return ApiResponse.success(service.current(user));}
    @PostMapping("/start") public ApiResponse<Current> start(@CurrentUserId Long user,@RequestHeader("Idempotency-Key") String key,@Valid @RequestBody Start request){return ApiResponse.success(service.start(user,request,key));}
    @PostMapping("/{runId}/advance") public ApiResponse<Batch> advance(@CurrentUserId Long user,@PathVariable String runId,@RequestHeader("Idempotency-Key") String key,@Valid @RequestBody Advance request){return ApiResponse.success(service.advance(user,runId,request,key));}
    @GetMapping("/{runId}/timeline") public ApiResponse<Timeline> timeline(@CurrentUserId Long user,@PathVariable String runId,
            @RequestParam(required=false) Instant from,@RequestParam(required=false) Instant to,@RequestParam(required=false) String cursor){return ApiResponse.success(service.timeline(user,runId,from,to,cursor));}
    @GetMapping("/{runId}/events") public ApiResponse<EventPage> events(@CurrentUserId Long user,@PathVariable String runId,
            @RequestParam Instant from,@RequestParam Instant to,@RequestParam(required=false) String cursor){return ApiResponse.success(service.events(user,runId,from,to,cursor));}
}
