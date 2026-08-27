package com.homektv.library;

import com.homektv.config.AppProperties;
import com.homektv.domain.Song;
import com.homektv.domain.SongFile;
import com.homektv.domain.AudioLayout;
import com.homektv.domain.AudioChannel;
import com.homektv.repo.PlayHistoryRepository;
import com.homektv.repo.SongFileRepository;
import com.homektv.repo.SongRepository;
import com.homektv.repo.QueueItemRepository;
import com.homektv.repo.PlayerStateRepository;
import com.homektv.web.ApiException;
import com.homektv.web.dto.DashboardDto;
import com.homektv.web.dto.AdminSongDto;
import com.homektv.web.dto.AudioLayoutDto;
import com.homektv.web.dto.AudioLayoutUpdateRequest;
import com.homektv.web.dto.SongEditRequest;
import com.homektv.web.dto.VocalReviewDto;
import com.homektv.ws.WsBroadcaster;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 管理后台服务（P2.1-P2.5，详设§8）。
 */
@Service
public class AdminService {

    private final SongRepository songRepo;
    private final SongFileRepository fileRepo;
    private final PlayHistoryRepository historyRepo;
    private final WsBroadcaster broadcaster;
    private final AssetWriter assetWriter;
    private final QueueItemRepository queueRepo;
    private final PlayerStateRepository playerRepo;
    private final AppProperties props;

    public AdminService(SongRepository songRepo, SongFileRepository fileRepo,
                        PlayHistoryRepository historyRepo, WsBroadcaster broadcaster,
                        AssetWriter assetWriter, QueueItemRepository queueRepo,
                        PlayerStateRepository playerRepo, AppProperties props) {
        this.songRepo = songRepo;
        this.fileRepo = fileRepo;
        this.historyRepo = historyRepo;
        this.broadcaster = broadcaster;
        this.assetWriter = assetWriter;
        this.queueRepo = queueRepo;
        this.playerRepo = playerRepo;
        this.props = props;
    }

    /** 仪表盘统计（P2.1） */
    @Transactional(readOnly = true)
    public DashboardDto dashboard() {
        long total = songRepo.count();
        return new DashboardDto(
                total,
                songRepo.countByMediaType(MediaClassifier.KTV_VIDEO),
                songRepo.countByMediaType(MediaClassifier.MV),
                songRepo.countByMediaType(MediaClassifier.AUDIO),
                songRepo.countByStatus("unrecognized"),
                historyRepo.count(),
                broadcaster.sessionCount(),
                null, null   // 播放状态由前端另查 /queue，避免重复
        );
    }

    /** 曲库分页列表 + 类型筛选（P2.2）。type 支持 KTV_VIDEO/MV/AUDIO/unrecognized/空(全部) */
    @Transactional(readOnly = true)
    public Page<Song> listSongs(String type, int page, int size) {
        Pageable pageable = PageRequest.of(Math.max(0, page), size, Sort.by(Sort.Direction.DESC, "createdAt"));
        if (type == null || type.isBlank()) return songRepo.findAll(pageable);
        if ("unrecognized".equals(type)) return songRepo.findByStatus("unrecognized", pageable);
        return songRepo.findByMediaType(type, pageable);
    }

    @Transactional(readOnly = true)
    public Page<AdminSongDto> listAdminSongs(String keyword, String type, String source, int page, int size) {
        int safeSize = Math.max(1, Math.min(size, 200));
        int safePage = Math.max(0, page);
        Pageable pageable = PageRequest.of(safePage, safeSize, Sort.by(Sort.Direction.DESC, "createdAt"));
        Page<Song> songs = songRepo.searchAdminSongs(normalizeFilter(keyword), normalizeFilter(type),
                normalizeFilter(source), pageable);
        List<Long> songIds = songs.getContent().stream().map(Song::getId).toList();
        Map<Long, SongFile> primaryFiles = new LinkedHashMap<>();
        if (!songIds.isEmpty()) {
            fileRepo.findBySongIdInAndValidTrueOrderByPriorityDesc(songIds)
                    .forEach(file -> primaryFiles.putIfAbsent(file.getSongId(), file));
        }
        return songs.map(song -> AdminSongDto.from(song, primaryFiles.get(song.getId())));
    }

