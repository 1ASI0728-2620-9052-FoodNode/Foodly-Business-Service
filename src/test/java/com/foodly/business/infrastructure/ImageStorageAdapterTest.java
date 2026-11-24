package com.foodly.business.infrastructure;

import com.foodly.business.application.port.IntegrationException;
import com.foodly.business.domain.model.DomainException;
import com.foodly.business.infrastructure.images.ImageStorageAdapter;
import org.junit.jupiter.api.Test;
import org.springframework.http.*;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import java.util.regex.Pattern;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

/** HTTP contract tests use a mock server; no claims of Cloudinary account integration. */
class ImageStorageAdapterTest {
    final byte[] png = new byte[]{(byte)137,80,78,71,13,10,26,10};
    @Test void rejectsMimeMismatch() {
        assertThatThrownBy(()->new ImageStorageAdapter("","","").upload(png,"image/jpeg")).isInstanceOf(DomainException.class);
    }
    @Test void rejectsOversizedUpload() {
        var oversized = java.util.Arrays.copyOf(png,5*1024*1024+1);
        assertThatThrownBy(()->new ImageStorageAdapter("","","").upload(oversized,"image/png")).isInstanceOf(DomainException.class);
    }
    @Test void requiresConfiguredCredentialsForValidInput() {
        assertThatThrownBy(()->new ImageStorageAdapter("","","").upload(png,"image/png")).isInstanceOf(IntegrationException.class).hasMessageContaining("not configured");
    }
    @Test void uploadsSignedMultipartToImageApiAndMapsReference() {
        var builder=RestClient.builder().baseUrl("https://api.cloudinary.com/v1_1/test/image");
        var server=MockRestServiceServer.bindTo(builder).build();
        var adapter=new ImageStorageAdapter("test","test-api-key","test-api-secret");
        ReflectionTestUtils.setField(adapter,"client",builder.build());
        server.expect(requestTo("https://api.cloudinary.com/v1_1/test/image/upload")).andExpect(method(HttpMethod.POST))
            .andExpect(content().contentTypeCompatibleWith(MediaType.MULTIPART_FORM_DATA))
            .andRespond(request->{
                String body=((MockClientHttpRequest)request).getBodyAsString();
                assertThat(body).contains("name=\"signature\"", "name=\"timestamp\"", "name=\"overwrite\"", "test-api-key").doesNotContain("test-api-secret");
                var matcher=Pattern.compile("foodly/business/[a-f0-9-]+").matcher(body); assertThat(matcher.find()).isTrue();
                var timestamp=Pattern.compile("name=\"timestamp\"[^\\r\\n]*\\r\\n(?:[^\\r\\n]+\\r\\n)*\\r\\n(\\d+)").matcher(body); assertThat(timestamp.find()).isTrue();
                try {
                    var signature=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest(
                            ("overwrite=false&public_id="+matcher.group()+"&timestamp="+timestamp.group(1)+"test-api-secret").getBytes(StandardCharsets.UTF_8)));
                    assertThat(body).contains(signature);
                } catch (java.security.NoSuchAlgorithmException e) { throw new AssertionError(e); }
                return withSuccess("{\"public_id\":\""+matcher.group()+"\",\"secure_url\":\"https://res.cloudinary.com/test/image.png\"}",MediaType.APPLICATION_JSON).createResponse(request);
            });
        assertThat(adapter.upload(png,"image/png").url()).isEqualTo("https://res.cloudinary.com/test/image.png"); server.verify();
    }
    @Test void treatsMissingAssetDeletionAsIdempotentSuccess() {
        var builder=RestClient.builder().baseUrl("https://api.cloudinary.com/v1_1/test/image"); var server=MockRestServiceServer.bindTo(builder).build();
        var adapter=new ImageStorageAdapter("test","test-api-key","test-api-secret"); ReflectionTestUtils.setField(adapter,"client",builder.build());
        server.expect(requestTo("https://api.cloudinary.com/v1_1/test/image/destroy")).andExpect(method(HttpMethod.POST))
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_FORM_URLENCODED))
            .andExpect(content().string(org.hamcrest.Matchers.containsString("invalidate=true")))
            .andRespond(withSuccess("{\"result\":\"not found\"}",MediaType.APPLICATION_JSON));
        adapter.delete("foodly/business/test"); server.verify();
    }
    @Test void sanitizesExternalErrors() {
        var builder=RestClient.builder().baseUrl("https://api.cloudinary.com/v1_1/test/image"); var server=MockRestServiceServer.bindTo(builder).build();
        var adapter=new ImageStorageAdapter("test","test-api-key","test-api-secret"); ReflectionTestUtils.setField(adapter,"client",builder.build());
        server.expect(requestTo("https://api.cloudinary.com/v1_1/test/image/destroy")).andRespond(withStatus(HttpStatus.UNAUTHORIZED).body("sensitive external response"));
        assertThatThrownBy(()->adapter.delete("foodly/business/test")).isInstanceOf(IntegrationException.class).hasMessage("Cloudinary request failed").hasNoCause(); server.verify();
    }
    @Test void rejectsNullAndEmptyImages() {
        var adapter=new ImageStorageAdapter("","","");
        assertThatThrownBy(()->adapter.upload(null,"image/png")).isInstanceOf(DomainException.class);
        assertThatThrownBy(()->adapter.upload(new byte[0],"image/png")).isInstanceOf(DomainException.class);
    }
    @Test void rejectsInvalidImageSignature() {
        assertThatThrownBy(()->new ImageStorageAdapter("","","").upload("not an image".getBytes(StandardCharsets.UTF_8),"image/png")).isInstanceOf(DomainException.class);
    }
    @Test void invalidProviderUrlIsIntegrationFailureAndCompensatesOnlyOwnedUpload() {
        var builder=RestClient.builder().baseUrl("https://api.cloudinary.com/v1_1/test/image");
        var server=MockRestServiceServer.bindTo(builder).build(); var adapter=new ImageStorageAdapter("test","test-api-key","test-api-secret");
        ReflectionTestUtils.setField(adapter,"client",builder.build());
        var requestedId=new java.util.concurrent.atomic.AtomicReference<String>();
        server.expect(requestTo("https://api.cloudinary.com/v1_1/test/image/upload")).andRespond(request->{
            var matcher=Pattern.compile("foodly/business/[a-f0-9-]+").matcher(((MockClientHttpRequest)request).getBodyAsString());
            assertThat(matcher.find()).isTrue(); requestedId.set(matcher.group());
            return withSuccess("{\"public_id\":\""+matcher.group()+"\",\"secure_url\":\"https:invalid\"}",MediaType.APPLICATION_JSON).createResponse(request);
        });
        server.expect(requestTo("https://api.cloudinary.com/v1_1/test/image/destroy")).andExpect(request->{
            String body=((MockClientHttpRequest)request).getBodyAsString();
            assertThat(java.net.URLDecoder.decode(body,StandardCharsets.UTF_8)).contains("public_id="+requestedId.get());
        }).andRespond(withSuccess("{\"result\":\"ok\"}",MediaType.APPLICATION_JSON));
        assertThatThrownBy(()->adapter.upload(png,"image/png")).isInstanceOf(IntegrationException.class).hasMessage("Invalid Cloudinary upload response"); server.verify();
    }
    @Test void uploadTransportFailureAttemptsOwnedAssetCleanupWithoutLeakingResponse() {
        var builder=RestClient.builder().baseUrl("https://api.cloudinary.com/v1_1/test/image"); var server=MockRestServiceServer.bindTo(builder).build();
        var adapter=new ImageStorageAdapter("test","test-api-key","test-api-secret"); ReflectionTestUtils.setField(adapter,"client",builder.build());
        server.expect(requestTo("https://api.cloudinary.com/v1_1/test/image/upload")).andRespond(withStatus(HttpStatus.BAD_GATEWAY).body("sensitive external response"));
        server.expect(requestTo("https://api.cloudinary.com/v1_1/test/image/destroy")).andRespond(withSuccess("{\"result\":\"not found\"}",MediaType.APPLICATION_JSON));
        assertThatThrownBy(()->adapter.upload(png,"image/png")).isInstanceOf(IntegrationException.class).hasMessage("Cloudinary request failed").hasNoCause(); server.verify();
    }
    @Test void mismatchedProviderIdNeverDeletesAnotherAsset() {
        var builder=RestClient.builder().baseUrl("https://api.cloudinary.com/v1_1/test/image"); var server=MockRestServiceServer.bindTo(builder).build();
        var adapter=new ImageStorageAdapter("test","test-api-key","test-api-secret"); ReflectionTestUtils.setField(adapter,"client",builder.build());
        var requestedId=new java.util.concurrent.atomic.AtomicReference<String>();
        server.expect(requestTo("https://api.cloudinary.com/v1_1/test/image/upload")).andRespond(request->{
            var matcher=Pattern.compile("foodly/business/[a-f0-9-]+").matcher(((MockClientHttpRequest)request).getBodyAsString());
            assertThat(matcher.find()).isTrue(); requestedId.set(matcher.group());
            return withSuccess("{\"public_id\":\"foodly/business/someone-else\",\"secure_url\":\"https://example.com/image.png\"}",MediaType.APPLICATION_JSON).createResponse(request);
        });
        server.expect(requestTo("https://api.cloudinary.com/v1_1/test/image/destroy")).andExpect(request->{
            var body=java.net.URLDecoder.decode(((MockClientHttpRequest)request).getBodyAsString(),StandardCharsets.UTF_8);
            assertThat(body).contains("public_id="+requestedId.get()).doesNotContain("someone-else");
        }).andRespond(withSuccess("{\"result\":\"not found\"}",MediaType.APPLICATION_JSON));
        assertThatThrownBy(()->adapter.upload(png,"image/png")).isInstanceOf(IntegrationException.class).hasMessage("Invalid Cloudinary upload response"); server.verify();
    }
    @Test void cleanupFailureDoesNotHideOriginalUploadFailure() {
        var builder=RestClient.builder().baseUrl("https://api.cloudinary.com/v1_1/test/image"); var server=MockRestServiceServer.bindTo(builder).build();
        var adapter=new ImageStorageAdapter("test","test-api-key","test-api-secret"); ReflectionTestUtils.setField(adapter,"client",builder.build());
        server.expect(requestTo("https://api.cloudinary.com/v1_1/test/image/upload")).andRespond(withStatus(HttpStatus.BAD_GATEWAY));
        server.expect(requestTo("https://api.cloudinary.com/v1_1/test/image/destroy")).andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));
        assertThatThrownBy(()->adapter.upload(png,"image/png")).isInstanceOf(IntegrationException.class).hasMessage("Cloudinary request failed")
                .satisfies(e->assertThat(e.getSuppressed()).hasSize(1)); server.verify();
    }
    @Test void rejectsDeletionOutsideManagedNamespace() {
        var adapter=new ImageStorageAdapter("test","test-api-key","test-api-secret");
        assertThatThrownBy(()->adapter.delete("another-application/asset")).isInstanceOf(IntegrationException.class);
        assertThatThrownBy(()->adapter.delete("foodly/business/")).isInstanceOf(IntegrationException.class);
    }
}
