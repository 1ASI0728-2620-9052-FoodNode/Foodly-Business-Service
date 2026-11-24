package com.foodly.business.infrastructure.images;

import com.fasterxml.jackson.databind.JsonNode;
import com.foodly.business.application.port.*;
import com.foodly.business.domain.model.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.RestClient;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import static com.foodly.business.infrastructure.images.CloudinaryIntegrationException.Operation.*;

@Component
public class ImageStorageAdapter implements ImageStorage {
    private final RestClient client;
    private final String cloudName, apiKey, apiSecret;
    public ImageStorageAdapter(@Value("${business.cloudinary.cloud-name:}") String cloudName,
            @Value("${business.cloudinary.api-key:}") String apiKey, @Value("${business.cloudinary.api-secret:}") String apiSecret) {
        this.cloudName = cloudName; this.apiKey = apiKey; this.apiSecret = apiSecret;
        var factory = new org.springframework.http.client.SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(5000); factory.setReadTimeout(15000);
        client = RestClient.builder().requestFactory(factory).baseUrl("https://api.cloudinary.com/v1_1/" + cloudName + "/image").build();
    }
    public ImageReference upload(byte[] data, String contentType) {
        if (data == null || data.length == 0) throw DomainException.invalid("Image must not be empty");
        boolean png = data.length >= 8 && Arrays.equals(Arrays.copyOf(data, 8), new byte[]{(byte)137,80,78,71,13,10,26,10});
        boolean jpeg = data.length >= 3 && data[0] == (byte)255 && data[1] == (byte)216 && data[2] == (byte)255;
        boolean webp = data.length >= 12 && new String(data,0,4,StandardCharsets.US_ASCII).equals("RIFF") && new String(data,8,4,StandardCharsets.US_ASCII).equals("WEBP");
        if (data.length > 5 * 1024 * 1024 || !(png && "image/png".equals(contentType) || jpeg && "image/jpeg".equals(contentType) || webp && "image/webp".equals(contentType)))
            throw DomainException.invalid("Image must be PNG, JPEG or WebP, at most 5 MiB, with matching content type");
        checkConfigured();
        String publicId = "foodly/business/" + UUID.randomUUID();
        String timestamp = Long.toString(Instant.now().getEpochSecond());
        var body = new LinkedMultiValueMap<String, Object>();
        body.add("api_key", apiKey); body.add("timestamp", timestamp); body.add("public_id", publicId);
        body.add("overwrite", "false");
        body.add("signature", sign("overwrite=false&public_id=" + publicId + "&timestamp=" + timestamp));
        body.add("file", new ByteArrayResource(data) { @Override public String getFilename() { return "image"; } });
        try {
            var response = request("/upload", body, MediaType.MULTIPART_FORM_DATA, UPLOAD, publicId);
            String returnedId = response.path("public_id").asText(), url = response.path("secure_url").asText();
            if (!publicId.equals(returnedId) || url.isBlank()) throw CloudinaryIntegrationException.invalidResponse(UPLOAD, publicId);
            try { return new ImageReference(UUID.randomUUID(), returnedId, url); }
            catch (DomainException invalidReference) { throw CloudinaryIntegrationException.invalidResponse(UPLOAD, publicId); }
        } catch (CloudinaryIntegrationException failure) {
            // The provider may have created this fresh UUID asset before a timeout or invalid response.
            // Compensate only our requested id, never a different id returned by the provider.
            try { delete(publicId); failure.confirmCompensatingDeletion(); }
            catch (RuntimeException cleanupFailure) {
                var safeCleanup = CloudinaryIntegrationException.from(DESTROY, publicId, cleanupFailure);
                org.slf4j.LoggerFactory.getLogger(getClass()).error("Cloudinary upload cleanup failed: {}. Manual reconciliation required", safeCleanup.diagnostic());
                failure.addSuppressed(safeCleanup);
            }
            throw failure;
        }
    }
    public void delete(String publicId) {
        checkConfigured();
        if (publicId == null || !publicId.startsWith("foodly/business/") || publicId.length() == "foodly/business/".length())
            throw new IntegrationException("Invalid managed image id");
        String timestamp = Long.toString(Instant.now().getEpochSecond());
        var body = new LinkedMultiValueMap<String, Object>();
        body.add("api_key", apiKey); body.add("public_id", publicId); body.add("timestamp", timestamp); body.add("invalidate", "true");
        body.add("signature", sign("invalidate=true&public_id=" + publicId + "&timestamp=" + timestamp));
        var result = request("/destroy", body, MediaType.APPLICATION_FORM_URLENCODED, DESTROY, publicId).path("result").asText();
        if (!Set.of("ok", "not found").contains(result)) throw CloudinaryIntegrationException.invalidResponse(DESTROY, publicId);
    }
    private JsonNode request(String path, LinkedMultiValueMap<String, Object> body, MediaType contentType,
            CloudinaryIntegrationException.Operation operation, String publicId) {
        try {
            var result = client.post().uri(path).contentType(contentType).body(body).retrieve().body(JsonNode.class);
            if (result == null) throw CloudinaryIntegrationException.invalidResponse(operation, publicId);
            return result;
        } catch (org.springframework.web.client.RestClientException e) {
            var safe = CloudinaryIntegrationException.from(operation, publicId, e);
            org.slf4j.LoggerFactory.getLogger(getClass()).warn("Cloudinary operation failed: {}", safe.diagnostic());
            throw safe;
        }
    }
    private String sign(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest((value + apiSecret).getBytes(StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
    private void checkConfigured() {
        if (cloudName == null || apiKey == null || apiSecret == null || cloudName.isBlank() || apiKey.isBlank() || apiSecret.isBlank())
            throw CloudinaryIntegrationException.configuration(true);
        if (!cloudName.matches("[a-zA-Z0-9_-]+") || invalidCredential(apiKey) || invalidCredential(apiSecret))
            throw CloudinaryIntegrationException.configuration(false);
    }
    private static boolean invalidCredential(String value) {
        return !value.equals(value.strip()) || value.codePoints().anyMatch(Character::isISOControl);
    }
}
