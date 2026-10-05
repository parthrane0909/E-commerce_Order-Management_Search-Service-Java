package com.meridian.backend.clients;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.json.jackson.JacksonJsonpMapper;
import co.elastic.clients.transport.ElasticsearchTransport;
import co.elastic.clients.transport.rest_client.RestClientTransport;
import org.apache.http.HttpHost;
import org.apache.http.auth.AuthScope;
import org.apache.http.auth.UsernamePasswordCredentials;
import org.apache.http.impl.client.BasicCredentialsProvider;
import org.elasticsearch.client.RestClient;
import org.elasticsearch.client.RestClientBuilder;
import com.meridian.backend.core.config.AppConfig;
import org.springframework.stereotype.Component;

import java.io.IOException;

@Component
public class ElasticsearchClientWrapper {

    private final ElasticsearchClient client;

    public ElasticsearchClientWrapper(AppConfig config) {
        String[] uris = config.getElasticsearchUrls();
        HttpHost[] hosts = new HttpHost[uris.length];
        for (int i = 0; i < uris.length; i++) {
            String uri = uris[i];
            String scheme = "http";
            if (uri.startsWith("https://")) {
                scheme = "https";
                uri = uri.substring(8);
            } else if (uri.startsWith("http://")) {
                uri = uri.substring(7);
            }
            String[] parts = uri.split(":");
            String host = parts[0];
            int port = parts.length > 1 ? Integer.parseInt(parts[1]) : 9200;
            hosts[i] = new HttpHost(host, port, scheme);
        }

        RestClientBuilder builder = RestClient.builder(hosts);

        String apiKey = config.getElasticsearchApiKey();
        if (apiKey != null && !apiKey.isEmpty()) {
            BasicCredentialsProvider credentialsProvider = new BasicCredentialsProvider();
            credentialsProvider.setCredentials(AuthScope.ANY, new UsernamePasswordCredentials("", apiKey));
            builder.setHttpClientConfigCallback(h -> h.setDefaultCredentialsProvider(credentialsProvider));
        }

        RestClient restClient = builder.build();
        ElasticsearchTransport transport = new RestClientTransport(restClient, new JacksonJsonpMapper());
        this.client = new ElasticsearchClient(transport);
    }

    public boolean ping() {
        try {
            return Boolean.TRUE.equals(client.ping().value());
        } catch (Exception ex) {
            return false;
        }
    }

    public ElasticsearchClient getClient() {
        return client;
    }

    public void close() {
        try {
            client._transport().close();
        } catch (Exception ex) {
            // ignore
        }
    }
}
