package com.homektv.library;

import com.homektv.web.ApiException;
import com.homektv.config.AppProperties;
import com.homektv.resource.ResourceBudget;
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
    void audioOnlyTranscodeDoesNotProbeHardwareOrAddVideoEncoderAndSelectsOpus() throws Exception {
        Path source = temp.resolve("audio.mp3");
        Path output = temp.resolve("audio.mkv");
        byte[] original = "audio-source".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        Files.write(source, original);
        AtomicReference<List<String>> commandRef = new AtomicReference<>();
        TranscodeHardwareService hardware = new TranscodeHardwareService(
                temp.resolve("dri").toString(), temp.resolve("sys").toString(), temp.resolve("mpp").toString(), "fake-ffmpeg") {
            @Override public HardwareStatus requireAvailable(String codec) {
                throw new AssertionError("audio-only transcode must not request a video accelerator");
            }
        };
        MediaTranscoder transcoder = new MediaTranscoder(hardware, "fake-ffmpeg", new AppProperties(), command -> {
            commandRef.set(command);
            return FakeFfmpegProcess.coverFallback(command, source);
        });
        SettingService.TranscodePolicy policy = new SettingService.TranscodePolicy(
                List.of("mp3"), List.of("h264"), List.of("opus"), false,
                "mkv", "h264", "opus", true);

        Path result = transcoder.transcode(source, output, policy, false);

        assertThat(result).isEqualTo(output);
        assertThat(commandRef.get()).contains("-c:a", "libopus").doesNotContain("-c:v", "-vaapi_device", "h264_rkmpp");
        assertThat(Files.readAllBytes(source)).containsExactly(original);
        assertThat(Files.exists(output)).isTrue();
    }

    @Test
    void softwareTranscodeSelectsHevcAndMp3Encoders() throws Exception {
        Path source = temp.resolve("hevc-source.mpg");
        Path output = temp.resolve("hevc-output.mkv");
        Files.writeString(source, "source");
        AtomicReference<List<String>> commandRef = new AtomicReference<>();
        TranscodeHardwareService hardware = new TranscodeHardwareService(
                temp.resolve("dri").toString(), temp.resolve("sys").toString(), temp.resolve("mpp").toString(), "fake-ffmpeg");
        MediaTranscoder transcoder = new MediaTranscoder(hardware, "fake-ffmpeg", new AppProperties(), command -> {
            commandRef.set(command);
            return FakeFfmpegProcess.coverFallback(command, source);
        });
        SettingService.TranscodePolicy policy = new SettingService.TranscodePolicy(
                List.of("mkv"), List.of("hevc"), List.of("mp3"), false,
                "mkv", "hevc", "mp3", false);

        Path result = transcoder.transcode(source, output, policy, true);

        assertThat(result).exists();
        assertThat(commandRef.get()).containsSubsequence("-c:v", "libx265", "-pix_fmt", "yuv420p")
                .contains("-c:a", "libmp3lame");
    }

    @Test
    void hardwareTranscodeUsesTheDeviceSpecificVaapiAndRkmppArguments() throws Exception {
        for (String acceleration : List.of("vaapi", "rkmpp")) {
            Path source = temp.resolve(acceleration + "-source.mpg");
            Path output = temp.resolve(acceleration).resolve("output.mkv");
            Files.createDirectories(output.getParent());
            Files.writeString(source, "source-" + acceleration);
            AtomicReference<List<String>> commandRef = new AtomicReference<>();
            TranscodeHardwareService hardware = new TranscodeHardwareService(
                    temp.resolve("dri").toString(), temp.resolve("sys").toString(), temp.resolve("mpp").toString(), "fake-ffmpeg") {
                @Override public HardwareStatus requireAvailable(String codec) {
                    return new HardwareStatus(true, "synthetic", "synthetic-device", List.of(codec), acceleration, null);
                }
            };
            MediaTranscoder transcoder = new MediaTranscoder(hardware, "fake-ffmpeg", new AppProperties(), command -> {
                commandRef.set(command);
                return FakeFfmpegProcess.coverFallback(command, source);
            });
            SettingService.TranscodePolicy policy = new SettingService.TranscodePolicy(
                    List.of("mkv"), List.of("h264"), List.of("aac"), false,
                    "mkv", "h264", "aac", true);

            Path result = transcoder.transcode(source, output, policy, true);

            assertThat(result).exists();
            if ("vaapi".equals(acceleration)) {
                assertThat(commandRef.get()).containsSubsequence("-vaapi_device", "synthetic-device",
                        "-vf", "format=nv12,hwupload", "-c:v", "h264_vaapi");
            } else {
                assertThat(commandRef.get()).containsSubsequence("-pix_fmt", "nv12", "-c:v", "h264_rkmpp")
                        .doesNotContain("-vaapi_device", "-vf");
            }
        }
    }

    @Test
    void processLaunchFailureReleasesBudgetAndRemovesReservedOutput() throws Exception {
        for (boolean hardwareAcceleration : List.of(false, true)) {
            String suffix = hardwareAcceleration ? "hardware" : "software";
            Path source = temp.resolve("unlaunchable-" + suffix + ".mpg");
            Path output = temp.resolve("unlaunchable-" + suffix + ".mkv");
            Files.writeString(source, "source-" + suffix);
            ResourceBudget budget = new ResourceBudget(2, 1, 4, () -> false);
            TranscodeHardwareService hardware = hardwareAcceleration
                    ? availableHardware("vaapi")
                    : new TranscodeHardwareService(temp.resolve("dri").toString(), temp.resolve("sys").toString(),
                            temp.resolve("mpp").toString(), "fake-ffmpeg");
            MediaTranscoder transcoder = new MediaTranscoder(hardware, "fake-ffmpeg", new AppProperties(),
                    command -> { throw new IOException("synthetic process launch failure"); },
                    java.time.Duration.ofSeconds(5), budget);
            SettingService.TranscodePolicy policy = new SettingService.TranscodePolicy(
                    List.of("mkv"), List.of("h264"), List.of("aac"), false,
                    "mkv", "h264", "aac", hardwareAcceleration);

            assertThatThrownBy(() -> transcoder.transcode(source, output, policy, true))
                    .isInstanceOfSatisfying(ApiException.class, error -> assertThat(error.getCode())
                            .isEqualTo(hardwareAcceleration ? "HARDWARE_TRANSCODE_FAILED" : "TRANSCODE_FAILED"))
                    .hasMessageContaining("synthetic process launch failure");

            assertThat(Files.readString(source)).isEqualTo("source-" + suffix);
            assertThat(output).doesNotExist();
            assertThat(budget.activeManagedTranscodeCount()).isZero();
        }
    }

    @Test
    void reservationFailureUsesTheMatchingSoftwareOrHardwareErrorCode() throws Exception {
        for (boolean hardwareAcceleration : List.of(false, true)) {
            String suffix = hardwareAcceleration ? "hardware" : "software";
            Path source = temp.resolve("reservation-" + suffix + ".mpg");
            Path outputParentFile = Files.writeString(temp.resolve("output-parent-" + suffix), "not a directory");
            Path output = outputParentFile.resolve("output.mkv");
            Files.writeString(source, "read-only-source");
            TranscodeHardwareService hardware = hardwareAcceleration
                    ? availableHardware("vaapi")
                    : new TranscodeHardwareService(temp.resolve("dri").toString(), temp.resolve("sys").toString(),
                            temp.resolve("mpp").toString(), "fake-ffmpeg");
            MediaTranscoder transcoder = new MediaTranscoder(hardware, "fake-ffmpeg", new AppProperties(),
                    command -> { throw new AssertionError("output reservation must fail before process launch"); });
            SettingService.TranscodePolicy policy = new SettingService.TranscodePolicy(
                    List.of("mkv"), List.of("h264"), List.of("aac"), false,
                    "mkv", "h264", "aac", hardwareAcceleration);

            assertThatThrownBy(() -> transcoder.transcode(source, output, policy, true))
                    .isInstanceOfSatisfying(ApiException.class, error -> assertThat(error.getCode())
                            .isEqualTo(hardwareAcceleration ? "HARDWARE_TRANSCODE_FAILED" : "TRANSCODE_FAILED"));

            assertThat(Files.readString(source)).isEqualTo("read-only-source");
            assertThat(output).doesNotExist();
        }
    }

    @Test
    void zeroExitWithoutReadableOrNonemptyOutputFailsAndUsesProcessDiagnostics() throws Exception {
        for (int index = 0; index < 2; index++) {
            final int scenario = index;
            boolean hardwareAcceleration = scenario == 1;
            Path source = temp.resolve("empty-output-source-" + scenario + ".mpg");
            Path output = temp.resolve("empty-output-" + scenario + ".mkv");
            Files.writeString(source, "source-" + scenario);
            TranscodeHardwareService hardware = hardwareAcceleration
                    ? availableHardware("rkmpp")
                    : new TranscodeHardwareService(temp.resolve("dri").toString(), temp.resolve("sys").toString(),
                            temp.resolve("mpp").toString(), "fake-ffmpeg");
            MediaTranscoder transcoder = new MediaTranscoder(hardware, "fake-ffmpeg", new AppProperties(), command -> {
                if (scenario == 0) Files.delete(Path.of(command.get(command.size() - 1)));
                return new FinishedProcess(0, scenario == 0 ? "ffmpeg produced no output" : "");
            });
            SettingService.TranscodePolicy policy = new SettingService.TranscodePolicy(
                    List.of("mkv"), List.of("h264"), List.of("aac"), false,
                    "mkv", "h264", "aac", hardwareAcceleration);

            assertThatThrownBy(() -> transcoder.transcode(source, output, policy, true))
                    .isInstanceOfSatisfying(ApiException.class, error -> assertThat(error.getCode())
                            .isEqualTo(hardwareAcceleration ? "HARDWARE_TRANSCODE_FAILED" : "TRANSCODE_FAILED"))
                    .hasMessageContaining(scenario == 0 ? "ffmpeg produced no output" : "ffmpeg 转码失败");

            assertThat(Files.readString(source)).isEqualTo("source-" + scenario);
            assertThat(output).doesNotExist();
        }
    }

    @Test
    void managedTranscodeHoldsSharedBudgetUntilTheChildProcessCompletes() throws Exception {
        Path source = temp.resolve("source.mpg");
        Path output = temp.resolve("output.mkv");
        Files.writeString(source, "source");
        ResourceBudget budget = new ResourceBudget(2, 1, 4, () -> false);
        TranscodeHardwareService hardware = new TranscodeHardwareService(
                temp.resolve("dri").toString(), temp.resolve("sys").toString(), temp.resolve("mpp").toString(), "fake-ffmpeg");
        MediaTranscoder transcoder = new MediaTranscoder(hardware, "fake-ffmpeg", new AppProperties(), command -> {
            assertThat(budget.activeManagedTranscodeCount()).isEqualTo(1);
            return FakeFfmpegProcess.coverFallback(command, source);
        }, java.time.Duration.ofSeconds(5), budget);
        SettingService.TranscodePolicy policy = new SettingService.TranscodePolicy(
                List.of("mkv"), List.of("h264"), List.of("aac"), false,
                "mkv", "h264", "aac", false);

        transcoder.transcode(source, output, policy, true);

        assertThat(budget.activeManagedTranscodeCount()).isZero();
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
        ResourceBudget budget = new ResourceBudget(2, 1, 4, () -> false);
        TranscodeHardwareService hardware = new TranscodeHardwareService(
                temp.resolve("dri").toString(), temp.resolve("sys").toString(), temp.resolve("mpp").toString(), "fake-ffmpeg");
        MediaTranscoder transcoder = new MediaTranscoder(hardware, "fake-ffmpeg", new AppProperties(),
                command -> hanging, java.time.Duration.ofMillis(100), budget);
        SettingService.TranscodePolicy policy = new SettingService.TranscodePolicy(
                List.of("mkv"), List.of("h264"), List.of("aac"), false,
                "mkv", "h264", "aac", false);

        assertThatThrownBy(() -> transcoder.transcode(source, output, policy, true))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("超时");
        assertThat(hanging.destroyed.get()).isTrue();
        assertThat(budget.activeManagedTranscodeCount()).isZero();
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

    private static final class FinishedProcess extends Process {
        private final int exitCode;
        private final InputStream stdout;
        private boolean alive = true;

        private FinishedProcess(int exitCode, String stdout) {
            this.exitCode = exitCode;
            this.stdout = new ByteArrayInputStream(stdout.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }

        @Override public OutputStream getOutputStream() { return new ByteArrayOutputStream(); }
        @Override public InputStream getInputStream() { return stdout; }
        @Override public InputStream getErrorStream() { return new ByteArrayInputStream(new byte[0]); }
        @Override public int waitFor() { alive = false; return exitCode; }
        @Override public boolean waitFor(long timeout, TimeUnit unit) { alive = false; return true; }
        @Override public int exitValue() {
            if (alive) throw new IllegalThreadStateException("process is still running");
            return exitCode;
        }
        @Override public void destroy() { alive = false; }
        @Override public Process destroyForcibly() { alive = false; return this; }
        @Override public boolean isAlive() { return alive; }
    }

    private TranscodeHardwareService availableHardware(String acceleration) {
        return new TranscodeHardwareService(
                temp.resolve("dri").toString(), temp.resolve("sys").toString(), temp.resolve("mpp").toString(), "fake-ffmpeg") {
            @Override public HardwareStatus requireAvailable(String codec) {
                return new HardwareStatus(true, "synthetic", "synthetic-device", List.of(codec), acceleration, null);
            }
        };
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
