package com.foodly.business.infrastructure;

import com.foodly.business.infrastructure.images.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.*;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import javax.net.ssl.SSLHandshakeException;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import static com.foodly.business.infrastructure.images.CloudinaryIntegrationException.Operation.*;
import static com.foodly.business.infrastructure.images.CloudinaryIntegrationException.Reason.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

/** Mock HTTP only: never uses account credentials or contacts Cloudinary. */
class CloudinaryDiagnosticsTest {
    private static final String ID = "foodly/business/58ec0ee8-1349-4197-adf1-4b0de8285ae7";
    private static final byte[] PNG = {(byte)137,80,78,71,13,10,26,10};
    private final RestClient.Builder builder = RestClient.builder().baseUrl("https://api.cloudinary.com/v1_1/test/image");
    private final MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();

    private ImageStorageAdapter adapter(String key, String secret) {
        var adapter = new ImageStorageAdapter("test", key, secret);
        ReflectionTestUtils.setField(adapter,"client",builder.build());
        return adapter;
    }

    @ParameterizedTest @ValueSource(ints={400,401,403,404,429,500,503})
    void preservesHttpStatusWithoutKeepingResponseOrRawCause(int status) {
        var adapter = adapter("test-api-key", "test-api-secret");
        String sensitiveBody = "test-api-key test-api-secret signature=1234567890123456789012345678901234567890 Authorization: Basic encoded-secret";
        server.expect(requestTo("https://api.cloudinary.com/v1_1/test/image/destroy"))
                .andRespond(withStatus(HttpStatusCode.valueOf(status)).body(sensitiveBody));
        var error = catchThrowableOfType(()->adapter.delete(ID), CloudinaryIntegrationException.class);
        assertThat(error.diagnostic()).isEqualTo(new CloudinaryIntegrationException.Diagnostic(DESTROY, HTTP_ERROR, status, ID));
        var trace = new StringWriter(); error.printStackTrace(new PrintWriter(trace));
        assertThat(trace.toString()+error.diagnostic()).doesNotContain("test-api-key","test-api-secret","encoded-secret","1234567890123456789012345678901234567890");
        assertThat(error).hasNoCause(); server.verify();
    }

    @ParameterizedTest @ValueSource(strings={"DNS","TLS","TIMEOUT","CONNECTION","IO"})
    void preservesAllowlistedTransportCauseOnly(String reason) {
        IOException cause = switch (reason) {
            case "DNS" -> new UnknownHostException("sensitive-host-and-api-secret");
            case "TLS" -> new SSLHandshakeException("sensitive certificate and api-secret");
            case "TIMEOUT" -> new SocketTimeoutException("signed URL and api-secret");
            case "CONNECTION" -> new ConnectException("credential-bearing URL");
            default -> new IOException("sensitive request body");
        };
        var adapter = adapter("test-api-key","test-api-secret");
        server.expect(requestTo("https://api.cloudinary.com/v1_1/test/image/destroy")).andRespond(withException(cause));
        var error = catchThrowableOfType(()->adapter.delete(ID), CloudinaryIntegrationException.class);
        assertThat(error.diagnostic().reason().name()).isEqualTo(reason);
        assertThat(error.diagnostic().httpStatus()).isNull();
        assertThat(error.diagnostic().operation()).isEqualTo(DESTROY);
        assertThat(error).hasNoCause();
        assertThat(error.toString()+error.diagnostic()).doesNotContain(cause.getMessage()); server.verify();
    }

    @Test void rejectsPasteControlAndWhitespaceBeforeUploadOrDeletionRequests() {
        for (String invalid : List.of(Character.toString(22), "prefix"+(char)22, " secret ", Character.toString(0x85))) {
            for (boolean invalidKey : List.of(true,false)) {
                var adapter = adapter(invalidKey ? invalid : "test-api-key", invalidKey ? "test-api-secret" : invalid);
                var upload = catchThrowableOfType(()->adapter.upload(PNG,"image/png"), CloudinaryIntegrationException.class);
                var destroy = catchThrowableOfType(()->adapter.delete(ID), CloudinaryIntegrationException.class);
                assertThat(upload.diagnostic().reason()).isEqualTo(CONFIGURATION_INVALID);
                assertThat(destroy.diagnostic().operation()).isEqualTo(CONFIGURATION);
            }
        }
        server.verify(); // No requests were expected or allowed.
    }

