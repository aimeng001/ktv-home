package com.homektv.common;

/**
 * 业务异常，携带错误码（详设§11.2）。
 * 业务码：SONG_IN_QUEUE / FILE_MISSING / TV_OFFLINE / INVALID_ACTION
 *
 * Business exception carrying an error code (detailed design §11.2).
 * Business codes: SONG_IN_QUEUE / FILE_MISSING / TV_OFFLINE / INVALID_ACTION
 */
public class ApiException extends RuntimeException {

    private final String code;
    private final Object data;

    /**
     * 构造业务异常，仅携带错误码和消息。
     *
     * Constructs a business exception with error code and message only.
     * @param code    错误码 / error code
     * @param message 错误消息 / error message
     */
    public ApiException(String code, String message) {
        this(code, message, null);
    }

    /**
     * 构造业务异常，携带错误码、消息和附加数据。
     *
     * Constructs a business exception with error code, message, and additional data.
     * @param code    错误码 / error code
     * @param message 错误消息 / error message
     * @param data    附加数据 / additional data
     */
    public ApiException(String code, String message, Object data) {
        super(message);
        this.code = code;
        this.data = data;
    }

    public String getCode() { return code; }
    public Object getData() { return data; }
}
