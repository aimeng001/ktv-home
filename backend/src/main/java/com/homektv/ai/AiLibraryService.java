package com.homektv.ai;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.homektv.domain.AiAnalysisTask;
import com.homektv.domain.Playlist;
import com.homektv.domain.PlaylistSong;
import com.homektv.domain.Song;
import com.homektv.repo.AiAnalysisTaskRepository;
import com.homektv.repo.PlaylistRepository;
import com.homektv.repo.PlaylistSongRepository;
import com.homektv.repo.SongRepository;
import com.homektv.repo.MediaImportRecordRepository;
import com.homektv.domain.MediaImportRecord;
import com.homektv.web.ApiException;
import org.springframework.stereotype.Service;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import com.homektv.library.AssetWriter;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;

import java.io.IOException;

import java.util.*;
import java.util.function.Consumer;

/**
 * AI 歌库服务 —— 管理 AI 分析任务、AI 歌单生成与分类结果应用。
 *
 * AI library service — manages AI analysis tasks, AI-generated playlists, and classification result application.
 */
@Service
public class AiLibraryService {
    private static final List<String> ACTIVE_STATUSES = List.of("pending", "processing", "review");
    private static final int SONG_PAGE_SIZE = 500;
    private static final int MAX_PLAYLIST_CANDIDATES = 5000;
    public static final int MAX_PLAYLIST_SONGS = 100;

    private final AiAnalysisTaskRepository taskRepository;
    private final SongRepository songRepository;
    private final PlaylistRepository playlistRepository;
    private final PlaylistSongRepository playlistSongRepository;
    private final AiAnalysisWorker worker;
    private final ObjectMapper objectMapper;
    private final AiConfigService configService;
    private final AssetWriter assetWriter;
    private final AiClassificationApplier classificationApplier;
    private final OpenAiCompatibleClient aiClient;
    private final MediaImportRecordRepository importRecordRepository;

    public AiLibraryService(AiAnalysisTaskRepository taskRepository, SongRepository songRepository,
                            PlaylistRepository playlistRepository, PlaylistSongRepository playlistSongRepository,
                            AiAnalysisWorker worker, ObjectMapper objectMapper, AiConfigService configService,
                            AssetWriter assetWriter, AiClassificationApplier classificationApplier,
                            OpenAiCompatibleClient aiClient, MediaImportRecordRepository importRecordRepository) {
        this.taskRepository = taskRepository;
        this.songRepository = songRepository;
        this.playlistRepository = playlistRepository;
        this.playlistSongRepository = playlistSongRepository;
        this.worker = worker;
        this.objectMapper = objectMapper;
        this.configService = configService;
        this.assetWriter = assetWriter;
        this.classificationApplier = classificationApplier;
        this.aiClient = aiClient;
        this.importRecordRepository = importRecordRepository;
    }

    /**
     * 为指定歌曲创建 AI 分析任务；若该歌曲已有进行中的任务则抛出异常。
     *
     * Creates an AI analysis task for the given song; throws if an active task already exists.
     *
     * @param songId 歌曲 ID / song ID
     * @return 创建的 AI 分析任务 / the created AI analysis task
     */
    public AiAnalysisTask createTask(Long songId) {
        songRepository.findById(songId).orElseThrow(() -> new ApiException("SONG_NOT_FOUND", "歌曲不存在"));
        if (taskRepository.existsBySongIdAndStatusIn(songId, ACTIVE_STATUSES)) {
            throw new ApiException("AI_TASK_EXISTS", "该歌曲已有待处理的 AI 任务");
        }
        AiAnalysisTask task = new AiAnalysisTask();
        task.setSongId(songId);
        AiConfigService.ResolvedConfig config = configService.resolve();
        task.setModel(configService.isConfigured() ? config.bulkModel() : "LOCAL");
        task.setModelRole(configService.isConfigured() ? "BULK" : "LOCAL");
        task.setTargetType("SONG");
        task.setTargetId(songId);
        try {
            task = taskRepository.saveAndFlush(task);
        } catch (DataIntegrityViolationException conflict) {
            if (taskRepository.findFirstBySongIdAndStatusInOrderByCreatedAtDesc(songId, ACTIVE_STATUSES).isPresent()) {
                throw new ApiException("AI_TASK_EXISTS", "该歌曲已有待处理的 AI 任务");
            }
            throw conflict;
        }
        worker.analyze(task.getId());
        return task;
    }

