package com.foodly.business.application.port;
import java.time.Instant;
import java.util.UUID;
public interface EventOutbox {
    void append(Event event);
    record Event(UUID eventId, String type, int schemaVersion, UUID huariqueId, Long aggregateVersion, Instant occurredAt, Object data) {
        public static Event of(String type, UUID huariqueId, Long version, Object data) {
            return new Event(UUID.randomUUID(), type, 1, huariqueId, version, Instant.now(), data);
        }
    }
}
