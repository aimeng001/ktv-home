package com.homektv.web;

import jakarta.servlet.AsyncEvent;
import jakarta.servlet.AsyncListener;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.servlet.AsyncHandlerInterceptor;

import java.io.IOException;

/** Releases a reserved transcode slot if servlet async processing ends before streaming begins. */
final class TranscodeAsyncLifecycleInterceptor implements AsyncHandlerInterceptor {
    static final String LEASE_ATTRIBUTE = TranscodeAsyncLifecycleInterceptor.class.getName() + ".lease";

    @Override
    public void afterConcurrentHandlingStarted(
            HttpServletRequest request,
            HttpServletResponse response,
            Object handler) {
        TranscodeLease lease = lease(request);
        if (lease == null) return;
        try {
            request.getAsyncContext().addListener(new AsyncListener() {
                @Override
                public void onComplete(AsyncEvent event) {
                    lease.close();
                }

                @Override
                public void onTimeout(AsyncEvent event) {
                    lease.releaseIfNotStarted();
                }

                @Override
                public void onError(AsyncEvent event) {
                    lease.releaseIfNotStarted();
                }

                @Override
                public void onStartAsync(AsyncEvent event) throws IOException {
                    event.getAsyncContext().addListener(this);
                }
            });
        } catch (IllegalStateException ignored) {
            // If async already completed, the body or completion callback owns release.
            lease.releaseIfNotStarted();
        }
    }

    @Override
    public void afterCompletion(
            HttpServletRequest request,
            HttpServletResponse response,
            Object handler,
            Exception ex) {
        if (!request.isAsyncStarted()) {
            TranscodeLease lease = lease(request);
            if (lease != null) lease.releaseIfNotStarted();
        }
    }

    private TranscodeLease lease(HttpServletRequest request) {
        Object value = request.getAttribute(LEASE_ATTRIBUTE);
        return value instanceof TranscodeLease lease ? lease : null;
    }
}
