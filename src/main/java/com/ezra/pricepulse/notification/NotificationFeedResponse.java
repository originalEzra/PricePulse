package com.ezra.pricepulse.notification;

import java.util.List;

public record NotificationFeedResponse(
        long totalCount,
        long unreadCount,
        List<NotificationResponse> latest) { }
