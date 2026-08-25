package org.dsi.dpn.common.exception;

/**
 * Exception thrown for SSL-related errors in the Federator.
 */
public class FederatorSslException extends RuntimeException {
    public FederatorSslException(String message) {
        super(message);
    }

    public FederatorSslException(String message, Throwable cause) {
        super(message, cause);
    }
}