    public AiAnalysisTask createImportTask(Long recordId) {
        importRecordRepository.findById(recordId)
                .orElseThrow(() -> new ApiException("IMPORT_RECORD_NOT_FOUND", "导入记录不存在"));
        if (taskRepository.existsByTargetTypeAndTargetIdAndStatusIn("IMPORT_RECORD", recordId, ACTIVE_STATUSES))
            throw new ApiException("AI_TASK_EXISTS", "该导入记录已有待处理的 AI 任务");
        AiConfigService.ResolvedConfig config = configService.resolve();
        AiAnalysisTask task = new AiAnalysisTask();
        task.setTargetType("IMPORT_RECORD");
        task.setTargetId(recordId);
        task.setSongId(null);
        task.setModel(configService.isConfigured() ? config.bulkModel() : "LOCAL");
        task.setModelRole(configService.isConfigured() ? "BULK" : "LOCAL");
        task = taskRepository.saveAndFlush(task);
        worker.analyze(task.getId());
        return task;
    }

    /**
     * 为所有尚未分析的歌曲批量创建 AI 分类任务，上限受参数控制（最大 500）。
     *
     * Batch-creates AI classification tasks for all unanalyzed songs, capped by the parameter (max 500).
     *
     * @param limit 任务数量上限 / maximum number of tasks
     * @return 创建的任务列表 / list of created tasks
     */
    public List<AiAnalysisTask> createUnclassifiedTasks(int limit) {
        int safeLimit = Math.max(1, Math.min(limit, 500));
        List<AiAnalysisTask> result = new ArrayList<>();
        for (int pageNumber = 0; result.size() < safeLimit; pageNumber++) {
            Page<Song> page = songPage(pageNumber);
            if (page == null || page.isEmpty()) break;
            for (Song song : page.getContent()) {
                if (result.size() >= safeLimit) break;
                if (song.getAiAnalyzedAt() == null
                        && !taskRepository.existsBySongIdAndStatusIn(song.getId(), ACTIVE_STATUSES)) {
                    result.add(createTask(song.getId()));
                }
            }
            if (!page.hasNext()) break;
        }
        return result;
    }

    /** Create a repair batch for every existing song, including already analyzed songs. */
    public Map<String, Object> createRepairBatch() {
        String batchId = UUID.randomUUID().toString();
        AiConfigService.ResolvedConfig config = configService.resolve();
        int created = 0;
        for (int pageNumber = 0; ; pageNumber++) {
            Page<Song> page = songPage(pageNumber);
            if (page == null || page.isEmpty()) break;
            for (Song song : page.getContent()) {
                AiAnalysisTask task = new AiAnalysisTask();
                task.setSongId(song.getId());
                task.setTargetId(song.getId());
                task.setTargetType("SONG");
                task.setBatchId(batchId);
                task.setModel(configService.isConfigured() ? config.bulkModel() : "LOCAL");
                task.setModelRole(configService.isConfigured() ? "BULK" : "LOCAL");
                taskRepository.saveAndFlush(task);
                worker.analyze(task.getId());
                created++;
            }
            if (!page.hasNext()) break;
        }
        return Map.of("batchId", batchId, "created", created);
    }

    @Transactional
    public Map<String, Object> pauseRepairBatch(String batchId) {
        List<AiAnalysisTask> tasks = taskRepository.findByBatchIdOrderByCreatedAtAsc(batchId);
        if (tasks.isEmpty()) throw new ApiException("AI_BATCH_NOT_FOUND", "修复批次不存在");
        int changed = 0;
        for (AiAnalysisTask task : tasks) {
            if (Set.of("pending", "processing").contains(task.getStatus())) {
                task.setStatus("paused");
                changed++;
            }
        }
        taskRepository.saveAll(tasks);
        return repairProgress(batchId);
    }

    @Transactional
    public Map<String, Object> resumeRepairBatch(String batchId) {
        List<AiAnalysisTask> tasks = taskRepository.findByBatchIdOrderByCreatedAtAsc(batchId);
        if (tasks.isEmpty()) throw new ApiException("AI_BATCH_NOT_FOUND", "修复批次不存在");
        int resumed = 0;
        for (AiAnalysisTask task : tasks) {
            if ("paused".equals(task.getStatus())) {
                task.setStatus("pending");
                taskRepository.saveAndFlush(task);
                worker.analyze(task.getId());
                resumed++;
            }
        }
        return Map.of("batchId", batchId, "resumed", resumed);
    }

    @Transactional
    public Map<String, Object> retryFailedRepairBatch(String batchId) {
        List<AiAnalysisTask> tasks = taskRepository.findByBatchIdOrderByCreatedAtAsc(batchId);
        if (tasks.isEmpty()) throw new ApiException("AI_BATCH_NOT_FOUND", "修复批次不存在");
        int retried = 0;
        for (AiAnalysisTask task : tasks) {
            if ("failed".equals(task.getStatus())) {
                task.setStatus("pending");
                taskRepository.saveAndFlush(task);
                worker.analyze(task.getId());
                retried++;
            }
        }
        return Map.of("batchId", batchId, "retried", retried);
    }

