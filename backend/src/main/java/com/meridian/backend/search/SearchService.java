package com.meridian.backend.search;

import com.meridian.backend.common.dto.SearchAggregations;
import com.meridian.backend.common.util.DateUtils;
import com.meridian.backend.exception.AppException;
import com.meridian.backend.exception.DependencyUnavailableException;
import com.meridian.backend.messaging.SyncPublisher;
import com.meridian.backend.orders.dto.StatusUpdateResponse;
import com.meridian.backend.search.dto.SearchRequest;
import com.meridian.backend.search.dto.SearchResponse;
import com.meridian.backend.search.dto.SearchResult;
import com.meridian.backend.search.repository.OrderSearchRepository;
import co.elastic.clients.elasticsearch._types.FieldValue;
import co.elastic.clients.elasticsearch._types.query_dsl.Operator;
import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import co.elastic.clients.elasticsearch._types.query_dsl.TextQueryType;
import co.elastic.clients.json.JsonData;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class SearchService {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(SearchService.class);

    private final OrderSearchRepository searchRepository;
    private final SyncPublisher syncPublisher;

    public SearchResponse searchOrders(SearchRequest request) {
        try {
            co.elastic.clients.elasticsearch._types.query_dsl.Query query = buildQuery(request);
            int from = (request.getPage() - 1) * request.getLimit();
            int size = request.getLimit();
            List<String> sort = List.of("-order_date", "-order_id");

            co.elastic.clients.elasticsearch.core.SearchResponse<com.meridian.backend.search.dto.ElasticsearchOrderDocument> esResponse = 
                searchRepository.search(query, from, size, sort);
            
            return convertResponse(esResponse, request.getPage(), request.getLimit());
        } catch (AppException ex) {
            throw ex;
        } catch (Exception ex) {
            if (ex instanceof AppException) throw (AppException) ex;
            if (ex.getMessage() != null && ex.getMessage().contains("400")) {
                log.warn("elasticsearch rejected the query: {}", ex.getMessage());
                throw new AppException(
                    "Elasticsearch could not understand the search request.",
                    "search_query_invalid",
                    400,
                    java.util.Map.of("reason", ex.getMessage().substring(0, Math.min(ex.getMessage().length(), 300)))
                );
            }
            log.error("elasticsearch search failed: {}", ex.getMessage(), ex);
            throw new DependencyUnavailableException(
                "Order search is temporarily unavailable. PostgreSQL still holds every order — try again shortly or rebuild the index.",
                "search_unavailable",
                java.util.Map.of("reason", ex.getMessage().substring(0, Math.min(ex.getMessage().length(), 300)))
            );
        }
    }

    private SearchResponse convertResponse(co.elastic.clients.elasticsearch.core.SearchResponse<com.meridian.backend.search.dto.ElasticsearchOrderDocument> esResponse, int page, int limit) {
        SearchResponse response = new SearchResponse();
        List<com.meridian.backend.search.dto.ElasticsearchOrderDocument> esDocs = searchRepository.parseResults(esResponse);
        List<SearchResult> results = esDocs.stream().map(this::convertToSearchResult).collect(Collectors.toList());
        response.setResults(results);
        long total = searchRepository.getTotalHits(esResponse);
        int totalInt = total > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) total;
        response.setTotal(totalInt);
        response.setPage(page);
        response.setLimit(limit);
        int pages = 0;
        if (limit > 0) {
            double pagesDouble = Math.ceil((double) total / (double) limit);
            pages = pagesDouble > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) pagesDouble;
        }
        response.setPages(pages);
        long tookMs = searchRepository.getTookMs(esResponse);
        response.setTookMs(tookMs > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) tookMs);
        response.setAggregations(searchRepository.parseAggregations(esResponse));
        return response;
    }

    private SearchResult convertToSearchResult(com.meridian.backend.search.dto.ElasticsearchOrderDocument doc) {
        SearchResult result = new SearchResult();
        result.setOrderId(doc.getOrderId().intValue());
        result.setOrderNumber(doc.getOrderNumber());
        SearchResult.SearchCustomer customer = new SearchResult.SearchCustomer();
        customer.setId(doc.getCustomer().getId().intValue());
        customer.setName(doc.getCustomer().getName());
        customer.setEmail(doc.getCustomer().getEmail());
        result.setCustomer(customer);
        result.setStatus(doc.getStatus());
        result.setOrderDate(doc.getOrderDate());
        result.setUpdatedAt(doc.getUpdatedAt());
        result.setTotalAmount(doc.getTotalAmount());
        List<SearchResult.SearchItem> items = doc.getItems().stream().map(item -> {
            SearchResult.SearchItem itemResult = new SearchResult.SearchItem();
            itemResult.setProductId(item.getProductId());
            itemResult.setTitle(item.getTitle());
            itemResult.setQuantity(item.getQuantity());
            itemResult.setUnitPrice(item.getUnitPrice());
            itemResult.setLineTotal(item.getLineTotal());
            return itemResult;
        }).collect(Collectors.toList());
        result.setItems(items);
        return result;
    }

    /**
     * Builds the Elasticsearch query for admin order search.
     *
     * Previously this returned matchAll unconditionally ("simplified query building for
     * compilation"), so every filter was silently ignored and no filter ever narrowed
     * the result set. Free text is applied to the flat fields plus a nested clause over
     * item titles (items is a nested mapping, so it cannot be matched at the top level);
     * status, date and price are added as non-scoring filter clauses.
     */
    private Query buildQuery(SearchRequest request) {
        List<Query> must = new ArrayList<>();
        List<Query> filter = new ArrayList<>();

        // 1) Omni search across order number, customer and nested item titles.
        String text = request.getQuery() == null ? "" : request.getQuery().trim();
        if (!text.isEmpty()) {
            must.add(buildOmniQuery(text));
        }

        // 2) Status filter — exact keyword match on a fixed vocabulary.
        if (request.getStatus() != null && !request.getStatus().isEmpty()) {
            List<FieldValue> values = request.getStatus().stream()
                .map(FieldValue::of)
                .collect(Collectors.toList());
            filter.add(Query.of(q -> q.terms(t -> t
                .field("status")
                .terms(tf -> tf.value(values)))));
        }

        // 3) Date range over order_date. dateTo is exclusive of the next day so the whole
        //    final day is included (order_date carries a time component).
        if (request.getDateFrom() != null || request.getDateTo() != null) {
            filter.add(Query.of(q -> q.range(r -> {
                r.field("order_date");
                if (request.getDateFrom() != null) {
                    r.gte(JsonData.of(request.getDateFrom().toString()));
                }
                if (request.getDateTo() != null) {
                    r.lt(JsonData.of(request.getDateTo().plusDays(1).toString()));
                }
                return r;
            })));
        }

        // 4) Price range over total_amount.
        if (request.getMinPrice() != null || request.getMaxPrice() != null) {
            filter.add(Query.of(q -> q.range(r -> {
                r.field("total_amount");
                if (request.getMinPrice() != null) {
                    r.gte(JsonData.of(request.getMinPrice()));
                }
                if (request.getMaxPrice() != null) {
                    r.lte(JsonData.of(request.getMaxPrice()));
                }
                return r;
            })));
        }

        if (must.isEmpty() && filter.isEmpty()) {
            return Query.of(q -> q.matchAll(m -> m));
        }

        return Query.of(q -> q.bool(b -> {
            if (!must.isEmpty()) {
                b.must(must);
            }
            if (!filter.isEmpty()) {
                b.filter(filter);
            }
            return b;
        }));
    }

    /**
     * Combines every whitespace-delimited term of the search text.
     *
     * Terms are combined with AND: otherwise "Wendy Wireless" would match every order
     * that merely contains a wireless product, because "wireless" also appears in the
     * nested item titles. Two whole-string clauses are added as alternatives, because
     * neither survives term-by-term AND:
     *
     * <ul>
     *   <li>a phrase match on the customer — an email address is several terms and
     *       "example"/"com" are shared by every address, so only the full phrase is
     *       identifying;</li>
     *   <li>a prefix match on order_number — it is a keyword field, so a partial order
     *       number matches nothing without one.</li>
     * </ul>
     */
    private Query buildOmniQuery(String text) {
        List<String> terms = new ArrayList<>();
        for (String raw : text.split("\\s+")) {
            if (!raw.isEmpty()) {
                terms.add(raw);
            }
        }

        List<Query> perTerm = new ArrayList<>();
        for (String term : terms) {
            perTerm.add(termQuery(term));
        }

        List<Query> alternatives = new ArrayList<>();
        if (perTerm.size() == 1) {
            alternatives.add(perTerm.get(0));
        } else {
            alternatives.add(Query.of(q -> q.bool(b -> b.must(perTerm))));
        }

        alternatives.add(Query.of(q -> q.multiMatch(mm -> mm
            .query(text)
            .type(TextQueryType.Phrase)
            .fields(List.of("customer.name^3", "customer.email")))));

        alternatives.add(Query.of(q -> q.prefix(p -> p
            .field("order_number")
            .value(text.toUpperCase()))));

        return Query.of(q -> q.bool(b -> b.should(alternatives).minimumShouldMatch("1")));
    }

    /**
     * One term, matched against every place it can legitimately appear: the flat order
     * and customer fields, the nested line items, or as a partial order number.
     */
    private Query termQuery(String term) {
        Query flat = Query.of(q -> q.multiMatch(mm -> mm
            .query(term)
            .operator(Operator.And)
            .fields(List.of("order_number^3", "customer.name^3"))));

        Query nested = Query.of(q -> q.nested(n -> n
            .path("items")
            .query(iq -> iq.multiMatch(mm -> mm
                .query(term)
                .operator(Operator.And)
                .fields(List.of("items.title^2", "items.product_id"))))));

        Query prefix = Query.of(q -> q.prefix(p -> p
            .field("order_number")
            .value(term.toUpperCase())));

        List<Query> any = new ArrayList<>();
        any.add(flat);
        any.add(nested);
        any.add(prefix);
        return Query.of(q -> q.bool(b -> b.should(any).minimumShouldMatch("1")));
    }
}