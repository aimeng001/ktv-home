package com.homektv.web;

import jakarta.servlet.AsyncContext;
import jakarta.servlet.AsyncEvent;
import jakarta.servlet.AsyncListener;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.concurrent.Semaphore;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TranscodeAsyncLifecycleInterceptorTest {
    @Test
    void asyncTimeoutBeforeBodyStartsReleasesReservedSlot() throws Exception {
        Semaphore semaphore = new Semaphore(1);
        assertThat(semaphore.tryAcquire()).isTrue();
        TranscodeLease lease = new TranscodeLease(semaphore);
        AsyncContext asyncContext = mock(AsyncContext.class);
        HttpServletRequest request = requestWithLease(lease, asyncContext);
        ArgumentCaptor<AsyncListener> listenerCaptor = ArgumentCaptor.forClass(AsyncListener.class);
        when(request.getAsyncContext()).thenReturn(asyncContext);

        new TranscodeAsyncLifecycleInterceptor().afterConcurrentHandlingStarted(
                request, mock(HttpServletResponse.class), new Object());
        verify(asyncContext).addListener(listenerCaptor.capture());
        listenerCaptor.getValue().onTimeout(new AsyncEvent(asyncContext));

        assertThat(semaphore.availablePermits()).isEqualTo(1);
    }

    @Test
    void asyncErrorAfterBodyStartsKeepsSlotUntilBodyFinallyClosesLease() throws Exception {
        Semaphore semaphore = new Semaphore(1);
        assertThat(semaphore.tryAcquire()).isTrue();
        TranscodeLease lease = new TranscodeLease(semaphore);
        assertThat(lease.beginStreaming()).isTrue();
        AsyncContext asyncContext = mock(AsyncContext.class);
        HttpServletRequest request = requestWithLease(lease, asyncContext);
        ArgumentCaptor<AsyncListener> listenerCaptor = ArgumentCaptor.forClass(AsyncListener.class);

        new TranscodeAsyncLifecycleInterceptor().afterConcurrentHandlingStarted(
                request, new MockHttpServletResponse(), new Object());
        verify(asyncContext).addListener(listenerCaptor.capture());
        listenerCaptor.getValue().onError(new AsyncEvent(asyncContext));
        assertThat(semaphore.availablePermits()).isZero();

        lease.close();
        lease.close();
        assertThat(semaphore.availablePermits()).isEqualTo(1);
    }

    private HttpServletRequest requestWithLease(TranscodeLease lease, AsyncContext asyncContext) {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getAttribute(TranscodeAsyncLifecycleInterceptor.LEASE_ATTRIBUTE)).thenReturn(lease);
        when(request.getAsyncContext()).thenReturn(asyncContext);
        return request;
    }
}
