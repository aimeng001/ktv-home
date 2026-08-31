package com.homektv.library;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** Runs an external media process without allowing a blocked child to hold a worker forever. */
final class MediaProcessRunner {
    static final Duration DEFAULT_TIMEOUT = Duration.ofMinutes(30);
    private static final Duration OUTPUT_DRAIN_TIMEOUT = Duration.ofSeconds(5);

    private MediaProcessRunner() { }

    static Duration fromSeconds(long seconds) {
        if (seconds <= 0) throw new IllegalArgumentException("media process timeout must be positive");
        return Duration.ofSeconds(seconds);
    }

    static Result run(Process process, Duration timeout) throws IOException, InterruptedException {
        Objects.requireNonNull(process, "process");
        requirePositive(timeout);
        FutureTask<String> outputTask = new FutureTask<>(
                () -> ProcessOutputTail.read(process.getInputStream(), StandardCharsets.UTF_8,
                        MediaTranscoder.MAX_PROCESS_LOG_BYTES));
        Thread.ofVirtual().name("media-process-output-reader").start(outputTask);
        try {
            if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                waitForExit(process);
                return new Result(-1, readOutput(outputTask), true);
            }
            return new Result(process.exitValue(), readOutput(outputTask), false);
        } catch (InterruptedException interrupted) {
            process.destroyForcibly();
            throw interrupted;
        } finally {
            if (process.isAlive()) process.destroyForcibly();
            outputTask.cancel(true);
        }
    }

    private static void waitForExit(Process process) {
        try {
            process.waitFor(OUTPUT_DRAIN_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private static String readOutput(FutureTask<String> outputTask) throws IOException, InterruptedException {
        try {
            return outputTask.get(OUTPUT_DRAIN_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        } catch (ExecutionException failure) {
            Throwable cause = failure.getCause();
            if (cause instanceof IOException io) throw io;
            throw new IOException("读取媒体进程输出失败", cause);
        } catch (TimeoutException timeout) {
            outputTask.cancel(true);
            throw new IOException("读取媒体进程输出超时", timeout);
        }
    }

    private static void requirePositive(Duration timeout) {
        if (timeout == null || timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("media process timeout must be positive");
        }
    }

    record Result(int exitCode, String output, boolean timedOut) { }
}
