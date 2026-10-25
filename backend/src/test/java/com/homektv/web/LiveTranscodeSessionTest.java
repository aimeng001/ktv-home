package com.homektv.web;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.homektv.library.ProcessOutputTail;
import com.homektv.resource.ResourceBudget;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LiveTranscodeSessionTest {

    @Test
    void admissionIsReservedBeforeStreamingAndClosedSessionNeverLaunches() throws Exception {
        ResourceBudget budget = budget(1);
        AtomicInteger launches = new AtomicInteger();
        LiveTranscodeSession session = reserve(budget, builder -> {
            launches.incrementAndGet();
            return new FakeProcess(new byte[0], 0);
        });

        assertThat(budget.activeLiveTranscodeCount()).isEqualTo(1);
        assertThat(LiveTranscodeSession.tryReserve(budget, builder -> new FakeProcess(new byte[0], 0),
                List.of("ffmpeg"), 2L, null)).isEmpty();

        session.close();
        session.streamTo(new ByteArrayOutputStream());

        assertThat(launches).hasValue(0);
        assertThat(budget.activeLiveTranscodeCount()).isZero();
    }

    @Test
    void streamsStdoutAndReleasesPermitAfterSuccessfulExit() throws Exception {
        byte[] media = {1, 2, 3, 4};
        FakeProcess process = new FakeProcess(media, 0);
        AtomicInteger launches = new AtomicInteger();
        ResourceBudget budget = budget(1);
        LiveTranscodeSession session = reserve(budget, builder -> {
            launches.incrementAndGet();
            return process;
        });
        ByteArrayOutputStream response = new ByteArrayOutputStream();

        session.streamTo(response);
        session.streamTo(new ByteArrayOutputStream());

        assertThat(response.toByteArray()).containsExactly(media);
        assertThat(launches).hasValue(1);
        assertThat(process.forceDestroyCalls).hasValue(0);
        assertThat(budget.activeLiveTranscodeCount()).isZero();
    }

    @Test
    void missingStderrStreamDoesNotBlockMediaOutputOrLeakThePermit() throws Exception {
        byte[] media = {8, 7, 6};
        FakeProcess process = new FakeProcess(media, 0, false, true);
        ResourceBudget budget = budget(1);
        LiveTranscodeSession session = reserve(budget, builder -> process);
        ByteArrayOutputStream response = new ByteArrayOutputStream();

        session.streamTo(response);

        assertThat(response.toByteArray()).containsExactly(media);
        assertThat(budget.activeLiveTranscodeCount()).isZero();
    }

    @Test
    void nullProcessFromLauncherIsReportedAndReleasesReservedCapacity() {
        ResourceBudget budget = budget(1);
        LiveTranscodeSession session = reserve(budget, builder -> null);

        assertThatThrownBy(() -> session.streamTo(new ByteArrayOutputStream()))
                .isInstanceOf(IOException.class)
                .hasMessage("Live transcode failed for fileId=1");

        assertThat(budget.activeLiveTranscodeCount()).isZero();
    }

    @Test
    void launchFailureReturnsAnIoErrorAndReleasesTheReservedPermit() {
        ResourceBudget budget = budget(1);
        LiveTranscodeSession session = reserve(budget, builder -> {
            throw new IOException("synthetic launch failure");
        });

        assertThatThrownBy(() -> session.streamTo(new ByteArrayOutputStream()))
                .isInstanceOf(IOException.class)
                .hasMessage("Live transcode failed for fileId=1");
        assertThat(budget.activeLiveTranscodeCount()).isZero();
    }

    @Test
    void nonzeroProcessExitFailsTheStreamAndClosesTheProcess() {
        FakeProcess process = new FakeProcess(new byte[]{9}, 23);
        ResourceBudget budget = budget(1);
        LiveTranscodeSession session = reserve(budget, builder -> process);

        assertThatThrownBy(() -> session.streamTo(new ByteArrayOutputStream()))
                .isInstanceOf(IOException.class)
                .hasMessage("Live transcode failed for fileId=1");
        assertThat(budget.activeLiveTranscodeCount()).isZero();
    }

    @Test
    void timeoutWhileWaitingForInitialProcessExitRetainsPermitUntilExitIsObserved() {
        FakeProcess process = new FakeProcess(new byte[]{4}, 23, true);
        ResourceBudget budget = budget(1);
        LiveTranscodeSession session = reserve(budget, builder -> process);

        assertThatThrownBy(() -> session.streamTo(new ByteArrayOutputStream()))
                .isInstanceOf(IOException.class)
                .hasMessage("Live transcode failed for fileId=1");

        assertThat(process.forceDestroyCalls).hasValue(1);
        assertThat(process.isAlive()).isTrue();
        assertThat(budget.activeLiveTranscodeCount()).isEqualTo(1);

        process.completeExternalExit();
        assertThat(budget.activeLiveTranscodeCount()).isZero();
    }

    @Test
    void redactsAndUtf8BoundsTheCapturedDiagnosticTail() throws Exception {
        byte[] media = {1, 2, 3};
        String diagnosticPrefix = "discard-this-prefix-";
        String diagnosticMarker = "final-ffmpeg-diagnostic-marker";
        String stderr = diagnosticPrefix + "x".repeat(70 * 1024)
                + diagnosticMarker + " api_key=x\n尾é💡";
        FakeProcess process = new FakeProcess(media, 23, false,
                new ByteArrayInputStream(stderr.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        ResourceBudget budget = budget(1);
        LiveTranscodeSession session = reserve(budget, builder -> process);
        Logger logger = (Logger) LoggerFactory.getLogger(LiveTranscodeSession.class);
        Level previousLevel = logger.getLevel();
        boolean previousAdditivity = logger.isAdditive();
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        logger.setLevel(Level.ERROR);
        logger.setAdditive(false);
        appender.start();
        logger.addAppender(appender);
        try {
            assertThatThrownBy(() -> session.streamTo(new ByteArrayOutputStream()))
                    .isInstanceOf(IOException.class);
        } finally {
            logger.detachAppender(appender);
            logger.setLevel(previousLevel);
            logger.setAdditive(previousAdditivity);
            appender.stop();
        }

        assertThat(appender.list).anySatisfy(event -> {
            String message = event.getFormattedMessage();
            assertThat(message).contains(diagnosticMarker, "尾é💡");
            assertThat(message).doesNotContain(diagnosticPrefix, "api_key=x");
            String tail = message.substring(message.indexOf("stderrTail=") + "stderrTail=".length());
            assertThat(tail.getBytes(java.nio.charset.StandardCharsets.UTF_8).length)
                    .isLessThanOrEqualTo(ProcessOutputTail.DEFAULT_MAX_BYTES);
        });
        assertThat(budget.activeLiveTranscodeCount()).isZero();
    }

    @Test
    void clientDisconnectForceKillsTheProcessAndReleasesCapacity() throws Exception {
        FakeProcess process = new FakeProcess(new byte[]{1, 2, 3}, 0);
        ResourceBudget budget = budget(1);
        LiveTranscodeSession session = reserve(budget, builder -> process);
        OutputStream disconnected = new OutputStream() {
            @Override
            public void write(int value) throws IOException {
                throw new IOException("client disconnected");
            }
        };

        assertThatCode(() -> session.streamTo(disconnected)).doesNotThrowAnyException();

        assertThat(process.forceDestroyCalls).hasValue(1);
        assertThat(budget.activeLiveTranscodeCount()).isZero();
    }

    @Test
    void retainsAdmissionPermitUntilUnresponsiveProcessExitIsObserved() {
        FakeProcess process = unresponsiveAfterSuccessfulWait();
        ResourceBudget budget = budget(1);
        LiveTranscodeSession session = reserve(budget, builder -> process);

        assertThatThrownBy(() -> session.streamTo(new ByteArrayOutputStream()))
                .isInstanceOf(IOException.class)
                .hasMessage("Live transcode failed for fileId=1");
        assertThat(process.forceDestroyCalls).hasValue(1);
        assertThat(process.isAlive()).isTrue();
        assertThat(budget.activeLiveTranscodeCount()).isEqualTo(1);

        process.completeExternalExit();

        assertThat(budget.activeLiveTranscodeCount()).isZero();
    }

    @Test
    void retainsPermitWhenUnresponsiveProcessExitObserverFails() {
        FakeProcess process = unresponsiveAfterSuccessfulWait();
        process.failExitObservation();
        ResourceBudget budget = budget(1);
        LiveTranscodeSession session = reserve(budget, builder -> process);

        assertThatThrownBy(() -> session.streamTo(new ByteArrayOutputStream()))
                .isInstanceOf(IOException.class)
                .hasMessage("Live transcode failed for fileId=1");

        assertThat(process.forceDestroyCalls).hasValue(1);
        assertThat(process.isAlive()).isTrue();
        assertThat(budget.activeLiveTranscodeCount()).isEqualTo(1);
    }

    @Test
    void retainsPermitIfProcessExitCannotBeObserved() {
        FakeProcess process = unresponsiveAfterSuccessfulWait();
        process.nullExitObserver = true;
        ResourceBudget budget = budget(1);
        LiveTranscodeSession session = reserve(budget, builder -> process);

        assertThatThrownBy(() -> session.streamTo(new ByteArrayOutputStream()))
                .isInstanceOf(IOException.class)
                .hasMessage("Live transcode failed for fileId=1");

        assertThat(process.forceDestroyCalls).hasValue(1);
        assertThat(process.isAlive()).isTrue();
        assertThat(budget.activeLiveTranscodeCount()).isEqualTo(1);
    }

    @Test
    void retainsPermitAfterTerminationWaitIsInterruptedAndReleasesItWhenExitArrives() {
        FakeProcess process = unresponsiveAfterSuccessfulWait();
        process.interruptTimedWait = true;
        ResourceBudget budget = budget(1);
        LiveTranscodeSession session = reserve(budget, builder -> process);

        try {
            assertThatThrownBy(() -> session.streamTo(new ByteArrayOutputStream()))
                    .isInstanceOf(IOException.class)
                    .hasMessage("Live transcode failed for fileId=1");
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
            assertThat(budget.activeLiveTranscodeCount()).isEqualTo(1);
            process.completeExternalExit();
            assertThat(budget.activeLiveTranscodeCount()).isZero();
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void retainsPermitAfterTerminationWaitThrowsAndReleasesItWhenExitArrives() {
        FakeProcess process = unresponsiveAfterSuccessfulWait();
        process.failTimedWait = true;
        ResourceBudget budget = budget(1);
        LiveTranscodeSession session = reserve(budget, builder -> process);

        assertThatThrownBy(() -> session.streamTo(new ByteArrayOutputStream()))
                .isInstanceOf(IOException.class)
                .hasMessage("Live transcode failed for fileId=1");

        assertThat(budget.activeLiveTranscodeCount()).isEqualTo(1);
        process.completeExternalExit();
        assertThat(budget.activeLiveTranscodeCount()).isZero();
    }

    @Test
    void releasesPermitWhenTheProcessExitsDuringTheTerminationWait() {
        FakeProcess process = new FakeProcess(new byte[]{5}, 23, false);
        process.finishFirstWaitWhileAlive = true;
        process.allowExitValueWhileAlive = true;
        process.retainAliveAfterForceDestroy = true;
        process.completeOnTerminationWait = true;
        ResourceBudget budget = budget(1);
        LiveTranscodeSession session = reserve(budget, builder -> process);

        assertThatThrownBy(() -> session.streamTo(new ByteArrayOutputStream()))
                .isInstanceOf(IOException.class);

        assertThat(process.forceDestroyCalls).hasValue(1);
        assertThat(process.isAlive()).isFalse();
        assertThat(budget.activeLiveTranscodeCount()).isZero();
    }

    @Test
    void releasesPermitWhenTheProcessExitsJustAfterATerminationWaitTimesOut() {
        FakeProcess process = new FakeProcess(new byte[]{5}, 23, false);
        process.finishFirstWaitWhileAlive = true;
        process.allowExitValueWhileAlive = true;
        process.retainAliveAfterForceDestroy = true;
        process.completeAfterFalseTerminationWait = true;
        ResourceBudget budget = budget(1);
        LiveTranscodeSession session = reserve(budget, builder -> process);

        assertThatThrownBy(() -> session.streamTo(new ByteArrayOutputStream()))
                .isInstanceOf(IOException.class);

        assertThat(process.forceDestroyCalls).hasValue(1);
        assertThat(process.isAlive()).isFalse();
        assertThat(budget.activeLiveTranscodeCount()).isZero();
    }

    @Test
    void releasesPermitIfExitObserverFailsAfterTheProcessHasStopped() {
        FakeProcess process = unresponsiveAfterSuccessfulWait();
        ResourceBudget budget = budget(1);
        LiveTranscodeSession session = reserve(budget, builder -> process);

        assertThatThrownBy(() -> session.streamTo(new ByteArrayOutputStream()))
                .isInstanceOf(IOException.class);
        assertThat(budget.activeLiveTranscodeCount()).isEqualTo(1);

        process.stopWithoutNotifyingObserver();
        process.failExitObservation();

        assertThat(budget.activeLiveTranscodeCount()).isZero();
    }

    @Test
    void closeDuringLaunchKillsTheLateProcessBeforeItCanStream() throws Exception {
        ResourceBudget budget = budget(1);
        CountDownLatch launchEntered = new CountDownLatch(1);
        CountDownLatch finishLaunch = new CountDownLatch(1);
        FakeProcess process = new FakeProcess(new byte[]{7}, 0);
        LiveTranscodeSession session = reserve(budget, builder -> {
            launchEntered.countDown();
            try {
                if (!finishLaunch.await(2, TimeUnit.SECONDS)) throw new IOException("test launch timed out");
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IOException("test launch interrupted", interrupted);
            }
            return process;
        });
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            var streaming = executor.submit(() -> {
                session.streamTo(new ByteArrayOutputStream());
                return null;
            });
            assertThat(launchEntered.await(2, TimeUnit.SECONDS)).isTrue();

            session.close();
            assertThat(budget.activeLiveTranscodeCount()).isEqualTo(1);
            finishLaunch.countDown();
            streaming.get(2, TimeUnit.SECONDS);

            assertThat(process.forceDestroyCalls).hasValue(1);
            assertThat(budget.activeLiveTranscodeCount()).isZero();
        } finally {
            finishLaunch.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void constructorFailureReleasesPermit() {
        ResourceBudget budget = budget(1);

        assertThatThrownBy(() -> LiveTranscodeSession.tryReserve(
                budget, builder -> new FakeProcess(new byte[0], 0), null, 1L, null))
                .isInstanceOf(NullPointerException.class);

        assertThat(budget.activeLiveTranscodeCount()).isZero();
    }

    private static ResourceBudget budget(int limit) {
        return new ResourceBudget(1, 1, limit, () -> false);
    }

    private static LiveTranscodeSession reserve(
            ResourceBudget budget, StreamController.ProcessLauncher launcher) {
        return LiveTranscodeSession.tryReserve(budget, launcher, List.of("ffmpeg-test"), 1L, null)
                .orElseThrow();
    }

    private static FakeProcess unresponsiveAfterSuccessfulWait() {
        FakeProcess process = new FakeProcess(new byte[]{5}, 23, true);
        process.finishFirstWaitWhileAlive = true;
        process.allowExitValueWhileAlive = true;
        return process;
    }

    private static final class FakeProcess extends Process {
        private final InputStream stdout;
        private final int configuredExitCode;
        private final boolean ignoresForceDestroy;
        private final AtomicInteger forceDestroyCalls = new AtomicInteger();
        private final CompletableFuture<Process> exit = new CompletableFuture<>();
        private volatile boolean alive = true;
        private volatile boolean nullExitObserver;
        private volatile boolean interruptTimedWait;
        private volatile boolean failTimedWait;
        private volatile boolean finishFirstWaitWhileAlive;
        private volatile boolean allowExitValueWhileAlive;
        private volatile boolean retainAliveAfterForceDestroy;
        private volatile boolean completeOnTerminationWait;
        private volatile boolean completeAfterFalseTerminationWait;
        private int timedWaitCalls;

        private FakeProcess(byte[] stdout, int exitCode) {
            this(stdout, exitCode, false, false);
        }

        private FakeProcess(byte[] stdout, int exitCode, boolean ignoresForceDestroy) {
            this(stdout, exitCode, ignoresForceDestroy, false);
        }

        private FakeProcess(byte[] stdout, int exitCode, boolean ignoresForceDestroy, boolean noErrorStream) {
            this(stdout, exitCode, ignoresForceDestroy,
                    noErrorStream ? null : InputStream.nullInputStream());
        }

        private FakeProcess(byte[] stdout, int exitCode, boolean ignoresForceDestroy, InputStream errorStream) {
            this.stdout = new ByteArrayInputStream(stdout);
            this.configuredExitCode = exitCode;
            this.ignoresForceDestroy = ignoresForceDestroy;
            this.errorStream = errorStream;
        }

        private final InputStream errorStream;

        @Override public OutputStream getOutputStream() { return OutputStream.nullOutputStream(); }
        @Override public InputStream getInputStream() { return stdout; }
        @Override public InputStream getErrorStream() { return errorStream; }

        @Override
        public int waitFor() {
            exitNormally();
            return configuredExitCode;
        }

        @Override
        public boolean waitFor(long timeout, TimeUnit unit) throws InterruptedException {
            timedWaitCalls++;
            if (timedWaitCalls > 1 && interruptTimedWait) throw new InterruptedException("synthetic wait interrupt");
            if (timedWaitCalls > 1 && failTimedWait) throw new IllegalStateException("synthetic wait failure");
            if (timedWaitCalls == 1 && finishFirstWaitWhileAlive) return true;
            if (timedWaitCalls > 1 && completeOnTerminationWait) {
                completeExternalExit();
                return true;
            }
            if (timedWaitCalls > 1 && completeAfterFalseTerminationWait) {
                completeExternalExit();
                return false;
            }
            if (alive && !ignoresForceDestroy) exitNormally();
            return !alive;
        }

        @Override
        public int exitValue() {
            if (alive && !allowExitValueWhileAlive) throw new IllegalThreadStateException("process is still running");
            return configuredExitCode;
        }

        @Override public void destroy() { destroyForcibly(); }

        @Override
        public Process destroyForcibly() {
            forceDestroyCalls.incrementAndGet();
            if (!ignoresForceDestroy && !retainAliveAfterForceDestroy) completeExternalExit();
            return this;
        }

        @Override public boolean isAlive() { return alive; }
        @Override public CompletableFuture<Process> onExit() { return nullExitObserver ? null : exit; }

        private void exitNormally() {
            completeExternalExit();
        }

        private void completeExternalExit() {
            alive = false;
            exit.complete(this);
        }

        private void stopWithoutNotifyingObserver() {
            alive = false;
        }

        private void failExitObservation() {
            exit.completeExceptionally(new IllegalStateException("synthetic exit observer failure"));
        }

    }
}
