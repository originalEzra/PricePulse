package com.ezra.pricepulse.notification;

import com.ezra.pricepulse.common.api.ApiResponse;
import com.ezra.pricepulse.user.CurrentUserId;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Positive;
import java.util.List;
import org.springframework.context.annotation.Profile;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Validated
@RestController
@RequestMapping("/api/notifications")
@Profile("!standalone & !mq-it")
public class NotificationController {
    private final NotificationService service;

    public NotificationController(NotificationService service) {
        this.service = service;
    }

    @GetMapping
    public ApiResponse<List<NotificationResponse>> list(
            @CurrentUserId Long userId,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "50") @Min(1) @Max(100) int limit) {
        return ApiResponse.success(service.list(userId, status, limit));
    }

    @GetMapping("/feed")
    public ApiResponse<NotificationFeedResponse> feed(@CurrentUserId Long userId) {
        return ApiResponse.success(service.feed(userId));
    }

    @PostMapping("/read-all")
    public ApiResponse<NotificationReadAllResponse> markAllRead(@CurrentUserId Long userId) {
        return ApiResponse.success(service.markAllRead(userId));
    }

    @PostMapping("/{notificationId}/read")
    public ApiResponse<NotificationResponse> markRead(
            @CurrentUserId Long userId,
            @PathVariable @Positive long notificationId) {
        return ApiResponse.success(service.markRead(userId, notificationId));
    }
}
