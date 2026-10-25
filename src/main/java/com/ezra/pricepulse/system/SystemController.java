package com.ezra.pricepulse.system;

import com.ezra.pricepulse.common.api.ApiResponse;
import java.util.Arrays;
import java.util.List;
import org.springframework.core.env.Environment;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/system")
public class SystemController {
    private final Environment environment;

    public SystemController(Environment environment) {
        this.environment = environment;
    }

    @GetMapping("/info")
    public ApiResponse<SystemInfo> info() {
        String[] active = environment.getActiveProfiles();
        var info = new SystemInfo("PricePulse", "phase-4", "21",
                Arrays.asList(active.length == 0 ? environment.getDefaultProfiles() : active));
        return ApiResponse.success(info);
    }

    public record SystemInfo(String name, String phase, String javaTarget, List<String> profiles) { }
}
