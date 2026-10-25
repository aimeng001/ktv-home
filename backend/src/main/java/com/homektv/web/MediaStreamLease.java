package com.homektv.web;

import com.homektv.resource.ResourceBudget;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.util.concurrent.atomic.AtomicInteger;

/** Owns a media-stream permit from HTTP admission until the response body actually stops. */
final class MediaStreamLease {
    static final String REQUEST_ATTRIBUTE = MediaStreamLease.class.getName() + ".lease";

    private static final int RESERVED = 0;
    private static final int BODY_RUNNING = 1;
    private static final int CLOSED = 2;

    private final ResourceBudget.Permit permit;
    private final AtomicInteger state = new AtomicInteger(RESERVED);

    MediaStreamLease(ResourceBudget.Permit permit) {
        this.permit = permit;
    }

    void attachTo(HttpServletRequest request) {
        if (request != null) request.setAttribute(REQUEST_ATTRIBUTE, this);
    }

    StreamingResponseBody wrap(StreamingResponseBody body) {
        return output -> {
            if (!state.compareAndSet(RESERVED, BODY_RUNNING)) return;
            try {
                body.writeTo(output);
            } finally {
                closeAfterBody();
            }
        };
    }

    /** Releases an admitted response only when its body has not started running. */
    void closeBeforeBodyIfReserved() {
        if (state.compareAndSet(RESERVED, CLOSED)) permit.close();
    }

    private void closeAfterBody() {
        if (state.compareAndSet(BODY_RUNNING, CLOSED)) permit.close();
    }
}
