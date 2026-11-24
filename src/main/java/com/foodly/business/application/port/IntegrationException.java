package com.foodly.business.application.port;
public class IntegrationException extends RuntimeException {
    public IntegrationException(String message) { super(message); }
    public IntegrationException(String message, Throwable cause) { super(message, cause); }
}
