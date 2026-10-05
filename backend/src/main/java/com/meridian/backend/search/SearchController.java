package com.meridian.backend.search;

import com.meridian.backend.common.dto.ErrorResponse;
import com.meridian.backend.search.dto.SearchRequest;
import com.meridian.backend.search.dto.SearchResponse;
import com.meridian.backend.security.CurrentUser;
import com.meridian.backend.orders.entity.User;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/search")
@RequiredArgsConstructor
public class SearchController {

    private final SearchService searchService;

    @PostMapping("/orders")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<SearchResponse> searchOrders(
            @Valid @RequestBody SearchRequest request,
            @CurrentUser User currentUser) {
        return ResponseEntity.ok(searchService.searchOrders(request));
    }
}