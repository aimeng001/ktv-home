package com.homektv.library;

import com.homektv.web.ApiException;
import com.homektv.config.AppProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import com.homektv.testutil.FakeFfmpegProcess;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

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
}
