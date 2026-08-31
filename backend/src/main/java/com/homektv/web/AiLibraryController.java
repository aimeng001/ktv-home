package com.homektv.web;

import com.homektv.ai.AiLibraryService;
import com.homektv.ai.AiSongClassification;
import com.homektv.domain.AiAnalysisTask;
import com.homektv.domain.Playlist;
import com.homektv.domain.Song;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;

/**
 * AI 曲库管理控制器，提供 AI 分析任务、歌单生成与管理的 REST API。
 *
 * AI library management controller, providing REST APIs for AI analysis tasks,
 * playlist generation, and playlist management.
 */
@RestController
@RequestMapping("/api/admin/ai")
public class AiLibraryController {
    private final AiLibraryService service;

    public AiLibraryController(AiLibraryService service) {
        this.service = service;
    }

    /**
     * 查询所有 AI 分析任务列表。
     *
     * Retrieves the list of all AI analysis tasks.
     *
     * @return AI 分析任务列表 / list of AI analysis tasks
     */
    @GetMapping("/tasks")
    public List<AiAnalysisTask> tasks() {
        return service.listTasks();
    }

    /**
     * 为指定歌曲创建 AI 分析任务。
     *
     * Creates an AI analysis task for the specified song.
     *
     * @param request 包含歌曲 ID 的请求体 / request body containing the song ID
     * @return 创建的 AI 分析任务 / the created AI analysis task
     */
    @PostMapping("/tasks")
    public AiAnalysisTask create(@RequestBody CreateTaskRequest request) {
        return service.createTask(request.songId());
    }

    @PostMapping("/tasks/import-records")
    public AiAnalysisTask createImportTask(@RequestBody CreateImportTaskRequest request) {
        return service.createImportTask(request.recordId());
    }

    /**
     * 批量为未分类歌曲创建 AI 分析任务。
     *
     * Batch-creates AI analysis tasks for unclassified songs.
     *
     * @param request 可选请求体，可指定数量上限，默认 50 / optional request body with a limit, defaults to 50
     * @return 包含创建数量和任务列表的 Map / map containing the created count and task list
     */
    @PostMapping("/tasks/unclassified")
    public Map<String, Object> createUnclassified(@RequestBody(required = false) BatchRequest request) {
        int limit = request == null || request.limit() == null ? 50 : request.limit();
        List<AiAnalysisTask> tasks = service.createUnclassifiedTasks(limit);
        return Map.of("created", tasks.size(), "tasks", tasks);
    }

    @PostMapping("/repair")
    public Map<String, Object> repair() { return service.createRepairBatch(); }

    @GetMapping("/repair/{batchId}")
    public Map<String, Object> repairProgress(@PathVariable String batchId) { return service.repairProgress(batchId); }

    @PostMapping("/repair/{batchId}/pause")
    public Map<String, Object> pauseRepair(@PathVariable String batchId) { return service.pauseRepairBatch(batchId); }

    @PostMapping("/repair/{batchId}/resume")
    public Map<String, Object> resumeRepair(@PathVariable String batchId) { return service.resumeRepairBatch(batchId); }

    @PostMapping("/repair/{batchId}/retry-failed")
    public Map<String, Object> retryFailedRepair(@PathVariable String batchId) { return service.retryFailedRepairBatch(batchId); }

    /**
     * 重新执行指定的 AI 分析任务。
     *
     * Retries the specified AI analysis task.
     *
     * @param id 任务 ID / task ID
     * @return 重新创建的 AI 分析任务 / the re-created AI analysis task
     */
    @PostMapping("/tasks/{id}/retry")
    public AiAnalysisTask retry(@PathVariable Long id) {
        return service.retry(id);
    }

    /**
     * 将 AI 分析结果应用到指定任务对应的歌曲。
     *
     * Applies AI classification results to the song associated with the task.
     *
     * @param id     任务 ID / task ID
     * @param result AI 分类结果，可选 / optional AI classification result
     * @return 更新后的歌曲 / the updated song
     */
    @PostMapping("/tasks/{id}/apply")
    public Object apply(@PathVariable Long id, @RequestBody(required = false) AiSongClassification result) {
        return service.apply(id, result);
    }

    /**
     * 根据标签自动生成歌单。
     *
     * Auto-generates a playlist based on the given tag.
     *
     * @param request 包含歌单名称、标签和数量上限的请求体 / request body with playlist name, tag, and limit
     * @return 生成的歌单 / the generated playlist
     */
    @PostMapping("/playlists/generate")
    public Playlist generatePlaylist(@RequestBody GeneratePlaylistRequest request) {
        return service.generatePlaylist(request.name(), request.tag(), request.limit() == null ? 100 : request.limit());
    }

    @PostMapping("/playlists/preview")
    public Map<String, Object> previewPlaylist(@RequestBody PlaylistPreviewRequest request) {
        return service.previewPlaylist(request.instruction(), request.limit() == null ? 100 : request.limit());
    }

