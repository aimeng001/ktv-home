package com.homektv.library;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MediaProcessRunnerTest {

    @Test
    void convertsPositiveSecondsAndRejectsNonPositiveTimeouts() throws Exception {
        assertThat(MediaProcessRunner.fromSeconds(3)).isEqualTo(Duration.ofSeconds(3));
        assertThatThrownBy(() -> MediaProcessRunner.fromSeconds(0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> MediaProcessRunner.fromSeconds(-1))
                .isInstanceOf(IllegalArgumentException.class);

        var process = new CompletedProcess(InputStream.nullInputStream(), 0);
        assertThatThrownBy(() -> MediaProcessRunner.run(process, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> MediaProcessRunner.run(process, Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> MediaProcessRunner.run(process, Duration.ofSeconds(-1)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void returnsExitCodeAndCapturedOutputWhenChildCompletes() throws Exception {
        var process = new CompletedProcess(new ByteArrayInputStream("probe ok".getBytes(StandardCharsets.UTF_8)), 7);

        var result = MediaProcessRunner.run(process, Duration.ofSeconds(1));

        assertThat(result.exitCode()).isEqualTo(7);
        assertThat(result.output()).isEqualTo("probe ok");
        assertThat(result.timedOut()).isFalse();
        assertThat(process.forcedDestroyCount.get()).isZero();
    }

    @Test
    void forceKillsAgainIfChildRemainsAliveAfterTimeoutGracePeriod() throws Exception {
        var process = new SlowTerminationProcess();

        var result = MediaProcessRunner.run(process, Duration.ofMillis(1));

        assertThat(result.exitCode()).isEqualTo(-1);
        assertThat(result.output()).isEqualTo("partial output");
        assertThat(result.timedOut()).isTrue();
        assertThat(process.forcedDestroyCount.get()).isEqualTo(2);
        assertThat(process.isAlive()).isFalse();
    }

    @Test
    void propagatesIOExceptionFromOutputReader() {
        var process = new CompletedProcess(new InputStream() {
            @Override public int read() throws IOException { throw new IOException("stream failed"); }
        }, 0);

        assertThatThrownBy(() -> MediaProcessRunner.run(process, Duration.ofSeconds(1)))
                .isInstanceOf(IOException.class)
                .hasMessage("stream failed");
    }

    @Test
    void wrapsUnexpectedOutputReaderFailureAsIOException() {
        var process = new CompletedProcess(new InputStream() {
            @Override public int read() { throw new IllegalStateException("reader failed"); }
        }, 0);

        assertThatThrownBy(() -> MediaProcessRunner.run(process, Duration.ofSeconds(1)))
                .isInstanceOf(IOException.class)
                .hasMessage("读取媒体进程输出失败")
                .hasCauseInstanceOf(IllegalStateException.class);
    }

    @Test
    void timesOutWhenOutputReaderCannotDrainAndCancelsIt() throws Exception {
        var input = new BlockingInputStream();
        var process = new CompletedProcess(input, 0);

        assertThatThrownBy(() -> MediaProcessRunner.run(process, Duration.ofSeconds(1)))
                .isInstanceOf(IOException.class)
                .hasMessage("读取媒体进程输出超时");
        assertThat(input.started.await(1, TimeUnit.SECONDS)).isTrue();
        try {
            assertThat(input.interrupted.await(1, TimeUnit.SECONDS)).isTrue();
        } finally {
            input.release.countDown();
        }
    }

    @Test
    void preservesInterruptionWhileWaitingForTimedOutChildToExit() throws Exception {
        var input = new BlockingInputStream();
        var process = new InterruptedDrainProcess(input);

        assertThatThrownBy(() -> MediaProcessRunner.run(process, Duration.ofMillis(1)))
                .isInstanceOf(InterruptedException.class);
        try {
            assertThat(input.interrupted.await(1, TimeUnit.SECONDS)).isTrue();
        } finally {
            input.release.countDown();
        }
        assertThat(process.waitCalls.get()).isEqualTo(3);
        assertThat(process.forcedDestroyCount.get()).isEqualTo(2);
    }

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

    private static class CompletedProcess extends Process {
        private final InputStream input;
        private final int exitCode;
        private final AtomicInteger forcedDestroyCount = new AtomicInteger();

        private CompletedProcess(InputStream input, int exitCode) {
            this.input = input;
            this.exitCode = exitCode;
        }

        @Override public OutputStream getOutputStream() { return new ByteArrayOutputStream(); }
        @Override public InputStream getInputStream() { return input; }
        @Override public InputStream getErrorStream() { return InputStream.nullInputStream(); }
        @Override public int waitFor() { return exitCode; }
        @Override public boolean waitFor(long timeout, TimeUnit unit) { return true; }
        @Override public int exitValue() { return exitCode; }
        @Override public void destroy() { destroyForcibly(); }
        @Override public Process destroyForcibly() {
            forcedDestroyCount.incrementAndGet();
            return this;
        }
        @Override public boolean isAlive() { return false; }
    }

    private static final class SlowTerminationProcess extends Process {
        private final AtomicInteger forcedDestroyCount = new AtomicInteger();
        private final AtomicBoolean alive = new AtomicBoolean(true);

        @Override public OutputStream getOutputStream() { return new ByteArrayOutputStream(); }
        @Override public InputStream getInputStream() {
            return new ByteArrayInputStream("partial output".getBytes(StandardCharsets.UTF_8));
        }
        @Override public InputStream getErrorStream() { return InputStream.nullInputStream(); }
        @Override public int waitFor() { return -1; }
        @Override public boolean waitFor(long timeout, TimeUnit unit) { return false; }
        @Override public int exitValue() { throw new IllegalThreadStateException("process is still alive"); }
        @Override public void destroy() { destroyForcibly(); }
        @Override public Process destroyForcibly() {
            if (forcedDestroyCount.incrementAndGet() >= 2) alive.set(false);
            return this;
        }
        @Override public boolean isAlive() { return alive.get(); }
    }

    private static final class InterruptedDrainProcess extends Process {
        private final BlockingInputStream input;
        private final AtomicInteger waitCalls = new AtomicInteger();
        private final AtomicInteger forcedDestroyCount = new AtomicInteger();

        private InterruptedDrainProcess(BlockingInputStream input) {
            this.input = input;
        }

        @Override public OutputStream getOutputStream() { return new ByteArrayOutputStream(); }
        @Override public InputStream getInputStream() { return input; }
        @Override public InputStream getErrorStream() { return InputStream.nullInputStream(); }
        @Override public int waitFor() { return -1; }
        @Override public boolean waitFor(long timeout, TimeUnit unit) throws InterruptedException {
            return switch (waitCalls.incrementAndGet()) {
                case 1 -> {
                    if (!input.started.await(1, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("output reader did not start");
                    }
                    yield false;
                }
                case 2 -> throw new InterruptedException("child exit wait interrupted");
                default -> true;
            };
        }
        @Override public int exitValue() { return -1; }
        @Override public void destroy() { destroyForcibly(); }
        @Override public Process destroyForcibly() {
            forcedDestroyCount.incrementAndGet();
            return this;
        }
        @Override public boolean isAlive() { return false; }
    }

    private static final class BlockingInputStream extends InputStream {
        private final CountDownLatch started = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);
        private final CountDownLatch interrupted = new CountDownLatch(1);

        @Override public int read() throws IOException {
            byte[] single = new byte[1];
            int count = read(single, 0, 1);
            return count < 0 ? -1 : single[0] & 0xff;
        }

        @Override public int read(byte[] buffer, int offset, int length) throws IOException {
            started.countDown();
            try {
                release.await();
                return -1;
            } catch (InterruptedException failure) {
                interrupted.countDown();
                Thread.currentThread().interrupt();
                throw new IOException("reader interrupted", failure);
            }
        }
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
