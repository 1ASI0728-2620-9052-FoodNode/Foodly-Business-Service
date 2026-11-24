package com.foodly.business.infrastructure.messaging;
import org.springframework.amqp.core.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.*;
@Configuration
public class RabbitConfiguration {
    @Bean TopicExchange businessExchange(@Value("${business.events.exchange}") String name) { return new TopicExchange(name, true, false); }
}
