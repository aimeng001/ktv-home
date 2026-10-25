package com.homektv.web;

import com.homektv.resource.ResourceBudget;
import jakarta.servlet.AsyncContext;
import jakarta.servlet.AsyncEvent;
import jakarta.servlet.AsyncListener;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TranscodeAsyncLifecycleInterceptorTest {
    @Test
    void synchronousCompletionReleasesAnUnstartedMediaStreamPermit() throws Exception {
        ResourceBudget budget = mediaStreamBudget();
        MediaStreamLease lease = reserveMediaStream(budget);
        MockHttpServletRequest request = new MockHttpServletRequest();
        lease.attachTo(request);

        new TranscodeAsyncLifecycleInterceptor().afterCompletion(
                request, new MockHttpServletResponse(), new Object(), null);

        assertThat(budget.activeMediaStreamCount()).isZero();
        AtomicBoolean bodyRan = new AtomicBoolean();
        lease.wrap(output -> bodyRan.set(true)).writeTo(new ByteArrayOutputStream());
        assertThat(bodyRan).isFalse();
    }

    @Test
    void asyncTimeoutKeepsPermitUntilAnAlreadyStartedStreamBodyStops() throws Exception {
        ResourceBudget budget = mediaStreamBudget();
        MediaStreamLease lease = reserveMediaStream(budget);
        AsyncContext asyncContext = mock(AsyncContext.class);
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getAttribute(MediaStreamLease.REQUEST_ATTRIBUTE)).thenReturn(lease);
        when(request.getAsyncContext()).thenReturn(asyncContext);
        ArgumentCaptor<AsyncListener> listenerCaptor = ArgumentCaptor.forClass(AsyncListener.class);
        TranscodeAsyncLifecycleInterceptor interceptor = new TranscodeAsyncLifecycleInterceptor();
        interceptor.afterConcurrentHandlingStarted(request, new MockHttpServletResponse(), new Object());
        verify(asyncContext).addListener(listenerCaptor.capture());

        CountDownLatch bodyStarted = new CountDownLatch(1);
        CountDownLatch releaseBody = new CountDownLatch(1);
        ExecutorService worker = Executors.newSingleThreadExecutor();
        try {
            var body = worker.submit(() -> {
                try {
                    lease.wrap(output -> {
                        bodyStarted.countDown();
                        try {
                            if (!releaseBody.await(2, TimeUnit.SECONDS)) {
                                throw new IOException("test did not release the media stream body");
                            }
                        } catch (InterruptedException interrupted) {
                            Thread.currentThread().interrupt();
                            throw new IOException("media stream body interrupted", interrupted);
                        }
                    }).writeTo(new ByteArrayOutputStream());
                } catch (IOException failure) {
                    throw new RuntimeException(failure);
                }
            });
            assertThat(bodyStarted.await(1, TimeUnit.SECONDS)).isTrue();

            listenerCaptor.getValue().onTimeout(new AsyncEvent(asyncContext));

            assertThat(budget.activeMediaStreamCount())
                    .as("a timeout must not free capacity while the body still occupies its worker")
                    .isEqualTo(1);
            releaseBody.countDown();
            body.get(2, TimeUnit.SECONDS);
            assertThat(budget.activeMediaStreamCount()).isZero();
        } finally {
            releaseBody.countDown();
            worker.shutdownNow();
        }
    }

    @Test
    void asyncTimeoutBeforeBodyStartsReleasesReservedSlot() throws Exception {
        ResourceBudget budget = budget();
        LiveTranscodeSession session = reserve(budget, builder -> {
            throw new AssertionError("a timed out response must not start FFmpeg");
        });
        AsyncContext asyncContext = mock(AsyncContext.class);
        HttpServletRequest request = requestWithSession(session, asyncContext);
        ArgumentCaptor<AsyncListener> listenerCaptor = ArgumentCaptor.forClass(AsyncListener.class);

        new TranscodeAsyncLifecycleInterceptor().afterConcurrentHandlingStarted(
                request, mock(HttpServletResponse.class), new Object());
        verify(asyncContext).addListener(listenerCaptor.capture());
        listenerCaptor.getValue().onTimeout(new AsyncEvent(asyncContext));

        assertThat(budget.activeLiveTranscodeCount()).isZero();
    }

    @Test
    void synchronousCompletionClosesSessionButAsyncCompletionLeavesItsOwnerActive() throws Exception {
        ResourceBudget syncBudget = budget();
        LiveTranscodeSession syncSession = reserve(syncBudget, builder -> {
            throw new AssertionError("completion without body must not start FFmpeg");
        });
        HttpServletRequest syncRequest = requestWithSession(syncSession, mock(AsyncContext.class));
        when(syncRequest.isAsyncStarted()).thenReturn(false);

        TranscodeAsyncLifecycleInterceptor interceptor = new TranscodeAsyncLifecycleInterceptor();
        interceptor.afterCompletion(syncRequest, mock(HttpServletResponse.class), new Object(), null);

        assertThat(syncBudget.activeLiveTranscodeCount()).isZero();

        ResourceBudget asyncBudget = budget();
        LiveTranscodeSession asyncSession = reserve(asyncBudget, builder -> {
            throw new AssertionError("async completion hook must leave the body owner in control");
        });
        HttpServletRequest asyncRequest = requestWithSession(asyncSession, mock(AsyncContext.class));
        when(asyncRequest.isAsyncStarted()).thenReturn(true);

        interceptor.afterCompletion(asyncRequest, mock(HttpServletResponse.class), new Object(), null);

        assertThat(asyncBudget.activeLiveTranscodeCount()).isEqualTo(1);
        asyncSession.close();
        assertThat(asyncBudget.activeLiveTranscodeCount()).isZero();
    }

    @Test
    void asyncContextAlreadyCompletedClosesReservedSessionAndMissingSessionIsIgnored() throws Exception {
        ResourceBudget budget = budget();
        LiveTranscodeSession session = reserve(budget, builder -> {
            throw new AssertionError("already-completed async context must not start FFmpeg");
        });
        HttpServletRequest request = requestWithSession(session, mock(AsyncContext.class));
        when(request.getAsyncContext()).thenThrow(new IllegalStateException("async request already completed"));

        new TranscodeAsyncLifecycleInterceptor().afterConcurrentHandlingStarted(
                request, mock(HttpServletResponse.class), new Object());

        assertThat(budget.activeLiveTranscodeCount()).isZero();

        HttpServletRequest requestWithoutSession = mock(HttpServletRequest.class);
        when(requestWithoutSession.getAttribute(TranscodeAsyncLifecycleInterceptor.SESSION_ATTRIBUTE))
                .thenReturn("untrusted request attribute");
        when(requestWithoutSession.isAsyncStarted()).thenReturn(false);
        new TranscodeAsyncLifecycleInterceptor().afterConcurrentHandlingStarted(
                requestWithoutSession, mock(HttpServletResponse.class), new Object());
        new TranscodeAsyncLifecycleInterceptor().afterCompletion(
                requestWithoutSession, mock(HttpServletResponse.class), new Object(), null);
        verify(requestWithoutSession, org.mockito.Mockito.never()).getAsyncContext();
    }

    @Test
    void asyncRestartReattachesListenerAndCompletionReleasesSession() throws Exception {
        ResourceBudget budget = budget();
        LiveTranscodeSession session = reserve(budget, builder -> {
            throw new AssertionError("async restart must not start FFmpeg");
        });
        AsyncContext firstContext = mock(AsyncContext.class);
        AsyncContext restartedContext = mock(AsyncContext.class);
        HttpServletRequest request = requestWithSession(session, firstContext);
        ArgumentCaptor<AsyncListener> listenerCaptor = ArgumentCaptor.forClass(AsyncListener.class);

        new TranscodeAsyncLifecycleInterceptor().afterConcurrentHandlingStarted(
                request, mock(HttpServletResponse.class), new Object());
        verify(firstContext).addListener(listenerCaptor.capture());
        AsyncListener listener = listenerCaptor.getValue();

        listener.onStartAsync(new AsyncEvent(restartedContext));
        verify(restartedContext).addListener(listener);
        listener.onComplete(new AsyncEvent(restartedContext));

        assertThat(budget.activeLiveTranscodeCount()).isZero();
    }

    @Test
    void asyncErrorAfterBodyStartsKillsProcessAndReleasesItsSlot() throws Exception {
        ResourceBudget budget = budget();
        AtomicBoolean processAlive = new AtomicBoolean(true);
        CountDownLatch processStarted = new CountDownLatch(1);
        CountDownLatch stdoutReadStarted = new CountDownLatch(1);
        CountDownLatch processKilled = new CountDownLatch(1);
        Process process = mock(Process.class);
        when(process.getInputStream()).thenReturn(new InputStream() {
            @Override
            public int read() throws IOException {
                return read(new byte[1], 0, 1) == -1 ? -1 : 0;
            }

            @Override
            public int read(byte[] buffer, int offset, int length) throws IOException {
                stdoutReadStarted.countDown();
                try {
                    if (!processKilled.await(5, TimeUnit.SECONDS)) {
                        throw new IOException("fixture process was not cancelled");
                    }
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IOException("fixture read interrupted", interrupted);
                }
                return -1;
            }
        });
        when(process.isAlive()).thenAnswer(invocation -> processAlive.get());
        when(process.waitFor(5, TimeUnit.SECONDS)).thenAnswer(invocation -> !processAlive.get());
        when(process.exitValue()).thenReturn(137);
        doAnswer(invocation -> {
            processAlive.set(false);
            processKilled.countDown();
            return process;
        }).when(process).destroyForcibly();
        LiveTranscodeSession session = reserve(budget, builder -> {
            processStarted.countDown();
            return process;
        });
        AsyncContext asyncContext = mock(AsyncContext.class);
        HttpServletRequest request = requestWithSession(session, asyncContext);
        ArgumentCaptor<AsyncListener> listenerCaptor = ArgumentCaptor.forClass(AsyncListener.class);
        TranscodeAsyncLifecycleInterceptor interceptor = new TranscodeAsyncLifecycleInterceptor();
        interceptor.afterConcurrentHandlingStarted(request, new MockHttpServletResponse(), new Object());
        verify(asyncContext).addListener(listenerCaptor.capture());
        ExecutorService worker = Executors.newSingleThreadExecutor();

        try {
            var body = worker.submit(() -> {
                try {
                    session.streamTo(new ByteArrayOutputStream());
                } catch (IOException expectedCancelledResponse) {
                    // Async cancellation may race the body's normal process-exit check.
                }
            });
            assertThat(processStarted.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(stdoutReadStarted.await(2, TimeUnit.SECONDS)).isTrue();

            listenerCaptor.getValue().onError(new AsyncEvent(asyncContext));
            body.get(2, TimeUnit.SECONDS);

            assertThat(processAlive.get()).isFalse();
            assertThat(budget.activeLiveTranscodeCount()).isZero();
            listenerCaptor.getValue().onComplete(new AsyncEvent(asyncContext));
            session.close();
            assertThat(budget.activeLiveTranscodeCount()).isZero();
        } finally {
            session.close();
            worker.shutdownNow();
        }
    }

    private static ResourceBudget budget() {
        return new ResourceBudget(1, 1, 1, () -> false);
    }

    private static ResourceBudget mediaStreamBudget() {
        return new ResourceBudget(1, 1, 1, 1, 1, () -> false);
    }

    private static MediaStreamLease reserveMediaStream(ResourceBudget budget) {
        return new MediaStreamLease(budget.tryAcquireMediaStream().orElseThrow());
    }

    private LiveTranscodeSession reserve(ResourceBudget budget, StreamController.ProcessLauncher launcher) {
        return LiveTranscodeSession.tryReserve(budget, launcher, List.of("ffmpeg", "pipe:1"), 42L, null)
                .orElseThrow();
    }

    private HttpServletRequest requestWithSession(LiveTranscodeSession session, AsyncContext asyncContext) {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getAttribute(TranscodeAsyncLifecycleInterceptor.SESSION_ATTRIBUTE)).thenReturn(session);
        when(request.getAsyncContext()).thenReturn(asyncContext);
        return request;
    }
}
