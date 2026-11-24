package com.foodly.business.interfaces.rest;

import com.foodly.business.application.port.IntegrationException;
import com.foodly.business.domain.model.DomainException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import org.springframework.dao.*;
import org.springframework.http.*;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.BindException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.method.annotation.*;
import org.springframework.web.multipart.*;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.slf4j.LoggerFactory;
import java.time.Instant;
import java.util.*;

@RestControllerAdvice
public class HttpErrorHandler {
    @ExceptionHandler(DomainException.class)
    ResponseEntity<ApiError> domain(DomainException e, HttpServletRequest r) {
        var status = switch (e.kind()) { case INVALID -> HttpStatus.BAD_REQUEST; case NOT_FOUND -> HttpStatus.NOT_FOUND;
            case CONFLICT -> HttpStatus.CONFLICT; case FORBIDDEN -> HttpStatus.FORBIDDEN; };
        return response(status, e.getMessage(), r, Map.of());
    }
    @ExceptionHandler(BindException.class)
    ResponseEntity<ApiError> validation(BindException e, HttpServletRequest r) {
        Map<String, String> fields = new TreeMap<>();
        e.getBindingResult().getFieldErrors().forEach(f -> fields.putIfAbsent(f.getField(), f.getDefaultMessage()));
        return response(HttpStatus.BAD_REQUEST, "Invalid request", r, fields);
    }
    @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class,
            ConstraintViolationException.class, HandlerMethodValidationException.class,
            org.springframework.web.bind.MissingServletRequestParameterException.class,
            org.springframework.web.multipart.support.MissingServletRequestPartException.class})
    ResponseEntity<ApiError> badRequest(Exception e, HttpServletRequest r) { return response(HttpStatus.BAD_REQUEST, "Malformed or missing request data", r, Map.of()); }
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    ResponseEntity<ApiError> tooLarge(Exception e, HttpServletRequest r) { return response(HttpStatus.PAYLOAD_TOO_LARGE, "Image exceeds upload limit", r, Map.of()); }
    @ExceptionHandler({DuplicateKeyException.class, OptimisticLockingFailureException.class})
    ResponseEntity<ApiError> conflict(Exception e, HttpServletRequest r) { return response(HttpStatus.CONFLICT, "Owner/menu already exists or resource was concurrently modified; reload", r, Map.of()); }
    @ExceptionHandler({IntegrationException.class, DataAccessException.class, org.springframework.transaction.TransactionException.class})
    ResponseEntity<ApiError> unavailable(Exception e, HttpServletRequest r) {
        LoggerFactory.getLogger(getClass()).warn("Dependency failure: {}", e.getClass().getSimpleName());
        return response(HttpStatus.SERVICE_UNAVAILABLE, "A required dependency is unavailable", r, Map.of());
    }
    @ExceptionHandler(NoResourceFoundException.class)
    ResponseEntity<ApiError> notFound(Exception e, HttpServletRequest r) { return response(HttpStatus.NOT_FOUND, "Route not found", r, Map.of()); }
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    ResponseEntity<ApiError> method(Exception e, HttpServletRequest r) { return response(HttpStatus.METHOD_NOT_ALLOWED, "Method not supported", r, Map.of()); }
    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    ResponseEntity<ApiError> media(Exception e, HttpServletRequest r) { return response(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "Unsupported content type", r, Map.of()); }
    @ExceptionHandler(Exception.class)
    ResponseEntity<ApiError> unexpected(Exception e, HttpServletRequest r) {
        LoggerFactory.getLogger(getClass()).error("Unhandled request failure: {}", e.getClass().getSimpleName());
        return response(HttpStatus.INTERNAL_SERVER_ERROR, "Unexpected server error", r, Map.of());
    }
    private ResponseEntity<ApiError> response(HttpStatus status, String message, HttpServletRequest r, Map<String, String> fields) {
        return ResponseEntity.status(status).body(new ApiError(Instant.now(), status.value(), status.getReasonPhrase(), message, r.getRequestURI(), fields));
    }
}
