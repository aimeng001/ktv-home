package com.homektv.library;

import com.homektv.config.AppProperties;
import com.homektv.domain.AudioLayout;
import com.homektv.domain.Song;
import com.homektv.domain.SongFile;
import com.homektv.media.FFprobeService;
import com.homektv.media.MediaProbe;
import com.homektv.media.MediaProbeException;
import com.homektv.repo.SongFileRepository;
import com.homektv.repo.SongRepository;
import com.homektv.web.ApiException;
import org.springframework.beans.factory.annotation.Autowired;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 曲库扫描入库管线（P1.1-P1.5，详设§9.3）。
 * 枚举文件 → ffprobe 探测 → 标签解析 → 文件名兜底 → 类型判定
 * → 指纹去重 → 拼音字段 → 歌词/封面落盘 → 写 songs/song_files。
 *
 * Library scanning and ingestion pipeline (P1.1-P1.5, Detailed Design §9.3).
 * Enumerates files, probes media, parses tags, applies filename fallbacks,
 * classifies media, deduplicates by fingerprint, generates Pinyin fields,
 * stores lyrics/covers, and persists songs/song_files.
 */
@Service
public class LibraryScanService {

    private static final Logger log = LoggerFactory.getLogger(LibraryScanService.class);

    private static final Set<String> MEDIA_EXT = Set.of(
            "mkv", "mp4", "m4v", "avi", "mov", "ts", "m2ts", "mts", "mpg", "mpeg",
            "vob", "webm", "wmv", "asf", "flv", "f4v", "3gp", "3g2", "rm", "rmvb", // 视频
            "mp3", "mp2", "aac", "flac", "wav", "m4a", "ape", "ogg", "oga", "opus",
            "ac3", "eac3", "dts", "mka", "wma", "aiff", "aif", "alac" // 音频
    );