    @Test void preservesBothUploadAndCompensationFailuresAndRequestedId() {
        var adapter = adapter("test-api-key","test-api-secret");
        server.expect(requestTo("https://api.cloudinary.com/v1_1/test/image/upload")).andRespond(withStatus(HttpStatus.UNAUTHORIZED).body("sensitive signature"));
        server.expect(requestTo("https://api.cloudinary.com/v1_1/test/image/destroy")).andRespond(withStatus(HttpStatus.FORBIDDEN).body("sensitive secret"));
        var error = catchThrowableOfType(()->adapter.upload(PNG,"image/png"),CloudinaryIntegrationException.class);
        assertThat(error.diagnostic().operation()).isEqualTo(UPLOAD);
        assertThat(error.diagnostic().httpStatus()).isEqualTo(401);
        assertThat(error.diagnostic().publicId()).matches("foodly/business/[a-f0-9-]{36}");
        assertThat(error.getSuppressed()).singleElement().satisfies(suppressed->{
            var cleanup = (CloudinaryIntegrationException)suppressed;
            assertThat(cleanup.diagnostic().operation()).isEqualTo(DESTROY);
            assertThat(cleanup.diagnostic().httpStatus()).isEqualTo(403);
            assertThat(cleanup.diagnostic().publicId()).isEqualTo(error.diagnostic().publicId());
        });
        assertThat(error.compensatingDeletionConfirmed()).isFalse(); server.verify();
    }

    @Test void recordsAcknowledgedCompensationWithoutClaimingSuccessfulUpload() {
        var adapter = adapter("test-api-key","test-api-secret");
        server.expect(requestTo("https://api.cloudinary.com/v1_1/test/image/upload")).andRespond(withStatus(HttpStatus.BAD_GATEWAY));
        server.expect(requestTo("https://api.cloudinary.com/v1_1/test/image/destroy")).andRespond(withSuccess("{\"result\":\"not found\"}",MediaType.APPLICATION_JSON));
        var error = catchThrowableOfType(()->adapter.upload(PNG,"image/png"),CloudinaryIntegrationException.class);
        assertThat(error.diagnostic().operation()).isEqualTo(UPLOAD);
        assertThat(error.compensatingDeletionConfirmed()).isTrue(); server.verify();
    }

    @Test void malformedJsonIsReportedAsInvalidResponseWithoutItsContents() {
        var adapter = adapter("test-api-key","test-api-secret");
        server.expect(requestTo("https://api.cloudinary.com/v1_1/test/image/destroy")).andRespond(withSuccess("{api-secret-invalid-json",MediaType.APPLICATION_JSON));
        var error = catchThrowableOfType(()->adapter.delete(ID),CloudinaryIntegrationException.class);
        assertThat(error.diagnostic().reason()).isEqualTo(INVALID_RESPONSE);
        assertThat(error).hasNoCause(); server.verify();
    }

    @Test void destroyUsesSignedFormWithExactlyTheExpectedAuthenticationParameters() {
        var adapter = adapter("test-api-key","test-api-secret");
        server.expect(requestTo("https://api.cloudinary.com/v1_1/test/image/destroy"))
            .andExpect(method(HttpMethod.POST)).andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_FORM_URLENCODED))
            .andExpect(request->{
                String body = ((org.springframework.mock.http.client.MockClientHttpRequest)request).getBodyAsString();
                Map<String,String> values = new HashMap<>();
                for (String item : body.split("&")) {
                    String[] pair=item.split("=",2); values.put(URLDecoder.decode(pair[0],StandardCharsets.UTF_8),URLDecoder.decode(pair[1],StandardCharsets.UTF_8));
                }
                assertThat(values.keySet()).containsExactlyInAnyOrder("api_key","timestamp","public_id","invalidate","signature");
                assertThat(values.get("public_id")).isEqualTo(ID);
                assertThat(values.get("invalidate")).isEqualTo("true");
                try {
                    String canonical="invalidate=true&public_id="+ID+"&timestamp="+values.get("timestamp")+"test-api-secret";
                    assertThat(values.get("signature")).isEqualTo(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest(canonical.getBytes(StandardCharsets.UTF_8))));
                } catch (java.security.NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
                assertThat(body).doesNotContain("test-api-secret");
                assertThat(request.getHeaders().containsKey(HttpHeaders.AUTHORIZATION)).isFalse();
            }).andRespond(withSuccess("{\"result\":\"ok\"}",MediaType.APPLICATION_JSON));
        adapter.delete(ID); server.verify();
    }
}
