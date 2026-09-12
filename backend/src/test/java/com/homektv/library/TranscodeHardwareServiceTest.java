package com.homektv.library;

import com.homektv.web.ApiException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TranscodeHardwareServiceTest {

    @TempDir Path temp;

    @Test
    void detectsIntelVaapiCodecs() throws Exception {
        Path dri = Files.createDirectories(temp.resolve("dri"));
        Path sys = Files.createDirectories(temp.resolve("sys/renderD128/device"));
        Files.createFile(dri.resolve("renderD128"));
        Files.writeString(sys.resolve("vendor"), "0x8086\n");
        TranscodeHardwareService service = workingService(dri, temp.resolve("sys"));

        TranscodeHardwareService.HardwareStatus status = service.detect();

        assertThat(status.available()).isTrue();
        assertThat(status.vendor()).isEqualTo("Intel VAAPI");
        assertThat(status.supportedCodecs()).containsExactly("h264", "hevc");
    }

    @Test
    void detectsAmdAndRejectsUnavailableCodec() throws Exception {
        Path dri = Files.createDirectories(temp.resolve("dri"));
        Path sys = Files.createDirectories(temp.resolve("sys/renderD129/device"));
        Files.createFile(dri.resolve("renderD129"));
        Files.writeString(sys.resolve("vendor"), "0x1002");
        TranscodeHardwareService service = new TranscodeHardwareService(dri.toString(), temp.resolve("sys").toString(), temp.resolve("mpp").toString(), "ffmpeg") {
            @Override boolean run(List<String> command) { return command.contains("h264_vaapi"); }
        };

        assertThat(service.detect().vendor()).isEqualTo("AMD VAAPI");
        assertThatThrownBy(() -> service.requireAvailable("hevc"))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("HEVC");
    }

    @Test
    void reportsMissingDevice() {
        TranscodeHardwareService service = workingService(temp.resolve("missing"), temp.resolve("sys"));
        assertThat(service.detect().available()).isFalse();
        assertThatThrownBy(() -> service.requireAvailable("h264"))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("未找到");
    }

    @Test
    void detectsRockchipMppCodecs() throws Exception {
        Path mpp = temp.resolve("mpp_service");
        Files.createFile(mpp);
        TranscodeHardwareService service = new TranscodeHardwareService(
                temp.resolve("dri").toString(), temp.resolve("sys").toString(), mpp.toString(), "ffmpeg") {
            @Override boolean run(List<String> command) { return command.contains("h264_rkmpp") || command.contains("hevc_rkmpp"); }
        };

        TranscodeHardwareService.HardwareStatus status = service.detect();

        assertThat(status.available()).isTrue();
        assertThat(status.vendor()).isEqualTo("Rockchip RK MPP");
        assertThat(status.acceleration()).isEqualTo("rkmpp");
        assertThat(status.supportedCodecs()).containsExactly("h264", "hevc");
    }

    @Test
    void smokeTestDrainsProcessOutputInsteadOfBlockingOnFullPipe() throws Exception {
        String executable = Path.of(System.getProperty("java.home"), "bin",
                "java" + (System.getProperty("os.name").toLowerCase().contains("win") ? ".exe" : ""))
                .toString();
        List<String> command = List.of(executable, "-cp", System.getProperty("java.class.path"),
                OutputFloodProcess.class.getName());

        TranscodeHardwareService service = new TranscodeHardwareService(
                temp.resolve("missing").toString(), temp.resolve("sys").toString(),
                temp.resolve("missing-mpp").toString(), "ffmpeg");

        assertThat(service.run(command)).isTrue();
    }

    public static final class OutputFloodProcess {
        private OutputFloodProcess() { }

        public static void main(String[] args) throws Exception {
            byte[] chunk = new byte[8192];
            Arrays.fill(chunk, (byte) 'x');
            for (int index = 0; index < 256; index++) {
                System.out.write(chunk);
            }
            System.out.flush();
        }
    }

    private TranscodeHardwareService workingService(Path dri, Path sys) {
        return new TranscodeHardwareService(dri.toString(), sys.toString(), temp.resolve("missing-mpp").toString(), "ffmpeg") {
            @Override boolean run(List<String> command) { return true; }
        };
    }
}
