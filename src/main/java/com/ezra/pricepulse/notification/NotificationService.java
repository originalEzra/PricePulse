package com.ezra.pricepulse.notification;

import com.ezra.pricepulse.common.error.BusinessException;
import com.ezra.pricepulse.common.error.ErrorCode;
import com.ezra.pricepulse.alert.OpeningGapContext;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Locale;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Service
@Profile("!standalone & !mq-it")
public class NotificationService {
    private static final Logger log = LoggerFactory.getLogger(NotificationService.class);
    private final NotificationMapper mapper;
    private final ObjectMapper json;
    private final Clock clock;
    private com.ezra.pricepulse.market.MonitorIncidentMapper incidents;
    @org.springframework.beans.factory.annotation.Autowired void incidents(com.ezra.pricepulse.market.MonitorIncidentMapper incidents){this.incidents=incidents;}

    public NotificationService(NotificationMapper mapper, ObjectMapper json, Clock clock) {
        this.mapper = mapper;
        this.json = json;
        this.clock = clock;
    }

    @Transactional
    public void consume(String payload) {
        try{
            var envelope=json.readTree(payload);
            if(envelope==null||!envelope.isObject())throw new IllegalArgumentException("Notification payload must be an object");
            String type=envelope.path("eventType").asText();
            if(type.equals("MARKET_MONITORING")){consumeMonitoring(json.readValue(payload,MonitoringNoticeEvent.class));return;}
            if(!type.isBlank())throw new IllegalArgumentException("Unknown notification event type");
        }catch(JsonProcessingException error){throw new IllegalArgumentException("Notification payload is malformed",error);}
        AlertTriggeredEvent event = parseEvent(payload);
        event.validate();
        AlertTriggerDelivery delivery = mapper.findDelivery(event.triggerId());
        if (delivery == null
                || delivery.getUserId() != event.userId()
                || delivery.getRuleId() != event.ruleId()
                || delivery.getStockId() != event.stockId()) {
            throw new IllegalArgumentException("Alert event does not match its persisted trigger");
        }
        if (!"REAL".equals(delivery.getOriginType())) {
            log.info("Acknowledged legacy alert message without creating a notification; triggerId={}", event.triggerId());
            return;
        }
        NotificationText text = createText(delivery);
        mapper.insertIfAbsent(delivery.getUserId(), delivery.getTriggerId(), text.title(), text.content());
    }

    private void consumeMonitoring(MonitoringNoticeEvent event){
        event.validate();var delivery=incidents.delivery(event.deliveryId());
        if(delivery==null||delivery.userId()!=event.userId()||!delivery.incidentKey().equals(event.incidentKey()))
            throw new IllegalArgumentException("Monitoring message does not match its persisted delivery");
        if(delivery.kind().equals("RECOVERED")){
            Long prior=incidents.deliveryId(delivery.incidentKey(),delivery.userId(),"INTERRUPTED");
            if(prior==null)throw new IllegalArgumentException("Recovery has no persisted interruption");
            // Concurrent MQ queues may reorder messages: materialize the prior persisted notice in this transaction.
            insertMonitoring(incidents.delivery(prior));
        }
        insertMonitoring(delivery);
    }
    private void insertMonitoring(com.ezra.pricepulse.market.MonitorIncidentMapper.Delivery d){
        boolean recovered=d.kind().equals("RECOVERED");
        boolean queue=d.reason().equals("QUEUE_OVERFLOW");
        String title=recovered?(queue?"行情处理队列已恢复":"实时行情监控已恢复"):queue?"行情处理队列溢出 · 已丢弃成交":"实时行情监控已中断超过 30 秒";
        String detail=recovered?(queue?"处理链路已从新的可信基线恢复。中断期间可能存在行情缺口，未补偿全部遗漏提醒。":"连接、相关订阅及处理链路已恢复。中断期间可能存在行情缺口，未补偿全部遗漏提醒。")
                :queue?"处理队列溢出，已确认丢弃 "+d.discardedTotal()+" 笔排队成交；行情连续性已中断，系统将重建基线。"
                :"行情连接或相关订阅尚未恢复，期间可能存在未检测到的价格提醒。系统按接入策略尝试恢复；若授权被拒绝需检查接入配置。";
        mapper.insertMonitoring(d.userId(),d.id(),title,d.symbols()+"："+detail+" 故障标识 "+d.incidentKey()+"。");
    }

