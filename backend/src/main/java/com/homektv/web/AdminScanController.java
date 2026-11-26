package com.homektv.web;
import com.homektv.common.ApiException;

import com.homektv.config.AppProperties;
import com.homektv.library.AdminService;
import com.homektv.library.LibraryScanService;
import com.homektv.library.LibraryScanCoordinator;
import com.homektv.library.LibraryModePolicy;
import com.homektv.library.LibraryWatchService;
import com.homektv.library.MediaImportService;
import com.homektv.library.SettingService;
import com.homektv.library.TranscodeService;
import com.homektv.library.TranscodeHardwareService;
import com.homektv.library.SongReparseService;
import com.homektv.library.SongMergeService;
import com.homektv.domain.Song;
import com.homektv.contract.DashboardDto;
import com.homektv.contract.AdminSongDto;
import com.homektv.contract.AudioLayoutDto;
import com.homektv.contract.AudioLayoutUpdateRequest;
import com.homektv.contract.SongDto;
import com.homektv.contract.SongEditRequest;
import org.springframework.data.domain.Page;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 管理后台 API（P2.1-P2.6/P1.8，详设§8/§11.1）。由统一管理会话拦截器保护。
 *
 * Admin backend API (P2.1-P2.6/P1.8, design spec §8/§11.1). Protected by the shared admin session interceptor.
 */
@RestController
@RequestMapping("/api/admin")
public class AdminScanController {

    private final LibraryScanService scanService;
    private final LibraryWatchService libraryWatchService;
    private final AdminService adminService;
    private final MediaImportService mediaImportService;
    private final SettingService settingService;
    private final TranscodeService transcodeService;
    private final TranscodeHardwareService transcodeHardwareService;
    private final SongReparseService reparseService;
    private final SongMergeService songMergeService;
    private final AppProperties props;
    private LibraryScanCoordinator scanCoordinator;
    private com.homektv.library.SongProbeService songProbeService;

    @Autowired
    public AdminScanController(LibraryScanService scanService, LibraryWatchService libraryWatchService,
                               AdminService adminService,
                               MediaImportService mediaImportService,
                               SettingService settingService, TranscodeService transcodeService,
                               TranscodeHardwareService transcodeHardwareService, SongReparseService reparseService,
                               SongMergeService songMergeService, AppProperties props,
                               com.homektv.library.SongProbeService songProbeService) {
        this(scanService, libraryWatchService, adminService, mediaImportService, settingService,
                transcodeService, transcodeHardwareService, reparseService, songMergeService, props);
        this.songProbeService = songProbeService;
    }

    public AdminScanController(LibraryScanService scanService, LibraryWatchService libraryWatchService,
                               AdminService adminService,
                               MediaImportService mediaImportService,
                               SettingService settingService, TranscodeService transcodeService,
                               TranscodeHardwareService transcodeHardwareService, SongReparseService reparseService,
                               SongMergeService songMergeService, AppProperties props) {
        this.scanService = scanService;
        this.libraryWatchService = libraryWatchService;
        this.adminService = adminService;
        this.mediaImportService = mediaImportService;
        this.settingService = settingService;
        this.transcodeService = transcodeService;
        this.transcodeHardwareService = transcodeHardwareService;
        this.reparseService = reparseService;
        this.songMergeService = songMergeService;
        this.props = props;
    }

    /** Optional setter keeps direct controller tests and non-Spring callers backward compatible. */
    @Autowired(required = false)
    void setScanCoordinator(LibraryScanCoordinator scanCoordinator) {
        this.scanCoordinator = scanCoordinator;
    }

    /**
     * 触发全量/增量扫描（P1.8）。
     *
     * Trigger a full/incremental scan (P1.8).
     * @return asynchronous scan progress for an external read-only library
     */
    @PostMapping("/scan")
    public Map<String, Object> scan() {
        if (LibraryModePolicy.isExternalReadOnly(props)) {
            // Fast Index is persisted before the background Media Probe queue;
            // do not hold this HTTP request open for NAS FFprobe work.
            return Map.of("libraryScan", scanCoordinator == null
                    ? scanService.startScan() : scanCoordinator.startScan());
        }
        MediaImportService.SourceScanResult sourceScan = mediaImportService.scanSourceLibrary();
        return Map.of("sourceScan", sourceScan);
    }

    /**
     * 启动源库扫描任务。
     *
     * Start a source library scan task.
     * @return scan progress indicating current status
     */
    @PostMapping("/scan/start")
    public Object startScan() {
        if (LibraryModePolicy.isExternalReadOnly(props)) {
            return scanCoordinator == null ? scanService.startScan() : scanCoordinator.startScan();
        }
        return mediaImportService.startSourceScan();
    }

    /**
     * 获取扫描任务的当前进度。
     *
     * Get current progress of the scan task.
     * @return scan progress details
     */
    @GetMapping("/scan/progress")
    public Object scanProgress() {
        if (LibraryModePolicy.isExternalReadOnly(props)) return scanService.getScanProgress();
        return mediaImportService.getScanProgress();
    }

    /**
     * Confirms a changed external root after operator review and fences stale workers.
     */
    @PostMapping("/scan/rebind")
    public Map<String, Object> rebindExternalRoot() {
        boolean rebound = scanCoordinator != null
                && scanCoordinator.rebindCurrentRoot();
        return Map.of("rebound", rebound);
    }

