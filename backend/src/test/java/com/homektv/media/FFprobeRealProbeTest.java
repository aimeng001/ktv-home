package com.homektv.media;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 真实调用 ffprobe 的端到端验证（P0.7 核心验收）。
 * 用 ffmpeg 现场生成一个「1 视频 + 2 音轨」的 MKV（模拟 A 类 KTV 视频），
 * 探测其音轨数/字幕流/时长/分辨率。无 ffmpeg/ffprobe 环境时自动跳过。
 */
class FFprobeRealProbeTest {

    private static boolean toolsAvailable;

    @BeforeAll
    static void checkTools() {
        toolsAvailable = commandExists("ffprobe") && commandExists("ffmpeg");
    }

    @Test
    void probeGeneratedDualAudioVideo(@TempDir Path tmp) throws Exception {
        assumeTrue(toolsAvailable, "ffmpeg/ffprobe 不可用，跳过真实探测测试");

        Path media = tmp.resolve("dual-audio.mkv");
        // 生成 3 秒测试视频：1 路 720p 测试图 + 2 路正弦音轨
        int gen = run(
                "ffmpeg", "-y",
                "-f", "lavfi", "-i", "testsrc=duration=3:size=1280x720:rate=25",
                "-f", "lavfi", "-i", "sine=frequency=440:duration=3",
                "-f", "lavfi", "-i", "sine=frequency=880:duration=3",
                "-map", "0:v", "-map", "1:a", "-map", "2:a",
                "-c:v", "libx264", "-preset", "ultrafast",
                media.toString()
        );
        assumeTrue(gen == 0, "ffmpeg 生成测试文件失败，跳过");

        FFprobeService service = new FFprobeService(new com.homektv.config.AppProperties());
        MediaProbe p = service.probe(media);

        assertThat(p.hasVideo()).isTrue();
        assertThat(p.audioTracks()).isEqualTo(2);       // KTV 双音轨判定的关键
        assertThat(p.resolution()).isEqualTo("1280x720");
        assertThat(p.durationMs()).isBetween(2800L, 3200L);
    }

    @Test
    void probeDelayedMpegTsKeepsAudioCodecAndChannels(@TempDir Path tmp) throws Exception {
        assumeTrue(toolsAvailable, "ffmpeg/ffprobe 不可用，跳过真实探测测试");

        Path media = tmp.resolve("delayed-audio.ts");
        int gen = run(
                "ffmpeg", "-y",
                "-f", "lavfi", "-i", "testsrc=duration=6:size=320x180:rate=25",
                "-itsoffset", "3",
                "-f", "lavfi", "-i", "sine=frequency=440:duration=3",
                "-map", "0:v", "-map", "1:a",
                "-c:v", "mpeg2video", "-c:a", "mp2", "-f", "mpegts",
                media.toString()
        );
        assumeTrue(gen == 0, "ffmpeg 生成延迟音频测试文件失败，跳过");

        FFprobeService service = new FFprobeService(new com.homektv.config.AppProperties());
        MediaProbe p = service.probe(media);

        assertThat(p.audioTracks()).isEqualTo(1);
        assertThat(p.audioCodec()).isEqualTo("mp2");
        assertThat(p.audioStreams()).singleElement()
                .extracting(AudioStreamInfo::channels).isEqualTo(1);
    }

    private static boolean commandExists(String cmd) {
        try {
            Process p = new ProcessBuilder(cmd, "-version")
                    .redirectErrorStream(true).start();
            p.getInputStream().readAllBytes();
            return p.waitFor(10, TimeUnit.SECONDS) && p.exitValue() == 0;
        } catch (IOException | InterruptedException e) {
            return false;
        }
    }

    private static int run(String... cmd) throws IOException, InterruptedException {
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        p.getInputStream().readAllBytes();
        p.waitFor(60, TimeUnit.SECONDS);
        return p.exitValue();
    }
}
