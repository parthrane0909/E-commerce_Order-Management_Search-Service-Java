package com.meridian.backend.config;

import com.mongodb.MongoClientSettings;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.core.convert.converter.Converter;
import org.springframework.data.convert.ReadingConverter;
import org.springframework.data.convert.WritingConverter;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.SimpleMongoClientDatabaseFactory;
import org.springframework.data.mongodb.core.convert.MappingMongoConverter;
import org.springframework.data.mongodb.core.convert.MongoCustomConversions;
import org.springframework.data.mongodb.core.mapping.MongoMappingContext;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.ConnectionString;
import org.springframework.data.mongodb.core.convert.DefaultDbRefResolver;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Date;
import java.util.List;

@Configuration
public class MongoConfig {

    @Value("${spring.data.mongodb.uri:mongodb://localhost:27017}")
    private String mongoUri;

    @Value("${spring.data.mongodb.database:catalog}")
    private String mongoDatabase;

    // NOTE: MongoClientSettingsBuilderCustomizer removed - it is only consumed by
    // Spring Boot's MongoAutoConfiguration, which is intentionally excluded in
    // MeridianApplication. Registering it here was unused dead configuration.

    /**
     * Writes OffsetDateTime as a BSON Date (ISODate) using its instant.
     *
     * The MongoDB Java driver's default Jsr310CodecProvider only supplies codecs for
     * Instant, LocalDate, LocalDateTime and LocalTime - it has NO OffsetDateTime codec.
     * Spring Data MongoDB therefore converts OffsetDateTime to java.util.Date here,
     * which the driver encodes natively as a BSON Date.
     *
     * The UTC offset is intentionally dropped: an instant has a single point in time,
     * and BSON Date stores milliseconds since epoch with no zone information.
     */
    @WritingConverter
    private static final class OffsetDateTimeToDateConverter implements Converter<OffsetDateTime, Date> {
        @Override
        public Date convert(OffsetDateTime source) {
            return source == null ? null : Date.from(source.toInstant());
        }
    }

    /**
     * Reads a BSON Date back as OffsetDateTime.
     *
     * Reconstruction policy: UTC. BSON Date carries no zone/offset information, so a
     * canonical, deterministic zone is required. UTC is chosen because the application
     * already treats timestamps as UTC (see DateUtils.utcNow(), which is what seeds and
     * services use to populate these fields), so round-tripping preserves the original
     * instant and yields OffsetDateTime with ZoneOffset.UTC.
     */
    @ReadingConverter
    private static final class DateToOffsetDateTimeConverter implements Converter<Date, OffsetDateTime> {
        @Override
        public OffsetDateTime convert(Date source) {
            return source == null ? null
                    : OffsetDateTime.ofInstant(source.toInstant(), ZoneOffset.UTC);
        }
    }

    /**
     * Registers the OffsetDateTime <-> Date converters with Spring Data.
     *
     * Selected because it is Spring Data MongoDB's documented conversion mechanism,
     * it operates entirely in the Spring Data layer, and it leaves the driver's codec
     * registry untouched (no manual Jsr310CodecProvider, no custom driver CodecProvider,
     * no second MongoClient).
     */
    @Bean
    public MongoCustomConversions mongoCustomConversions() {
        return new MongoCustomConversions(List.of(
                new OffsetDateTimeToDateConverter(),
                new DateToOffsetDateTimeConverter()));
    }

    @Bean
    @Primary
    public com.mongodb.client.MongoClient mongoClient() {
        // Driver's DEFAULT codec registry, untouched. No manual Jsr310CodecProvider:
        // adding one was investigated and disproven as the cause of the failure.
        return MongoClients.create(MongoClientSettings.builder()
            .applyConnectionString(new ConnectionString(mongoUri))
            .build());
    }

    @Bean
    public SimpleMongoClientDatabaseFactory mongoDatabaseFactory(com.mongodb.client.MongoClient mongoClient) {
        return new SimpleMongoClientDatabaseFactory(mongoClient, mongoDatabase);
    }

    @Bean
    public MongoTemplate mongoTemplate(SimpleMongoClientDatabaseFactory mongoDatabaseFactory,
                                       MongoCustomConversions mongoCustomConversions) {
        MongoMappingContext mappingContext = new MongoMappingContext();
        mappingContext.setSimpleTypeHolder(mongoCustomConversions.getSimpleTypeHolder());
        mappingContext.afterPropertiesSet();

        MappingMongoConverter converter = new MappingMongoConverter(
                new DefaultDbRefResolver(mongoDatabaseFactory), mappingContext);
        converter.setCustomConversions(mongoCustomConversions);
        converter.afterPropertiesSet();

        // The converter MUST be passed in: the single-arg MongoTemplate constructor
        // builds an internal MappingMongoConverter with empty conversions, which would
        // silently ignore the MongoCustomConversions bean above.
        return new MongoTemplate(mongoDatabaseFactory, converter);
    }
}