    /**
     * 为指定歌曲生成 Android TV 兼容的 H.264/AAC 衍生文件；原始文件保持不变。
     *
     * Generate an Android TV compatible H.264/AAC derivative; original remains untouched.
     * @param id song ID
     * @return transcode result with scan info
     */
    @PostMapping("/songs/{id}/transcode")
    public Map<String, Object> transcode(@PathVariable Long id) {
        TranscodeService.Result result = transcodeService.transcodeSong(id);
        LibraryScanService.ScanResult scan = scanService.scanAll();
        int cleaned = Boolean.TRUE.equals(settingService.getAll().get(SettingService.DELETE_SOURCE_AFTER_TRANSCODE))
                ? mediaImportService.cleanupSongSource(id) : 0;
        return Map.of("status", "completed", "sourceFileId", result.sourceFileId(),
                "outputPath", result.outputPath(), "outputBytes", result.outputBytes(),
                "scan", scan, "sourceDeleted", cleaned);
    }

    /**
     * 仪表盘（P2.1）。
     *
     * Dashboard overview (P2.1).
     * @return dashboard statistics
     */
    @GetMapping("/status")
    public DashboardDto status() {
        return adminService.dashboard();
    }

    /**
     * 曲库分页列表 + 类型筛选（P2.2）。
     *
     * Paginated song library list with type filter (P2.2).
     * @param keyword search keyword
     * @param type song type filter
     * @param source source filter
     * @param page page number (0-based)
     * @param size page size
     * @return paginated song list
     */
    @GetMapping("/songs")
    public Map<String, Object> listSongs(@RequestParam(defaultValue = "") String keyword,
                                         @RequestParam(defaultValue = "") String type,
                                         @RequestParam(defaultValue = "") String source,
                                         @RequestParam(defaultValue = "0") int page,
                                         @RequestParam(defaultValue = "20") int size) {
        Page<AdminSongDto> p = adminService.listAdminSongs(keyword, type, source, page, size);
        return Map.of(
                "content", p.getContent(),
                "total", p.getTotalElements(),
                "page", p.getNumber(),
                "totalPages", p.getTotalPages()
        );
    }

    @GetMapping("/songs/{id}")
    public AdminSongDto song(@PathVariable Long id) {
        return adminService.getAdminSong(id);
    }

    /**
     * 编辑曲目（P2.3）。
     *
     * Edit a song (P2.3).
     * @param id song ID
     * @param req edit request body
     * @return updated song DTO
     */
    @PutMapping("/songs/{id}")
    public SongDto editSong(@PathVariable Long id, @RequestBody SongEditRequest req) {
        return SongDto.from(adminService.editSong(id, req));
    }

    /**
     * 删除记录（P2.5，不删 NAS 文件）。
     *
     * Delete a song record (P2.5, NAS files are not deleted).
     * @param id song ID
     * @return deletion confirmation
     */
    @DeleteMapping("/songs/{id}")
    public Map<String, String> deleteSong(@PathVariable Long id) {
        adminService.deleteSong(id);
        return Map.of("status", "deleted");
    }

    /**
     * 批量删除曲目。
     *
     * Batch delete songs by ID list.
     * @param request request containing song IDs to delete
     * @return deletion result with deleted count
     */
    @DeleteMapping("/songs")
    public Map<String, Object> deleteSongs(@RequestBody SongIdsRequest request) {
        return Map.of("status", "deleted", "deleted", adminService.deleteSongs(request.ids()));
    }

    @PostMapping("/songs/{id}/merge")
    public Map<String, Object> mergeSong(@PathVariable Long id, @RequestBody MergeSongRequest request) {
        return songMergeService.merge(id, request.sourceSongId());
    }

    /** Update one file's audio layout semantics without touching the media file. */
    @PutMapping("/files/{fileId}/audio-layout")
    public AudioLayoutDto updateAudioLayout(@PathVariable Long fileId,
                                            @RequestBody AudioLayoutUpdateRequest request) {
        return adminService.updateAudioLayout(fileId, request);
    }

    /** Swap original/accompaniment semantic assignment without reopening or rewriting media. */
    @PostMapping("/files/{fileId}/audio-layout/swap")
    public AudioLayoutDto swapAudioLayout(@PathVariable Long fileId) {
        return adminService.swapAudioLayout(fileId);
    }

    /**
     * 预览歌曲重新解析的结果。
     *
     * Preview the result of re-parsing songs without applying changes.
     * @param request request containing song IDs and parse rule
     * @return preview results for each song
     */
    @PostMapping("/songs/reparse/preview")
    public List<SongReparseService.Preview> previewReparse(@RequestBody ReparseRequest request) {
        return reparseService.preview(request.songIds(), request.rule());
    }

    /**
     * 应用歌曲重新解析并持久化变更。
     *
     * Apply song re-parsing and persist the changes.
     * @param request request containing song IDs and parse rule
     * @return apply result including updated song count
     */
    @PostMapping("/songs/reparse/apply")
    public SongReparseService.ApplyResult applyReparse(@RequestBody ReparseRequest request) {
        return reparseService.apply(request.songIds(), request.rule());
    }

    /**
     * 单曲按需即时探测接口（用于打通未就绪曲目探测与调试）。
     */
    @PostMapping("/songs/{id}/probe")
    public Map<String, Object> probeSong(@PathVariable("id") Long id) {
        if (songProbeService == null) {
            throw new ApiException("PROBE_SERVICE_UNAVAILABLE", "探测服务未就绪");
        }
        boolean success = songProbeService.probeAndPersist(id);
        return Map.of("success", success, "songId", id, "playable", true);
    }

    public record ReparseRequest(List<Long> songIds, String rule) {}
    public record MergeSongRequest(Long sourceSongId) {}
    public record SongIdsRequest(List<Long> ids) {
        public SongIdsRequest {
            ids = ids == null ? List.of() : ids;
        }
    }
}
