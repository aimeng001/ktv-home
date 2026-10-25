package com.homektv.library;

import com.homektv.config.AppProperties;
import com.homektv.domain.AudioLayout;
import com.homektv.domain.AudioLayoutSource;
import com.homektv.domain.Song;
import com.homektv.domain.SongFile;
import com.homektv.media.MediaProbe;
import com.homektv.musicsource.CoverImageNormalizer;
import com.homektv.repo.SongFileRepository;
import com.homektv.repo.SongRepository;
import com.homektv.web.ApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

import static com.homektv.library.LibraryScanService.applySnapshots;
import static com.homektv.library.LibraryScanService.extOf;
import static com.homektv.library.LibraryScanService.firstNonBlank;
import static com.homektv.library.LibraryScanService.hasManualIdentityOverride;
import static com.homektv.library.LibraryScanService.isAudioFile;
import static com.homektv.library.LibraryScanService.isProvisionalSong;
import static com.homektv.library.LibraryScanService.lrcTag;
import static com.homektv.library.LibraryScanService.normalizeLanguage;
import static com.homektv.library.LibraryScanService.normalizeMtime;
import static com.homektv.library.LibraryScanService.readSidecarLyric;
import static com.homektv.library.LibraryScanService.sameSong;
import static com.homektv.library.LibraryScanService.sidecarLyricOf;
import static com.homektv.library.LibraryScanService.sourceOfArtist;

/** Owns transactional reconciliation of probed media into songs and song_files. */
final class LibraryIngestionWriter {
    private static final Logger log = LoggerFactory.getLogger(LibraryIngestionWriter.class);

    private final AppProperties props;
    private final TagReader tagReader;
    private final SongRepository songRepo;
    private final SongFileRepository fileRepo;
    private final AssetWriter assetWriter;
    private final SettingService settingService;
    private final SongProjectionService songProjectionService;
    private final AudioLayoutResolver audioLayoutResolver = new AudioLayoutResolver();
    private final TransactionTemplate transaction;
    private CoverImageNormalizer coverImageNormalizer;
    private ArtistCreditService artistCreditService;

    record Input(Path file, String path, String relativePath, long size, OffsetDateTime mtime,
                 String fileIdentity, ParsedMeta filenameMeta, OffsetDateTime mediaMtime,
                 String mediaIdentity, LibraryScanService.LyricSnapshot lyricSnapshot,
                 Long existingSongFileId, boolean existedBeforeScan, boolean pendingBeforeScan) {}

    LibraryIngestionWriter(AppProperties props, TagReader tagReader, SongRepository songRepo,
                           SongFileRepository fileRepo, AssetWriter assetWriter, SettingService settingService,
                           PlatformTransactionManager transactionManager, SongProjectionService songProjectionService) {
        this.props = props;
        this.tagReader = tagReader;
        this.songRepo = songRepo;
        this.fileRepo = fileRepo;
        this.assetWriter = assetWriter;
        this.settingService = settingService;
        this.songProjectionService = songProjectionService;
        this.transaction = transactionManager == null ? null : new TransactionTemplate(transactionManager);
    }

    void setCoverImageNormalizer(CoverImageNormalizer coverImageNormalizer) {
        this.coverImageNormalizer = coverImageNormalizer;
    }

    void setArtistCreditService(ArtistCreditService artistCreditService) {
        this.artistCreditService = artistCreditService;
    }

    private Optional<SongFile> resolveExisting(Input input) {
        String fileRole = LibraryModePolicy.isExternalReadOnly(props)
                ? LibraryModePolicy.EXTERNAL_FILE_ROLE : "LIBRARY";
        Optional<SongFile> currentPath = fileRepo.findByFileRoleAndFilePath(fileRole, input.path());
        if (currentPath.isPresent()) return currentPath;
        if (input.existingSongFileId() == null) return Optional.empty();
        return fileRepo.findById(input.existingSongFileId())
                .filter(file -> Objects.equals(fileRole, file.getFileRole()));
    }

    private void syncArtistCredits(Song song, Collection<String> knownArtists) {
        if (artistCreditService == null) return;
        if (knownArtists.isEmpty()) {
            artistCreditService.replace(song.getId(), song.getArtist());
        } else {
            artistCreditService.replace(song.getId(), song.getArtist(), knownArtists);
        }
    }

