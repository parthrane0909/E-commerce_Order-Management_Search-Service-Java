package com.meridian.backend.orders.repository;

import com.meridian.backend.orders.entity.Order;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Repository
public interface OrderRepository extends JpaRepository<Order, Integer> {

    @Query("SELECT o FROM Order o LEFT JOIN FETCH o.items LEFT JOIN FETCH o.user WHERE o.id = :id")
    Optional<Order> findByIdWithItemsAndUser(@Param("id") Integer id);

    @Query("SELECT COUNT(o) FROM Order o")
    long countAllOrders();

    @Query("SELECT COUNT(oi) FROM OrderItem oi")
    long countAllOrderItems();

    @Query("SELECT o FROM Order o WHERE o.id > :lastId ORDER BY o.id ASC")
    List<Order> findOrdersAfterId(@Param("lastId") Integer lastId, org.springframework.data.domain.Pageable pageable);

    @Query("SELECT o.status, COUNT(o) FROM Order o GROUP BY o.status")
    List<Object[]> getStatusCounts();

    @Modifying
    @Transactional
    @Query("UPDATE Order o SET o.searchIndexedAt = :indexedAt WHERE o.id = :orderId")
    void updateSearchIndexedAt(@Param("orderId") Integer orderId, @Param("indexedAt") java.time.OffsetDateTime indexedAt);
}
