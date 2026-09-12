package com.homektv.library;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class MediaProcessRunnerTest {

    @Test
    void waitsForTheChildAfterTheCallerIsInterrupted() throws Exception {
        InterruptibleProcess process = new InterruptibleProcess();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread runner = new Thread(() -> {
            try {
                MediaProcessRunner.run(process, Duration.ofMinutes(1));
            } catch (Throwable exception) {
                failure.set(exception);
            }
        });
        runner.start();

        assertThat(process.waitStarted.await(2, TimeUnit.SECONDS)).isTrue();
        runner.interrupt();
        runner.join(2_000L);

        assertThat(failure.get()).isInstanceOf(InterruptedException.class);
        assertThat(process.destroyed.get()).isTrue();
        assertThat(process.waitedAfterDestroy.get()).isTrue();
    }

    private static final class InterruptibleProcess extends Process {
        private final CountDownLatch waitStarted = new CountDownLatch(1);
        private final AtomicBoolean destroyed = new AtomicBoolean();
        private final AtomicBoolean waitedAfterDestroy = new AtomicBoolean();

        @Override public OutputStream getOutputStream() { return new ByteArrayOutputStream(); }
        @Override public InputStream getInputStream() { return new ByteArrayInputStream(new byte[0]); }
        @Override public InputStream getErrorStream() { return new ByteArrayInputStream(new byte[0]); }

        @Override public int waitFor() throws InterruptedException {
            while (!destroyed.get()) Thread.sleep(10L);
            return 143;
        }

        @Override public boolean waitFor(long timeout, TimeUnit unit) throws InterruptedException {
            waitStarted.countDown();
            if (destroyed.get()) {
                waitedAfterDestroy.set(true);
                return true;
            }
            new CountDownLatch(1).await(timeout, unit);
            if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
            return destroyed.get();
        }

        @Override public int exitValue() {
            if (!destroyed.get()) throw new IllegalThreadStateException("process is still running");
            return 143;
        }

        @Override public void destroy() { destroyForcibly(); }

        @Override public Process destroyForcibly() {
            destroyed.set(true);
            return this;
        }

        @Override public boolean isAlive() { return !destroyed.get(); }
    }
}
