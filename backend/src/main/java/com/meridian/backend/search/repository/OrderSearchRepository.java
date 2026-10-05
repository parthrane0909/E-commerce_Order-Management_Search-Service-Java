package com.meridian.backend.search.repository;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.Refresh;
import co.elastic.clients.elasticsearch._types.aggregations.Aggregate;
import co.elastic.clients.elasticsearch._types.aggregations.StringTermsBucket;
import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import co.elastic.clients.elasticsearch.core.*;
import co.elastic.clients.elasticsearch.core.bulk.BulkOperation;
import co.elastic.clients.elasticsearch.indices.CreateIndexRequest;
import co.elastic.clients.elasticsearch.indices.ExistsRequest;
import com.meridian.backend.common.dto.SearchAggregations;
import com.meridian.backend.search.dto.ElasticsearchOrderDocument;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Repository;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Repository
public class OrderSearchRepository {

    private final ElasticsearchClient esClient;

    @Value("${app.elasticsearch.index:orders}")
    private String indexName;

    @Value("${app.elasticsearch.refresh-on-write:true}")
    private boolean refreshOnWrite;

    public OrderSearchRepository(ElasticsearchClient esClient) {
        this.esClient = esClient;
    }

    public boolean ensureIndex() throws IOException {
        ExistsRequest existsRequest = ExistsRequest.of(e -> e.index(indexName));
        boolean exists = esClient.indices().exists(existsRequest).value();

        if (!exists) {
            CreateIndexRequest createRequest = CreateIndexRequest.of(c -> c
                .index(indexName)
                .settings(s -> s
                    .numberOfShards("1")
                    .numberOfReplicas("0")
                )
                .mappings(m -> m
                    .properties("order_id", p -> p.long_(l -> l))
                    .properties("order_number", p -> p.keyword(k -> k))
                    .properties("status", p -> p.keyword(k -> k))
                    .properties("order_date", p -> p.date(d -> d.format("strict_date_optional_time||epoch_millis")))
                    .properties("updated_at", p -> p.date(d -> d.format("strict_date_optional_time||epoch_millis")))
                    .properties("total_amount", p -> p.double_(d -> d))
                    .properties("customer", p -> p.object(o -> o
                        .properties("id", prop -> prop.long_(l -> l))
                        .properties("name", prop -> prop.searchAsYouType(s -> s
                            .fields("keyword", f -> f.keyword(k -> k.ignoreAbove(256))))
                        )
                        .properties("email", prop -> prop.searchAsYouType(s -> s
                            .fields("keyword", f -> f.keyword(k -> k.ignoreAbove(256))))
                        )
                    ))
                    .properties("items", p -> p.nested(n -> n
                        .properties("product_id", prop -> prop.keyword(k -> k))
                        .properties("title", prop -> prop.searchAsYouType(s -> s
                            .fields("keyword", f -> f.keyword(k -> k.ignoreAbove(256))))
                        )
                        .properties("quantity", prop -> prop.integer(i -> i))
                        .properties("unit_price", prop -> prop.double_(d -> d))
                        .properties("line_total", prop -> prop.double_(d -> d))
                    ))
                )
            );
            esClient.indices().create(createRequest);
            return true;
        }
        return false;
    }

    public void deleteIndex() throws IOException {
        esClient.indices().delete(d -> d.index(indexName));
    }

    public IndexResponse indexOrder(ElasticsearchOrderDocument document) throws IOException {
        return esClient.index(i -> i
            .index(indexName)
            .id(String.valueOf(document.getOrderId()))
            .document(document)
            .refresh(refreshOnWrite ? Refresh.True : Refresh.False)
        );
    }

    public BulkResponse bulkIndexOrders(List<ElasticsearchOrderDocument> documents) throws IOException {
        if (documents.isEmpty()) {
            return null;
        }

        List<BulkOperation> operations = new ArrayList<>();
        for (ElasticsearchOrderDocument doc : documents) {
            operations.add(BulkOperation.of(b -> b
                .index(idx -> idx
                    .index(indexName)
                    .id(String.valueOf(doc.getOrderId()))
                    .document(doc)
                )
            ));
        }

        return esClient.bulk(b -> b
            .index(indexName)
            .operations(operations)
            .refresh(refreshOnWrite ? Refresh.True : Refresh.False)
        );
    }

    public SearchResponse<ElasticsearchOrderDocument> search(Query query, int from, int size, List<String> sort) throws IOException {
        SearchRequest.Builder requestBuilder = new SearchRequest.Builder()
            .index(indexName)
            .query(query)
            .from(from)
            .size(size)
            .trackTotalHits(t -> t.enabled(true));

        if (sort != null && !sort.isEmpty()) {
            for (String s : sort) {
                if (s.startsWith("-")) {
                    requestBuilder.sort(sortOpt -> sortOpt.field(f -> f.field(s.substring(1)).order(co.elastic.clients.elasticsearch._types.SortOrder.Desc)));
                } else {
                    requestBuilder.sort(sortOpt -> sortOpt.field(f -> f.field(s).order(co.elastic.clients.elasticsearch._types.SortOrder.Asc)));
                }
            }
        }

        // Add aggregations
        requestBuilder.aggregations("revenue", a -> a.sum(s -> s.field("total_amount")));
        requestBuilder.aggregations("status_counts", a -> a.terms(t -> t.field("status").size(10)));

        return esClient.search(requestBuilder.build(), ElasticsearchOrderDocument.class);
    }

    public long countDocuments() throws IOException {
        CountResponse response = esClient.count(c -> c.index(indexName));
        return response.count();
    }

    public SearchAggregations parseAggregations(SearchResponse<ElasticsearchOrderDocument> response) {
        SearchAggregations aggregations = new SearchAggregations();
        
        if (response.aggregations() != null) {
            // Revenue - sum aggregation
            Aggregate revenueAgg = response.aggregations().get("revenue");
            if (revenueAgg != null) {
                try {
                    double value = revenueAgg.sum().value();
                    aggregations.setRevenue(value);
                } catch (Exception e) {
                    // ignore
                }
            }

            // Status counts - terms aggregation
            Aggregate statusAgg = response.aggregations().get("status_counts");
            if (statusAgg != null) {
                Map<String, Integer> statusCounts = new HashMap<>();
                try {
                    List<StringTermsBucket> buckets = statusAgg.sterms().buckets().array();
                    for (StringTermsBucket bucket : buckets) {
                        String key = bucket.key().stringValue();
                        long count = bucket.docCount();
                        statusCounts.put(key, (int) count);
                    }
                } catch (Exception e) {
                    // ignore
                }
                // Ensure all three statuses are present
                statusCounts.putIfAbsent("PENDING", 0);
                statusCounts.putIfAbsent("PROCESSING", 0);
                statusCounts.putIfAbsent("SHIPPED", 0);
                aggregations.setStatusCounts(statusCounts);
            }
        }

        return aggregations;
    }

    public List<ElasticsearchOrderDocument> parseResults(SearchResponse<ElasticsearchOrderDocument> response) {
        return response.hits().hits().stream()
            .map(hit -> hit.source())
            .collect(Collectors.toList());
    }

    public long getTotalHits(SearchResponse<ElasticsearchOrderDocument> response) {
        return response.hits().total() != null ? response.hits().total().value() : 0;
    }

    public long getTookMs(SearchResponse<ElasticsearchOrderDocument> response) {
        return response.took();
    }

    public void recreateIndex() throws IOException {
        deleteIndex();
        ensureIndex();
    }
}