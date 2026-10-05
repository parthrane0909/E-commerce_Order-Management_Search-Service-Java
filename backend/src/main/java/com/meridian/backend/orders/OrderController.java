package com.meridian.backend.orders;

import com.meridian.backend.common.dto.ErrorResponse;
import com.meridian.backend.orders.dto.*;
import com.meridian.backend.orders.entity.User;
import com.meridian.backend.security.CurrentUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/orders")
@RequiredArgsConstructor
public class OrderController {

    private final OrderService orderService;

    @PostMapping
    public ResponseEntity<OrderCreateResponse> createOrder(
            @Valid @RequestBody OrderCreate payload,
            @CurrentUser User currentUser) {
        OrderCreateResponse response = orderService.createOrder(payload, currentUser);
        return ResponseEntity.status(201).body(response);
    }

    @GetMapping("/{orderId}")
    public ResponseEntity<OrderResponse> getOrder(
            @PathVariable @Min(1) Integer orderId,
            @CurrentUser User currentUser) {

        OrderResponse order = orderService.getOrder(orderId);

        // Authorization: customers can only view their own orders
        if (!"ADMIN".equals(currentUser.getRole()) && !order.getUserId().equals(currentUser.getId())) {
            return ResponseEntity.status(403).build();
        }

        return ResponseEntity.ok(order);
    }

    @PatchMapping("/{orderId}/status")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<StatusUpdateResponse> updateStatus(
            @PathVariable @Min(1) Integer orderId,
            @Valid @RequestBody StatusUpdateRequest payload) {
        StatusUpdateResponse response = orderService.updateOrderStatus(orderId, payload.getStatus());
        return ResponseEntity.ok(response);
    }
}