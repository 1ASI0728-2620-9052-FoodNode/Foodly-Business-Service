package com.foodly.business.infrastructure.config;

import com.foodly.business.domain.repository.IHuariqueRepository;
import com.foodly.business.domain.service.BusinessDomainService;
import com.foodly.business.infrastructure.persistence.*;
import org.bson.types.Decimal128;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.*;
import org.springframework.core.convert.converter.Converter;
import org.springframework.data.convert.*;
import org.springframework.data.mongodb.MongoDatabaseFactory;
import org.springframework.data.mongodb.MongoTransactionManager;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.convert.MongoCustomConversions;
import org.springframework.data.mongodb.core.index.Index;
import org.springframework.data.domain.Sort.Direction;
import org.springframework.scheduling.annotation.EnableScheduling;
import java.math.BigDecimal;
import java.util.List;

@Configuration
@EnableScheduling
public class BusinessConfiguration {
    @Bean BusinessDomainService businessDomainService(IHuariqueRepository repository) { return new BusinessDomainService(repository); }
    @Bean MongoTransactionManager transactionManager(MongoDatabaseFactory factory) { return new MongoTransactionManager(factory); }
    @Bean MongoCustomConversions mongoConversions() { return new MongoCustomConversions(List.of(new WriteDecimal(), new ReadDecimal())); }
    @WritingConverter static class WriteDecimal implements Converter<BigDecimal, Decimal128> {
        public Decimal128 convert(BigDecimal value) { return new Decimal128(value); }
    }
    @ReadingConverter static class ReadDecimal implements Converter<Decimal128, BigDecimal> {
        public BigDecimal convert(Decimal128 value) { return value.bigDecimalValue(); }
    }
    // Collections/indexes are created before transactions (Mongo cannot create collections within every transaction scenario).
    @Bean ApplicationRunner businessIndexes(MongoTemplate mongo) {
        return args -> {
            mongo.indexOps(HuariqueDocument.class).createIndex(new Index().on("ownerId", Direction.ASC).unique());
            mongo.indexOps(MenuDocument.class).createIndex(new Index().on("huariqueId", Direction.ASC).unique());
            mongo.indexOps(OutboxDocument.class).createIndex(new Index().on("publishedAt", Direction.ASC).on("nextAttemptAt", Direction.ASC).on("occurredAt", Direction.ASC));
        };
    }
}