    @Transactional(readOnly = true)
    public AdminSongDto getAdminSong(Long id) {
        Song song = songRepo.findById(id).orElseThrow(() -> new ApiException("SONG_NOT_FOUND", "歌曲不存在"));
        SongFile file = fileRepo.findBySongIdAndValidTrueOrderByPriorityDesc(id).stream().findFirst().orElse(null);
        return AdminSongDto.from(song, file);
    }

    private static String normalizeFilter(String value) {
        return value == null ? "" : value.trim();
    }

    /** 编辑曲目（P2.3）：改元数据后重算拼音；可粘贴歌词 */
    @Transactional
    public Song editSong(Long id, SongEditRequest req) {
        Song song = songRepo.findById(id)
                .orElseThrow(() -> new ApiException("SONG_NOT_FOUND", "歌曲不存在"));
        boolean manualIdentityEdited = false;
        if (req.title() != null && !req.title().isBlank()) {
            song.setTitle(req.title().trim());
            song.setTitlePy(PinyinUtil.fullPinyin(req.title()));
            song.setTitleInit(PinyinUtil.initials(req.title()));
            song.lockMetadata("title");
            manualIdentityEdited = true;
        }
        if (req.artist() != null && !req.artist().isBlank()) {
            song.setArtist(req.artist().trim());
            song.setArtistPy(PinyinUtil.fullPinyin(req.artist()));
            song.setArtistInit(PinyinUtil.initials(req.artist()));
            song.lockMetadata("artist");
            manualIdentityEdited = true;
        }
        if (req.language() != null) { song.setLanguage(req.language()); song.lockMetadata("language"); }
        if (req.vocalForm() != null && !req.vocalForm().isBlank()) { song.setVocalForm(req.vocalForm()); song.lockMetadata("vocalForm"); }
        if (req.artistGender() != null && !req.artistGender().isBlank()) {
            String gender = req.artistGender().trim();
            if (!java.util.Set.of("男歌手", "女歌手", "组合", "未知").contains(gender))
                throw new ApiException("INVALID_ARTIST_GENDER", "歌手类型只能是男歌手、女歌手、组合或未知");
            song.setArtistGender(gender);
            song.lockMetadata("artistGender");
        }
        if (req.tags() != null) {
            song.setTags(req.tags());
            song.lockMetadata("tags");
        }
        if (manualIdentityEdited) {
            String fingerprint = MediaClassifier.fingerprint(
                    song.getArtist(), song.getTitle(), song.getDurationMs());
            songRepo.findByFingerprint(fingerprint)
                    .filter(other -> !java.util.Objects.equals(other.getId(), song.getId()))
                    .ifPresent(other -> {
                        throw new ApiException("SONG_FINGERPRINT_CONFLICT",
                                "修改后的歌曲身份与歌曲 #" + other.getId() + " 重复");
                    });
            song.setFingerprint(fingerprint);
        }
        if (req.lyricText() != null && !req.lyricText().isBlank()) {
            String path = assetWriter.writeLyric(song.getFingerprint(), req.lyricText());
            song.setLyricPath(path);
            song.setLyricType(LyricType.detect(req.lyricText()));
            song.setLyricSource(Song.LYRIC_SOURCE_MANUAL);
        }
        // 只有人工确认歌名或歌手时，才允许未识别歌曲转正
        if (manualIdentityEdited && "unrecognized".equals(song.getStatus())) {
            song.setStatus("ok");
        }
        return songRepo.save(song);
    }

