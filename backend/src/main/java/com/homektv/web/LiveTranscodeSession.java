package com.homektv.web;

import com.homektv.diagnostics.SecretRedactor;
import com.homektv.library.ProcessOutputTail;
import com.homektv.resource.ResourceBudget;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

/** Owns one live FFmpeg process and its admission permit from reservation to confirmed exit. */
final class LiveTranscodeSession implements AutoCloseable {
    private static final int BUFFER_SIZE = 64 * 1024;
    private static final long PROCESS_EXIT_TIMEOUT_SECONDS = 5;
    private static final long STDERR_DRAIN_TIMEOUT_SECONDS = 5;
    private static final Logger log = LoggerFactory.getLogger(LiveTranscodeSession.class);

    private final ResourceBudget.Permit permit;
    private final StreamController.ProcessLauncher processLauncher;
    private final List<String> command;
    private final List<String> fallbackCommand;
    private final Long fileId;
    private final Double startSeconds;
    private final Object lifecycleLock = new Object();
    private final AtomicBoolean bodyStarted = new AtomicBoolean();
    private final AtomicBoolean closeRequested = new AtomicBoolean();
    private final AtomicBoolean terminationStarted = new AtomicBoolean();
    private Process process;
    private boolean launchInProgress;

    static Optional<LiveTranscodeSession> tryReserve(
            ResourceBudget resourceBudget,
            StreamController.ProcessLauncher processLauncher,
            List<String> command,
            Long fileId,
            Double startSeconds) {
        return tryReserve(resourceBudget, processLauncher, command, null, fileId, startSeconds);
    }

    static Optional<LiveTranscodeSession> tryReserve(
            ResourceBudget resourceBudget,
            StreamController.ProcessLauncher processLauncher,
            List<String> command,
            List<String> fallbackCommand,
            Long fileId,
            Double startSeconds) {
        ResourceBudget.Permit permit = resourceBudget
                .tryAcquireLiveTranscode(Duration.ofMillis(250))
                .orElse(null);
        if (permit == null) return Optional.empty();
        try {
            return Optional.of(new LiveTranscodeSession(
                    permit, processLauncher, command, fallbackCommand, fileId, startSeconds));
        } catch (RuntimeException | Error failure) {
            permit.close();
            throw failure;
        }
    }

    private LiveTranscodeSession(
            ResourceBudget.Permit permit,
            StreamController.ProcessLauncher processLauncher,
            List<String> command,
            List<String> fallbackCommand,
            Long fileId,
            Double startSeconds) {
        this.permit = permit;
        this.processLauncher = processLauncher;
        this.command = List.copyOf(command);
        this.fallbackCommand = fallbackCommand == null ? null : List.copyOf(fallbackCommand);
        this.fileId = fileId;
        this.startSeconds = startSeconds;
    }

    void streamTo(OutputStream responseOutput) throws IOException {
        if (!beginLaunch()) return;
        try {
            AtomicBoolean bytesWritten = new AtomicBoolean(false);
            try {
                streamCommand(command, responseOutput, bytesWritten);
            } catch (IOException primaryFailure) {
                if (bytesWritten.get()
                        || fallbackCommand == null
                        || fallbackCommand.isEmpty()
                        || closeRequested.get()
                        || !prepareFallbackAttempt()) {
                    throw primaryFailure;
                }
                log.warn("Hardware live transcode failed before first byte; falling back to CPU libx264: fileId={}, startSeconds={}, cause={}",
                        fileId, startSeconds, primaryFailure.getMessage());
                streamCommand(fallbackCommand, responseOutput, bytesWritten);
            }
        } catch (ClientDisconnectedException disconnected) {
            close();
            log.debug("Live transcode client disconnected: fileId={}", fileId);
        } finally {
            close();
        }
    }