    LibraryScanService.IngestState write(Input input,
                                         Path sourceFile, String sourceMd5, String outputMd5,
                                         boolean transcodeRequired, Collection<String> knownArtists,
                                         LibraryScanService.ScanCounters counters, AudioLayout externalDefault,
                                         MediaProbe probe, LibraryScanLeaseGuard leaseGuard) {
        Supplier<LibraryScanService.IngestState> work = () -> {
            if (leaseGuard != null) leaseGuard.assertOwned();
            LibraryScanService.IngestState state = write(input, sourceFile, sourceMd5, outputMd5,
                    transcodeRequired, knownArtists, counters, externalDefault, probe);
            if (leaseGuard != null) leaseGuard.assertOwned();
            return state;
        };
        return transaction == null ? work.get() : transaction.execute(status -> work.get());
    }

    private LibraryScanService.IngestState write(Input input, Path sourceFile, String sourceMd5, String outputMd5,
                                        boolean transcodeRequired, Collection<String> knownArtists,
                                        LibraryScanService.ScanCounters counters, AudioLayout externalDefault, MediaProbe preProbed) {
        Path file = input.file();
        String pathStr = input.path();
        Path sidecarLyric = sidecarLyricOf(file);
        OffsetDateTime mtime = input.mtime();
        Optional<SongFile> existing = resolveExisting(input);

        // 1) ffprobe 探测
        MediaProbe probe = Objects.requireNonNull(preProbed,
                "Media must be probed before entering the library write transaction");

        // 2) 标签解析 + 容器标签 + LRC + 3) 文件名兜底
        TagInfo tag = isAudioFile(file) ? tagReader.read(file.toFile()) : new TagInfo();
        LibraryScanService.SidecarLyricContent sidecarLyricContent = readSidecarLyric(sidecarLyric, input.lyricSnapshot());
        String sidecarLyricText = sidecarLyricContent.text();
        String lrcTitle = lrcTag(sidecarLyricText, "ti");
        String lrcArtist = lrcTag(sidecarLyricText, "ar");
        boolean recognized;
        String title, artist;
        String titleSource;
        String artistSource;
        ParsedMeta filenameMeta = input.filenameMeta();
        String legacyTitle;
        String legacyTitleSource;
        if (tag.hasTitle()) {
            legacyTitle = tag.getTitle();
            legacyTitleSource = "audio_tag";
        } else if (probe.title() != null && !probe.title().isBlank()) {
            legacyTitle = probe.title();
            legacyTitleSource = "container_tag";
        } else if (lrcTitle != null && !lrcTitle.isBlank()) {
            legacyTitle = lrcTitle;
            legacyTitleSource = "lrc_tag";
        } else {
            legacyTitle = filenameMeta.title();
            legacyTitleSource = "filename";
        }
        String legacyArtist = firstNonBlank(
                tag.getArtist(),
                probe.artist(),
                lrcArtist,
                filenameMeta.artist()
        );
        String legacyArtistSource = sourceOfArtist(
                tag.getArtist(), probe.artist(), lrcArtist, filenameMeta.artist());
        boolean structuredFilenamePreferred = structuredFilenameMetadataPreferred();
        boolean structuredFilename = LibraryMetadataResolver.isStructured(filenameMeta);
        LibraryMetadataResolver.Value resolvedTitle = LibraryMetadataResolver.resolve(
                new LibraryMetadataResolver.Candidates(
                        false, null, structuredFilenamePreferred && structuredFilename,
                        new LibraryMetadataResolver.Value(filenameMeta.title(), "filename"),
                        new LibraryMetadataResolver.Value(legacyTitle, legacyTitleSource)));
        LibraryMetadataResolver.Value resolvedArtist = LibraryMetadataResolver.resolve(
                new LibraryMetadataResolver.Candidates(
                        false, null, structuredFilenamePreferred && structuredFilename,
                        new LibraryMetadataResolver.Value(filenameMeta.artist(), "filename"),
                        new LibraryMetadataResolver.Value(legacyArtist, legacyArtistSource)));
        title = resolvedTitle.text();
        titleSource = resolvedTitle.source();
        recognized = !"filename".equals(legacyTitleSource) || filenameMeta.recognized();
        artist = resolvedArtist.text();
        artistSource = resolvedArtist.source();
        if (artist.isBlank()) artist = "未知歌手";

        // 4) Resolve the file-level semantic layout before classification. A
        // one-stream DUAL_CHANNEL video is a karaoke source even though the
        // legacy probe-only classifier would call it an MV.
        AudioLayout storedLayout = existing.map(SongFile::getAudioLayout).orElse(null);
        AudioLayoutSource storedLayoutSource = existing.map(SongFile::getAudioLayoutSource)
                .orElse(AudioLayoutSource.AUTO_DEFAULT);
        AudioLayoutResolver.Result resolvedLayout = audioLayoutResolver.resolve(
                LibraryModePolicy.isExternalReadOnly(props)
                        ? LibraryMode.EXTERNAL_READ_ONLY : LibraryMode.MANAGED,
                storedLayoutSource, storedLayout, probe.audioTracks(), externalDefault);
        AudioLayout audioLayout = resolvedLayout.layout();
        String mediaType = MediaClassifier.classify(probe, audioLayout);
        boolean hasVocal = MediaClassifier.hasVocalTrack(probe, audioLayout);
        VocalTrackDetector.Result vocalDetect = VocalTrackDetector.detect(probe);
        Song existingSong = existing.map(SongFile::getSongId)
                .filter(Objects::nonNull)
                .flatMap(songRepo::findById)
                .orElse(null);
        boolean manualIdentity = hasManualIdentityOverride(existingSong);
        String effectiveTitle = existingSong != null && existingSong.isMetadataLocked("title")
                ? existingSong.getTitle() : title;
        String effectiveArtist = existingSong != null && existingSong.isMetadataLocked("artist")
                ? existingSong.getArtist() : artist;
        String fingerprint = MediaClassifier.fingerprint(effectiveArtist, effectiveTitle, probe.durationMs());

        // 5) 指纹去重：同指纹已存在 → 作为多文件源加入，按 priority 择优
        Optional<Song> dup = songRepo.findByFingerprint(fingerprint);
        Song provisional = existing.map(SongFile::getSongId)
                .filter(Objects::nonNull)
                .flatMap(songRepo::findById)
                .filter(LibraryScanService::isProvisionalSong)
                .orElse(null);
        boolean initialCreditsAlreadyMatch = false;
        Song song;
        boolean isNew;
        Song provisionalToDelete = null;
        if (manualIdentity) {
            Optional<Song> conflict = dup.filter(other -> !sameSong(other, existingSong));
            if (conflict.isPresent()) {
                throw new ApiException("FINGERPRINT_CONFLICT",
                        "人工确认的歌曲身份与歌曲 #" + conflict.get().getId() + " 冲突");
            }
            song = existingSong;
            applyProbedMetadata(song, title, artist, mediaType, hasVocal, probe,
                    fingerprint, recognized, tag, filenameMeta, titleSource, artistSource);
            isNew = false;
        } else if (dup.isPresent() && !sameSong(dup.get(), provisional)) {
            song = dup.get();
            isNew = false;
            provisionalToDelete = provisional;
        } else if (provisional != null) {
            String fastIndexedArtist = provisional.getArtist();
            song = provisional;
            applyProbedMetadata(song, title, artist, mediaType, hasVocal, probe,
                    fingerprint, recognized, tag, filenameMeta, titleSource, artistSource);
            initialCreditsAlreadyMatch = counters.initialArtistCreditSongIds.contains(provisional.getId())
                    && Objects.equals(fastIndexedArtist, song.getArtist());
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
            String language = effectiveLanguage(tag, probe, filenameMeta);
            if (!language.isBlank()) song.setLanguage(language);
            if (!filenameMeta.category().isBlank()) {
                // 现有模型没有独立 category 列；沿用 Home KTV 的 tags 数组承载文件名分类。
                song.setTags(new String[]{filenameMeta.category()});
            }
            if (!filenameMeta.vocalForm().isBlank()) {
                song.setVocalForm(filenameMeta.vocalForm());
            }
            song.setMetadataProvenance("{\"title\":{\"source\":\"" + titleSource
                    + "\"},\"artist\":{\"source\":\"" + artistSource + "\"}}");
            song.setNeedsAiOptimization(LibraryScanService.needsAiOptimization(recognized, song));
            isNew = true;
        }

        // The catalog number is filename-derived metadata. Keep it in sync for
        // new, existing, and fingerprint-merged rows, including clearing a
        // stale number when a file is renamed without the commercial prefix.
        if (!Objects.equals(song.getCatalogNumber(), filenameMeta.catalogNumber())) {
            song.setCatalogNumber(filenameMeta.catalogNumber());
        }

        // 6) 歌词/封面落盘。同名增强 LRC 优先，且允许侧车文件独立更新。
        if (sidecarLyricText != null) {
            String lyricPath = assetWriter.writeLyric(fingerprint, sidecarLyricText);
            song.setLyricPath(lyricPath);
            song.setLyricType(LyricType.detect(sidecarLyricText));
            song.setLyricSource(Song.LYRIC_SOURCE_SIDECAR);
        } else if (sidecarLyricContent.readable()
                && Song.LYRIC_SOURCE_SIDECAR.equals(song.getLyricSource())) {
            // Only clear a cache that this scanner previously established from this
            // sidecar. Legacy/embedded/manual lyric caches remain fail-closed.
            song.setLyricPath(null);
            song.setLyricType(LyricType.NONE);
            song.setLyricSource(Song.LYRIC_SOURCE_NONE);
        }
        // Fast Index creates a provisional row before probing. Its first
        // successful probe still needs to import embedded lyrics and cover;
        // a provisional row discarded in favor of an existing duplicate does not.
        boolean storeEmbeddedAssets = isNew || (provisional != null && provisionalToDelete == null);
        if (storeEmbeddedAssets) {
            String lyricText = sidecarLyricText == null ? tag.getEmbeddedLyric() : null;
            if (lyricText != null && !lyricText.isBlank()) {
                String lyricPath = assetWriter.writeLyric(fingerprint, lyricText);
                song.setLyricPath(lyricPath);
                song.setLyricType(LyricType.detect(lyricText));
                song.setLyricSource(Song.LYRIC_SOURCE_EMBEDDED);
            } else if (sidecarLyricText == null && !sidecarLyricContent.readable()) {
                song.setLyricSource(Song.LYRIC_SOURCE_UNKNOWN);
            } else if (sidecarLyricText == null) {
                song.setLyricSource(Song.LYRIC_SOURCE_NONE);
            }
            if (tag.getCoverImage() != null) {
                byte[] normalizedCover = normalizeEmbeddedCover(tag.getCoverImage());
                if (normalizedCover != null) {
                    String coverPath = assetWriter.writeCover(fingerprint, normalizedCover, "jpg");
                    song.setCoverPath(coverPath);
                }
            }
        }
        song = songRepo.save(song);
        if (!initialCreditsAlreadyMatch) syncArtistCredits(song, knownArtists);
        synchronized (counters) {
            counters.dbUpdates++;
        }

        // 7) 写 song_files（KTV 视频优先级高）
        int priority = switch (mediaType) {
            case MediaClassifier.KTV_VIDEO -> 100;
            case MediaClassifier.MV -> 50;
            default -> 10;
        };
        SongFile sf = existing.orElseGet(SongFile::new);
        Integer storedOriginalTrackIndex = existing.map(SongFile::getOriginalTrackIndex).orElse(null);
        sf.setSongId(song.getId());
        sf.setFilePath(pathStr);
        sf.setRelativePath(input.relativePath());
        sf.setFormat(extOf(file));
        sf.setAudioTracks(probe.audioTracks());
        sf.setAudioLayout(audioLayout);
        sf.setAudioLayoutSource(resolvedLayout.source());
        sf.setMediaType(mediaType);
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
            sf.setOriginalTrackIndex(storedOriginalTrackIndex != null
                    ? storedOriginalTrackIndex
                    : vocalTrackIndex == 0 ? 1 : 0);
        } else {
            sf.setVocalTrackIndex(null);
            sf.setOriginalTrackIndex(null);
            sf.setAccompanimentTrackIndex(null);
        }
        sf.setVocalConfidence(vocalConfidence);
        sf.setResolution(probe.resolution());
        sf.setFileSize(input.size());
        sf.setFileMtime(normalizeMtime(mtime));
        sf.setFileIdentity(input.fileIdentity());
        applySnapshots(sf, input);
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
        synchronized (counters) {
            counters.dbUpdates++;
        }
        restoreSongAfterSuccessfulProbe(song, counters);
        songProjectionService.recompute(song.getId());
        if (provisionalToDelete != null) songRepo.delete(provisionalToDelete);

