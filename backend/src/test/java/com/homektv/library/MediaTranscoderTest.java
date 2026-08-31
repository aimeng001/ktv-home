package com.homektv.library;

import com.homektv.web.ApiException;
import com.homektv.config.AppProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import com.homektv.testutil.FakeFfmpegProcess;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MediaTranscoderTest {

    @TempDir Path temp;

    @Test
    void removesPartialOutputWhenFfmpegFails() throws Exception {
        Path source = temp.resolve("source.mpg");
        Path output = temp.resolve("output.mkv");
        Files.writeString(source, "source");
        TranscodeHardwareService hardware = new TranscodeHardwareService(
                temp.resolve("dri").toString(), temp.resolve("sys").toString(), temp.resolve("mpp").toString(), "fake-ffmpeg");
        MediaTranscoder transcoder = new MediaTranscoder(hardware, "fake-ffmpeg", new AppProperties(),
                FakeFfmpegProcess::failing);
        SettingService.TranscodePolicy policy = new SettingService.TranscodePolicy(
                List.of("mkv"), List.of("h264"), List.of("aac"), false,
                "mkv", "h264", "aac", false);

        assertThatThrownBy(() -> transcoder.transcode(source, output, policy, true))
                .isInstanceOf(ApiException.class);
        assertThat(output).doesNotExist();
    }

    @Test
    void successfulTranscodeLeavesTheInputBytesUntouched() throws Exception {
        Path source = temp.resolve("source.mpg");
        Path output = temp.resolve("cache").resolve("output.mkv");
        Files.createDirectories(output.getParent());
        byte[] original = "source-media".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        Files.write(source, original);
        AtomicReference<List<String>> commandRef = new AtomicReference<>();
        TranscodeHardwareService hardware = new TranscodeHardwareService(
                temp.resolve("dri").toString(), temp.resolve("sys").toString(), temp.resolve("mpp").toString(), "fake-ffmpeg");
        MediaTranscoder transcoder = new MediaTranscoder(hardware, "fake-ffmpeg", new AppProperties(), command -> {
            commandRef.set(command);
            return FakeFfmpegProcess.coverFallback(command, source);
        });
        SettingService.TranscodePolicy policy = new SettingService.TranscodePolicy(
                List.of("mkv"), List.of("h264"), List.of("aac"), false,
                "mkv", "h264", "aac", false);

        Path result = transcoder.transcode(source, output, policy, true);

        assertThat(result).isEqualTo(output);
        assertThat(Files.readAllBytes(source)).containsExactly(original);
        assertThat(Files.readAllBytes(output)).containsExactly(original);
        assertThat(commandRef).hasValueSatisfying(command -> {
            assertThat(command).containsExactly("fake-ffmpeg", "-hide_banner", "-loglevel", "error", "-y",
                    "-i", source.toString(), "-map", "0:v:0?", "-map", "0:a?", "-c:v", "libx264",
                    "-pix_fmt", "yuv420p", "-c:a", "aac", "-b:a", "192k", "-ar", "48000", "-c:s", "copy",
                    output.toString());
        });
    }

    @Test
    void existingOutputIsNeverOverwrittenDuringTranscode() throws Exception {
        Path source = temp.resolve("source.mpg");
        Path desired = temp.resolve("cache").resolve("output.mkv");
        Files.createDirectories(desired.getParent());
        Files.writeString(source, "new-source");
        Files.writeString(desired, "existing-output");
        TranscodeHardwareService hardware = new TranscodeHardwareService(
                temp.resolve("dri").toString(), temp.resolve("sys").toString(), temp.resolve("mpp").toString(), "fake-ffmpeg");
        MediaTranscoder transcoder = new MediaTranscoder(hardware, "fake-ffmpeg", new AppProperties(),
                command -> FakeFfmpegProcess.coverFallback(command, source));
        SettingService.TranscodePolicy policy = new SettingService.TranscodePolicy(
                List.of("mkv"), List.of("h264"), List.of("aac"), false,
                "mkv", "h264", "aac", false);

        Path result = transcoder.transcode(source, desired, policy, true);

        assertThat(result).isNotEqualTo(desired);
        assertThat(Files.readString(desired)).isEqualTo("existing-output");
        assertThat(Files.readString(result)).isEqualTo("new-source");
    }

    @Test
    void killsFfmpegAndRemovesReservedOutputWhenTheProcessTimesOut() throws Exception {
        Path source = temp.resolve("source.mpg");
        Path output = temp.resolve("output.mkv");
        Files.writeString(source, "source");
        HangingProcess hanging = new HangingProcess();
        TranscodeHardwareService hardware = new TranscodeHardwareService(
                temp.resolve("dri").toString(), temp.resolve("sys").toString(), temp.resolve("mpp").toString(), "fake-ffmpeg");
        MediaTranscoder transcoder = new MediaTranscoder(hardware, "fake-ffmpeg", new AppProperties(),
                command -> hanging, java.time.Duration.ofMillis(100));
        SettingService.TranscodePolicy policy = new SettingService.TranscodePolicy(
                List.of("mkv"), List.of("h264"), List.of("aac"), false,
                "mkv", "h264", "aac", false);

        assertThatThrownBy(() -> transcoder.transcode(source, output, policy, true))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("超时");
        assertThat(hanging.destroyed.get()).isTrue();
        assertThat(output).doesNotExist();
    }

    private static final class HangingProcess extends Process {
        private final BlockingInputStream stdout = new BlockingInputStream();
        private final AtomicBoolean destroyed = new AtomicBoolean();

        @Override public OutputStream getOutputStream() { return new ByteArrayOutputStream(); }
        @Override public InputStream getInputStream() { return stdout; }
        @Override public InputStream getErrorStream() { return new ByteArrayInputStream(new byte[0]); }
        @Override public int waitFor() throws InterruptedException {
            while (!destroyed.get()) Thread.sleep(10);
            return 143;
        }
        @Override public boolean waitFor(long timeout, TimeUnit unit) throws InterruptedException {
            long deadline = System.nanoTime() + unit.toNanos(timeout);
            while (!destroyed.get() && System.nanoTime() < deadline) {
                TimeUnit.MILLISECONDS.sleep(1L);
            }
            return destroyed.get();
        }
        @Override public int exitValue() {
            if (!destroyed.get()) throw new IllegalThreadStateException("process is still running");
            return 143;
        }
        @Override public void destroy() { destroyForcibly(); }
        @Override public Process destroyForcibly() {
            destroyed.set(true);
            stdout.close();
            return this;
        }
        @Override public boolean isAlive() { return !destroyed.get(); }
    }

    private static final class BlockingInputStream extends InputStream {
        private final AtomicBoolean closed = new AtomicBoolean();

        @Override public int read() throws IOException {
            while (!closed.get()) {
                if (Thread.currentThread().isInterrupted()) throw new IOException("reader interrupted");
                Thread.onSpinWait();
            }
            return -1;
        }

        @Override public void close() { closed.set(true); }
    }
}
