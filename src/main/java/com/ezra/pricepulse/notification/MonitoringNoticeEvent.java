package com.ezra.pricepulse.notification;

public record MonitoringNoticeEvent(int schemaVersion,String eventType,long deliveryId,long userId,String incidentKey) {
    void validate(){
        if(schemaVersion!=1||!"MARKET_MONITORING".equals(eventType)||deliveryId<=0||userId<=0
                ||incidentKey==null||!incidentKey.matches("[0-9a-fA-F-]{36}"))throw new IllegalArgumentException("Malformed monitoring notice event");
    }
}
