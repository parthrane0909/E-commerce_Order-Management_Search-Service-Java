package com.meridian.backend.messaging;

import com.meridian.backend.common.util.DateUtils;
import com.meridian.backend.orders.entity.Order;
import com.meridian.backend.orders.repository.OrderRepository;
import com.meridian.backend.search.repository.OrderSearchRepository;
import com.meridian.backend.search.dto.ElasticsearchOrderDocument;
import lombok.RequiredArgsConstructor;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

import java.io.IOException;

@Component
@RequiredArgsConstructor
public class OrderSyncListener {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(OrderSyncListener.class);

    private final OrderRepository orderRepository;
    private final OrderSearchRepository searchRepository;

    @RabbitListener(queues = "${app.rabbitmq.sync-queue:orders_sync}")
    public void handleOrderSync(SyncPublisher.OrderSyncMessage message) {
        log.info("sync task start order_id={} reason={}", message.getOrderId(), message.getReason());
        try {
            syncOrderDocument(message.getOrderId(), message.getReason());
        } catch (Exception ex) {
            log.error("sync task failed order_id={} reason={}: {}", message.getOrderId(), message.getReason(), ex.getMessage(), ex);
            throw ex;
        }
    }

    private void syncOrderDocument(Integer orderId, String reason) {
        Order order = orderRepository.findByIdWithItemsAndUser(orderId)
            .orElse(null);

        if (order == null) {
            log.warn("order_id={} not present in PostgreSQL — nothing to index", orderId);
            return;
        }

        ElasticsearchOrderDocument document = buildOrderDocument(order);

        try {
            searchRepository.ensureIndex();
            searchRepository.indexOrder(document);
        } catch (IOException ex) {
            log.error("failed to index order_id={}: {}", orderId, ex.getMessage(), ex);
            throw new RuntimeException(ex);
        }

        try {
            orderRepository.updateSearchIndexedAt(orderId, DateUtils.utcNow());
        } catch (Exception ex) {
            log.warn("could not record search_indexed_at for order_id={}: {}", orderId, ex.getMessage());
        }

        log.info("elasticsearch synchronised order_id={} number={} status={} reason={}",
            orderId, document.getOrderNumber(), document.getStatus(), reason);
    }

    private ElasticsearchOrderDocument buildOrderDocument(Order order) {
        ElasticsearchOrderDocument doc = new ElasticsearchOrderDocument();
        doc.setOrderId((long) order.getId());
        doc.setOrderNumber(order.getOrderNumber());
        doc.setStatus(order.getStatus());
        doc.setOrderDate(order.getOrderDate());
        doc.setUpdatedAt(order.getUpdatedAt());
        doc.setTotalAmount(order.getTotalAmount().doubleValue());

        ElasticsearchOrderDocument.Customer customer = new ElasticsearchOrderDocument.Customer();
        customer.setId((long) order.getUser().getId());
        customer.setName(order.getUser().getName());
        customer.setEmail(order.getUser().getEmail());
        doc.setCustomer(customer);

        doc.setItems(order.getItems().stream().map(item -> {
            ElasticsearchOrderDocument.Item esItem = new ElasticsearchOrderDocument.Item();
            esItem.setProductId(item.getProductId());
            esItem.setTitle(item.getTitle());
            esItem.setQuantity(item.getQuantity());
            esItem.setUnitPrice(item.getUnitPrice().doubleValue());
            esItem.setLineTotal(item.getLineTotal().doubleValue());
            return esItem;
        }).collect(java.util.stream.Collectors.toList()));

        return doc;
    }
}