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
    static final String CLEANUP_ATTRIBUTE = TranscodeAsyncLifecycleInterceptor.class.getName() + ".cleanup";

    @Override
    public void afterConcurrentHandlingStarted(
            HttpServletRequest request,
            HttpServletResponse response,
            Object handler) {
        LiveTranscodeSession session = session(request);
        MediaStreamLease mediaStreamLease = mediaStreamLease(request);
        Runnable cleanup = cleanup(request);
        if (session == null && mediaStreamLease == null && cleanup == null) return;
        try {
            request.getAsyncContext().addListener(new AsyncListener() {
                @Override
                public void onComplete(AsyncEvent event) {
                    closeAsyncResources(session, mediaStreamLease, cleanup);
                }

                @Override
                public void onTimeout(AsyncEvent event) {
                    closeAsyncResources(session, mediaStreamLease, cleanup);
                }

                @Override
                public void onError(AsyncEvent event) {
                    closeAsyncResources(session, mediaStreamLease, cleanup);
                }

                @Override
                public void onStartAsync(AsyncEvent event) throws IOException {
                    event.getAsyncContext().addListener(this);
                }
            });
        } catch (IllegalStateException ignored) {
            // If async already completed, close the same idempotent resource owners.
            closeAsyncResources(session, mediaStreamLease, cleanup);
        }
    }

    @Override
    public void afterCompletion(
            HttpServletRequest request,
            HttpServletResponse response,
            Object handler,
            Exception ex) {
        if (!request.isAsyncStarted()) {
            closeAsyncResources(session(request), mediaStreamLease(request), cleanup(request));
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

    private Runnable cleanup(HttpServletRequest request) {
        Object value = request.getAttribute(CLEANUP_ATTRIBUTE);
        return value instanceof Runnable runnable ? runnable : null;
    }

    private void closeAsyncResources(LiveTranscodeSession session, MediaStreamLease mediaStreamLease, Runnable cleanup) {
        if (cleanup != null) cleanup.run();
        if (session != null) session.close();
        if (mediaStreamLease != null) mediaStreamLease.closeBeforeBodyIfReserved();
    }
}
