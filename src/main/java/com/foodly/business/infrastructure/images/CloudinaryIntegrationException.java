package com.foodly.business.infrastructure.images;

import com.foodly.business.application.port.IntegrationException;
import org.springframework.http.converter.HttpMessageConversionException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import javax.net.ssl.SSLException;
import java.io.IOException;
import java.net.*;
import java.net.http.HttpTimeoutException;

/** Internal allowlisted metadata. Never retain provider bodies, headers, URLs or raw exceptions. */
public final class CloudinaryIntegrationException extends IntegrationException {
    public enum Operation { CONFIGURATION, UPLOAD, DESTROY, ADMIN_LOOKUP }
    public enum Reason {
        CONFIGURATION_MISSING, CONFIGURATION_INVALID, HTTP_ERROR, TIMEOUT, DNS, TLS,
        CONNECTION, IO, INVALID_RESPONSE, CLIENT_ERROR, LOCAL_ERROR
    }
    public record Diagnostic(Operation operation, Reason reason, Integer httpStatus, String publicId) { }

    private final Diagnostic diagnostic;
    private boolean compensatingDeletionConfirmed;

    private CloudinaryIntegrationException(String message, Operation operation, Reason reason, Integer httpStatus, String publicId) {
        super(message); // Keeping the original cause would also keep response bodies/headers and signed URLs.
        String managedId = publicId != null && publicId.matches("foodly/business/[a-f0-9-]{36}") ? publicId : null;
        diagnostic = new Diagnostic(operation, reason, httpStatus, managedId);
    }

    public Diagnostic diagnostic() { return diagnostic; }
    public boolean compensatingDeletionConfirmed() { return compensatingDeletionConfirmed; }
    void confirmCompensatingDeletion() { compensatingDeletionConfirmed = true; }

    public static CloudinaryIntegrationException configuration(boolean missing) {
        return new CloudinaryIntegrationException(missing ? "Cloudinary is not configured" : "Invalid Cloudinary configuration",
                Operation.CONFIGURATION, missing ? Reason.CONFIGURATION_MISSING : Reason.CONFIGURATION_INVALID, null, null);
    }

    public static CloudinaryIntegrationException invalidResponse(Operation operation, String publicId) {
        return new CloudinaryIntegrationException(operation == Operation.UPLOAD ? "Invalid Cloudinary upload response" : "Invalid Cloudinary response",
                operation, Reason.INVALID_RESPONSE, null, publicId);
    }

    public static CloudinaryIntegrationException from(Operation operation, String publicId, Throwable failure) {
        if (failure instanceof CloudinaryIntegrationException safe) return safe;
        Reason reason = failure instanceof RestClientException ? Reason.CLIENT_ERROR : Reason.LOCAL_ERROR;
        // Classify types only. Messages, HTTP headers and response bodies are intentionally discarded.
        Throwable current = failure;
        for (int depth = 0; current != null && depth < 16; depth++, current = current.getCause()) {
            if (current instanceof RestClientResponseException response)
                return new CloudinaryIntegrationException("Cloudinary request failed", operation, Reason.HTTP_ERROR,
                        response.getStatusCode().value(), publicId);
            if (current instanceof SSLException) { reason = Reason.TLS; break; }
            if (current instanceof UnknownHostException) { reason = Reason.DNS; break; }
            if (current instanceof SocketTimeoutException || current instanceof HttpTimeoutException) { reason = Reason.TIMEOUT; break; }
            if (current instanceof ConnectException) { reason = Reason.CONNECTION; break; }
            if (current instanceof HttpMessageConversionException) { reason = Reason.INVALID_RESPONSE; break; }
            if (current instanceof IOException) reason = Reason.IO;
        }
        return new CloudinaryIntegrationException("Cloudinary request failed", operation, reason, null, publicId);
    }
}
