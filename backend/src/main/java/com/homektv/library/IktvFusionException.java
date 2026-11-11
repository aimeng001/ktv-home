package com.homektv.library;

/** A safe, stable error code for administrator-operated fusion controls. */
public final class IktvFusionException extends RuntimeException {
    private final String code;

    public IktvFusionException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String getCode() {
        return code;
    }
}
