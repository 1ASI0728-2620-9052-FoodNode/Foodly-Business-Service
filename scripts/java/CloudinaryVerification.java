package com.foodly.business.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.foodly.business.domain.model.ImageReference;
import com.foodly.business.infrastructure.images.ImageStorageAdapter;
import com.foodly.business.infrastructure.images.CloudinaryIntegrationException;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.Arrays;
import static com.foodly.business.infrastructure.images.CloudinaryIntegrationException.Operation.*;

/** Opt-in real account probe. No Spring context, MongoDB, RabbitMQ, workers or mocks. */
public final class CloudinaryVerification {
    private static final ObjectMapper JSON = new ObjectMapper();

    public static void main(String[] args) {
        try {
            if (args.length != 2 || !(args[0].equals("--approved") || args[0].equals("--prepare-only")))
                throw new IllegalArgumentException("Use the PowerShell wrapper and its explicit authorization guard.");
            byte[] png = png();
            if (args[0].equals("--prepare-only")) {
                System.out.println("PREPARED: production adapter linked; genuine 1x1 PNG generated. No external requests executed.");
                return;
            }
            verify(Path.of(args[1]), png);
        } catch (Exception failure) {
            // Never print provider response bodies, credentials or raw exception messages.
            if (failure instanceof CloudinaryIntegrationException safe)
                System.err.println("Cloudinary sanitized diagnostic: " + safe.diagnostic());
            System.err.println("CLOUDINARY VERIFICATION FAILED (" + failure.getClass().getSimpleName() + "). No success is claimed; review the private receipt/logged asset id.");
            System.exit(1);
        }
    }