    /**
     * 伴奏轨低置信度复核列表：入库判不准原伴唱、回落默认轨的文件源，供人工核对后 swap 校正。
     */
    @Transactional(readOnly = true)
    public Page<VocalReviewDto> listVocalReview(int page, int size) {
        Pageable pageable = PageRequest.of(Math.max(0, page), size, Sort.by(Sort.Direction.ASC, "songId"));
        return fileRepo.findByVocalConfidence("LOW", pageable).map(f -> {
            Song s = songRepo.findById(f.getSongId()).orElse(null);
            return new VocalReviewDto(
                    f.getId(), f.getSongId(),
                    s != null ? s.getTitle() : null,
                    s != null ? s.getArtist() : null,
                    f.getFormat(), f.getAudioTracks(),
                    f.getVocalTrackIndex(), f.getVocalConfidence());
        });
    }

    /**
     * 后台人工确认伴奏轨：直接指定 index 并标 HIGH（脱离复核列表）。
     * index 须在音轨范围内。
     */
    @Transactional
    public void confirmVocalTrack(Long fileId, int accompanimentIndex) {
        SongFile file = fileRepo.findById(fileId)
                .orElseThrow(() -> new ApiException("FILE_NOT_FOUND", "文件源不存在"));
        if (accompanimentIndex < 0 || accompanimentIndex >= file.getAudioTracks()) {
            throw new ApiException("INVALID_ACTION",
                    "伴奏轨 index 越界：" + accompanimentIndex + "（共 " + file.getAudioTracks() + " 轨）");
        }
        file.setAudioLayout(AudioLayout.DUAL_TRACK);
        file.setVocalTrackIndex(accompanimentIndex);
        file.setAccompanimentTrackIndex(accompanimentIndex);
        file.setOriginalTrackIndex(accompanimentIndex == 0 ? 1 : 0);
        file.setVocalConfidence("HIGH");
        fileRepo.save(file);
    }

    /**
     * Update only the persisted audio semantics for one file. No media bytes,
     * source path, or source-file metadata are written by this operation.
     */
    @Transactional
    public AudioLayoutDto updateAudioLayout(Long fileId, AudioLayoutUpdateRequest request) {
        SongFile file = findFile(fileId);
        if (request == null) throw new ApiException("INVALID_AUDIO_LAYOUT", "缺少音频布局设置");

        switch (request.layout()) {
            case NORMAL_STEREO -> {
                file.setAudioLayout(AudioLayout.NORMAL_STEREO);
                clearTrackSemantics(file);
                setDefaultChannels(file);
            }
            case DUAL_CHANNEL -> {
                AudioChannel original = request.originalChannel();
                AudioChannel accompaniment = request.accompanimentChannel();
                if (original == accompaniment) {
                    throw new ApiException("INVALID_AUDIO_LAYOUT", "原唱和伴唱必须使用不同声道");
                }
                file.setAudioLayout(AudioLayout.DUAL_CHANNEL);
                file.setOriginalChannel(original);
                file.setAccompanimentChannel(accompaniment);
                clearTrackSemantics(file);
            }
            case DUAL_TRACK -> {
                if (file.getAudioTracks() < 2) {
                    throw new ApiException("INVALID_AUDIO_LAYOUT", "双音轨布局要求媒体至少有 2 条音轨");
                }
                int accompaniment = request.accompanimentTrackIndex() != null
                        ? request.accompanimentTrackIndex()
                        : defaultAccompanimentTrack(file);
                int original = request.originalTrackIndex() != null
                        ? request.originalTrackIndex()
                        : defaultOriginalTrack(file, accompaniment);
                validateTrackIndex(original, file.getAudioTracks(), "原唱");
                validateTrackIndex(accompaniment, file.getAudioTracks(), "伴唱");
                if (original == accompaniment) {
                    throw new ApiException("INVALID_AUDIO_LAYOUT", "原唱和伴唱不能是同一条音轨");
                }
                file.setAudioLayout(AudioLayout.DUAL_TRACK);
                file.setOriginalTrackIndex(original);
                file.setAccompanimentTrackIndex(accompaniment);
                setDefaultChannels(file);
                file.setVocalConfidence("HIGH");
            }
        }
        return AudioLayoutDto.from(fileRepo.save(file));
    }

