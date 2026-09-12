package com.homektv.library;

import com.homektv.web.ApiException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

@Service
public class TranscodeHardwareService {

    private final Path driRoot;
    private final Path drmSysRoot;
    private final Path rkmppDevice;
    private final String ffmpegPath;

    public TranscodeHardwareService(
            @Value("${app.transcode.dri-path:/dev/dri}") String driPath,
            @Value("${app.transcode.drm-sys-path:/sys/class/drm}") String drmSysPath,
            @Value("${app.transcode.rkmpp-device-path:/dev/mpp_service}") String rkmppDevicePath,
            @Value("${app.transcode.ffmpeg-path:ffmpeg}") String ffmpegPath) {
        this.driRoot = Path.of(driPath);
        this.drmSysRoot = Path.of(drmSysPath);
        this.rkmppDevice = Path.of(rkmppDevicePath);
        this.ffmpegPath = ffmpegPath;
    }

    public HardwareStatus detect() {
        HardwareStatus rockchip = detectRockchip();
        if (rockchip != null) return rockchip;
        if (!Files.isDirectory(driRoot)) {
            return HardwareStatus.unavailable("未找到 /dev/dri 或 /dev/mpp_service 硬件加速设备");
        }
        List<Path> devices;
        try (var stream = Files.list(driRoot)) {
            devices = stream.filter(path -> path.getFileName().toString().startsWith("renderD"))
                    .sorted().toList();
        } catch (IOException e) {
            return HardwareStatus.unavailable("无法读取硬件加速设备：" + e.getMessage());
        }
        if (devices.isEmpty()) return HardwareStatus.unavailable("未找到 VAAPI render 设备");

        for (Path device : devices) {
            String vendor = readVendor(device);
            if (vendor == null) continue;
            List<String> codecs = new ArrayList<>();
            if (smokeTest(device, "h264_vaapi")) codecs.add("h264");
            if (smokeTest(device, "hevc_vaapi")) codecs.add("hevc");
            if (!codecs.isEmpty()) {
                return new HardwareStatus(true, vendor, device.toString(), List.copyOf(codecs), "vaapi", null);
            }
        }
        return HardwareStatus.unavailable("检测到 Intel/AMD 设备，但 FFmpeg VAAPI 编码测试失败");
    }

    public HardwareStatus requireAvailable(String codec) {
        HardwareStatus status = detect();
        if (!status.available()) {
            throw new ApiException("HARDWARE_ACCELERATOR_NOT_FOUND", status.reason());
        }
        if (!status.supportedCodecs().contains(codec)) {
            throw new ApiException("HARDWARE_CODEC_UNAVAILABLE",
                    status.vendor() + " 设备不支持 " + codec.toUpperCase(Locale.ROOT) + " 硬件编码");
        }
        return status;
    }

    private String readVendor(Path device) {
        Path vendorFile = drmSysRoot.resolve(device.getFileName()).resolve("device/vendor");
        try {
            String value = Files.readString(vendorFile).trim().toLowerCase(Locale.ROOT);
            return switch (value) {
                case "0x8086" -> "Intel VAAPI";
                case "0x1002" -> "AMD VAAPI";
                default -> null;
            };
        } catch (IOException e) {
            return null;
        }
    }

    private HardwareStatus detectRockchip() {
        if (!Files.exists(rkmppDevice)) return null;
        if (!Files.isReadable(rkmppDevice) || !Files.isWritable(rkmppDevice)) {
            return HardwareStatus.unavailable("检测到瑞芯微 MPP 设备，但应用没有访问权限：" + rkmppDevice);
        }
        List<String> codecs = new ArrayList<>();
        if (smokeTestRkmpp("h264_rkmpp")) codecs.add("h264");
        if (smokeTestRkmpp("hevc_rkmpp")) codecs.add("hevc");
        if (!codecs.isEmpty()) {
            return new HardwareStatus(true, "Rockchip RK MPP", rkmppDevice.toString(),
                    List.copyOf(codecs), "rkmpp", null);
        }
        return HardwareStatus.unavailable("检测到瑞芯微 MPP 设备，但当前 FFmpeg 未提供可用的 h264_rkmpp/hevc_rkmpp 编码器");
    }

    private boolean smokeTest(Path device, String encoder) {
        return run(List.of(ffmpegPath, "-hide_banner", "-loglevel", "error",
                "-vaapi_device", device.toString(), "-f", "lavfi", "-i", "color=size=128x128:rate=1",
                "-vf", "format=nv12,hwupload", "-frames:v", "1", "-c:v", encoder, "-f", "null", "-"));
    }

    private boolean smokeTestRkmpp(String encoder) {
        return run(List.of(ffmpegPath, "-hide_banner", "-loglevel", "error",
                "-f", "lavfi", "-i", "color=size=128x128:rate=1", "-frames:v", "1",
                "-c:v", encoder, "-f", "null", "-"));
    }

    boolean run(List<String> command) {
        try {
            Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
            MediaProcessRunner.Result result = MediaProcessRunner.run(process, Duration.ofSeconds(15));
            return !result.timedOut() && result.exitCode() == 0;
        } catch (IOException e) {
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    public record HardwareStatus(boolean available, String vendor, String device,
                                 List<String> supportedCodecs, String acceleration, String reason) {
        static HardwareStatus unavailable(String reason) {
            return new HardwareStatus(false, null, null, List.of(), null, reason);
        }
    }
}
