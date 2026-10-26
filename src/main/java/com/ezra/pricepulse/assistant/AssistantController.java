package com.ezra.pricepulse.assistant;

import com.ezra.pricepulse.common.api.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/assistant")
@Profile("!standalone & !mq-it")
public class AssistantController {
    private final AssistantService service;

    public AssistantController(AssistantService service) {
        this.service = service;
    }

    @PostMapping("/alert-drafts")
    public ApiResponse<AlertDraftResponse> draft(@Valid @RequestBody AlertDraftRequest request) {
        return ApiResponse.success(service.draft(request));
    }
}
