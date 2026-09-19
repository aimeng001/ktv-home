package com.homektv.library;

/** Raised when a scan worker no longer owns its persistent lease. */
public class LeaseLostException extends RuntimeException {
    public LeaseLostException(String message) {
        super(message);
    }

    public LeaseLostException(String message, Throwable cause) {
        super(message, cause);
    }
}