    @Transactional(readOnly = true)
    public Map<String, Object> repairProgress(String batchId) {
        List<AiAnalysisTask> tasks = taskRepository.findByBatchIdOrderByCreatedAtAsc(batchId);
        long completed = tasks.stream().filter(t -> Set.of("auto_applied", "review", "failed", "applied").contains(t.getStatus())).count();
        return Map.of("batchId", batchId, "total", tasks.size(), "completed", completed,
                "failed", tasks.stream().filter(t -> "failed".equals(t.getStatus())).count(),
                "review", tasks.stream().filter(t -> "review".equals(t.getStatus())).count(),
                "paused", tasks.stream().filter(t -> "paused".equals(t.getStatus())).count(),
                "running", tasks.stream().anyMatch(t -> Set.of("pending", "processing").contains(t.getStatus())));
    }

    /** Preview an AI-curated playlist. No playlist or playlist-song rows are written here. */
    public Map<String, Object> previewPlaylist(String instruction, int limit) {
        try {
            configService.requireConfigured();
        } catch (ApiException missingConfiguration) {
            throw new ApiException("AI_PLAYLIST_REQUIRES_MODEL", "自然语言主题歌单没有可靠的本地替代方案，请先配置 AI 模型");
        }
        if (instruction == null || instruction.isBlank()) throw new ApiException("INVALID_ARGUMENT", "请描述想要的歌单");
        int maximum = playlistLimit(limit);
        List<Song> candidates = playlistCandidates();
        if (candidates.isEmpty()) throw new ApiException("PLAYLIST_CANDIDATES_EMPTY", "KTV 曲库中没有可用于生成歌单的歌曲");
        int selectionMaximum = Math.min(maximum, candidates.size());
        List<Map<String, Object>> candidateValues = candidates.stream().map(this::playlistCandidate).toList();
        String candidateJson = json(candidateValues);
        long metadataEnrichedCount = candidateValues.stream()
                .filter(value -> Boolean.TRUE.equals(value.get("metadataScraped"))).count();
        JsonNode intent = aiClient.completeJsonPromptOnly("BULK",
                "你是家庭 KTV 歌单需求分析器。只返回 JSON，不要 Markdown。输出 intent（自然语言策划意图）、languages(string[]), genres(string[]), themes(string[]), eras(string[]), artists(string[]), mood(string), diversity(string), maxSongs(number)。不要选择歌曲，不要编造歌曲 ID。",
                "用户策划要求：" + instruction + "\n歌单歌曲上限：" + selectionMaximum
                        + "。这是上限，不要求凑满，可以生成更少歌曲。", 900);
        String selectionPrompt = "策划意图：" + intent + "\n最多选择 " + selectionMaximum
                + " 首。这是数量上限，不是必须达到的数量；只选真正符合要求的歌曲，可以少于上限。"
                + "metadataScraped=true 表示专辑、发行时间、别名等字段已经过外部平台刮削或人工复核，"
                + "metadataSources 表示各字段的可信来源，应优先作为选曲依据。只能从候选中返回 ID，按播放顺序排列；"
                + "候选外、重复或无效 ID 都不要返回。候选：" + candidateJson;
        String selectionSystemPrompt =
                "你是家庭 KTV 歌单策划器。根据结构化策划意图以及候选歌曲的可信刮削元数据选歌。"
                        + "优先使用 metadataSources 标记的可信字段，并结合专辑、发行时间、别名、时长、语种、演唱形式、曲风和主题判断；"
                        + "缺失字段只能谨慎推断。只返回紧凑 JSON：name(string), description(string), songIds(number[]), confidence(number)。"
                        + "不要输出逐首理由。songIds 只能来自候选，去重，不超过上限；不要求凑满上限。";
        String reasoningModel = configService.resolve().reasoningModel();
        boolean reasoningUsed = false;
        JsonNode selection;
        try {
            selection = aiClient.completeJsonPromptOnly("BULK", selectionSystemPrompt, selectionPrompt, 3200);
        } catch (OpenAiCompatibleClient.AiProviderException failure) {
            if (reasoningModel == null || reasoningModel.isBlank()
                    || !Set.of("AI_JSON_INVALID", "AI_EMPTY_RESPONSE").contains(failure.getCode())) throw failure;
            selection = aiClient.completeJsonPromptOnly("REASONING", selectionSystemPrompt, selectionPrompt, 6000);
            reasoningUsed = true;
        }
        JsonNode playlistResult = playlistResult(selection);
        if (!reasoningUsed && !hasPlaylistSelection(playlistResult) && reasoningModel != null && !reasoningModel.isBlank()) {
            selection = aiClient.completeJsonPromptOnly("REASONING", selectionSystemPrompt, selectionPrompt, 6000);
            playlistResult = playlistResult(selection);
        }
        Set<Long> allowed = candidates.stream().map(Song::getId).collect(java.util.stream.Collectors.toSet());
        List<Long> ids = playlistSongIds(playlistResult, allowed, selectionMaximum);
        Map<Long, Song> songsById = candidates.stream().collect(java.util.stream.Collectors.toMap(Song::getId, song -> song));
        List<Map<String, Object>> selectedSongs = ids.stream().map(songsById::get).filter(Objects::nonNull)
                .map(song -> Map.<String, Object>of("id", song.getId(), "title", song.getTitle(), "artist", song.getArtist()))
                .toList();
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("name", firstText(playlistResult, "name", "playlistName", "title", "AI 歌单"));
        response.put("description", firstText(playlistResult, "description", "summary", "reason", ""));
        response.put("songIds", ids);
        response.put("songs", selectedSongs);
        response.put("reasons", firstNode(playlistResult, "reasons", "selectionReasons"));
        response.put("intent", intent);
        response.put("limit", maximum);
        response.put("selectedCount", ids.size());
        response.put("candidateCount", candidates.size());
        response.put("metadataEnrichedCount", metadataEnrichedCount);
        return response;
    }

