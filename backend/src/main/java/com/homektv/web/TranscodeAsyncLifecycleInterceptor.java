package com.homektv.web;

import jakarta.servlet.AsyncEvent;
import jakarta.servlet.AsyncListener;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.servlet.AsyncHandlerInterceptor;

import java.io.IOException;

/** Releases reserved media resources if servlet async processing ends before the body can finish. */
final class TranscodeAsyncLifecycleInterceptor implements AsyncHandlerInterceptor {
    static final String SESSION_ATTRIBUTE = TranscodeAsyncLifecycleInterceptor.class.getName() + ".session";

    @Override
    public void afterConcurrentHandlingStarted(
            HttpServletRequest request,
            HttpServletResponse response,
            Object handler) {
        LiveTranscodeSession session = session(request);
        MediaStreamLease mediaStreamLease = mediaStreamLease(request);
        if (session == null && mediaStreamLease == null) return;
        try {
            request.getAsyncContext().addListener(new AsyncListener() {
                @Override
                public void onComplete(AsyncEvent event) {
                    closeAsyncResources(session, mediaStreamLease);
                }

                @Override
                public void onTimeout(AsyncEvent event) {
                    closeAsyncResources(session, mediaStreamLease);
                }

                @Override
                public void onError(AsyncEvent event) {
                    closeAsyncResources(session, mediaStreamLease);
                }

                @Override
                public void onStartAsync(AsyncEvent event) throws IOException {
                    event.getAsyncContext().addListener(this);
                }
            });
        } catch (IllegalStateException ignored) {
            // If async already completed, close the same idempotent resource owners.
            closeAsyncResources(session, mediaStreamLease);
        }
    }

    @Override
    public void afterCompletion(
            HttpServletRequest request,
            HttpServletResponse response,
            Object handler,
            Exception ex) {
        if (!request.isAsyncStarted()) {
            LiveTranscodeSession session = session(request);
            if (session != null) session.close();
            MediaStreamLease mediaStreamLease = mediaStreamLease(request);
            if (mediaStreamLease != null) mediaStreamLease.closeBeforeBodyIfReserved();
        }
    }

    private LiveTranscodeSession session(HttpServletRequest request) {
        Object value = request.getAttribute(SESSION_ATTRIBUTE);
        return value instanceof LiveTranscodeSession session ? session : null;
    }

    private MediaStreamLease mediaStreamLease(HttpServletRequest request) {
        Object value = request.getAttribute(MediaStreamLease.REQUEST_ATTRIBUTE);
        return value instanceof MediaStreamLease lease ? lease : null;
    }

    private void closeAsyncResources(LiveTranscodeSession session, MediaStreamLease mediaStreamLease) {
        if (session != null) session.close();
        if (mediaStreamLease != null) mediaStreamLease.closeBeforeBodyIfReserved();
    }
}
