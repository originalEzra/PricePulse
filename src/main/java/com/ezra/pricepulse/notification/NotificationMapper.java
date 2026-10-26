package com.ezra.pricepulse.notification;

import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface NotificationMapper {
    AlertTriggerDelivery findDelivery(long triggerId);

    int insertIfAbsent(
            @Param("userId") long userId,
            @Param("triggerId") long triggerId,
            @Param("title") String title,
            @Param("content") String content);

    Notification findOwned(@Param("userId") long userId, @Param("notificationId") long notificationId);

    List<Notification> listOwned(
            @Param("userId") long userId,
            @Param("status") NotificationStatus status,
            @Param("limit") int limit);

    int markRead(
            @Param("userId") long userId,
            @Param("notificationId") long notificationId,
            @Param("readAt") LocalDateTime readAt);
}
