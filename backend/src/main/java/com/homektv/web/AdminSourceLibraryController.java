package com.homektv.web;

import com.homektv.common.ApiException;
import com.homektv.config.AppProperties;
import com.homektv.contract.MediaImportRecordDto;
import com.homektv.domain.MediaImportRecord;
import com.homektv.library.LibraryModePolicy;
import com.homektv.library.MediaImportService;
import org.springframework.data.domain.Page;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * Managed source-library inspection, transcode queue, and import cleanup endpoints.
 */
@RestController
@RequestMapping("/api/admin")
public class AdminSourceLibraryController {

    private final MediaImportService mediaImportService;
    private final AppProperties props;

    public AdminSourceLibraryController(MediaImportService mediaImportService, AppProperties props) {
        this.mediaImportService = mediaImportService;
        this.props = props;
    }

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

    @PostMapping("/source-library/transcode")
    public MediaImportService.TranscodeProgress transcodeSourceLibrary(
            @RequestBody(required = false) SourceLibraryRequest request) {
        return mediaImportService.startPendingTranscode(
                request == null ? List.of() : request.ids(),
                request != null && request.all());
    }

    @GetMapping("/source-library/progress")
    public MediaImportService.TranscodeProgress sourceLibraryProgress() {
        return mediaImportService.getProgress();
    }

    @PostMapping("/source-library/transcode/priority")
    public MediaImportService.PriorityResult prioritizeSourceTranscode(@RequestBody PriorityTranscodeRequest request) {
        return mediaImportService.prioritizeTranscode(request.id());
    }

    @PostMapping("/source-library/cleanup")
    public MediaImportService.AutoCleanupResult cleanupImportedSources() {
        return mediaImportService.cleanupImportedSources();
    }

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

    @GetMapping("/imports")
    public Map<String, Object> imports(@RequestParam(defaultValue = "") String action,
                                       @RequestParam(defaultValue = "0") int page,
                                       @RequestParam(defaultValue = "20") int size) {
        Page<MediaImportRecord> p = mediaImportService.listRecords(action, page, size);
        return Map.of(
                "content", p.getContent().stream().map(MediaImportRecordDto::from).toList(),
                "total", p.getTotalElements(),
                "page", p.getNumber(),
                "totalPages", p.getTotalPages()
        );
    }

    @DeleteMapping("/imports/{id}/source")
    public Map<String, Object> deleteSource(@PathVariable Long id) {
        mediaImportService.deleteSource(id);
        return Map.of("status", "deleted", "id", id);
    }

    public record PriorityTranscodeRequest(Long id) {}

    public record SourceLibraryRequest(List<Long> ids, boolean all, String keyword, String status,
                                       String formatAnalysis, Boolean sourceDeleted) {
        public SourceLibraryRequest {
            ids = ids == null ? List.of() : ids;
        }
    }
}