    @Transactional(readOnly = true)
    public List<NotificationResponse> list(long userId, String requestedStatus, int limit) {
        NotificationStatus status = parseStatus(requestedStatus);
        return mapper.listOwned(userId, status, limit).stream().map(NotificationResponse::from).toList();
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public NotificationFeedResponse feed(long userId) {
        NotificationCounts counts = mapper.countOwned(userId);
        var latest = mapper.listOwned(userId, null, 20).stream().map(NotificationResponse::from).toList();
        return new NotificationFeedResponse(counts.totalCount(), counts.unreadCount(), latest);
    }

    @Transactional
    public NotificationReadAllResponse markAllRead(long userId) {
        return new NotificationReadAllResponse(mapper.markAllRead(userId, now()));
    }

    @Transactional
    public NotificationResponse markRead(long userId, long notificationId) {
        Notification existing = mapper.findOwned(userId, notificationId);
        if (existing == null) {
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "Notification was not found");
        }
        if (existing.getStatus() == NotificationStatus.UNREAD) {
            mapper.markRead(userId, notificationId, now());
        }
        return NotificationResponse.from(mapper.findOwned(userId, notificationId));
    }

    private AlertTriggeredEvent parseEvent(String payload) {
        try {
            return json.readValue(payload, AlertTriggeredEvent.class);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("Alert event payload is malformed", exception);
        }
    }

    private NotificationText createText(AlertTriggerDelivery delivery) {
        try {
            JsonNode snapshot = json.readTree(delivery.getRuleSnapshot());
            String ruleName = snapshot.path("name").asText();
            if (ruleName.isBlank()) {
                ruleName = "价格提醒";
            }
            String direction = snapshot.path("direction").asText();
            String threshold = snapshot.path("thresholdPrice").asText();
            if (threshold.isBlank()) {
                throw new IllegalStateException("Rule snapshot does not contain a threshold price");
            }
            String verb = switch (direction) {
                case "ABOVE" -> "上破";
                case "BELOW" -> "下破";
                default -> throw new IllegalStateException("Rule snapshot contains an unknown direction");
            };
            String displayThreshold = displayPrice(new BigDecimal(threshold));
            String title = delivery.getStockSymbol() + " 已" + verb + " " + displayThreshold;
            String content = "%s（%s）价格 %s，触发提醒“%s”（%s %s）。".formatted(
                    delivery.getStockName(), delivery.getStockSymbol(),
                    displayPrice(delivery.getTriggerPrice()), ruleName, verb, displayThreshold);
            if (delivery.getTriggerContext()!=null) {
                var context=json.readTree(delivery.getTriggerContext());
                if("TARGET_REACHED".equals(context.path("kind").asText())){
                    var reach=json.treeToValue(context,com.ezra.pricepulse.alert.TargetReachContext.class);
                    String label=switch(reach.confirmation()){
                        case "RECOVERY" -> "恢复后确认达到";
                        case "RULE_CREATED" -> "创建时确认达到";
                        case "RULE_ACTIVATED" -> "启用时确认达到";
                        default -> "已达到";
                    };
                    title=delivery.getStockSymbol()+" "+label+" "+verb+"目标 "+displayThreshold;
                    content="%s（%s）%s目标，确认价格 %s，提醒“%s”（%s %s）。参考真实成交时间 %s；不推断缺口期间的突破时间。".formatted(
                            delivery.getStockName(),delivery.getStockSymbol(),label,delivery.getTriggerPrice().toPlainString(),ruleName,verb,displayThreshold,
                            reach.quoteEventTime().atZone(java.time.ZoneId.of("America/New_York")).format(java.time.format.DateTimeFormatter.ofPattern("MM/dd HH:mm:ss z")));
                    return new NotificationText(title,content);
                }
                OpeningGapContext opening=json.readValue(delivery.getTriggerContext(),OpeningGapContext.class);
                if (opening.ruleEffectiveAt()==null || !opening.ruleEffectiveAt().isBefore(opening.sessionOpen()))
                    throw new IllegalStateException("Opening trigger has no eligible rule activation");
                title=delivery.getStockSymbol()+" 开盘跳空 · "+verb+" "+displayThreshold;
                content="%s（%s）开盘跳空，实际成交价 %s，参考收盘价 %s（IEX 正常时段最后一分钟），触发提醒“%s”（%s %s）。".formatted(
                        delivery.getStockName(),delivery.getStockSymbol(),delivery.getTriggerPrice().toPlainString(),
                        opening.referencePrice(),ruleName,verb,displayThreshold);
            }
            return new NotificationText(title, content);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Cannot read the persisted rule snapshot", exception);
        }
    }

    private String displayPrice(BigDecimal value) {
        return value.setScale(2, RoundingMode.HALF_UP).toPlainString();
    }

    private LocalDateTime now() {
        return LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
    }

    private NotificationStatus parseStatus(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return NotificationStatus.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw new BusinessException(
                    ErrorCode.BUSINESS_RULE_VIOLATION,
                    "Unknown notification status: " + value);
        }
    }

    private record NotificationText(String title, String content) { }
}