    private static void verify(Path root, byte[] png) throws Exception {
        String cloud = required("CLOUDINARY_CLOUD_NAME");
        if (!cloud.matches("[a-zA-Z0-9_-]+")) throw new IllegalArgumentException("Invalid cloud name");
        String key = required("CLOUDINARY_API_KEY"), secret = required("CLOUDINARY_API_SECRET");
        var adapter = new ImageStorageAdapter(cloud, key, secret);
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(5000); factory.setReadTimeout(15000);
        var admin = RestClient.builder().requestFactory(factory)
                .baseUrl("https://api.cloudinary.com/v1_1/" + cloud + "/resources/image/upload/")
                .defaultHeaders(headers -> headers.setBasicAuth(key, secret)).build();
        var directory = root.toAbsolutePath().normalize().resolve(".local/cloudinary");
        // The wrapper prepares the directory with a private Windows ACL.
        if (!Files.isDirectory(directory)) throw new IllegalStateException("Private receipt directory is missing");
        var receipt = directory.resolve("verification-" + UUID.randomUUID() + ".json");
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("startedAt", Instant.now().toString());
        report.put("scope", "production ImageStorageAdapter against a real account; no Business HTTP/database/outbox operations");
        report.put("status", "STARTED");
        for (var indicator : new String[]{"uploadConfirmed", "adminReadConfirmed", "destroyConfirmed", "adminAbsentConfirmed"})
            report.put(indicator, false);
        report.put("lastOperation", "UPLOAD");
        Files.writeString(receipt, JSON.writerWithDefaultPrettyPrinter().writeValueAsString(report), StandardOpenOption.CREATE_NEW);
        ImageReference image = null;
        boolean verified = false, deleted = false;
        Exception failure = null;
        try {
            image = adapter.upload(png, "image/png");
            report.put("publicId", image.publicId());
            report.put("url", image.url());
            report.put("uploadConfirmed", true);
            report.put("lastOperation", "ADMIN_LOOKUP");
            write(receipt, report); // Preserve the only asset id this probe is allowed to delete.
            var metadata = resource(admin, image.publicId());
            verified = metadata != null && image.publicId().equals(metadata.path("public_id").asText())
                    && "png".equals(metadata.path("format").asText())
                    && metadata.path("width").asInt() == 1 && metadata.path("height").asInt() == 1;
            report.put("adminReadConfirmed", verified);
            if (!verified) throw new IllegalStateException("Uploaded fixture metadata could not be verified");
        } catch (Exception e) {
            var safe = CloudinaryIntegrationException.from(image == null ? UPLOAD : ADMIN_LOOKUP,
                    image == null ? null : image.publicId(), e);
            failure = safe;
            report.put("failureType", safe.getClass().getSimpleName());
            report.put("failureDiagnostic", safe.diagnostic());
            report.put("cleanupDiagnostics", Arrays.stream(safe.getSuppressed())
                    .filter(CloudinaryIntegrationException.class::isInstance)
                    .map(CloudinaryIntegrationException.class::cast).map(CloudinaryIntegrationException::diagnostic).toList());
            if (safe.diagnostic().publicId() != null) report.put("attemptedPublicId", safe.diagnostic().publicId());
            report.put("destroyConfirmed", safe.compensatingDeletionConfirmed());
        } finally {
            if (image != null) {
                try {
                    // Fresh asset returned by this upload only. Never accept an arbitrary deletion id.
                    if (!image.publicId().matches("foodly/business/[a-f0-9-]{36}"))
                        throw new IllegalStateException("Unexpected managed asset id");
                    report.put("lastOperation", "DESTROY");
                    adapter.delete(image.publicId());
                    report.put("destroyConfirmed", true);
                    report.put("lastOperation", "ADMIN_LOOKUP");
                    deleted = resource(admin, image.publicId()) == null;
                    report.put("adminAbsentConfirmed", deleted);
                    if (!deleted) throw new IllegalStateException("Asset still exists after destroy");
                } catch (Exception cleanupFailure) {
                    var safe = CloudinaryIntegrationException.from(
                            "DESTROY".equals(report.get("lastOperation")) ? DESTROY : ADMIN_LOOKUP, image.publicId(), cleanupFailure);
                    report.put("cleanupFailureType", safe.getClass().getSimpleName());
                    report.put("cleanupFailureDiagnostic", safe.diagnostic());
                    if (failure == null) failure = safe;
                }
            }
            report.put("status", failure == null && verified && deleted ? "PASSED" : "FAILED_OR_INCOMPLETE");
            report.put("finishedAt", Instant.now().toString());
            write(receipt, report);
            System.out.println("Private verification receipt: " + receipt);
        }
        if (failure != null) throw failure;
        if (!verified || !deleted) throw new IllegalStateException("Verification incomplete");
        System.out.println("PASSED: real PNG upload, authenticated resource lookup, destroy acknowledgement and resource-not-found confirmation.");
    }

    /** Only HTTP 404 means absent; 401/403/429/timeouts must fail verification. */
    private static JsonNode resource(RestClient admin, String publicId) {
        try {
            var response = admin.get().uri(publicId).retrieve().body(JsonNode.class);
            if (response == null) throw new IllegalStateException("Empty resource lookup response");
            return response;
        }
        catch (RestClientResponseException e) {
            if (e.getStatusCode().value() == 404) return null;
            throw CloudinaryIntegrationException.from(ADMIN_LOOKUP, publicId, e);
        } catch (org.springframework.web.client.RestClientException e) {
            throw CloudinaryIntegrationException.from(ADMIN_LOOKUP, publicId, e);
        }
    }

    private static byte[] png() throws Exception {
        var pixel = new BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB);
        pixel.setRGB(0, 0, 0x19794d);
        var bytes = new ByteArrayOutputStream();
        if (!ImageIO.write(pixel, "png", bytes)) throw new IllegalStateException("PNG encoder unavailable");
        return bytes.toByteArray();
    }

    private static void write(Path path, Map<String, Object> report) throws Exception {
        Files.writeString(path, JSON.writerWithDefaultPrettyPrinter().writeValueAsString(report));
    }

    private static String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) throw new IllegalStateException("Missing configuration: " + name);
        return value;
    }
}
