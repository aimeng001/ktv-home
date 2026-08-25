package com.homektv.testutil;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

/** Test-only fake process for exercising the external ffmpeg boundary. */
public final class FakeFfmpegProcess extends Process {

    @FunctionalInterface
    private interface CompletionAction {
        void run() throws IOException;
    }

    private final CompletionAction completionAction;
    private final int exitCode;
    private final ByteArrayOutputStream output = new ByteArrayOutputStream();
    private boolean alive = true;
    private boolean completed;

    private FakeFfmpegProcess(CompletionAction completionAction, int exitCode) {
        this.completionAction = Objects.requireNonNull(completionAction);
        this.exitCode = exitCode;
    }

    public static Process failing(List<String> command) {
        Path output = outputPath(command);
        return new FakeFfmpegProcess(
                () -> Files.writeString(output, "partial"), 1);
    }

    public static Process coverFallback(List<String> command, Path converted) {
        Path output = outputPath(command);
        return new FakeFfmpegProcess(
                () -> Files.copy(converted, output, StandardCopyOption.REPLACE_EXISTING), 0);
    }

    private static Path outputPath(List<String> command) {
        if (command == null || command.isEmpty()) {
            throw new IllegalArgumentException("ffmpeg command must include an output path");
        }
        return Path.of(command.get(command.size() - 1));
    }

    @Override
    public OutputStream getOutputStream() {
        return output;
    }

    @Override
    public InputStream getInputStream() {
        return new ByteArrayInputStream(new byte[0]);
    }

    @Override
    public InputStream getErrorStream() {
        return new ByteArrayInputStream(new byte[0]);
    }

    @Override
    public int waitFor() {
        complete();
        return exitCode;
    }

    @Override
    public boolean waitFor(long timeout, TimeUnit unit) {
        complete();
        return true;
    }

    @Override
    public int exitValue() {
        if (alive) throw new IllegalThreadStateException("fake process is still running");
        return exitCode;
    }

    @Override
    public void destroy() {
        alive = false;
    }

    @Override
    public Process destroyForcibly() {
        alive = false;
        return this;
    }

    @Override
    public boolean isAlive() {
        return alive;
    }

    private void complete() {
        if (completed) return;
        try {
            completionAction.run();
        } catch (IOException failure) {
            throw new IllegalStateException("fake ffmpeg process failed", failure);
        } finally {
            completed = true;
            alive = false;
        }
    }
}
