package com.foodly.business.infrastructure.persistence;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;
import java.time.Instant;
import java.util.UUID;
@Document("business_outbox")
public record OutboxDocument(@Id UUID id, String type, UUID huariqueId, String payload, Instant occurredAt,
        Instant nextAttemptAt, Instant publishedAt, String leaseToken, int attempts, String lastError) { }