    private int playlistLimit(int requested) {
        return Math.max(1, Math.min(requested, MAX_PLAYLIST_SONGS));
    }

    JsonNode playlistResult(JsonNode result) {
        if (result == null || result.isMissingNode() || result.isNull()) return objectMapper.createObjectNode();
        for (String key : List.of("playlist", "result", "data")) {
            JsonNode nested = result.path(key);
            if (nested.isObject() && hasPlaylistSelection(nested)) return nested;
        }
        return result;
    }

    List<Long> playlistSongIds(JsonNode result, Set<Long> allowed, int maximum) {
        JsonNode values = firstNode(result, "songIds", "selectedSongIds", "ids", "songs", "selectedSongs", "tracks");
        List<Long> ids = new ArrayList<>();
        if (!values.isArray()) return ids;
        for (JsonNode value : values) {
            long id = value.isObject() ? firstLong(value, "id", "songId", "song_id") : value.asLong(-1);
            if (allowed.contains(id) && !ids.contains(id) && ids.size() < maximum) ids.add(id);
        }
        return ids;
    }

    private boolean hasPlaylistSelection(JsonNode node) {
        return List.of("songIds", "selectedSongIds", "ids", "songs", "selectedSongs", "tracks")
                .stream().anyMatch(node::has);
    }

    private JsonNode firstNode(JsonNode node, String... keys) {
        if (node != null) for (String key : keys) if (node.has(key) && !node.path(key).isNull()) return node.path(key);
        return objectMapper.createArrayNode();
    }

    private long firstLong(JsonNode node, String... keys) {
        for (String key : keys) if (node.has(key)) return node.path(key).asLong(-1);
        return -1;
    }

    private String firstText(JsonNode node, String first, String second, String third, String fallback) {
        for (String key : List.of(first, second, third)) {
            String value = node.path(key).asText("").strip();
            if (!value.isBlank()) return value;
        }
        return fallback;
    }

