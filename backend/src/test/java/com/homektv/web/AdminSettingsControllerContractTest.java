package com.homektv.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.homektv.config.AppProperties;
import com.homektv.diagnostics.MemoryDiagnostics;
import com.homektv.diagnostics.MemoryDiagnosticsService;
import com.homektv.domain.Setting;
import com.homektv.library.AdminService;
import com.homektv.library.LibraryScanService;
import com.homektv.library.LibraryWatchService;
import com.homektv.library.MediaImportService;
import com.homektv.library.SettingService;
import com.homektv.library.SongMergeService;
import com.homektv.library.SongReparseService;
import com.homektv.library.TranscodeHardwareService;
import com.homektv.library.TranscodeService;
import com.homektv.repo.SettingRepository;
import com.homektv.resource.ResourceBudget;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

class AdminSettingsControllerContractTest {

    private SettingRepository repository;
    private SettingService settingService;
    private LibraryWatchService libraryWatchService;
    private AppProperties props;
    private MockMvc settingsMvc;

    private MemoryDiagnosticsService memoryDiagnosticsService;
    private MockMvc memoryMvc;

    private final List<Setting> storedSettings = new ArrayList<>();

    @BeforeEach
    void setUp() {
        storedSettings.clear();
        repository = mock(SettingRepository.class);
        when(repository.findAll()).thenAnswer(inv -> new ArrayList<>(storedSettings));
        when(repository.findById(any())).thenAnswer(inv -> {
            String key = inv.getArgument(0, String.class);
            return storedSettings.stream().filter(s -> s.getKey().equals(key)).findFirst();
        });
        when(repository.save(any(Setting.class))).thenAnswer(inv -> {
            Setting s = inv.getArgument(0, Setting.class);
            storedSettings.removeIf(existing -> existing.getKey().equals(s.getKey()));
            storedSettings.add(s);
            return s;
        });

        props = new AppProperties();
        props.getDiscovery().setInstanceName("Docker客厅KTV");
        props.getDiscovery().setEnabled(true);
        props.setStructuredFilenameMetadataPreferred(true);

        settingService = new SettingService(repository, new ObjectMapper(), props);
        libraryWatchService = mock(LibraryWatchService.class);

        AdminScanController scanController = new AdminScanController(
                mock(LibraryScanService.class),
                libraryWatchService,
                mock(AdminService.class),
                mock(MediaImportService.class),
                settingService,
                mock(TranscodeService.class),
                mock(TranscodeHardwareService.class),
                mock(SongReparseService.class),
                mock(SongMergeService.class),
                props
        );

        settingsMvc = standaloneSetup(scanController)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();

        memoryDiagnosticsService = mock(MemoryDiagnosticsService.class);
        memoryMvc = standaloneSetup(new MemoryDiagnosticsController(memoryDiagnosticsService)).build();
    }

    @Test
    void getSettingsReturnsDockerConfigKeysFromPropertiesWhenNotPersisted() throws Exception {
        settingsMvc.perform(get("/api/admin/settings"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.library_filename_metadata_preferred").value(true))
                .andExpect(jsonPath("$.discovery_name").value("Docker客厅KTV"))
                .andExpect(jsonPath("$.discovery_enabled").value(true));
    }

    @Test
    void putSettingsUpdatesAndPersistsDockerConfigKeys() throws Exception {
        String payload = """
                {
                    "library_filename_metadata_preferred": false,
                    "discovery_name": "VIP包厢",
                    "discovery_enabled": false
                }
                """;

        settingsMvc.perform(put("/api/admin/settings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.library_filename_metadata_preferred").value(false))
                .andExpect(jsonPath("$.discovery_name").value("VIP包厢"))
                .andExpect(jsonPath("$.discovery_enabled").value(false));

        verify(libraryWatchService).reloadFromSettings();

        // 再次 GET 验证已持久化在内存及库中
        settingsMvc.perform(get("/api/admin/settings"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.library_filename_metadata_preferred").value(false))
                .andExpect(jsonPath("$.discovery_name").value("VIP包厢"))
                .andExpect(jsonPath("$.discovery_enabled").value(false));
    }

    @Test
    void putSettingsBlankDiscoveryNameFallbacksToConfiguredOrSystemDefault() throws Exception {
        // 当 properties 配置了实例名时，fallback 到配置名
        String payload = """
                {
                    "discovery_name": "   "
                }
                """;

        settingsMvc.perform(put("/api/admin/settings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.discovery_name").value("Docker客厅KTV"));

        // 当 properties 未配置时，fallback 到系统默认 "家庭KTV"
        props.getDiscovery().setInstanceName("");
        settingsMvc.perform(put("/api/admin/settings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.discovery_name").value("家庭KTV"));
    }

    @Test
    void putSettingsRejectsInternalProtectedKeysWithBadRequest() throws Exception {
        String payload = """
                {
                    "library_mode": "MANAGED",
                    "standby_logo_path": "/etc/passwd",
                    "host_user_id": 999
                }
                """;

        settingsMvc.perform(put("/api/admin/settings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("library_mode")));

        assertThat(storedSettings).noneMatch(s ->
                s.getKey().equals("library_mode") ||
                s.getKey().equals("standby_logo_path") ||
                s.getKey().equals("host_user_id")
        );
    }

    @Test
    void memoryDiagnosticsReturnsHeapMetricsContract() throws Exception {
        when(memoryDiagnosticsService.snapshot()).thenReturn(new MemoryDiagnostics(
                536_870_912L,
                1_073_741_824L,
                2_147_483_648L,
                128L,
                false,
                "IDLE",
                0,
                0,
                new ResourceBudget.Snapshot(
                        new ResourceBudget.WaitSnapshot(0, 0, 0, 0, 0, 0),
                        0,
                        4,
                        new ResourceBudget.WaitSnapshot(0, 0, 0, 0, 0, 0)
                )
        ));

        memoryMvc.perform(get("/api/admin/diagnostics/memory"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.heapUsedBytes").value(536_870_912L))
                .andExpect(jsonPath("$.heapCommittedBytes").value(1_073_741_824L))
                .andExpect(jsonPath("$.heapMaxBytes").value(2_147_483_648L))
                .andExpect(jsonPath("$.scanRunning").value(false))
                .andExpect(jsonPath("$.scanPhase").value("IDLE"));
    }
}
