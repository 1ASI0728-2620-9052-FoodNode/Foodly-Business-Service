package com.foodly.business.interfaces.rest;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Full application context (springdoc is not part of the {@code @WebMvcTest} slices) with the real security chain.
 * Proves the OpenAPI documentation is public while the business API stays protected. Mongo/RabbitMQ connect lazily and
 * are never used here; the index runner is mocked as in {@code BusinessServiceApplicationTests}.
 */
@SpringBootTest(properties = {"business.outbox.enabled=false", "business.iam.secret-base64=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
        "business.iam.issuer=foodly-iam", "business.iam.audience=foodly-business", "spring.rabbitmq.username=test", "spring.rabbitmq.password=test"})
@AutoConfigureMockMvc
class OpenApiDocsTest {
    @MockitoBean(name = "businessIndexes") ApplicationRunner indexes;
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;

    private JsonNode apiDocs() throws Exception {
        var body = mvc.perform(get("/v3/api-docs")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return json.readTree(body);
    }

    @Test
    void apiDocsArePublicAndDescribeTheService() throws Exception {
        var docs = apiDocs();
        assertThat(docs.at("/info/title").asText()).isEqualTo("Foodly Business Service");
        assertThat(docs.at("/info/version").asText()).isNotBlank();
        var scheme = docs.at("/components/securitySchemes/bearerAuth");
        assertThat(scheme.path("type").asText()).isEqualTo("http");
        assertThat(scheme.path("scheme").asText()).isEqualTo("bearer");
        assertThat(scheme.path("bearerFormat").asText()).isEqualTo("JWT");
    }

    @Test
    void documentsEveryEndpointGroupWithTags() throws Exception {
        var docs = apiDocs();
        var tags = new java.util.HashSet<String>();
        docs.path("paths").forEach(path -> path.forEach(op -> op.path("tags").forEach(t -> tags.add(t.asText()))));
        assertThat(tags).containsExactlyInAnyOrder("Huariques", "Carta", "Imágenes");
        assertThat(docs.at("/paths/~1business-api~1huariques/post/summary").asText()).isNotBlank();
    }

    @Test
    void registerDocumentsSuccessAndErrorResponses() throws Exception {
        var responses = apiDocs().at("/paths/~1business-api~1huariques/post/responses");
        assertThat(responses.fieldNames()).toIterable().contains("201", "400", "401", "403", "409");
        assertThat(responses.at("/401/$ref").asText()).isEqualTo("#/components/responses/Unauthorized");
        var components = apiDocs().at("/components/responses");
        assertThat(components.fieldNames()).toIterable().contains("BadRequest", "Unauthorized", "Forbidden", "NotFound", "Conflict");
        assertThat(apiDocs().at("/components/schemas/ApiError").isMissingNode()).isFalse();
    }

    @Test
    void getByIdDocumentsNotFound() throws Exception {
        var responses = apiDocs().at("/paths/~1business-api~1huariques~1{id}/get/responses");
        assertThat(responses.fieldNames()).toIterable().contains("200", "400", "401", "404");
    }

    @Test
    void swaggerUiAndConfigArePublic() throws Exception {
        mvc.perform(get("/swagger-ui.html")).andExpect(result ->
                assertThat(result.getResponse().getStatus()).isNotIn(401, 403));
        mvc.perform(get("/swagger-ui/index.html")).andExpect(status().isOk());
        mvc.perform(get("/v3/api-docs/swagger-config")).andExpect(status().isOk());
    }

    @Test
    void businessApiStillRequiresAToken() throws Exception {
        mvc.perform(get("/business-api/huariques")).andExpect(status().isUnauthorized());
        mvc.perform(get("/business-api/huariques/me")).andExpect(status().isUnauthorized());
        mvc.perform(post("/business-api/huariques").contentType("application/json").content("{}")).andExpect(status().isUnauthorized());
    }

    @Test
    void otherPathsStayClosed() throws Exception {
        mvc.perform(get("/v3/other")).andExpect(result -> assertThat(result.getResponse().getStatus()).isIn(401, 403, 404));
        mvc.perform(get("/actuator/env")).andExpect(result -> assertThat(result.getResponse().getStatus()).isIn(401, 403, 404));
    }
}
