package com.meridian.backend.orders;

import com.meridian.backend.common.util.DateUtils;
import com.meridian.backend.common.util.MoneyUtils;
import com.meridian.backend.exception.NotFoundException;
import com.meridian.backend.exception.OrderPersistenceException;
import com.meridian.backend.exception.UnprocessableException;
import com.meridian.backend.messaging.SyncPublisher;
import com.meridian.backend.orders.dto.*;
import com.meridian.backend.orders.entity.Order;
import com.meridian.backend.orders.entity.OrderItem;
import com.meridian.backend.orders.entity.User;
import com.meridian.backend.orders.repository.OrderItemRepository;
import com.meridian.backend.orders.repository.OrderRepository;
import com.meridian.backend.orders.repository.UserRepository;
import com.meridian.backend.products.document.ProductDocument;
import com.meridian.backend.products.repository.ProductRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.util.*;
import java.util.stream.Collectors;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class OrderService {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(OrderService.class);

    private final OrderRepository orderRepository;
    private final OrderItemRepository orderItemRepository;
    private final UserRepository userRepository;
    private final ProductRepository productRepository;
    private final SyncPublisher syncPublisher;

    private static final BigDecimal CENT = BigDecimal.valueOf(0.01);
    private static final int MAX_LINE_QUANTITY = 100;

    @Transactional
    public OrderCreateResponse createOrder(OrderCreate payload, User currentUser) {
        payload.setUserId(currentUser.getId());

        ResolvedCart resolved = resolveCart(payload.getItems());
        List<ResolvedLine> lines = resolved.getLines();
        BigDecimal total = resolved.getTotal();

        log.info("order priced user_id={} lines={} total={}", payload.getUserId(), lines.size(), total);

        Order order;
        try {
            User user = userRepository.findById(payload.getUserId())
                .orElseThrow(() -> new NotFoundException(
                    "User '" + payload.getUserId() + "' does not exist.",
                    "user_not_found",
                    Map.of("user_id", payload.getUserId())
                ));

            OffsetDateTime now = DateUtils.utcNow();
            order = new Order();
            order.setOrderNumber("TMP-" + UUID.randomUUID().toString().substring(0, 12).toUpperCase());
            order.setUser(user);
            order.setOrderDate(now);
            order.setStatus("PENDING");
            order.setTotalAmount(total);
            order.setCreatedAt(now);
            order.setUpdatedAt(now);

            order = orderRepository.save(order);
            order.setOrderNumber(String.format("ORD-%06d", order.getId()));
            order = orderRepository.save(order);

            for (ResolvedLine line : lines) {
                OrderItem item = new OrderItem();
                item.setOrder(order);
                item.setProductId(line.getProductId());
                item.setTitle(line.getTitle());
                item.setQuantity(line.getQuantity());
                item.setUnitPrice(line.getUnitPrice());
                orderItemRepository.save(item);
            }

            order.setTotalAmount(total);
            order = orderRepository.save(order);

        } catch (Exception ex) {
            if (ex instanceof com.meridian.backend.exception.AppException) {
                log.info("order rejected user_id={} code={}: {}", payload.getUserId(), ((com.meridian.backend.exception.AppException) ex).getCode(), ex.getMessage());
                throw ex;
            }
            log.error("order transaction failed user_id={}: {}", payload.getUserId(), ex.getMessage(), ex);
            throw new OrderPersistenceException(
                "The order could not be saved. No data was written — please try again."
            );
        }

        log.info("order created id={} number={} user_id={} total={} items={}",
            order.getId(), order.getOrderNumber(), payload.getUserId(), total, lines.size());

        // Publish only once the commit succeeds. Publishing from inside the transaction lets
        // the listener read a pre-commit row, find nothing, return successfully and have the
        // message acked — permanently dropping the order from Elasticsearch.
        final Integer createdOrderId = order.getId();
        final OrderCreateResponse createResponse = toOrderCreateResponse(order, null);
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                createResponse.setSync(syncPublisher.publishOrderSync(createdOrderId, "order_created"));
            }
        });
        return createResponse;
    }

    public OrderResponse getOrder(Integer orderId) {
        Order order = orderRepository.findByIdWithItemsAndUser(orderId)
            .orElseThrow(() -> new NotFoundException(
                "Order '" + orderId + "' was not found.",
                "order_not_found",
                Map.of("order_id", orderId)
            ));
        return toOrderResponse(order);
    }

    @Transactional
    public StatusUpdateResponse updateOrderStatus(Integer orderId, String status) {
        Order order = orderRepository.findByIdWithItemsAndUser(orderId)
            .orElseThrow(() -> new NotFoundException(
                "Order '" + orderId + "' was not found.",
                "order_not_found",
                Map.of("order_id", orderId)
            ));

        String previous = order.getStatus();

        if (!previous.equals(status)) {
            order.setStatus(status);
            order.setUpdatedAt(DateUtils.utcNow());
            orderRepository.save(order);

            log.info("order status changed order_id={} {} -> {}", orderId, previous, status);

            // Publish only once the commit succeeds. Publishing from inside the transaction lets
            // the listener read the pre-commit row and index the previous status.
            final StatusUpdateResponse statusResponse = toStatusUpdateResponse(order, null);
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    statusResponse.setSync(syncPublisher.publishOrderSync(orderId, "status_update"));
                }
            });
            return statusResponse;
        }

        log.info("order status unchanged order_id={} status={}", orderId, status);
        return toStatusUpdateResponse(order, SyncInfo.builder()
            .enqueued(true)
            .queue("in-process")
            .build());
    }

    public List<OrderResponse.UserResponse> listUsers() {
        return userRepository.findAllOrderByIdAsc().stream()
            .map(this::toUserResponse)
            .collect(Collectors.toList());
    }

    private ResolvedCart resolveCart(List<OrderItemCreate> items) {
        if (items == null || items.isEmpty()) {
            throw new UnprocessableException("An order needs at least one item.", "empty_cart", null);
        }

        Map<String, Integer> quantities = new LinkedHashMap<>();
        for (OrderItemCreate item : items) {
            quantities.merge(item.getProductId(), item.getQuantity(), Integer::sum);
        }

        List<OrderItemCreate> merged = new ArrayList<>();
        for (Map.Entry<String, Integer> entry : quantities.entrySet()) {
            int quantity = entry.getValue();
            if (quantity > MAX_LINE_QUANTITY) {
                throw new UnprocessableException(
                    "Cannot order more than " + MAX_LINE_QUANTITY + " units of the same product.",
                    "quantity_limit_exceeded",
                    Map.of("product_id", entry.getKey(), "quantity", quantity)
                );
            }
            OrderItemCreate mergedItem = new OrderItemCreate();
            mergedItem.setProductId(entry.getKey());
            mergedItem.setQuantity(quantity);
            merged.add(mergedItem);
        }

        List<String> productIds = merged.stream().map(OrderItemCreate::getProductId).collect(Collectors.toList());
        List<ProductDocument> products = productRepository.findByIds(productIds);
        Map<String, ProductDocument> byId = products.stream()
            .collect(Collectors.toMap(p -> p.getId(), p -> p));

        List<ResolvedLine> lines = new ArrayList<>();
        for (OrderItemCreate requested : merged) {
            ProductDocument product = byId.get(requested.getProductId());
            if (product == null) {
                throw new NotFoundException(
                    "Product '" + requested.getProductId() + "' does not exist in the catalog.",
                    "product_not_found",
                    Map.of("product_id", requested.getProductId())
                );
            }
            if (Boolean.FALSE.equals(product.getActive())) {
                throw new UnprocessableException(
                    "'" + product.getTitle() + "' is no longer available and cannot be ordered.",
                    "inactive_product",
                    Map.of("product_id", requested.getProductId(), "title", product.getTitle())
                );
            }
            Double rawPrice = product.getPrice();
            if (rawPrice == null) {
                throw new UnprocessableException(
                    "'" + product.getTitle() + "' has no valid price.",
                    "invalid_price",
                    Map.of("product_id", requested.getProductId())
                );
            }
            BigDecimal unitPrice = MoneyUtils.toMoney(rawPrice);
            if (unitPrice.compareTo(BigDecimal.ZERO) <= 0) {
                throw new UnprocessableException(
                    "'" + product.getTitle() + "' has an invalid price.",
                    "invalid_price",
                    Map.of("product_id", requested.getProductId(), "price", unitPrice.toString())
                );
            }

            lines.add(new ResolvedLine(
                requested.getProductId(),
                product.getTitle(),
                requested.getQuantity(),
                unitPrice
            ));
        }

        BigDecimal total = MoneyUtils.calculateTotal(lines.stream().map(ResolvedLine::getLineTotal).collect(Collectors.toList()));
        return new ResolvedCart(lines, total);
    }

    private OrderCreateResponse toOrderCreateResponse(Order order, SyncInfo sync) {
        OrderCreateResponse response = OrderCreateResponse.builder()
            .id(order.getId())
            .orderNumber(order.getOrderNumber())
            .userId(order.getUser().getId())
            .customer(toUserResponse(order.getUser()))
            .orderDate(order.getOrderDate())
            .status(order.getStatus())
            .totalAmount(order.getTotalAmount().doubleValue())
            .updatedAt(order.getUpdatedAt())
            .searchIndexedAt(order.getSearchIndexedAt())
            .items(order.getItems().stream().map(this::toItemResponse).collect(Collectors.toList()))
            .sync(sync)
            .build();
        return response;
    }

    private OrderResponse toOrderResponse(Order order) {
        return OrderResponse.builder()
            .id(order.getId())
            .orderNumber(order.getOrderNumber())
            .userId(order.getUser().getId())
            .customer(toUserResponse(order.getUser()))
            .orderDate(order.getOrderDate())
            .status(order.getStatus())
            .totalAmount(order.getTotalAmount().doubleValue())
            .updatedAt(order.getUpdatedAt())
            .searchIndexedAt(order.getSearchIndexedAt())
            .items(order.getItems().stream().map(this::toItemResponse).collect(Collectors.toList()))
            .build();
    }

    private StatusUpdateResponse toStatusUpdateResponse(Order order, SyncInfo sync) {
        return StatusUpdateResponse.builder()
            .id(order.getId())
            .orderNumber(order.getOrderNumber())
            .userId(order.getUser().getId())
            .customer(toUserResponse(order.getUser()))
            .orderDate(order.getOrderDate())
            .status(order.getStatus())
            .totalAmount(order.getTotalAmount().doubleValue())
            .updatedAt(order.getUpdatedAt())
            .searchIndexedAt(order.getSearchIndexedAt())
            .items(order.getItems().stream().map(this::toItemResponse).collect(Collectors.toList()))
            .sync(sync)
            .build();
    }

    private OrderResponse.UserResponse toUserResponse(User user) {
        return OrderResponse.UserResponse.builder()
            .id(user.getId())
            .name(user.getName())
            .email(user.getEmail())
            .role(user.getRole())
            .build();
    }

    private OrderItemResponse toItemResponse(OrderItem item) {
        return OrderItemResponse.builder()
            .id(item.getId())
            .productId(item.getProductId())
            .title(item.getTitle())
            .quantity(item.getQuantity())
            .unitPrice(item.getUnitPrice().doubleValue())
            .lineTotal(item.getLineTotal().doubleValue())
            .build();
    }

    // Inner classes with explicit getters
    public static class ResolvedLine {
        private final String productId;
        private final String title;
        private final int quantity;
        private final BigDecimal unitPrice;

        public ResolvedLine(String productId, String title, int quantity, BigDecimal unitPrice) {
            this.productId = productId;
            this.title = title;
            this.quantity = quantity;
            this.unitPrice = unitPrice;
        }

        public String getProductId() { return productId; }
        public String getTitle() { return title; }
        public int getQuantity() { return quantity; }
        public BigDecimal getUnitPrice() { return unitPrice; }

        public BigDecimal getLineTotal() {
            return unitPrice.multiply(BigDecimal.valueOf(quantity)).setScale(2, RoundingMode.HALF_UP);
        }
    }

    public static class ResolvedCart {
        private final List<ResolvedLine> lines;
        private final BigDecimal total;

        public ResolvedCart(List<ResolvedLine> lines, BigDecimal total) {
            this.lines = lines;
            this.total = total;
        }

        public List<ResolvedLine> getLines() { return lines; }
        public BigDecimal getTotal() { return total; }
    }
}