    /**
     * 查询所有歌单列表。
     *
     * Retrieves the list of all playlists.
     *
     * @return 歌单列表 / playlist list
     */
    @GetMapping("/playlists")
    public List<Map<String, Object>> playlists() {
        return service.listPlaylists();
    }

    /**
     * 查询指定歌单的详细信息。
     *
     * Retrieves the detail of a specific playlist.
     *
     * @param id 歌单 ID / playlist ID
     * @return 歌单详细信息 / playlist detail
     */
    @GetMapping("/playlists/{id}")
    public Map<String, Object> playlist(@PathVariable Long id) {
        return service.playlistDetail(id);
    }

    /**
     * 创建新歌单。
     *
     * Creates a new playlist.
     *
     * @param request 包含歌单名称、描述、主题和公开可见性的请求体 / request body with name, description, theme, and visibility
     * @return 创建的歌单 / the created playlist
     */
    @PostMapping("/playlists")
    public Playlist createPlaylist(@RequestBody SavePlaylistRequest request) {
        return service.savePlaylist(null, request.name(), request.description(), request.theme(), request.publicVisible());
    }

    @PostMapping("/playlists/from-preview")
    public Playlist savePlaylistFromPreview(@RequestBody SavePlaylistFromPreviewRequest request) {
        return service.savePlaylistFromPreview(request.name(), request.description(), request.theme(),
                request.publicVisible() == null || request.publicVisible(), request.songIds());
    }

    /**
     * 更新指定歌单的信息。
     *
     * Updates the specified playlist.
     *
     * @param id      歌单 ID / playlist ID
     * @param request 包含歌单名称、描述、主题和公开可见性的请求体 / request body with name, description, theme, and visibility
     * @return 更新后的歌单 / the updated playlist
     */
    @PutMapping("/playlists/{id}")
    public Playlist updatePlaylist(@PathVariable Long id, @RequestBody SavePlaylistRequest request) {
        return service.savePlaylist(id, request.name(), request.description(), request.theme(), request.publicVisible());
    }

    /**
     * 删除指定歌单。
     *
     * Deletes the specified playlist.
     *
     * @param id 歌单 ID / playlist ID
     */
    @DeleteMapping("/playlists/{id}")
    public void deletePlaylist(@PathVariable Long id) {
        service.deletePlaylist(id);
    }

    /**
     * 向指定歌单添加歌曲。
     *
     * Adds a song to the specified playlist.
     *
     * @param id      歌单 ID / playlist ID
     * @param request 包含歌曲 ID 的请求体 / request body containing the song ID
     * @return 包含操作结果的 Map / map containing the operation result
     */
    @PostMapping("/playlists/{id}/songs")
    public Map<String, Object> addSong(@PathVariable Long id, @RequestBody PlaylistSongRequest request) {
        return service.addPlaylistSong(id, request.songId());
    }

    /**
     * 从指定歌单移除歌曲。
     *
     * Removes a song from the specified playlist.
     *
     * @param id     歌单 ID / playlist ID
     * @param songId 歌曲 ID / song ID
     */
    @DeleteMapping("/playlists/{id}/songs/{songId}")
    public void removeSong(@PathVariable Long id, @PathVariable Long songId) {
        service.removePlaylistSong(id, songId);
    }

    /**
     * 为指定歌单上传封面图片。
     *
     * Uploads a cover image for the specified playlist.
     *
     * @param id   歌单 ID / playlist ID
     * @param file 封面图片文件 / cover image file
     * @return 更新后的歌单 / the updated playlist
     */
    @PostMapping(value = "/playlists/{id}/cover", consumes = "multipart/form-data")
    public Playlist uploadCover(@PathVariable Long id, @RequestPart("file") MultipartFile file) {
        return service.uploadPlaylistCover(id, file);
    }

    /**
     * 调整指定歌单中歌曲的排序。
     *
     * Reorders songs within the specified playlist.
     *
     * @param id      歌单 ID / playlist ID
     * @param request 包含歌曲 ID 顺序列表的请求体 / request body containing the ordered song ID list
     * @return 包含操作结果的 Map / map containing the operation result
     */
    @PutMapping("/playlists/{id}/songs/order")
    public Map<String, Object> reorderSongs(@PathVariable Long id, @RequestBody ReorderSongsRequest request) {
        return service.reorderPlaylistSongs(id, request.songIds());
    }

    public record CreateTaskRequest(Long songId) {}
    public record CreateImportTaskRequest(Long recordId) {}
    public record BatchRequest(Integer limit) {}
    public record GeneratePlaylistRequest(String name, String tag, Integer limit) {}
    public record PlaylistPreviewRequest(String instruction, Integer limit) {}
    public record SavePlaylistRequest(String name, String description, String theme, boolean publicVisible) {}
    public record SavePlaylistFromPreviewRequest(String name, String description, String theme,
                                                 Boolean publicVisible, List<Long> songIds) {}
    public record PlaylistSongRequest(Long songId) {}
    public record ReorderSongsRequest(List<Long> songIds) {}
}
