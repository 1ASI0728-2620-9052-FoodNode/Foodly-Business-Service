package com.foodly.business.infrastructure;
import com.foodly.business.application.port.IntegrationException;
import com.foodly.business.infrastructure.messaging.BusinessEventPublisher;
import com.foodly.business.infrastructure.persistence.OutboxDocument;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import java.time.Instant;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class BusinessEventPublisherTest {
    OutboxDocument event() { return new OutboxDocument(UUID.randomUUID(),"HuariqueRegistrado",UUID.randomUUID(),"{}",Instant.now(),Instant.EPOCH,null,null,0,null); }
    @Test void waitsForConfirmAndSendsPersistentMessageWithStableId() {
        var rabbit=mock(RabbitTemplate.class); var event=event();
        doAnswer(i->{ Message m=i.getArgument(2); assertThat(m.getMessageProperties().getMessageId()).isEqualTo(event.id().toString()); assertThat(m.getMessageProperties().getDeliveryMode()).isEqualTo(MessageDeliveryMode.PERSISTENT); ((CorrelationData)i.getArgument(3)).getFuture().complete(new CorrelationData.Confirm(true,null)); return null; })
                .when(rabbit).convertAndSend(eq("exchange"),eq("business.HuariqueRegistrado"),any(Object.class),any(CorrelationData.class));
        new BusinessEventPublisher(rabbit,"exchange").publish(event);
    }
    @Test void rejectsBrokerNack() {
        var rabbit=mock(RabbitTemplate.class);
        doAnswer(i->{ ((CorrelationData)i.getArgument(3)).getFuture().complete(new CorrelationData.Confirm(false,"nack")); return null; }).when(rabbit).convertAndSend(anyString(),anyString(),any(Object.class),any(CorrelationData.class));
        assertThatThrownBy(()->new BusinessEventPublisher(rabbit,"exchange").publish(event())).isInstanceOf(IntegrationException.class);
    }
    @Test void rejectsUnroutableEvenWhenBrokerAcknowledges() {
        var rabbit=mock(RabbitTemplate.class);
        doAnswer(i->{ CorrelationData c=i.getArgument(3); c.setReturned(new ReturnedMessage(new Message(new byte[0]),312,"NO_ROUTE","exchange","business.HuariqueRegistrado")); c.getFuture().complete(new CorrelationData.Confirm(true,null)); return null; }).when(rabbit).convertAndSend(anyString(),anyString(),any(Object.class),any(CorrelationData.class));
        assertThatThrownBy(()->new BusinessEventPublisher(rabbit,"exchange").publish(event())).isInstanceOf(IntegrationException.class);
    }
}
