package com.foodly.business.infrastructure.persistence;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.foodly.business.application.port.EventOutbox;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Repository;
import java.time.Instant;
@Repository
public class MongoEventOutbox implements EventOutbox {
    private final MongoTemplate mongo;
    private final ObjectMapper json;
    public MongoEventOutbox(MongoTemplate mongo, ObjectMapper json) { this.mongo = mongo; this.json = json; }
    public void append(Event event) {
        try {
            mongo.insert(new OutboxDocument(event.eventId(), event.type(), event.huariqueId(), json.writeValueAsString(event),
                    event.occurredAt(), Instant.EPOCH, null, null, 0, null));
        } catch (JsonProcessingException e) { throw new IllegalStateException("Cannot serialize business event", e); }
    }
}
