package com.meridian.backend.core.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
@Getter
@Setter
public class AppConfig {

    @Value("${APP_NAME:E-Commerce Order Management & Search Service}")
    private String name;

    @Value("${ENVIRONMENT:development}")
    private String environment;

    @Value("${LOG_LEVEL:INFO}")
    private String logLevel;

    @Value("${API_HOST:0.0.0.0}")
    private String apiHost;

    @Value("${API_PORT:8000}")
    private int apiPort;

    @Value("${CORS_ORIGINS:http://localhost:5173}")
    private String corsOrigins;

    @Value("${POSTGRES_HOST:localhost}")
    private String postgresHost;

    @Value("${POSTGRES_PORT:5432}")
    private int postgresPort;

    @Value("${POSTGRES_USER:ecommerce}")
    private String postgresUser;

    @Value("${POSTGRES_PASSWORD:ecommerce}")
    private String postgresPassword;

    @Value("${POSTGRES_DB:ecommerce}")
    private String postgresDb;

    @Value("${POSTGRES_SSLMODE:disable}")
    private String postgresSslmode;

    @Value("${POSTGRES_POOL_SIZE:5}")
    private int postgresPoolSize;

    @Value("${MONGO_URI:mongodb://localhost:27017}")
    private String mongoUri;

    @Value("${MONGO_DB:catalog}")
    private String mongoDb;

    @Value("${MONGO_TIMEOUT_MS:5000}")
    private int mongoTimeoutMs;

    @Value("${ELASTICSEARCH_URL:http://localhost:9200}")
    private String[] elasticsearchUrls = {"http://localhost:9200"};

    @Value("${ELASTICSEARCH_INDEX:orders}")
    private String elasticsearchIndex;

    @Value("${ELASTICSEARCH_API_KEY:}")
    private String elasticsearchApiKey;

    @Value("${ELASTICSEARCH_TIMEOUT:10}")
    private int elasticsearchTimeout;

    @Value("${ELASTICSEARCH_REFRESH_ON_WRITE:true}")
    private boolean elasticsearchRefreshOnWrite;

    @Value("${RABBITMQ_HOST:localhost}")
    private String rabbitmqHost;

    @Value("${RABBITMQ_PORT:5672}")
    private int rabbitmqPort;

    @Value("${RABBITMQ_DEFAULT_USER:ecommerce}")
    private String rabbitmqDefaultUser;

    @Value("${RABBITMQ_DEFAULT_PASSWORD:ecommerce}")
    private String rabbitmqDefaultPassword;

    @Value("${RABBITMQ_URL:}")
    private String rabbitmqUrl;

    @Value("${UPLOADS_DIR:/app/uploads}")
    private String uploadsDir;

    @Value("${SEED_RANDOM_SEED:20240917}")
    private int seedRandomSeed;

    @Value("${JWT_SECRET_KEY:change-me-in-production-use-a-long-random-string}")
    private String jwtSecretKey;

    @Value("${JWT_ALGORITHM:HS256}")
    private String jwtAlgorithm;

    @Value("${JWT_ACCESS_TOKEN_EXPIRE_MINUTES:1440}")
    private int jwtAccessTokenExpireMinutes;

    // Non-@Value fields with defaults - only declare them once
    // No duplicate field declarations here

    public String getPostgresJdbcUrl() {
        return String.format("jdbc:postgresql://%s:%d/%s?sslmode=%s",
            postgresHost, postgresPort, postgresDb, postgresSslmode);
    }

    public String getRabbitmqUrl() {
        if (rabbitmqUrl != null && !rabbitmqUrl.isEmpty()) {
            return rabbitmqUrl;
        }
        return String.format("amqp://%s:%s@%s:%d/",
            rabbitmqDefaultUser, rabbitmqDefaultPassword, rabbitmqHost, rabbitmqPort);
    }

    public String getCorsOriginList() {
        return corsOrigins;
    }

    // Explicit getters for fields that might not work with @Value
    public String getName() { return name; }
    public String getEnvironment() { return environment; }
    public String getLogLevel() { return logLevel; }
    public String getApiHost() { return apiHost; }
    public int getApiPort() { return apiPort; }
    public String getCorsOrigins() { return corsOrigins; }
    public String getPostgresHost() { return postgresHost; }
    public int getPostgresPort() { return postgresPort; }
    public String getPostgresUser() { return postgresUser; }
    public String getPostgresPassword() { return postgresPassword; }
    public String getPostgresDb() { return postgresDb; }
    public String getPostgresSslmode() { return postgresSslmode; }
    public int getPostgresPoolSize() { return postgresPoolSize; }
    public String getMongoUri() { return mongoUri; }
    public String getMongoDb() { return mongoDb; }
    public int getMongoTimeoutMs() { return mongoTimeoutMs; }
    public String[] getElasticsearchUrls() { return elasticsearchUrls; }
    public String getElasticsearchIndex() { return elasticsearchIndex; }
    public String getElasticsearchApiKey() { return elasticsearchApiKey; }
    public int getElasticsearchTimeout() { return elasticsearchTimeout; }
    public boolean isElasticsearchRefreshOnWrite() { return elasticsearchRefreshOnWrite; }
    public String getRabbitmqHost() { return rabbitmqHost; }
    public int getRabbitmqPort() { return rabbitmqPort; }
    public String getRabbitmqDefaultUser() { return rabbitmqDefaultUser; }
    public String getRabbitmqDefaultPassword() { return rabbitmqDefaultPassword; }
    public String getRabbitmqUrlField() { return rabbitmqUrl; }
    public int getSeedRandomSeed() { return seedRandomSeed; }
    public String getJwtSecretKey() { return jwtSecretKey; }
    public String getJwtAlgorithm() { return jwtAlgorithm; }
    public int getJwtAccessTokenExpireMinutes() { return jwtAccessTokenExpireMinutes; }
}
