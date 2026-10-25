package com.ezra.pricepulse.alert;

import com.ezra.pricepulse.common.api.ApiResponse;
import com.ezra.pricepulse.user.CurrentUserId;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Positive;
import java.util.List;
import java.util.Map;
import org.springframework.context.annotation.Profile;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Validated
@RestController
@RequestMapping("/api/alert-rules")
@Profile("!standalone & !mq-it")
public class AlertRuleController {
    private final AlertRuleService alertRuleService;
    private final AlertEvaluationService evaluation;

    public AlertRuleController(AlertRuleService alertRuleService, AlertEvaluationService evaluation) {
        this.alertRuleService = alertRuleService;
        this.evaluation = evaluation;
    }

    @PostMapping
    public ApiResponse<AlertRuleResponse> create(
            @CurrentUserId Long userId,
            @Valid @RequestBody SaveAlertRuleRequest request) {
        return ApiResponse.success(alertRuleService.create(userId, request));
    }

    @GetMapping
    public ApiResponse<List<AlertRuleResponse>> list(
            @CurrentUserId Long userId,
            @RequestParam(required = false) @Positive Long stockId,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "50") @Min(1) @Max(100) int limit) {
        return ApiResponse.success(alertRuleService.list(userId, stockId, status, limit));
    }

    @GetMapping("/{ruleId}")
    public ApiResponse<AlertRuleResponse> get(
            @CurrentUserId Long userId,
            @PathVariable @Positive long ruleId) {
        return ApiResponse.success(alertRuleService.get(userId, ruleId));
    }

    @GetMapping("/{ruleId}/triggers")
    public ApiResponse<List<AlertTriggerResponse>> triggers(
            @CurrentUserId Long userId,
            @PathVariable @Positive long ruleId,
            @RequestParam(defaultValue = "100") @Min(1) @Max(500) int limit) {
        return ApiResponse.success(evaluation.history(userId, ruleId, limit));
    }

    @PutMapping("/{ruleId}")
    public ApiResponse<AlertRuleResponse> update(
            @CurrentUserId Long userId,
            @PathVariable @Positive long ruleId,
            @Valid @RequestBody UpdateAlertRuleRequest request) {
        return ApiResponse.success(alertRuleService.update(userId, ruleId, request));
    }

    @PostMapping("/{ruleId}/pause")
    public ApiResponse<AlertRuleResponse> pause(
            @CurrentUserId Long userId,
            @PathVariable @Positive long ruleId,
            @Valid @RequestBody RuleVersionRequest request) {
        return ApiResponse.success(alertRuleService.pause(userId, ruleId, request.version()));
    }

    @PostMapping("/{ruleId}/resume")
    public ApiResponse<AlertRuleResponse> resume(
            @CurrentUserId Long userId,
            @PathVariable @Positive long ruleId,
            @Valid @RequestBody RuleVersionRequest request) {
        return ApiResponse.success(alertRuleService.resume(userId, ruleId, request.version()));
    }

    @DeleteMapping("/{ruleId}")
    public ApiResponse<Map<String, Long>> delete(
            @CurrentUserId Long userId,
            @PathVariable @Positive long ruleId,
            @RequestParam @Min(0) long version) {
        alertRuleService.delete(userId, ruleId, version);
        return ApiResponse.success(Map.of("deletedRuleId", ruleId));
    }
}