    Map<String, Object> playlistCandidate(Song song) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("id", song.getId());
        value.put("title", Objects.toString(song.getTitle(), ""));
        value.put("artist", Objects.toString(song.getArtist(), ""));
        putText(value, "language", song.getLanguage(), "未知");
        putText(value, "vocalForm", song.getVocalForm(), "未知");
        putText(value, "album", song.getAlbum(), null);
        putText(value, "releaseDate", song.getReleaseDate(), null);
        putValues(value, "aliases", song.getAliases());
        putValues(value, "tags", song.getTags());
        putValues(value, "genres", song.getAiGenres());
        putValues(value, "themes", song.getAiThemes());
        putText(value, "era", song.getAiEra(), null);
        if (song.getDurationMs() > 0) value.put("durationSeconds", Math.round(song.getDurationMs() / 1000.0));
        Map<String, String> sources = trustedMetadataSources(song.getMetadataProvenance());
        value.put("metadataScraped", !sources.isEmpty());
        if (!sources.isEmpty()) value.put("metadataSources", sources);
        return value;
    }

    private Map<String, String> trustedMetadataSources(String provenanceJson) {
        if (provenanceJson == null || provenanceJson.isBlank()) return Map.of();
        try {
            JsonNode provenance = objectMapper.readTree(provenanceJson);
            Map<String, String> sources = new LinkedHashMap<>();
            provenance.fields().forEachRemaining(field -> {
                JsonNode evidence = field.getValue();
                String source = evidence.path("source").asText("").strip();
                if (evidence.path("trusted").asBoolean(false) && !source.isBlank()) sources.put(field.getKey(), source);
            });
            return sources;
        } catch (JsonProcessingException ignored) {
            return Map.of();
        }
    }

    private void putText(Map<String, Object> target, String key, String value, String ignoredValue) {
        if (value == null || value.isBlank() || Objects.equals(value, ignoredValue)) return;
        target.put(key, value);
    }

    private void putValues(Map<String, Object> target, String key, String[] values) {
        if (values == null) return;
        List<String> present = Arrays.stream(values).filter(Objects::nonNull).map(String::strip)
                .filter(value -> !value.isBlank()).distinct().toList();
        if (!present.isEmpty()) target.put(key, present);
    }

    private String json(Object value) {
        try { return objectMapper.writeValueAsString(value == null ? "" : value); }
        catch (JsonProcessingException e) { return "\"\""; }
    }

    /**
     * 列出最近的 AI 分析任务（最新 100 条）。
     *
     * Lists the most recent AI analysis tasks (up to 100).
     *
     * @return AI 分析任务列表 / list of AI analysis tasks
     */
    public List<AiAnalysisTask> listTasks() {
        List<AiAnalysisTask> tasks = taskRepository.findTop100ByOrderByCreatedAtDesc();
        Map<Long, Song> songs = new HashMap<>();
        songRepository.findAllById(tasks.stream().map(AiAnalysisTask::getSongId).filter(Objects::nonNull).toList())
                .forEach(song -> songs.put(song.getId(), song));
        Map<Long, MediaImportRecord> imports = new HashMap<>();
        importRecordRepository.findAllById(tasks.stream()
                        .filter(task -> "IMPORT_RECORD".equals(task.getTargetType()))
                        .map(AiAnalysisTask::getTargetId).filter(Objects::nonNull).toList())
                .forEach(record -> imports.put(record.getId(), record));
        tasks.forEach(task -> {
            if ("IMPORT_RECORD".equals(task.getTargetType())) {
                MediaImportRecord record = imports.get(task.getTargetId());
                if (record != null) {
                    task.setTargetTitle(firstNonBlank(record.getParsedTitle(), record.getSourceFilename(), "导入记录"));
                    task.setTargetArtist(firstNonBlank(record.getParsedArtist(), "待识别歌手"));
                }
            } else {
                Song song = songs.get(task.getSongId());
                if (song != null) {
                    task.setTargetTitle(firstNonBlank(song.getTitle(), "未命名歌曲"));
                    task.setTargetArtist(firstNonBlank(song.getArtist(), "未知歌手"));
                }
            }
        });
        return tasks;
    }

    private String firstNonBlank(String... values) {
        for (String value : values) if (value != null && !value.isBlank()) return value;
        return "";
    }

    /**
     * 列出所有歌单，返回含歌曲数量、手动数量等摘要信息的列表。
     *
     * Lists all playlists with summary info including song count and manual count.
     *
     * @return 歌单摘要列表 / list of playlist summaries
     */
    public List<Map<String, Object>> listPlaylists() {
        return playlistRepository.findAllByOrderByUpdatedAtDesc().stream().map(playlist -> {
            List<PlaylistSong> items = playlistSongRepository.findByPlaylistIdOrderBySortOrder(playlist.getId());
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("id", playlist.getId());
            value.put("name", playlist.getName());
            value.put("description", playlist.getDescription());
            value.put("theme", playlist.getTheme());
            value.put("coverUrl", playlist.getCoverPath() == null ? null : "/api/playlists/" + playlist.getId() + "/cover");
            value.put("publicVisible", playlist.isPublicVisible());
            value.put("aiGenerated", playlist.isAiGenerated());
            value.put("songCount", items.size());
            value.put("manualCount", items.stream().filter(PlaylistSong::isManual).count());
            value.put("updatedAt", playlist.getUpdatedAt());
            return value;
        }).toList();
    }

    /**
     * 获取歌单详情，包含歌曲列表及每首歌的标题、歌手等信息。
     *
     * Gets playlist details including the song list with title, artist, etc.
     *
     * @param playlistId 歌单 ID / playlist ID
     * @return 歌单详情 map / playlist detail map
     */
    public Map<String, Object> playlistDetail(Long playlistId) {
        Playlist playlist = requirePlaylist(playlistId);
        List<Map<String, Object>> songs = playlistSongRepository.findByPlaylistIdOrderBySortOrder(playlistId).stream()
                .map(item -> {
                    Song song = songRepository.findById(item.getSongId()).orElse(null);
                    Map<String, Object> value = new LinkedHashMap<>();
                    value.put("songId", item.getSongId());
                    value.put("title", song == null ? "歌曲已删除" : song.getTitle());
                    value.put("artist", song == null ? "" : song.getArtist());
                    value.put("manual", item.isManual());
                    value.put("sortOrder", item.getSortOrder());
                    return value;
                }).toList();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", playlist.getId());
        result.put("name", playlist.getName());
        result.put("description", playlist.getDescription());
        result.put("theme", playlist.getTheme());
        result.put("coverPath", playlist.getCoverPath());
        result.put("coverUrl", playlist.getCoverPath() == null ? null : "/api/playlists/" + playlist.getId() + "/cover");
        result.put("publicVisible", playlist.isPublicVisible());
        result.put("aiGenerated", playlist.isAiGenerated());
        result.put("aiRule", playlist.getAiRule());
        result.put("songs", songs);
        return result;
    }

    /**
     * 创建或更新歌单；名称为空时抛出异常。
     *
     * Creates or updates a playlist; throws when the name is blank.
     *
     * @param playlistId 歌单 ID，为 null 时新建 / playlist ID, create new when null
     * @param name 歌单名称 / playlist name
     * @param description 描述 / description
     * @param theme 主题标签 / theme tag
     * @param publicVisible 是否公开可见 / whether publicly visible
     * @return 保存后的歌单实体 / the saved playlist entity
     */
    @Transactional
    public Playlist savePlaylist(Long playlistId, String name, String description, String theme, boolean publicVisible) {
        if (name == null || name.isBlank()) throw new ApiException("INVALID_ARGUMENT", "歌单名称不能为空");
        Playlist playlist = playlistId == null ? new Playlist() : requirePlaylist(playlistId);
        playlist.setName(name.trim());
        playlist.setDescription(description == null ? "" : description.trim());
        playlist.setTheme(theme == null || theme.isBlank() ? null : theme.trim());
        playlist.setPublicVisible(publicVisible);
        return playlistRepository.save(playlist);
    }

    /**
     * 删除指定歌单。
     *
     * Deletes the specified playlist.
     *
     * @param playlistId 歌单 ID / playlist ID
     */
    @Transactional
    public void deletePlaylist(Long playlistId) {
        playlistRepository.delete(requirePlaylist(playlistId));
    }

    /**
     * 向歌单中添加一首歌曲；已存在的歌曲会被忽略。
     *
     * Adds a song to the playlist; silently ignored if the song is already present.
     *
     * @param playlistId 歌单 ID / playlist ID
     * @param songId 歌曲 ID / song ID
     * @return 更新后的歌单详情 / updated playlist detail
     */
    @Transactional
    public Map<String, Object> addPlaylistSong(Long playlistId, Long songId) {
        requirePlaylist(playlistId);
        Song song = songRepository.findById(songId).orElseThrow(() -> new ApiException("SONG_NOT_FOUND", "歌曲不存在"));
        playlistSongRepository.lockPlaylist(playlistId);
        List<PlaylistSong> current = playlistSongRepository.findByPlaylistIdOrderBySortOrder(playlistId);
        if (current.stream().anyMatch(item -> item.getSongId().equals(songId))) return playlistDetail(playlistId);
        if (current.size() >= MAX_PLAYLIST_SONGS) {
            throw new ApiException("PLAYLIST_SONG_LIMIT", "每个歌单最多包含 " + MAX_PLAYLIST_SONGS + " 首歌曲");
        }
        int sortOrder = current.stream().mapToInt(PlaylistSong::getSortOrder).max().orElse(-1) + 1;
        playlistSongRepository.insertManualIfAbsent(playlistId, song.getId(), sortOrder);
        return playlistDetail(playlistId);
    }

    /**
     * 从歌单中移除指定歌曲。
     *
     * Removes the specified song from the playlist.
     *
     * @param playlistId 歌单 ID / playlist ID
     * @param songId 歌曲 ID / song ID
     */
    @Transactional
    public void removePlaylistSong(Long playlistId, Long songId) {
        requirePlaylist(playlistId);
        playlistSongRepository.deleteByPlaylistIdAndSongId(playlistId, songId);
    }

    /**
     * 上传歌单封面图片（仅支持 JPG、PNG、WebP，最大 5MB）。
     *
     * Uploads a cover image for the playlist (JPG/PNG/WebP only, max 5MB).
     *
     * @param playlistId 歌单 ID / playlist ID
     * @param file 上传的图片文件 / uploaded image file
     * @return 更新后的歌单实体 / updated playlist entity
     */
    @Transactional
    public Playlist uploadPlaylistCover(Long playlistId, MultipartFile file) {
        Playlist playlist = requirePlaylist(playlistId);
        if (file == null || file.isEmpty()) throw new ApiException("INVALID_IMAGE", "请选择封面图片");
        if (file.getSize() > 5 * 1024 * 1024) throw new ApiException("IMAGE_TOO_LARGE", "封面图片不能超过 5MB");
        String contentType = file.getContentType() == null ? "" : file.getContentType().toLowerCase();
        String extension = switch (contentType) {
            case "image/png" -> "png";
            case "image/webp" -> "webp";
            case "image/jpeg", "image/jpg" -> "jpg";
            default -> throw new ApiException("INVALID_IMAGE", "仅支持 JPG、PNG 或 WebP 图片");
        };
        try {
            playlist.setCoverPath(assetWriter.writePlaylistCover(playlistId, file.getBytes(), extension));
            return playlistRepository.save(playlist);
        } catch (IOException e) {
            throw new ApiException("IMAGE_WRITE_FAILED", "封面保存失败");
        }
    }

    /**
     * 重新排列歌单中歌曲的顺序。
     *
     * Reorders the songs within the playlist.
     *
     * @param playlistId 歌单 ID / playlist ID
     * @param songIds   按新顺序排列的歌曲 ID 列表 / ordered song ID list
     * @return 更新后的歌单详情 / updated playlist detail
     */
    @Transactional
    public Map<String, Object> reorderPlaylistSongs(Long playlistId, List<Long> songIds) {
        requirePlaylist(playlistId);
        List<PlaylistSong> current = playlistSongRepository.findByPlaylistIdOrderBySortOrder(playlistId);
        if (songIds == null || songIds.size() != current.size() || new HashSet<>(songIds).size() != current.size()) {
            throw new ApiException("INVALID_PLAYLIST_ORDER", "排序歌曲列表不完整或包含重复项");
        }
        Map<Long, PlaylistSong> bySongId = new HashMap<>();
        current.forEach(item -> bySongId.put(item.getSongId(), item));
        for (int index = 0; index < songIds.size(); index++) {
            PlaylistSong item = bySongId.get(songIds.get(index));
            if (item == null) throw new ApiException("INVALID_PLAYLIST_ORDER", "排序中包含不属于该歌单的歌曲");
            item.setSortOrder(index);
        }
        playlistSongRepository.saveAll(current);
        return playlistDetail(playlistId);
    }

    /**
     * 重新执行失败的 AI 分析任务。
     *
     * Retries a failed AI analysis task.
     *
     * @param taskId 任务 ID / task ID
     * @return 重置后的任务 / the reset task
     */
    public AiAnalysisTask retry(Long taskId) {
        AiAnalysisTask task = requireTask(taskId);
        if ("pending".equals(task.getStatus()) || "processing".equals(task.getStatus())) {
            throw new ApiException("INVALID_AI_TASK", "任务正在处理中，不能重复分析");
        }
        task.setStatus("pending");
        task.setErrorMessage(null);
        taskRepository.save(task);
        worker.analyze(task.getId());
        return task;
    }

    /**
     * 应用 AI 分类结果到歌曲，可传入覆盖值。
     *
     * Applies AI classification results to the song, with optional override.
     *
     * @param taskId 任务 ID / task ID
     * @param override 覆盖的分类结果，为 null 时使用任务中的结果 / override classification, uses task result when null
     * @return 更新后的歌曲实体 / updated song entity
     */
    @Transactional
    public Object apply(Long taskId, AiSongClassification override) {
        AiAnalysisTask task = requireTask(taskId);
        if (!"review".equals(task.getStatus())) throw new ApiException("INVALID_AI_TASK", "任务不处于待确认状态");
        AiSongClassification result = override == null ? parse(task.getResultJson()) : override;
        Object target;
        if ("IMPORT_RECORD".equals(task.getTargetType())) {
            MediaImportRecord record = importRecordRepository.findById(task.getTargetId())
                    .orElseThrow(() -> new ApiException("IMPORT_RECORD_NOT_FOUND", "导入记录不存在"));
            if (result.title() == null || result.title().isBlank())
                throw new ApiException("AI_RESULT_INVALID", "歌名不能为空");
            record.setParsedTitle(result.title().trim());
            record.setParsedArtist(result.artist() == null ? "" : result.artist().trim());
            record.setReason("人工确认 AI 文件身份：" + (result.reason() == null ? "" : result.reason()));
            target = importRecordRepository.save(record);
        } else {
            target = classificationApplier.apply(task.getSongId(), result);
        }
        task.setStatus("applied");
        task.setResultJson(write(result));
        taskRepository.save(task);
        return target;
    }

    /**
     * 根据 AI 标签自动生成歌单，将匹配标签的歌曲填入，并保留手动添加的曲目。
     *
     * Generates a playlist by AI tag — fills in matching songs while preserving manually added ones.
     *
     * @param name 歌单名称 / playlist name
     * @param tag  AI 分类标签 / AI classification tag
     * @param limit 匹配歌曲数量上限 / max matching songs
     * @return 生成/更新后的歌单实体 / the generated or updated playlist entity
     */
    @Transactional
    public Playlist generatePlaylist(String name, String tag, int limit) {
        if (name == null || name.isBlank() || tag == null || tag.isBlank()) {
            throw new ApiException("INVALID_ARGUMENT", "歌单名称和 AI 标签不能为空");
        }
        String normalizedName = name.trim();
        playlistRepository.lockGeneratedName(normalizedName);
        Playlist playlist = playlistRepository.findByName(normalizedName).orElseGet(Playlist::new);
        playlist.setName(normalizedName);
        playlist.setTheme(tag.trim());
        playlist.setDescription("由 AI 标签「" + tag.trim() + "」自动生成");
        playlist.setAiGenerated(true);
        playlist.setAiRule(write(Map.of("tag", tag.trim(), "limit", playlistLimit(limit))));
        playlist = playlistRepository.save(playlist);

        playlistSongRepository.deleteByPlaylistIdAndManualFalse(playlist.getId());
        Set<Long> existing = new HashSet<>();
        int order = 0;
        for (PlaylistSong item : playlistSongRepository.findByPlaylistIdOrderBySortOrder(playlist.getId())) {
            existing.add(item.getSongId());
            order = Math.max(order, item.getSortOrder() + 1);
        }
        int maximum = playlistLimit(limit);
        for (int pageNumber = 0; existing.size() < maximum; pageNumber++) {
            Page<Song> page = songPage(pageNumber);
            if (page == null || page.isEmpty()) break;
            for (Song song : page.getContent()) {
                if (existing.size() >= maximum) break;
                if (!matches(song, tag) || existing.contains(song.getId())) continue;
                PlaylistSong item = new PlaylistSong();
                item.setPlaylistId(playlist.getId());
                item.setSongId(song.getId());
                item.setSortOrder(order++);
                item.setManual(false);
                playlistSongRepository.save(item);
                existing.add(song.getId());
            }
            if (!page.hasNext()) break;
        }
        return playlist;
    }

    private Page<Song> songPage(int pageNumber) {
        return songRepository.findAll(PageRequest.of(pageNumber, SONG_PAGE_SIZE,
                Sort.by(Sort.Direction.ASC, "id")));
    }

    private void forEachSong(Consumer<Song> consumer) {
        for (int pageNumber = 0; ; pageNumber++) {
            Page<Song> page = songPage(pageNumber);
            if (page == null || page.isEmpty()) return;
            page.getContent().stream().filter(Objects::nonNull).forEach(consumer);
            if (!page.hasNext()) return;
        }
    }

    /** Keep playlist preview memory bounded while retaining the old top-5000 rule. */
    private List<Song> playlistCandidates() {
        Comparator<Song> preferred = Comparator.comparingInt(Song::getPlayCount).reversed()
                .thenComparing(song -> song.getAiAnalyzedAt() == null);
        PlaylistCandidateCollector collector = new PlaylistCandidateCollector(preferred);
        forEachSong(collector::accept);
        return collector.finish();
    }

    // 判断歌曲是否匹配指定 AI 标签 / Checks whether a song matches the given AI tag
    private boolean matches(Song song, String tag) {
        return equalsAny(tag, song.getAiLanguage(), song.getAiEra(), song.getAiAgeRange(), song.getAiVocalForm())
                || contains(tag, song.getAiGenres()) || contains(tag, song.getAiThemes()) || contains(tag, song.getTags());
    }

    // 忽略大小写判断 tag 是否等于任一给定值 / Case-insensitive check if tag equals any of the given values
    private boolean equalsAny(String tag, String... values) {
        return Arrays.stream(values).filter(Objects::nonNull).anyMatch(tag::equalsIgnoreCase);
    }

    // 忽略大小写判断 tag 是否存在于数组中 / Case-insensitive check if tag exists in array
    private boolean contains(String tag, String[] values) {
        return values != null && Arrays.stream(values).anyMatch(tag::equalsIgnoreCase);
    }

    // 查找任务，不存在则抛异常 / Find task by ID or throw
    private AiAnalysisTask requireTask(Long id) { return taskRepository.findById(id).orElseThrow(() -> new ApiException("AI_TASK_NOT_FOUND", "AI 任务不存在")); }
    // 查找歌单，不存在则抛异常 / Find playlist by ID or throw
    private Playlist requirePlaylist(Long id) { return playlistRepository.findById(id).orElseThrow(() -> new ApiException("PLAYLIST_NOT_FOUND", "歌单不存在")); }
    // 将 JSON 字符串解析为分类结果对象 / Parse JSON string to classification object
    private AiSongClassification parse(String json) {
        try { return objectMapper.readValue(json, AiSongClassification.class); }
        catch (JsonProcessingException e) { throw new ApiException("AI_RESULT_INVALID", "AI 结果无法解析"); }
    }
    // 将对象序列化为 JSON 字符串 / Serialize object to JSON string
    private String write(Object value) {
        try { return objectMapper.writeValueAsString(value); }
        catch (JsonProcessingException e) { throw new IllegalStateException("JSON 序列化失败", e); }
    }

    private static final class PlaylistCandidateCollector {
        private final Comparator<Song> preferred;
        private final List<Song> first = new ArrayList<>(MAX_PLAYLIST_CANDIDATES);
        private PriorityQueue<Song> top;

        private PlaylistCandidateCollector(Comparator<Song> preferred) {
            this.preferred = preferred;
        }

        private void accept(Song song) {
            if (top == null && first.size() < MAX_PLAYLIST_CANDIDATES) {
                first.add(song);
                return;
            }
            if (top == null) {
                top = new PriorityQueue<>(MAX_PLAYLIST_CANDIDATES + 1, preferred.reversed());
                top.addAll(first);
                first.clear();
            }
            top.offer(song);
            if (top.size() > MAX_PLAYLIST_CANDIDATES) top.poll();
        }

        private List<Song> finish() {
            if (top == null) return List.copyOf(first);
            return top.stream().sorted(preferred).toList();
        }
    }
}
