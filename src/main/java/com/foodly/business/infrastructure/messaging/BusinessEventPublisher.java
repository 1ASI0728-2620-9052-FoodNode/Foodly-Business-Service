package com.foodly.business.infrastructure.messaging;

import com.foodly.business.application.port.IntegrationException;
import com.foodly.business.infrastructure.persistence.OutboxDocument;
import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

@Component
public class BusinessEventPublisher {
    private final RabbitTemplate rabbit;
    private final String exchange;
    public BusinessEventPublisher(RabbitTemplate rabbit, @Value("${business.events.exchange}") String exchange) {
        this.rabbit = rabbit; this.exchange = exchange;
    }
    public void publish(OutboxDocument event) {
        var properties = new MessageProperties();
        properties.setContentType(MessageProperties.CONTENT_TYPE_JSON);
        properties.setDeliveryMode(MessageDeliveryMode.PERSISTENT);
        properties.setMessageId(event.id().toString()); properties.setType(event.type());
        properties.setHeader("schemaVersion", 1);
        var correlation = new CorrelationData(event.id().toString());
        try {
            rabbit.convertAndSend(exchange, "business." + event.type(), new Message(event.payload().getBytes(StandardCharsets.UTF_8), properties), correlation);
            var confirm = correlation.getFuture().get(5, TimeUnit.SECONDS);
            if (!confirm.isAck() || correlation.getReturned() != null) throw new IntegrationException("Broker did not acknowledge a routed message");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt(); throw new IntegrationException("Interrupted waiting for RabbitMQ confirm", e);
        } catch (IntegrationException e) { throw e; }
        catch (Exception e) { throw new IntegrationException("RabbitMQ publish failed", e); }
    }
}