    /** Swap the semantic original/accompaniment assignment in the database only. */
    @Transactional
    public AudioLayoutDto swapAudioLayout(Long fileId) {
        SongFile file = findFile(fileId);
        if (file.getAudioLayout() == AudioLayout.DUAL_TRACK
                && (file.getOriginalTrackIndex() == null || file.getAccompanimentTrackIndex() == null)) {
            throw new ApiException("INVALID_AUDIO_LAYOUT", "双音轨布局缺少原唱或伴唱音轨");
        }
        file.swapOriginalAndAccompaniment();
        return AudioLayoutDto.from(fileRepo.save(file));
    }

    private SongFile findFile(Long fileId) {
        return fileRepo.findById(fileId)
                .orElseThrow(() -> new ApiException("FILE_NOT_FOUND", "文件源不存在"));
    }

    private static int defaultAccompanimentTrack(SongFile file) {
        Integer current = file.getAccompanimentTrackIndex();
        return current != null ? current : 1;
    }

    private static int defaultOriginalTrack(SongFile file, int accompaniment) {
        Integer current = file.getOriginalTrackIndex();
        return current != null ? current : accompaniment == 0 ? 1 : 0;
    }

    private static void validateTrackIndex(int index, int trackCount, String label) {
        if (index < 0 || index >= trackCount) {
            throw new ApiException("INVALID_AUDIO_LAYOUT", label + "音轨 index 越界：" + index
                    + "（共 " + trackCount + " 轨）");
        }
    }

    private static void clearTrackSemantics(SongFile file) {
        file.setVocalTrackIndex(null);
        file.setOriginalTrackIndex(null);
        file.setAccompanimentTrackIndex(null);
        file.setVocalConfidence(null);
    }

    private static void setDefaultChannels(SongFile file) {
        file.setOriginalChannel(AudioChannel.LEFT);
        file.setAccompanimentChannel(AudioChannel.RIGHT);
    }

    /** 删除正式曲库歌曲：删除 /music 下文件和数据库记录，不影响扫描源目录。 */
    @Transactional
    public void deleteSong(Long id) {
        LibraryModePolicy.requireManaged(props, "删除曲库歌曲");
        Song song = songRepo.findById(id)
                .orElseThrow(() -> new ApiException("SONG_NOT_FOUND", "歌曲不存在"));
        deleteLibraryFiles(id);
        var queueItems = queueRepo.findBySongId(id);
        var player = playerRepo.getSingleton();
        if (queueItems.stream().anyMatch(q -> q.getId().equals(player.getCurrentQueueId()))) {
            player.setCurrentQueueId(null);
            player.setState("idle");
            playerRepo.save(player);
        }
        queueRepo.deleteAll(queueItems);
        historyRepo.deleteBySongId(id);
        songRepo.delete(song); // song_files 级联删除（ON DELETE CASCADE）
    }

    @Transactional
    public int deleteSongs(Collection<Long> ids) {
        if (ids == null || ids.isEmpty()) return 0;
        int deleted = 0;
        for (Long id : ids.stream().distinct().toList()) {
            deleteSong(id);
            deleted++;
        }
        return deleted;
    }

    private void deleteLibraryFiles(Long songId) {
        LibraryModePolicy.requireManaged(props, "删除曲库文件");
        Path root = Path.of(props.getKtvLibraryPath()).toAbsolutePath().normalize();
        for (SongFile file : fileRepo.findBySongIdOrderByPriorityDesc(songId)) {
            Path path = Path.of(file.getFilePath()).toAbsolutePath().normalize();
            if (!path.startsWith(root)) {
                throw new ApiException("INVALID_LIBRARY_PATH", "拒绝删除 KTV 曲库目录以外的文件：" + path);
            }
            try {
                Files.deleteIfExists(path);
            } catch (IOException e) {
                throw new ApiException("DELETE_LIBRARY_FILE_FAILED", "删除 KTV 文件失败：" + path + "，" + e.getMessage());
            }
        }
    }
}
