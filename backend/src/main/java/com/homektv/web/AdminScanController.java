package com.homektv.web;

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
import com.homektv.domain.MediaImportRecord;
import com.homektv.web.dto.DashboardDto;
import com.homektv.web.dto.AdminSongDto;
import com.homektv.web.dto.AudioLayoutDto;
import com.homektv.web.dto.AudioLayoutUpdateRequest;
import com.homektv.web.dto.MediaImportRecordDto;
import com.homektv.web.dto.SongDto;
import com.homektv.web.dto.SongEditRequest;
import com.homektv.web.dto.VocalReviewDto;
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
     * 分页查询源库文件列表，支持关键词、状态、格式分析等筛选条件。
     *
     * Paginated query of the source library file list, with filters for keyword, status, and format analysis.
     * @param keyword search keyword
     * @param status import status filter
     * @param formatAnalysis format analysis filter
     * @param sourceDeleted whether the source has been deleted
     * @param page page number (0-based)
     * @param size page size
     * @return paginated source library records
     */
    @GetMapping("/source-library")
    public Map<String, Object> sourceLibrary(@RequestParam(defaultValue = "") String keyword,
                                             @RequestParam(defaultValue = "") String status,
                                             @RequestParam(defaultValue = "") String formatAnalysis,
                                             @RequestParam(required = false) Boolean sourceDeleted,
                                             @RequestParam(defaultValue = "0") int page,
                                             @RequestParam(defaultValue = "20") int size) {
        if (LibraryModePolicy.isExternalReadOnly(props)) {
            return Map.of(
                    "content", List.of(),
                    "total", 0L,
                    "page", page,
                    "totalPages", 0,
                    "libraryMode", props.getLibraryMode().name()
            );
        }
        Page<MediaImportRecord> records = mediaImportService.listSourceLibrary(
                keyword, status, formatAnalysis, sourceDeleted, page, size);
        return Map.of(
                "content", records.getContent().stream().map(MediaImportRecordDto::from).toList(),
                "total", records.getTotalElements(),
                "page", records.getNumber(),
                "totalPages", records.getTotalPages(),
                "libraryMode", props.getLibraryMode().name()
        );
    }

    /**
     * 启动源库文件的转码任务，支持指定 ID 列表或全部待转码文件。
     *
     * Start transcoding for source library files. Supports specific ID list or all pending files.
     * @param request optional body containing file IDs and/or "all" flag
     * @return transcode progress
     */
    @PostMapping("/source-library/transcode")
    public MediaImportService.TranscodeProgress transcodeSourceLibrary(
            @RequestBody(required = false) SourceLibraryRequest request) {
        return mediaImportService.startPendingTranscode(
                request == null ? List.of() : request.ids(),
                request != null && request.all());
    }

    /**
     * 获取源库转码任务的当前进度。
     *
     * Get current progress of the source library transcode task.
     * @return transcode progress details
     */
    @GetMapping("/source-library/progress")
    public MediaImportService.TranscodeProgress sourceLibraryProgress() {
        return mediaImportService.getProgress();
    }

    /**
     * 将指定文件的转码任务提升为优先处理。
     *
     * Prioritize the transcode task for the specified file.
     * @param request request containing the file ID to prioritize
     * @return priority result
     */
    @PostMapping("/source-library/transcode/priority")
    public MediaImportService.PriorityResult prioritizeSourceTranscode(@RequestBody PriorityTranscodeRequest request) {
        return mediaImportService.prioritizeTranscode(request.id());
    }

    /**
     * 清理已导入的源文件。
     *
     * Clean up source files that have already been imported.
     * @return auto cleanup result
     */
    @PostMapping("/source-library/cleanup")
    public MediaImportService.AutoCleanupResult cleanupImportedSources() {
        return mediaImportService.cleanupImportedSources();
    }

    /**
     * 删除源库文件，支持按 ID 列表删除或按条件筛选删除。
     *
     * Delete source library files by ID list or by filter criteria.
     * @param request request containing IDs or filter criteria
     * @return deletion result
     */
    @DeleteMapping("/source-library")
    public MediaImportService.DeleteSourcesResult deleteSources(@RequestBody SourceLibraryRequest request) {
        if (!request.ids().isEmpty()) {
            return mediaImportService.deleteSources(request.ids());
        }
        if (!request.all()) {
            throw new ApiException("DELETE_FILTER_REQUIRED",
                    "按条件删除源文件必须显式传 all=true 或提供 ids");
        }
        return mediaImportService.deleteSourcesByFilter(
                request.keyword(), request.status(), request.formatAnalysis(), request.sourceDeleted());
    }

    /**
     * 分页查询导入记录列表，支持按操作类型筛选。
     *
     * Paginated query of import records, with optional action filter.
     * @param action import action filter
     * @param page page number (0-based)
     * @param size page size
     * @return paginated import records
     */
    @GetMapping("/imports")
    public Map<String, Object> imports(@RequestParam(defaultValue = "") String action,
                                       @RequestParam(defaultValue = "0") int page,
                                       @RequestParam(defaultValue = "20") int size) {
        org.springframework.data.domain.Page<MediaImportRecord> p = mediaImportService.listRecords(action, page, size);
        return Map.of(
                "content", p.getContent().stream().map(MediaImportRecordDto::from).toList(),
                "total", p.getTotalElements(),
                "page", p.getNumber(),
                "totalPages", p.getTotalPages()
        );
    }

    /**
     * 删除单个源文件记录。
     *
     * Delete a single source file record.
     * @param id source record ID
     * @return status confirmation
     */
    @DeleteMapping("/imports/{id}/source")
    public Map<String, Object> deleteSource(@PathVariable Long id) {
        mediaImportService.deleteSource(id);
        return Map.of("status", "deleted", "id", id);
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

    /**
     * 伴奏轨低置信度复核列表：入库判不准原伴唱、需人工核对的文件源。
     *
     * Low-confidence accompaniment track review list: source files whose vocal/accompaniment
     * classification is uncertain and require manual verification.
     * @param page page number (0-based)
     * @param size page size
     * @return paginated vocal review list
     */
    @GetMapping("/vocal-review")
    public Map<String, Object> vocalReview(@RequestParam(defaultValue = "0") int page,
                                           @RequestParam(defaultValue = "20") int size) {
        Page<VocalReviewDto> p = adminService.listVocalReview(page, size);
        return Map.of(
                "content", p.getContent(),
                "total", p.getTotalElements(),
                "page", p.getNumber(),
                "totalPages", p.getTotalPages()
        );
    }

    /**
     * 人工确认伴奏轨 index（0-based），标为高置信度、脱离复核列表。
     *
     * Manually confirm the accompaniment track index (0-based), marking it high-confidence
     * and removing it from the review list.
     * @param fileId source file ID
     * @param body request body containing accompanimentIndex
     * @return confirmation result
     */
    @PutMapping("/files/{fileId}/vocal-track")
    public Map<String, Object> confirmVocalTrack(@PathVariable Long fileId,
                                                 @RequestBody Map<String, Object> body) {
        Object rawIndex = body.getOrDefault("accompanimentIndex", 1);
        int index;
        if (rawIndex instanceof Number num) {
            index = num.intValue();
        } else if (rawIndex instanceof String str) {
            try {
                index = Integer.parseInt(str.trim());
            } catch (NumberFormatException e) {
                throw new ApiException("INVALID_ARGUMENT", "伴奏轨索引必须为非负整数");
            }
        } else {
            throw new ApiException("INVALID_ARGUMENT", "伴奏轨索引类型无效");
        }
        if (index < 0) {
            throw new ApiException("INVALID_ARGUMENT", "伴奏轨索引不能为负数");
        }
        adminService.confirmVocalTrack(fileId, index);
        return Map.of("status", "confirmed", "fileId", fileId, "accompanimentIndex", index);
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
     * 读取设置（P2.6）。
     *
     * Read all settings (P2.6).
     * @return current settings map
     */
    @GetMapping("/settings")
    public Map<String, Object> getSettings() {
        return settingService.getEditable();
    }

    /**
     * 写入设置（P2.6）。
     *
     * Write/update settings (P2.6).
     * @param settings key-value settings to apply
     * @return updated settings map
     */
    @PutMapping("/settings")
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

    /**
     * 检测转码硬件加速能力。
     *
     * Detect transcode hardware acceleration capabilities.
     * @return hardware status including available codecs
     */
    @GetMapping("/settings/transcode-hardware")
    public TranscodeHardwareService.HardwareStatus transcodeHardware() {
        return transcodeHardwareService.detect();
    }

    /**
     * 重置转码配置为默认值。
     *
     * Reset transcode settings to their default values.
     * @return default transcode settings map
     */
    @PostMapping("/settings/transcode-defaults")
    public Map<String, Object> resetTranscodeDefaults() {
        return settingService.resetTranscodeDefaults();
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

    public record ReparseRequest(List<Long> songIds, String rule) {}
    public record MergeSongRequest(Long sourceSongId) {}
    public record PriorityTranscodeRequest(Long id) {}
    public record SongIdsRequest(List<Long> ids) {
        public SongIdsRequest {
            ids = ids == null ? List.of() : ids;
        }
    }
    public record SourceLibraryRequest(List<Long> ids, boolean all, String keyword, String status,
                                       String formatAnalysis, Boolean sourceDeleted) {
        public SourceLibraryRequest {
            ids = ids == null ? List.of() : ids;
        }
    }
}
