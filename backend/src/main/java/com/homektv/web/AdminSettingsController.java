package com.homektv.web;

import com.homektv.library.LibraryWatchService;
import com.homektv.library.SettingService;
import com.homektv.library.TranscodeHardwareService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Admin system settings &amp; hardware transcoding configuration endpoints.
 */
@RestController
@RequestMapping("/api/admin/settings")
public class AdminSettingsController {

    private final SettingService settingService;
    private final TranscodeHardwareService transcodeHardwareService;
    private final LibraryWatchService libraryWatchService;

    public AdminSettingsController(SettingService settingService,
                                   TranscodeHardwareService transcodeHardwareService,
                                   LibraryWatchService libraryWatchService) {
        this.settingService = settingService;
        this.transcodeHardwareService = transcodeHardwareService;
        this.libraryWatchService = libraryWatchService;
    }

    @GetMapping
    public Map<String, Object> getSettings() {
        return settingService.getEditable();
    }

    @PutMapping
    public Map<String, Object> putSettings(@RequestBody Map<String, Object> settings) {
        if (Boolean.TRUE.equals(settings.get("transcode_hardware_acceleration"))) {
            String codec = String.valueOf(settings.getOrDefault("transcode_video_codec",
                    settingService.transcodePolicy().videoCodec())).toLowerCase();
            transcodeHardwareService.requireAvailable(codec);
        }
        settingService.putEditable(settings);
        libraryWatchService.reloadFromSettings();
        return settingService.getEditable();
    }

    @GetMapping("/transcode-hardware")
    public TranscodeHardwareService.HardwareStatus transcodeHardware() {
        return transcodeHardwareService.detect();
    }

    @PostMapping("/transcode-defaults")
    public Map<String, Object> resetTranscodeDefaults() {
        return settingService.resetTranscodeDefaults();
    }
}
