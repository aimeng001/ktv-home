package com.homektv.library;

import com.homektv.web.ApiException;
import com.homektv.config.AppProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import com.homektv.testutil.FakeFfmpegProcess;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

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
}