    private final AppProperties props;
    private final FFprobeService ffprobe;
    private final TagReader tagReader;
    private final SongRepository songRepo;
    private final SongFileRepository fileRepo;
    private final AssetWriter assetWriter;
    private final SettingService settingService;
    private final ExecutorService scanExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "library-scan");
        thread.setDaemon(true);
        return thread;
    });
    private final AtomicReference<ScanProgress> scanProgress = new AtomicReference<>(ScanProgress.idle());
    private final Object scanLock = new Object();
    private final Object artistIndexLock = new Object();
    private volatile Set<String> cachedArtistNames = Set.of();
    private volatile FilenameParser.ArtistIndex cachedArtistIndex =
            FilenameParser.prepareKnownArtists(Set.of());

    public LibraryScanService(AppProperties props, FFprobeService ffprobe, TagReader tagReader,
                              SongRepository songRepo, SongFileRepository fileRepo, AssetWriter assetWriter) {
        this(props, ffprobe, tagReader, songRepo, fileRepo, assetWriter, null);
    }

    @Autowired
    public LibraryScanService(AppProperties props, FFprobeService ffprobe, TagReader tagReader,
                              SongRepository songRepo, SongFileRepository fileRepo, AssetWriter assetWriter,
                              SettingService settingService) {
        this.props = props;
        this.ffprobe = ffprobe;
        this.tagReader = tagReader;
        this.songRepo = songRepo;
        this.fileRepo = fileRepo;
        this.assetWriter = assetWriter;
        this.settingService = settingService;
    }

    public record ScanResult(int scanned, int added, int updated, int skipped, int unrecognized,
                             int fastIndexed, int probeQueued, int probeCalls, int hashCalls,
                             int dbUpdates, int missing) {
        public ScanResult(int scanned, int added, int updated, int skipped, int unrecognized) {
            this(scanned, added, updated, skipped, unrecognized, 0, 0, 0, 0, 0, 0);
        }
    }
    public record IngestResult(boolean imported, Long songId, Long songFileId) {}
    public record ScanProgress(boolean running, int total, int completed, String currentFile,
                               int added, int updated, int skipped, int unrecognized,
                               OffsetDateTime startedAt, OffsetDateTime finishedAt) {
        static ScanProgress idle() {
            return new ScanProgress(false, 0, 0, null, 0, 0, 0, 0, null, null);
        }
    }

    /** 全量/增量扫描曲库根目录。Fast Index 与串行 Media Probe Queue 分阶段执行。 */
    public ScanResult scanAll() {
        synchronized (scanLock) {
            OffsetDateTime startedAt = OffsetDateTime.now();
            scanProgress.set(new ScanProgress(true, 0, 0, null, 0, 0, 0, 0, startedAt, null));
            Path root = LibraryModePolicy.activeLibraryRoot(props);
            if (!Files.isDirectory(root)) {
                log.warn("曲库目录不存在：{}", root);
                ScanResult result = new ScanResult(0, 0, 0, 0, 0);
                scanProgress.set(new ScanProgress(false, 0, 0, null, 0, 0, 0, 0,
                        startedAt, OffsetDateTime.now()));
                return result;
            }
            Set<String> knownArtists = existingArtistNames();
            AudioLayout externalDefault = LibraryModePolicy.isExternalReadOnly(props)
                    ? configuredExternalDefaultAudioLayout() : null;
            FilenameParser.ArtistIndex artistIndex = artistIndexFor(knownArtists);
            String activeRole = activeFileRole();
            List<SongFile> trackedFiles = trackedFiles(activeRole);
            Map<String, SongFile> trackedByPath = new HashMap<>();
            for (SongFile tracked : trackedFiles) {
                if (tracked != null && tracked.getFilePath() != null) {
                    trackedByPath.put(tracked.getFilePath(), tracked);
                }
            }
            List<FastIndexEntry> indexedEntries = new ArrayList<>();
            Set<String> currentPaths = new HashSet<>();
            ScanCounters counters = new ScanCounters();
            boolean[] enumerationComplete = {true};
            try {
                Files.walkFileTree(root, new SimpleFileVisitor<>() {
                    @Override
                    public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                        return isExternalPathAllowed(dir)
                                ? FileVisitResult.CONTINUE
                                : FileVisitResult.SKIP_SUBTREE;
                    }

                    @Override
                    public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                        if (isMediaFile(file) && isExternalPathAllowed(file)) {
                            try {
                                FastIndexEntry entry = fastIndex(file, attrs, artistIndex, trackedByPath);
                                indexedEntries.add(entry);
                                currentPaths.add(entry.path());
                                counters.fastIndexed++;
                            } catch (RuntimeException failure) {
                                enumerationComplete[0] = false;
                                log.warn("快速索引失败，跳过：{} - {}", file, failure.getMessage());
                            }
                        }
                        return FileVisitResult.CONTINUE;
                    }
                });
            } catch (IOException e) {
                enumerationComplete[0] = false;
                log.error("遍历曲库失败：{}", e.getMessage());
            }
            scanProgress.set(new ScanProgress(true, indexedEntries.size(), 0, null, 0, 0, 0, 0,
                    startedAt, null));

            int added = 0, updated = 0, skipped = 0, unrecognized = 0;
            ArrayDeque<FastIndexEntry> probeQueue = new ArrayDeque<>();
            for (FastIndexEntry entry : indexedEntries) {
                if (hasUnchangedSnapshot(entry)) {
                    reactivateIfNeeded(entry, counters);
                    skipped++;
                } else {
                    probeQueue.addLast(prepareFastIndex(entry, counters, externalDefault));
                }
            }
            counters.probeQueued = probeQueue.size();
            if (enumerationComplete[0]) {
                markMissingFiles(root, currentPaths, counters, trackedFiles);
            } else {
                log.warn("曲库枚举未完整结束，本轮不标记消失文件，等待下次扫描重试：{}", root);
            }
            scanProgress.set(new ScanProgress(true, indexedEntries.size(), skipped, null, 0, 0, skipped, 0,
                    startedAt, null));

            int completed = skipped;
            while (!probeQueue.isEmpty()) {
                FastIndexEntry entry = probeQueue.removeFirst();
                try {
                    IngestOutcome outcome = ingest(entry, knownArtists, counters, externalDefault);
                    switch (outcome) {
                        case ADDED -> added++;
                        case UPDATED -> updated++;
                        case SKIPPED -> skipped++;
                        case UNRECOGNIZED -> { added++; unrecognized++; }
                    }
                } catch (Exception e) {
                    log.warn("入库失败，跳过：{} - {}", entry.file(), e.getMessage());
                    skipped++;
                }
                completed++;
                scanProgress.set(new ScanProgress(true, indexedEntries.size(), completed,
                        entry.file().getFileName().toString(), added, updated, skipped, unrecognized,
                        startedAt, null));
            }
            log.info("扫描完成：共 {} 文件，新增 {}，更新 {}，跳过 {}，未识别 {}",
                    indexedEntries.size(), added, updated, skipped, unrecognized);
            ScanResult result = new ScanResult(indexedEntries.size(), added, updated, skipped, unrecognized,
                    counters.fastIndexed, counters.probeQueued, counters.probeCalls, counters.hashCalls,
                    counters.dbUpdates, counters.missing);
            scanProgress.set(new ScanProgress(false, indexedEntries.size(), indexedEntries.size(), null, added, updated,
                    skipped, unrecognized, startedAt, OffsetDateTime.now()));
            return result;
        }
    }

    /** Starts the active library scan asynchronously for the admin progress endpoint. */
    public ScanProgress startScan() {
        synchronized (scanLock) {
            ScanProgress current = scanProgress.get();
            if (current.running()) return current;
            scanProgress.set(new ScanProgress(true, 0, 0, null, 0, 0, 0, 0,
                    OffsetDateTime.now(), null));
            scanExecutor.submit(() -> {
                try {
                    scanAll();
                } catch (RuntimeException exception) {
                    ScanProgress failed = scanProgress.get();
                    scanProgress.set(new ScanProgress(false, failed.total(), failed.completed(), null,
                            failed.added(), failed.updated(), failed.skipped() + 1,
                            failed.unrecognized(), failed.startedAt(), OffsetDateTime.now()));
                }
            });
            return scanProgress.get();
        }
    }

    public ScanProgress getScanProgress() {
        return scanProgress.get();
    }

    /** Shared filename parsing entry point for Managed imports and active-library scans. */
    public ParsedMeta parseFilename(String filename) {
        return FilenameParser.parse(filename, artistIndexFor(existingArtistNames()));
    }

    /**
     * Fast Index 阶段只读取目录项属性和文件名元数据；不会打开媒体内容。
     * The fast-index phase reads directory attributes and filename metadata only;
     * it never opens the media payload.
     */
    private FastIndexEntry fastIndex(Path file, Collection<String> knownArtists) {
        try {
            BasicFileAttributes attrs = Files.readAttributes(file, BasicFileAttributes.class);
            return fastIndex(file, attrs, artistIndexFor(knownArtists), null);
        } catch (IOException e) {
            throw new IllegalStateException("读取文件属性失败：" + file, e);
        }
    }

    private FastIndexEntry fastIndex(Path file, BasicFileAttributes attrs,
                                     FilenameParser.ArtistIndex artistIndex,
                                     Map<String, SongFile> trackedByPath) {
        String path = file.toString();
        Path sidecarLyric = sidecarLyricOf(file);
        OffsetDateTime mediaMtime = attrs.lastModifiedTime().toInstant().atOffset(ZoneOffset.UTC);
        SongFile tracked = trackedByPath == null
                ? fileRepo.findByFilePath(path).orElse(null)
                : trackedByPath.get(path);
        return new FastIndexEntry(file, path, attrs.size(), newestMtime(file, sidecarLyric, mediaMtime),
                fileIdentity(attrs, sidecarLyric),
                FilenameParser.parse(file.getFileName().toString(), artistIndex),
                Optional.ofNullable(tracked), tracked != null, tracked != null && tracked.isProbePending());
    }

    /**
     * Stable snapshot identity is path + media size + effective input mtime.
     * The effective mtime includes a valid LRC sidecar because it is another scan input.
     * PostgreSQL TIMESTAMPTZ stores microseconds, so compare and persist at that same
     * precision. This avoids false changes after database truncation while remaining
     * much finer than the old seconds-only comparison.
     */
    private static boolean hasUnchangedSnapshot(FastIndexEntry entry) {
        return entry.existing().filter(existing -> existing.getFileSize() == entry.size()
                && sameMtime(existing.getFileMtime(), entry.mtime())
                && sameFileIdentity(existing.getFileIdentity(), entry.fileIdentity())
                && !existing.isProbePending()).isPresent();
    }

    private static boolean sameFileIdentity(String stored, String current) {
        return stored == null && current == null
                || stored != null && current != null && stored.equals(current);
    }

    private static boolean sameMtime(OffsetDateTime left, OffsetDateTime right) {
        return left != null && right != null
                && mtimeKey(left) == mtimeKey(right);
    }

    private static long mtimeKey(OffsetDateTime value) {
        var instant = value.toInstant();
        return instant.getEpochSecond() * 1_000_000L + instant.getNano() / 1_000;
    }

    private static OffsetDateTime normalizeMtime(OffsetDateTime value) {
        if (value == null) return null;
        return value.withNano((value.getNano() / 1_000) * 1_000);
    }

    /** Persist filename metadata before opening the media file for FFprobe. */
    private FastIndexEntry prepareFastIndex(FastIndexEntry entry, ScanCounters counters,
                                            AudioLayout externalDefault) {
        SongFile existing = entry.existing().orElse(null);
        OffsetDateTime normalizedMtime = normalizeMtime(entry.mtime());
        if (existing != null) {
            boolean changed = existing.getFileSize() != entry.size()
                    || !sameMtime(existing.getFileMtime(), entry.mtime())
                    || !sameFileIdentity(existing.getFileIdentity(), entry.fileIdentity())
                    || !existing.isProbePending() || !existing.isValid();
            if (changed) {
                existing.setFileSize(entry.size());
                existing.setFileMtime(normalizedMtime);
                existing.setFileIdentity(entry.fileIdentity());
                existing.setProbePending(true);
                existing.setValid(true);
                fileRepo.save(existing);
                counters.dbUpdates++;
            }
            return entry.withExisting(existing);
        }

        Song provisional = new Song();
        ParsedMeta parsed = entry.filenameMeta();
        String title = parsed.title() == null || parsed.title().isBlank()
                ? entry.file().getFileName().toString() : parsed.title();
        String artist = parsed.artist() == null || parsed.artist().isBlank()
                ? "未知歌手" : parsed.artist();
        provisional.setTitle(title);
        provisional.setArtist(artist);
        provisional.setTitlePy(PinyinUtil.fullPinyin(title));
        provisional.setTitleInit(PinyinUtil.initials(title));
        provisional.setArtistPy(PinyinUtil.fullPinyin(artist));
        provisional.setArtistInit(PinyinUtil.initials(artist));
        provisional.setLanguage(parsed.language() == null || parsed.language().isBlank()
                ? "未知" : normalizeLanguage(parsed.language()));
        provisional.setMediaType(MediaClassifier.PENDING_PROBE);
        provisional.setHasVocalTrack(false);
        provisional.setDurationMs(0);
        provisional.setLyricType(LyricType.NONE);
        provisional.setFingerprint(MediaClassifier.fastIndexFingerprint(entry.path()));
        // Provisional rows remain searchable while their media details are pending.
        provisional.setStatus("ok");
        provisional.setTags(parsed.category() == null || parsed.category().isBlank()
                ? new String[0] : new String[]{parsed.category()});
        provisional.setMetadataProvenance("{\"title\":{\"source\":\"filename_fast_index\"},"
                + "\"artist\":{\"source\":\"filename_fast_index\"}}");
        provisional.setNeedsAiOptimization(!parsed.recognized());
        provisional = songRepo.save(provisional);
        counters.dbUpdates++;

        SongFile indexed = new SongFile();
        indexed.setSongId(provisional.getId());
        indexed.setFilePath(entry.path());
        indexed.setFormat(extOf(entry.file()));
        indexed.setFileSize(entry.size());
        indexed.setFileMtime(normalizedMtime);
        indexed.setFileIdentity(entry.fileIdentity());
        indexed.setFileRole(activeFileRole());
        if (LibraryModePolicy.isExternalReadOnly(props)) {
            // Store the configured default on the pending row so a later retry
            // can distinguish it from any per-file override made in the admin UI.
            indexed.setAudioLayout(externalDefault == null
                    ? AudioLayout.NORMAL_STEREO : externalDefault);
        }
        indexed.setValid(true);
        indexed.setProbePending(true);
        indexed = fileRepo.save(indexed);
        counters.dbUpdates++;
        return entry.withExisting(indexed);
    }

    private List<SongFile> trackedFiles(String role) {
        List<SongFile> tracked = fileRepo.findByFileRoleOrderByImportedAtDesc(role);
        return tracked == null ? List.of() : tracked;
    }

    private String activeFileRole() {
        return LibraryModePolicy.isExternalReadOnly(props)
                ? LibraryModePolicy.EXTERNAL_FILE_ROLE : "LIBRARY";
    }

    private void reactivateIfNeeded(FastIndexEntry entry, ScanCounters counters) {
        SongFile existing = entry.existing().orElse(null);
        if (existing == null || existing.isValid()) return;

        existing.setValid(true);
        fileRepo.save(existing);
        counters.dbUpdates++;
        if (existing.getSongId() == null) return;
        songRepo.findById(existing.getSongId()).ifPresent(song -> {
            if ("file_missing".equals(song.getStatus())) {
                song.setStatus("ok");
                songRepo.save(song);
                counters.dbUpdates++;
            }
        });
    }

    /** Mark disappeared records invalid; never delete or touch a source path. */
    private void markMissingFiles(Path root, Set<String> currentPaths, ScanCounters counters,
                                  List<SongFile> tracked) {
        if (tracked == null) return;
        for (SongFile file : tracked) {
            String path = file == null ? null : file.getFilePath();
            if (path == null || !file.isValid() || currentPaths.contains(path)
                    || !isPathInsideActiveRoot(root, path)) continue;
            file.setValid(false);
            fileRepo.save(file);
            counters.dbUpdates++;
            counters.missing++;
            markSongMissingIfNeeded(file.getSongId(), counters);
        }
    }

    private boolean isPathInsideActiveRoot(Path root, String path) {
        try {
            Path candidate = Path.of(path);
            if (LibraryModePolicy.isExternalReadOnly(props)) {
                LibraryModePolicy.requireExternalPathInsideSource(props, candidate);
                return true;
            }
            return candidate.toAbsolutePath().normalize()
                    .startsWith(root.toAbsolutePath().normalize());
        } catch (RuntimeException rejected) {
            return false;
        }
    }

    private void markSongMissingIfNeeded(Long songId, ScanCounters counters) {
        if (songId == null) return;
        List<SongFile> validFiles = fileRepo.findBySongIdAndValidTrueOrderByPriorityDesc(songId);
        if (validFiles != null && !validFiles.isEmpty()) return;
        songRepo.findById(songId).ifPresent(song -> {
            if ("ok".equals(song.getStatus())) {
                song.setStatus("file_missing");
                songRepo.save(song);
                counters.dbUpdates++;
            }
        });
    }

    enum IngestOutcome { ADDED, UPDATED, SKIPPED, UNRECOGNIZED }

    /** 单文件入库（幂等：已存在的路径按 path + size + mtime 判断是否需更新） */
    @Transactional
    public IngestOutcome ingest(Path file) {
        LibraryModePolicy.requireExternalPathInsideSource(props, file);
        Collection<String> knownArtists = existingArtistNames();
        FastIndexEntry entry = fastIndex(file, knownArtists);
        if (hasUnchangedSnapshot(entry)) {
            return IngestOutcome.SKIPPED;
        }
        AudioLayout externalDefault = LibraryModePolicy.isExternalReadOnly(props)
                ? configuredExternalDefaultAudioLayout() : null;
        return ingest(entry, knownArtists, new ScanCounters(), externalDefault);
    }

    private IngestOutcome ingest(FastIndexEntry entry, Collection<String> knownArtists,
                                 ScanCounters counters, AudioLayout externalDefault) {
        LibraryModePolicy.requireExternalPathInsideSource(props, entry.file());
        return ingestInternal(entry, null, null, null, false, knownArtists, counters, externalDefault).outcome();
    }

    private boolean isExternalPathAllowed(Path path) {
        if (!LibraryModePolicy.isExternalReadOnly(props)) return true;
        try {
            LibraryModePolicy.requireExternalPathInsideSource(props, path);
            return true;
        } catch (ApiException e) {
            log.warn("外部曲库路径越界，跳过：{} - {}", path, e.getMessage());
            return false;
        }
    }

    @Transactional
    public IngestResult ingestLibraryFile(Path file, Path sourceFile, String sourceMd5, String outputMd5, boolean transcodeRequired) {
        LibraryModePolicy.requireManaged(props, "导入");
        Collection<String> knownArtists = existingArtistNames();
        FastIndexEntry entry = fastIndex(file, knownArtists);
        if (hasUnchangedSnapshot(entry)) {
            SongFile existing = entry.existing().orElse(null);
            return new IngestResult(false, existing == null ? null : existing.getSongId(),
                    existing == null ? null : existing.getId());
        }
        IngestState state = ingestInternal(entry, sourceFile, sourceMd5, outputMd5,
                transcodeRequired, knownArtists, new ScanCounters(), null);
        return new IngestResult(
                state.outcome() == IngestOutcome.ADDED || state.outcome() == IngestOutcome.UPDATED,
                state.songId(),
                state.songFileId()
        );
    }

    private IngestState ingestInternal(FastIndexEntry entry, Path sourceFile, String sourceMd5, String outputMd5,
                                        boolean transcodeRequired, Collection<String> knownArtists,
                                        ScanCounters counters, AudioLayout externalDefault) {
        Path file = entry.file();
        String pathStr = entry.path();
        Path sidecarLyric = sidecarLyricOf(file);
        OffsetDateTime mtime = entry.mtime();
        Optional<SongFile> existing = entry.existing();

        // 1) ffprobe 探测
        MediaProbe probe;
        try {
            counters.probeCalls++;
            probe = ffprobe.probe(file);
        } catch (MediaProbeException e) {
            log.debug("ffprobe 失败：{} - {}", file.getFileName(), e.getMessage());
            return new IngestState(IngestOutcome.SKIPPED, null, null);
        }

        // 2) 标签解析 + 容器标签 + LRC + 3) 文件名兜底
        TagInfo tag = tagReader.read(file.toFile());
        String sidecarLyricText = readValidSidecarLyric(sidecarLyric);
        String lrcTitle = lrcTag(sidecarLyricText, "ti");
        String lrcArtist = lrcTag(sidecarLyricText, "ar");
        boolean recognized;
        String title, artist;
        String identitySource;
        ParsedMeta filenameMeta = null;
        if (tag.hasTitle()) {
            title = tag.getTitle();
            artist = tag.getArtist() != null ? tag.getArtist() : "";
            recognized = true;
            identitySource = "audio_tag";
        } else if (probe.title() != null && !probe.title().isBlank()) {
            title = probe.title();
            artist = probe.artist() == null ? "" : probe.artist();
            recognized = true;
            identitySource = "container_tag";
        } else if (lrcTitle != null && !lrcTitle.isBlank()) {
            title = lrcTitle;
            artist = lrcArtist == null ? "" : lrcArtist;
            recognized = true;
            identitySource = "lrc_tag";
        } else {
            filenameMeta = entry.filenameMeta();
            title = filenameMeta.title();
            artist = filenameMeta.artist();
            recognized = filenameMeta.recognized();
            identitySource = "filename";
        }
        if (artist == null || artist.isBlank()) artist = "未知歌手";

        // 4) 类型判定 + 伴奏轨判定 + 指纹
        String mediaType = MediaClassifier.classify(probe);
        boolean hasVocal = MediaClassifier.hasVocalTrack(probe);
        VocalTrackDetector.Result vocalDetect = VocalTrackDetector.detect(probe);
        String fingerprint = MediaClassifier.fingerprint(artist, title, probe.durationMs());

        // 5) 指纹去重：同指纹已存在 → 作为多文件源加入，按 priority 择优
        Optional<Song> dup = songRepo.findByFingerprint(fingerprint);
        Song provisional = existing.map(SongFile::getSongId)
                .filter(Objects::nonNull)
                .flatMap(songRepo::findById)
                .filter(LibraryScanService::isProvisionalSong)
                .orElse(null);
        Song song;
        boolean isNew;
        Song provisionalToDelete = null;
        if (dup.isPresent() && !sameSong(dup.get(), provisional)) {
            song = dup.get();
            isNew = false;
            provisionalToDelete = provisional;
        } else if (provisional != null) {
            song = provisional;
            applyProbedMetadata(song, title, artist, mediaType, hasVocal, probe,
                    fingerprint, recognized, tag, filenameMeta, identitySource);
            isNew = false;
        } else if (dup.isPresent()) {
            song = dup.get();
            isNew = false;
        } else {
            song = new Song();
            song.setTitle(title);
            song.setArtist(artist);
            song.setTitlePy(PinyinUtil.fullPinyin(title));
            song.setTitleInit(PinyinUtil.initials(title));
            song.setArtistPy(PinyinUtil.fullPinyin(artist));
            song.setArtistInit(PinyinUtil.initials(artist));
            song.setMediaType(mediaType);
            song.setHasVocalTrack(hasVocal);
            song.setDurationMs((int) probe.durationMs());
            song.setLyricType(LyricType.NONE);
            song.setFingerprint(fingerprint);
            // 未识别（标签+文件名均无有效歌名）标记 unrecognized，供后台筛选补录；否则 ok
            song.setStatus(recognized ? "ok" : "unrecognized");
            if (tag.getLanguage() != null && !tag.getLanguage().isBlank()) song.setLanguage(normalizeLanguage(tag.getLanguage()));
            else if (probe.language() != null && !probe.language().isBlank()) song.setLanguage(normalizeLanguage(probe.language()));
            else if (filenameMeta != null && !filenameMeta.language().isBlank()) {
                song.setLanguage(normalizeLanguage(filenameMeta.language()));
            }
            if (filenameMeta != null && !filenameMeta.category().isBlank()) {
                // 现有模型没有独立 category 列；沿用 Home KTV 的 tags 数组承载文件名分类。
                song.setTags(new String[]{filenameMeta.category()});
            }
            song.setMetadataProvenance("{\"title\":{\"source\":\"" + identitySource + "\"},\"artist\":{\"source\":\"" + identitySource + "\"}}");
            song.setNeedsAiOptimization(!recognized || "未知".equals(song.getLanguage()) || "未知歌手".equals(song.getArtist()));
            isNew = true;
        }

        // 6) 歌词/封面落盘。同名增强 LRC 优先，且允许侧车文件独立更新。
        if (sidecarLyricText != null) {
            String lyricPath = assetWriter.writeLyric(fingerprint, sidecarLyricText);
            song.setLyricPath(lyricPath);
            song.setLyricType(LyricType.detect(sidecarLyricText));
        }
        if (isNew) {
            String lyricText = sidecarLyricText == null ? tag.getEmbeddedLyric() : null;
            if (lyricText != null && !lyricText.isBlank()) {
                String lyricPath = assetWriter.writeLyric(fingerprint, lyricText);
                song.setLyricPath(lyricPath);
                song.setLyricType(LyricType.detect(lyricText));
            }
            if (tag.getCoverImage() != null) {
                String coverPath = assetWriter.writeCover(fingerprint, tag.getCoverImage(), tag.getCoverExt());
                song.setCoverPath(coverPath);
            }
        }
        song = songRepo.save(song);
        counters.dbUpdates++;

        // 7) 写 song_files（KTV 视频优先级高）
        int priority = switch (mediaType) {
            case MediaClassifier.KTV_VIDEO -> 100;
            case MediaClassifier.MV -> 50;
            default -> 10;
        };
        SongFile sf = existing.orElseGet(SongFile::new);
        sf.setSongId(song.getId());
        sf.setFilePath(pathStr);
        sf.setFormat(extOf(file));
        sf.setAudioTracks(probe.audioTracks());
        // External files keep the layout stored on the file row. The Fast Index
        // placeholder also stores the configured default, so a failed probe
        // retry cannot overwrite a per-file override made while it is pending.
        AudioLayout storedLayout = existing.map(SongFile::getAudioLayout).orElse(null);
        AudioLayout audioLayout;
        if (LibraryModePolicy.isExternalReadOnly(props) && storedLayout != null) {
            audioLayout = externalDefaultAudioLayout(probe.audioTracks(), storedLayout);
        } else if (LibraryModePolicy.isExternalReadOnly(props)) {
            audioLayout = externalDefaultAudioLayout(probe.audioTracks(), externalDefault);
        } else {
            // Managed-mode behavior remains the existing two-track detector.
            audioLayout = storedLayout == AudioLayout.DUAL_CHANNEL
                    ? AudioLayout.DUAL_CHANNEL
                    : hasVocal ? AudioLayout.DUAL_TRACK : AudioLayout.NORMAL_STEREO;
        }
        sf.setAudioLayout(audioLayout);
        // 伴奏轨 index（0-based 音频相对序号）。已有值优先（尊重人工/历史校正），
        // 否则用元数据判定，判不出再回落默认 1（多数双轨片源 track0=原唱、track1=伴奏）。
        Integer existingVocalTrackIndex = existing.map(SongFile::getVocalTrackIndex).orElse(null);
        Integer vocalTrackIndex = null;
        String vocalConfidence = null;
        if (audioLayout == AudioLayout.DUAL_TRACK) {
            if (existingVocalTrackIndex != null) {
                // 尊重人工/历史校正：index 不变，置信度视为已确认（HIGH）
                vocalTrackIndex = existingVocalTrackIndex;
                vocalConfidence = VocalTrackDetector.Confidence.HIGH.name();
            } else if (vocalDetect.accompanimentIndex() != null) {
                vocalTrackIndex = vocalDetect.accompanimentIndex();
                vocalConfidence = vocalDetect.confidence().name();
                log.debug("伴奏轨判定 {} → track#{}（{}，{}）", file.getFileName(),
                        vocalTrackIndex, vocalDetect.confidence(), vocalDetect.reason());
            } else {
                // 判不出：回落默认 track#1，标 LOW 供后台筛选人工复核
                vocalTrackIndex = 1;
                vocalConfidence = VocalTrackDetector.Confidence.LOW.name();
                log.info("伴奏轨无法确定，回落默认 track#1，建议人工复核：{}（{}）",
                        file.getFileName(), vocalDetect.reason());
            }
        }
        if (audioLayout == AudioLayout.DUAL_TRACK) {
            sf.setAccompanimentTrackIndex(vocalTrackIndex);
            Integer storedOriginalTrackIndex = existing.map(SongFile::getOriginalTrackIndex).orElse(null);
            sf.setOriginalTrackIndex(storedOriginalTrackIndex != null
                    ? storedOriginalTrackIndex
                    : vocalTrackIndex == null ? null : vocalTrackIndex == 0 ? 1 : 0);
        } else {
            sf.setVocalTrackIndex(null);
            sf.setOriginalTrackIndex(null);
            sf.setAccompanimentTrackIndex(null);
        }
        sf.setVocalConfidence(vocalConfidence);
        sf.setResolution(probe.resolution());
        sf.setFileSize(entry.size());
        sf.setFileMtime(normalizeMtime(mtime));
        sf.setFileIdentity(entry.fileIdentity());
        sf.setPriority(priority);
        // 文件重新被成功探测，说明之前的瞬时播放失败不应永久屏蔽该源。
        sf.setValid(true);
        if (LibraryModePolicy.isExternalReadOnly(props)) {
            sf.setFileRole(LibraryModePolicy.EXTERNAL_FILE_ROLE);
            sf.setSourcePath(null);
            sf.setSourceMd5(null);
            sf.setOutputMd5(null);
            sf.setTranscodeRequired(false);
            sf.setImportedAt(null);
            sf.setSourceDeleted(false);
        } else {
            sf.setFileRole("LIBRARY");
            sf.setSourcePath(sourceFile != null ? sourceFile.toString() : sf.getSourcePath());
            sf.setSourceMd5(sourceMd5);
            sf.setOutputMd5(outputMd5);
            sf.setTranscodeRequired(transcodeRequired);
            sf.setImportedAt(OffsetDateTime.now());
        }
        sf.setSourceDeleted(false);
        sf.setProbePending(false);
        sf = fileRepo.save(sf);
        counters.dbUpdates++;
        if (provisionalToDelete != null) songRepo.delete(provisionalToDelete);

        IngestOutcome outcome;
        if (!recognized) outcome = IngestOutcome.UNRECOGNIZED;
        else if (!entry.existedBeforeScan() || entry.pendingBeforeScan()) outcome = IngestOutcome.ADDED;
        else outcome = IngestOutcome.UPDATED;
        return new IngestState(outcome, song.getId(), sf.getId());
    }

    private AudioLayout configuredExternalDefaultAudioLayout() {
        AudioLayout configured = settingService == null
                ? AudioLayout.NORMAL_STEREO : settingService.externalDefaultAudioLayout();
        if (configured == null) configured = AudioLayout.NORMAL_STEREO;
        return configured;
    }

    private static AudioLayout externalDefaultAudioLayout(int audioTracks, AudioLayout configured) {
        if (configured == null) configured = AudioLayout.NORMAL_STEREO;
        // A global DUAL_TRACK default cannot describe a one-track media file;
        // keep that file safe and playable rather than persisting invalid indices.
        return configured == AudioLayout.DUAL_TRACK && audioTracks < 2
                ? AudioLayout.NORMAL_STEREO : configured;
    }

    private static boolean sameSong(Song left, Song right) {
        if (left == null || right == null) return false;
        if (left == right) return true;
        return left.getId() != null && left.getId().equals(right.getId());
    }

    private static boolean isProvisionalSong(Song song) {
        return song != null && (MediaClassifier.PENDING_PROBE.equals(song.getMediaType())
                || song.getFingerprint() != null && song.getFingerprint().startsWith("fast-index-"));
    }

    private static void applyProbedMetadata(Song song, String title, String artist, String mediaType,
                                            boolean hasVocal, MediaProbe probe, String fingerprint,
                                            boolean recognized, TagInfo tag, ParsedMeta filenameMeta,
                                            String identitySource) {
        song.setTitle(title);
        song.setArtist(artist);
        song.setTitlePy(PinyinUtil.fullPinyin(title));
        song.setTitleInit(PinyinUtil.initials(title));
        song.setArtistPy(PinyinUtil.fullPinyin(artist));
        song.setArtistInit(PinyinUtil.initials(artist));
        song.setMediaType(mediaType);
        song.setHasVocalTrack(hasVocal);
        song.setDurationMs((int) probe.durationMs());
        song.setLyricType(LyricType.NONE);
        song.setFingerprint(fingerprint);
        song.setStatus(recognized ? "ok" : "unrecognized");
        if (tag.getLanguage() != null && !tag.getLanguage().isBlank()) {
            song.setLanguage(normalizeLanguage(tag.getLanguage()));
        } else if (probe.language() != null && !probe.language().isBlank()) {
            song.setLanguage(normalizeLanguage(probe.language()));
        } else if (filenameMeta != null && !filenameMeta.language().isBlank()) {
            song.setLanguage(normalizeLanguage(filenameMeta.language()));
        }
        if (filenameMeta != null && !filenameMeta.category().isBlank()) {
            song.setTags(new String[]{filenameMeta.category()});
        }
        song.setMetadataProvenance("{\"title\":{\"source\":\"" + identitySource
                + "\"},\"artist\":{\"source\":\"" + identitySource + "\"}}");
        song.setNeedsAiOptimization(!recognized || "未知".equals(song.getLanguage())
                || "未知歌手".equals(song.getArtist()));
    }

    public static boolean isMediaFile(Path file) {
        return MEDIA_EXT.contains(extOf(file));
    }

    private static String extOf(Path file) {
        String name = file.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(dot + 1).toLowerCase() : "";
    }

    private static OffsetDateTime mtimeOf(Path file) {
        try {
            return Files.getLastModifiedTime(file).toInstant().atOffset(ZoneOffset.UTC);
        } catch (IOException e) {
            return OffsetDateTime.now();
        }
    }

    private static Path sidecarLyricOf(Path mediaFile) {
        String name = mediaFile.getFileName().toString();
        int dot = name.lastIndexOf('.');
        String stem = dot > 0 ? name.substring(0, dot) : name;
        return mediaFile.resolveSibling(stem + ".lrc");
    }

    private static OffsetDateTime newestMtime(Path mediaFile, Path sidecarLyric, OffsetDateTime mediaMtime) {
        if (!Files.isRegularFile(sidecarLyric)) return mediaMtime;
        OffsetDateTime lyricMtime = mtimeOf(sidecarLyric);
        return lyricMtime.isAfter(mediaMtime) ? lyricMtime : mediaMtime;
    }

    private static String fileIdentity(BasicFileAttributes mediaAttrs, Path sidecarLyric) {
        Object mediaKey = mediaAttrs.fileKey();
        Object lyricKey = null;
        if (Files.isRegularFile(sidecarLyric)) {
            try {
                lyricKey = Files.readAttributes(sidecarLyric, BasicFileAttributes.class).fileKey();
            } catch (IOException ignored) {
                // The mtime snapshot still detects ordinary sidecar changes.
            }
        }
        if (mediaKey == null && lyricKey == null) return null;
        return String.valueOf(mediaKey) + "|" + String.valueOf(lyricKey);
    }

    private static String readValidSidecarLyric(Path sidecarLyric) {
        if (!Files.isRegularFile(sidecarLyric)) return null;
        try {
            String text = Files.readString(sidecarLyric);
            return LyricType.NONE.equals(LyricType.detect(text)) ? null : text;
        } catch (IOException e) {
            log.warn("读取同名歌词失败：{} - {}", sidecarLyric, e.getMessage());
            return null;
        }
    }

    private static String lrcTag(String lyric, String key) {
        if (lyric == null) return null;
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("(?im)^\\[" + key + "\\s*:\\s*(.+?)\\]\\s*$").matcher(lyric);
        return matcher.find() ? matcher.group(1).trim() : null;
    }

    private static String normalizeLanguage(String raw) {
        String value = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
        if (value.isBlank()) return "未知";
        if (value.matches("zh(-|_)?cn|中文|mandarin|国语|普通话")) return "国语";
        if (value.matches("yue|zh(-|_)?hk|粤语|cantonese")) return "粤语";
        if (value.matches("nan|闽南语|台语|hokkien")) return "闽南语";
        if (value.matches("en|英语|英文|english")) return "英语";
        if (value.matches("ja|日语|日文|japanese")) return "日语";
        if (value.matches("ko|韩语|韩文|korean")) return "韩语";
        if (value.matches("instrumental|纯音乐|music")) return "纯音乐";
        return Set.of("国语", "粤语", "闽南语", "英语", "日语", "韩语", "纯音乐", "其他", "未知").contains(raw) ? raw : "其他";
    }

    private record FastIndexEntry(Path file, String path, long size, OffsetDateTime mtime,
                                  String fileIdentity, ParsedMeta filenameMeta,
                                  Optional<SongFile> existing, boolean existedBeforeScan,
                                  boolean pendingBeforeScan) {
        private FastIndexEntry withExisting(SongFile replacement) {
            return new FastIndexEntry(file, path, size, mtime, fileIdentity, filenameMeta,
                    Optional.ofNullable(replacement), existedBeforeScan, pendingBeforeScan);
        }
    }

    private record IngestState(IngestOutcome outcome, Long songId, Long songFileId) {}

    private static final class ScanCounters {
        private int fastIndexed;
        private int probeQueued;
        private int probeCalls;
        private int hashCalls;
        private int dbUpdates;
        private int missing;
    }

    @PreDestroy
    void shutdown() {
        scanExecutor.shutdownNow();
    }

    private Set<String> existingArtistNames() {
        Set<String> artists = new LinkedHashSet<>();
        try {
            for (Song song : songRepo.findAll()) {
                if (song != null && "ok".equals(song.getStatus())
                        && song.getArtist() != null && !song.getArtist().isBlank()
                        && !"未知歌手".equals(song.getArtist().trim())) {
                    artists.add(song.getArtist().trim());
                }
            }
        } catch (RuntimeException failure) {
            // 歌手库只是解析增强证据；数据库暂时不可用时仍可安全扫描并将复杂名称待审核。
            log.debug("读取已有歌手库失败，文件名复杂边界将进入待审核：{}", failure.getMessage());
        }
        return artists;
    }

    private FilenameParser.ArtistIndex artistIndexFor(Collection<String> artistNames) {
        Set<String> snapshot = artistNames == null || artistNames.isEmpty()
                ? Set.of() : Set.copyOf(artistNames);
        FilenameParser.ArtistIndex current = cachedArtistIndex;
        if (snapshot.equals(cachedArtistNames)) return current;
        synchronized (artistIndexLock) {
            if (!snapshot.equals(cachedArtistNames)) {
                cachedArtistIndex = FilenameParser.prepareKnownArtists(snapshot);
                cachedArtistNames = snapshot;
            }
            return cachedArtistIndex;
        }
    }
}