    private void streamCommand(
            List<String> activeCommand,
            OutputStream responseOutput,
            AtomicBoolean bytesWritten) throws IOException {
        Process current = null;
        FutureTask<String> stderrDrain = null;
        try {
            current = launchProcess(activeCommand);
            if (current == null) return;
            stderrDrain = startStderrDrain(current);
            try (InputStream input = current.getInputStream()) {
                copyToResponse(input, responseOutput, bytesWritten);
            }
            if (!current.waitFor(PROCESS_EXIT_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                throw new IOException("FFmpeg did not exit before the stream completion timeout");
            }
            readStderrTail(stderrDrain);
            int exitCode = current.exitValue();
            if (exitCode != 0) throw new TranscodeProcessExitException(exitCode);
        } catch (ClientDisconnectedException disconnected) {
            throw disconnected;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            String stderrTail = safeDiagnosticTail(readStderrTail(stderrDrain));
            log.warn("Live transcode interrupted: fileId={}, startSeconds={}, stderrTail={}",
                    fileId, startSeconds, stderrTail);
            throw new IOException("Live transcode interrupted for fileId=" + fileId);
        } catch (IOException failure) {
            Integer exitCode = failure instanceof TranscodeProcessExitException exitFailure
                    ? exitFailure.exitCode
                    : null;
            String stderrTail = safeDiagnosticTail(readStderrTail(stderrDrain));
            log.error("Live transcode failed: fileId={}, startSeconds={}, failureType={}, exitCode={}, stderrTail={}",
                    fileId, startSeconds, failure.getClass().getSimpleName(), exitCode, stderrTail);
            throw new IOException("Live transcode failed for fileId=" + fileId);
        }
    }

    private boolean prepareFallbackAttempt() {
        Process current;
        synchronized (lifecycleLock) {
            if (closeRequested.get()) return false;
            current = process;
        }
        if (!confirmFailedAttemptExited(current)) {
            return false;
        }
        synchronized (lifecycleLock) {
            if (closeRequested.get()) return false;
            if (process != null && process.isAlive()) return false;
            process = null;
            launchInProgress = true;
            return true;
        }
    }

    private boolean confirmFailedAttemptExited(Process current) {
        if (current == null) return true;
        try {
            if (current.isAlive()) current.destroyForcibly();
            if (!current.isAlive()
                    || current.waitFor(PROCESS_EXIT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                    || !current.isAlive()) {
                synchronized (lifecycleLock) {
                    if (process == current) process = null;
                }
                return true;
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } catch (RuntimeException ignored) {
        }
        return false;
    }

    private boolean beginLaunch() {
        if (!bodyStarted.compareAndSet(false, true)) return false;
        synchronized (lifecycleLock) {
            if (closeRequested.get()) return false;
            launchInProgress = true;
            return true;
        }
    }

    private Process launchProcess(List<String> activeCommand) throws IOException {
        Process launched;
        try {
            ProcessBuilder builder = new ProcessBuilder(activeCommand);
            builder.redirectError(ProcessBuilder.Redirect.PIPE);
            builder.redirectErrorStream(false);
            launched = processLauncher.start(builder);
            if (launched == null) throw new IOException("FFmpeg process launcher returned no process");
        } catch (IOException | RuntimeException | Error failure) {
            synchronized (lifecycleLock) {
                launchInProgress = false;
            }
            throw failure;
        }

        boolean cancelled;
        synchronized (lifecycleLock) {
            process = launched;
            launchInProgress = false;
            cancelled = closeRequested.get();
        }
        if (cancelled) {
            terminateAndRelease(launched);
            return null;
        }
        return launched;
    }

    private static void copyToResponse(
            InputStream processOutput,
            OutputStream responseOutput,
            AtomicBoolean bytesWritten)
            throws IOException {
        byte[] buffer = new byte[BUFFER_SIZE];
        int pendingFlushBytes = 0;
        int read;
        while ((read = processOutput.read(buffer)) != -1) {
            if (read == 0) continue;
            try {
                boolean firstWrite = !bytesWritten.get();
                responseOutput.write(buffer, 0, read);
                bytesWritten.set(true);
                pendingFlushBytes += read;
                if (firstWrite || pendingFlushBytes >= BUFFER_SIZE || processOutput.available() == 0) {
                    responseOutput.flush();
                    pendingFlushBytes = 0;
                }
            } catch (IOException clientDisconnected) {
                throw new ClientDisconnectedException(clientDisconnected);
            }
        }
        try {
            responseOutput.flush();
        } catch (IOException clientDisconnected) {
            throw new ClientDisconnectedException(clientDisconnected);
        }
    }

    private FutureTask<String> startStderrDrain(Process current) {
        InputStream errorStream = current.getErrorStream();
        if (errorStream == null) errorStream = InputStream.nullInputStream();
        InputStream stream = errorStream;
        FutureTask<String> drain = new FutureTask<>(() -> ProcessOutputTail.read(
                stream, StandardCharsets.UTF_8, ProcessOutputTail.DEFAULT_MAX_BYTES));
        Thread.ofVirtual().name("live-transcode-stderr-" + fileId).start(drain);
        return drain;
    }

    private static String readStderrTail(FutureTask<String> drain) {
        if (drain == null) return "[stderr unavailable]";
        try {
            return drain.get(STDERR_DRAIN_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (ExecutionException failure) {
            return "[stderr read failed]";
        } catch (TimeoutException timeout) {
            drain.cancel(true);
            return "[stderr drain timed out]";
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            drain.cancel(true);
            return "[stderr drain interrupted]";
        }
    }

    private static String safeDiagnosticTail(String value) {
        String sanitized = SecretRedactor.redact(value).replace('\r', ' ').replace('\n', ' ');
        int bytes = 0;
        int start = sanitized.length();
        while (start > 0) {
            int codePoint = sanitized.codePointBefore(start);
            int encodedBytes = codePoint <= 0x7f ? 1 : codePoint <= 0x7ff ? 2 : codePoint <= 0xffff ? 3 : 4;
            if (bytes + encodedBytes > ProcessOutputTail.DEFAULT_MAX_BYTES) break;
            bytes += encodedBytes;
            start -= Character.charCount(codePoint);
        }
        return sanitized.substring(start);
    }

    @Override
    public void close() {
        closeRequested.set(true);
        Process current;
        boolean starting;
        synchronized (lifecycleLock) {
            current = process;
            starting = launchInProgress;
        }
        if (current != null) {
            terminateAndRelease(current);
        } else if (!starting) {
            permit.close();
        }
    }

    private void terminateAndRelease(Process current) {
        if (!terminationStarted.compareAndSet(false, true)) return;
        try {
            if (current.isAlive()) current.destroyForcibly();
            if (!current.isAlive()
                    || current.waitFor(PROCESS_EXIT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                    || !current.isAlive()) {
                permit.close();
                return;
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } catch (RuntimeException failure) {
            log.warn("Unable to confirm live transcode exit: fileId={}, failureType={}",
                    fileId, failure.getClass().getSimpleName());
        }
        retainPermitUntilExit(current);
    }

    private void retainPermitUntilExit(Process current) {
        try {
            CompletableFuture<Process> exit = current.onExit();
            if (exit == null) {
                log.error("Live transcode exit cannot be observed; retaining permit: fileId={}", fileId);
                return;
            }
            exit.whenComplete((ignored, failure) -> {
                if (failure == null || !current.isAlive()) {
                    permit.close();
                } else {
                    log.error("Live transcode exit observer failed; retaining permit: fileId={}", fileId);
                }
            });
        } catch (RuntimeException failure) {
            log.error("Live transcode exit observer failed; retaining permit: fileId={}, failureType={}",
                    fileId, failure.getClass().getSimpleName());
        }
    }

    private static final class ClientDisconnectedException extends IOException {
        private ClientDisconnectedException(IOException cause) {
            super(cause);
        }
    }

    private static final class TranscodeProcessExitException extends IOException {
        private final int exitCode;

        private TranscodeProcessExitException(int exitCode) {
            super("FFmpeg exited with non-zero status");
            this.exitCode = exitCode;
        }
    }
}
