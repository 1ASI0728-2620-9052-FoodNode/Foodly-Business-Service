package com.foodly.business.infrastructure.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.foodly.business.application.port.ImageStorage;
import com.foodly.business.infrastructure.persistence.OutboxDocument;
import org.slf4j.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.*;
import org.springframework.data.mongodb.core.query.*;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import java.time.Instant;
import java.util.UUID;
import static org.springframework.data.mongodb.core.query.Criteria.where;

@Component
@ConditionalOnProperty(name = "business.outbox.enabled", havingValue = "true", matchIfMissing = true)
public class OutboxPublisher {
    private static final Logger log = LoggerFactory.getLogger(OutboxPublisher.class);
    private final MongoTemplate mongo;
    private final BusinessEventPublisher publisher;
    private final ImageStorage images;
    private final ObjectMapper json;
    public OutboxPublisher(MongoTemplate mongo, BusinessEventPublisher publisher, ImageStorage images, ObjectMapper json) {
        this.mongo = mongo; this.publisher = publisher; this.images = images; this.json = json;
    }
    @Scheduled(fixedDelayString = "${business.outbox.delay-ms:1000}")
    public void drain() {
        for (int i = 0; i < 20; i++) {
            String token = UUID.randomUUID().toString();
            var now = Instant.now();
            var query = Query.query(where("publishedAt").is(null).and("nextAttemptAt").lte(now)).with(Sort.by("occurredAt", "id"));
            // A renewable next-attempt timestamp doubles as a lease; expired leases are retried after a crash.
            var event = mongo.findAndModify(query, new Update().set("leaseToken", token).set("nextAttemptAt", now.plusSeconds(60)).inc("attempts", 1),
                    FindAndModifyOptions.options().returnNew(true), OutboxDocument.class);
            if (event == null) return;
            var leased = Query.query(where("id").is(event.id()).and("leaseToken").is(token).and("publishedAt").is(null));
            try {
                if (event.type().equals("internal.ImageDeletionRequested")) images.delete(json.readTree(event.payload()).path("data").path("publicId").asText());
                else publisher.publish(event);
                mongo.updateFirst(leased, new Update().set("publishedAt", Instant.now()).unset("leaseToken").unset("lastError"), OutboxDocument.class);
            } catch (Exception failure) {
                // Avoid persisting external responses, which can contain credentials or signed URLs.
                mongo.updateFirst(leased, new Update().set("nextAttemptAt", Instant.now().plusSeconds(Math.min(300, 1L << Math.min(event.attempts(), 8))))
                        .set("lastError", failure.getClass().getSimpleName()).unset("leaseToken"), OutboxDocument.class);
                log.warn("Outbox delivery failed: event={}, type={}, attempt={}, cause={}", event.id(), event.type(), event.attempts(), failure.getClass().getSimpleName());
            }
        }
    }
}