        LibraryScanService.IngestOutcome outcome;
        if (!recognized) outcome = LibraryScanService.IngestOutcome.UNRECOGNIZED;
        else if (!input.existedBeforeScan() || input.pendingBeforeScan()) outcome = LibraryScanService.IngestOutcome.ADDED;
        else outcome = LibraryScanService.IngestOutcome.UPDATED;
        return new LibraryScanService.IngestState(outcome, song.getId(), sf.getId());
    }

    private byte[] normalizeEmbeddedCover(byte[] source) {
        if (coverImageNormalizer == null) return source;
        try {
            return coverImageNormalizer.normalize(source);
        } catch (ApiException failure) {
            log.warn("内嵌封面无效，跳过缓存：{}", failure.getMessage());
            return null;
        }
    }

    private void restoreSongAfterSuccessfulProbe(Song song, LibraryScanService.ScanCounters counters) {
        if (!"file_missing".equals(song.getStatus())) return;
        song.setStatus("ok");
        songRepo.save(song);
        counters.dbUpdates++;
    }

    private void applyProbedMetadata(Song song, String title, String artist, String mediaType,
                                            boolean hasVocal, MediaProbe probe, String fingerprint,
                                            boolean recognized, TagInfo tag, ParsedMeta filenameMeta,
                                            String titleSource, String artistSource) {
        boolean titleLocked = song.isMetadataLocked("title");
        boolean artistLocked = song.isMetadataLocked("artist");
        boolean languageLocked = song.isMetadataLocked("language");
        boolean tagsLocked = song.isMetadataLocked("tags");
        if (!titleLocked) {
            song.setTitle(title);
            song.setTitlePy(PinyinUtil.fullPinyin(title));
            song.setTitleInit(PinyinUtil.initials(title));
        }
        if (!artistLocked) {
            song.setArtist(artist);
            song.setArtistPy(PinyinUtil.fullPinyin(artist));
            song.setArtistInit(PinyinUtil.initials(artist));
        }
        song.setMediaType(mediaType);
        song.setHasVocalTrack(hasVocal);
        song.setDurationMs((int) probe.durationMs());
        song.setFingerprint(fingerprint);
        if (!hasManualIdentityOverride(song)) {
            song.setStatus(recognized ? "ok" : "unrecognized");
        }
        if (!languageLocked) {
            String language = effectiveLanguage(tag, probe, filenameMeta);
            if (!language.isBlank()) song.setLanguage(language);
        }
        if (!tagsLocked && !filenameMeta.category().isBlank()) {
            song.setTags(new String[]{filenameMeta.category()});
        }
        if (!song.isMetadataLocked("vocalForm") && !filenameMeta.vocalForm().isBlank()) {
            song.setVocalForm(filenameMeta.vocalForm());
        }
        if (!Objects.equals(song.getCatalogNumber(), filenameMeta.catalogNumber())) {
            song.setCatalogNumber(filenameMeta.catalogNumber());
        }
        if (!titleLocked && !artistLocked) {
            song.setMetadataProvenance("{\"title\":{\"source\":\"" + titleSource
                    + "\"},\"artist\":{\"source\":\"" + artistSource + "\"}}");
        }
        if (!hasManualIdentityOverride(song)) {
            song.setNeedsAiOptimization(LibraryScanService.needsAiOptimization(recognized, song));
        }
    }

    private boolean structuredFilenameMetadataPreferred() {
        return props.isStructuredFilenameMetadataPreferred();
    }

    private String effectiveLanguage(TagInfo tag, MediaProbe probe, ParsedMeta filenameMeta) {
        if (structuredFilenameMetadataPreferred() && LibraryMetadataResolver.isStructured(filenameMeta)) {
            return normalizeLanguage(filenameMeta.language());
        }
        if (tag.getLanguage() != null && !tag.getLanguage().isBlank()) {
            return normalizeLanguage(tag.getLanguage());
        }
        if (probe.language() != null && !probe.language().isBlank()) {
            return normalizeLanguage(probe.language());
        }
        return filenameMeta.language().isBlank() ? "" : normalizeLanguage(filenameMeta.language());
    }
}
