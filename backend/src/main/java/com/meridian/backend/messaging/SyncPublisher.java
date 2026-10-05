package com.meridian.backend.messaging;

import com.meridian.backend.orders.dto.SyncInfo;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class SyncPublisher {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(SyncPublisher.class);

    private final RabbitTemplate rabbitTemplate;

    @Value("${app.rabbitmq.sync-queue:orders_sync}")
    private String syncQueue;

    public SyncPublisher(RabbitTemplate rabbitTemplate) {
        this.rabbitTemplate = rabbitTemplate;
    }

    @Value("${app.rabbitmq.sync-queue:orders_sync}")
    public void setSyncQueue(String syncQueue) {
        this.syncQueue = syncQueue;
    }

    public SyncInfo publishOrderSync(Integer orderId, String reason) {
        try {
            OrderSyncMessage message = new OrderSyncMessage(orderId, reason);
            rabbitTemplate.convertAndSend(syncQueue, message);

            log.info("sync task queued order_id={} reason={} queue={}", orderId, reason, syncQueue);

            return SyncInfo.builder()
                .enqueued(true)
                .queue(syncQueue)
                .build();
        } catch (Exception ex) {
            log.error("failed to queue sync task for order_id={} reason={}: {}", orderId, reason, ex.getMessage());
            return SyncInfo.builder()
                .enqueued(false)
                .queue(syncQueue)
                .error("Could not reach RabbitMQ: " + ex.getMessage())
                .build();
        }
    }

    public static class OrderSyncMessage {
        private final Integer orderId;
        private final String reason;

        public OrderSyncMessage(Integer orderId, String reason) {
            this.orderId = orderId;
            this.reason = reason;
        }

        public Integer getOrderId() { return orderId; }
        public String getReason() { return reason; }
    }
}