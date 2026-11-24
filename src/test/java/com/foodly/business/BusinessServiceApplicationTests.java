package com.foodly.business;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.ApplicationRunner;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@SpringBootTest(properties = {"business.outbox.enabled=false", "business.iam.secret-base64=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
        "business.iam.issuer=foodly-iam", "business.iam.audience=business", "spring.rabbitmq.username=test", "spring.rabbitmq.password=test"})
class BusinessServiceApplicationTests {
    // This is a wiring test. Real Mongo indexes/transactions are verified separately with external infrastructure.
    @MockitoBean(name = "businessIndexes") ApplicationRunner indexes;

	@Test
	void contextLoads() {
	}

}
