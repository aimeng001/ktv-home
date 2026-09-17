package com.homektv.queue;

import com.homektv.domain.PlayHistory;
import com.homektv.domain.PlayerState;
import com.homektv.domain.QueueItem;
import com.homektv.domain.Song;
import com.homektv.domain.SongFile;
import com.homektv.domain.AudioLayout;
import com.homektv.domain.AudioLayoutSource;
import com.homektv.library.SongAvailabilityPolicy;
import com.homektv.repo.PlayHistoryRepository;
import com.homektv.repo.PlayerStateRepository;
import com.homektv.repo.QueueItemRepository;
import com.homektv.repo.SongRepository;
import com.homektv.repo.SongFileRepository;
import com.homektv.web.ApiException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.List;

/**
 * 播放控制状态机（P1.10/P1.11，详设§4.4/§9.2/§9.4）。
 * play/pause/next/restart + 音量/静音/原伴唱；状态落 player_state。
 */
@Service
public class PlaybackService {

    private final PlayerStateRepository playerRepo;
    private final QueueItemRepository queueRepo;
    private final SongRepository songRepo;
    private final PlayHistoryRepository historyRepo;
    private final SongFileRepository fileRepo;
    private final SongAvailabilityPolicy availabilityPolicy;

    /** Compatibility constructor for direct unit callers; Spring uses the injected policy below. */
    public PlaybackService(PlayerStateRepository playerRepo, QueueItemRepository queueRepo,
                           SongRepository songRepo, PlayHistoryRepository historyRepo,
                           SongFileRepository fileRepo) {
        this(playerRepo, queueRepo, songRepo, historyRepo, fileRepo,
                new SongAvailabilityPolicy(fileRepo));
    }

    @Autowired
    public PlaybackService(PlayerStateRepository playerRepo, QueueItemRepository queueRepo,
                           SongRepository songRepo, PlayHistoryRepository historyRepo,
                           SongFileRepository fileRepo, SongAvailabilityPolicy availabilityPolicy) {
        this.playerRepo = playerRepo;
        this.queueRepo = queueRepo;
        this.songRepo = songRepo;
        this.historyRepo = historyRepo;
        this.fileRepo = fileRepo;
        this.availabilityPolicy = availabilityPolicy;
    }

    /** 开始/恢复播放。若当前无曲目，尝试从队列取第一首。 */
    @Transactional
    public PlayerState play() {
        queueRepo.lockQueueMutation();
        PlayerState ps = playerRepo.getSingletonForUpdate();
        if (ps.getCurrentQueueId() == null) {
            advanceToNext(ps);
        } else {
            ps.setState("playing");
        }
        return playerRepo.save(ps);
    }

    /** 点歌后仅在播放器空闲时自动开始，不打断正在暂停的歌曲。 */
    @Transactional
    public boolean startIfIdle() {
        queueRepo.lockQueueMutation();
        PlayerState ps = playerRepo.getSingletonForUpdate();
        if (ps.getCurrentQueueId() != null || !"idle".equals(ps.getState())) {
            return false;
        }
        advanceToNext(ps);
        playerRepo.save(ps);
        return ps.getCurrentQueueId() != null;
    }

    @Transactional
    public PlayerState pause() {
        PlayerState ps = playerRepo.getSingletonForUpdate();
        if ("playing".equals(ps.getState())) {
            ps.setState("paused");
        }
        return playerRepo.save(ps);
    }

    /** Stop playback without consuming or removing the current queue item. */
    @Transactional
    public PlayerState stop() {
        PlayerState ps = playerRepo.getSingletonForUpdate();
        ps.setState("idle");
        return playerRepo.save(ps);
    }

    /** Explicit seek requested by a remote controller. */
    @Transactional
    public PlayerState seek(long positionMs) {
        if (positionMs < 0) {
            throw new ApiException("INVALID_ACTION", "播放位置不能为负数");
        }
        PlayerState ps = playerRepo.getSingletonForUpdate();
        if (ps.getCurrentQueueId() == null) {
            throw new ApiException("INVALID_ACTION", "当前没有正在播放的歌曲");
        }
        ps.setPositionMs(positionMs);
        ps.setSeekSequence(ps.getSeekSequence() + 1);
        return playerRepo.save(ps);
    }

    /**
     * Persist a player-reported position. The WebSocket layer requires a queue
     * id for registered players; the nullable argument keeps direct/API callers
     * backward-compatible while still rejecting mismatched ids when provided.
     */
    @Transactional
    public PositionUpdateResult updatePosition(Long expectedQueueId, long positionMs) {
        PlayerState ps = playerRepo.getSingletonForUpdate();
        if (expectedQueueId != null && !expectedQueueId.equals(ps.getCurrentQueueId())) {
            return PositionUpdateResult.rejected(ps);
        }
        if (ps.getCurrentQueueId() == null || "idle".equals(ps.getState())) {
            return PositionUpdateResult.rejected(ps);
        }
        long normalized = Math.max(0, positionMs);
        if (ps.getPositionMs() != normalized) {
            ps.setPositionMs(normalized);
            return PositionUpdateResult.accepted(playerRepo.save(ps));
        }
        return PositionUpdateResult.accepted(ps);
    }

    /** 切歌：当前行标记 skipped，推进到下一首（详设§9.2）。 */
    @Transactional
    public PlayerState next() {
        queueRepo.lockQueueMutation();
        PlayerState ps = playerRepo.getSingletonForUpdate();
        markCurrent(ps, QueueService.SKIPPED, true);
        advanceToNext(ps);
        return playerRepo.save(ps);
    }

    @Transactional
    public FinishResult onFinished(Long expectedQueueId) {
        queueRepo.lockQueueMutation();
        PlayerState ps = playerRepo.getSingletonForUpdate();
        if (expectedQueueId == null) {
            return FinishResult.stale(ps, null);
        }

        if (!expectedQueueId.equals(ps.getCurrentQueueId())) {
            return historyRepo.existsByQueueId(expectedQueueId)
                    ? FinishResult.alreadyApplied(ps, expectedQueueId)
                    : FinishResult.stale(ps, expectedQueueId);
        }

        QueueItem item = queueRepo.findById(expectedQueueId).orElse(null);
        if (item == null || !QueueService.PLAYING.equals(item.getStatus())) {
            return historyRepo.existsByQueueId(expectedQueueId)
                    ? FinishResult.alreadyApplied(ps, expectedQueueId)
                    : FinishResult.stale(ps, expectedQueueId);
        }

        item.setStatus(QueueService.DONE);
        item.setPlayedAt(OffsetDateTime.now());
        queueRepo.save(item);
        recordHistory(item);
        advanceToNext(ps);
        return FinishResult.applied(playerRepo.save(ps), expectedQueueId);
    }

    @Transactional
    public PlaybackTransitionResult onPlayError(Long fileId) {
        return onPlayError(fileId, null);
    }

    @Transactional
    public PlaybackTransitionResult onPlayError(Long fileId, Long expectedQueueId) {
        queueRepo.lockQueueMutation();
        PlayerState ps = playerRepo.getSingletonForUpdate();
        if (expectedQueueId != null && !expectedQueueId.equals(ps.getCurrentQueueId())) {
            return PlaybackTransitionResult.rejected(ps);
        }
        // fileId is retained for wire compatibility, but a client playback
        // error does not prove that the source file is invalid. Server-side
        // scanning remains responsible for marking disappeared files.
        markCurrent(ps, QueueService.SKIPPED, false);
        advanceToNext(ps);
        return PlaybackTransitionResult.accepted(playerRepo.save(ps));
    }

    @Transactional
    public PlayerState recoverAfterRestart() {
        queueRepo.lockQueueMutation();
        PlayerState ps = playerRepo.getSingletonForUpdate();
        QueueItem current = ps.getCurrentQueueId() == null
                ? null
                : queueRepo.findById(ps.getCurrentQueueId()).orElse(null);
        if (current != null
                && (QueueService.DONE.equals(current.getStatus())
                || QueueService.SKIPPED.equals(current.getStatus()))) {
            current = null;
        }

        List<QueueItem> playingItems = queueRepo.findByStatusOrderByOrderIndexAsc(QueueService.PLAYING);
        if (current == null && !playingItems.isEmpty()) {
            current = playingItems.get(0);
        }

        if (current == null) {
            ps.setCurrentQueueId(null);
            ps.setState("idle");
            advanceToNext(ps);
            return playerRepo.save(ps);
        }

        for (QueueItem playing : playingItems) {
            if (!playing.getId().equals(current.getId())) {
                playing.setStatus(QueueService.WAITING);
                queueRepo.save(playing);
            }
        }
        current.setStatus(QueueService.PLAYING);
        queueRepo.save(current);
        ps.setCurrentQueueId(current.getId());
        ps.setState("playing");
        return playerRepo.save(ps);
    }

    /** 重唱：当前曲目回到 0，队列不变（详设§4.4.5）。 */
    @Transactional
    public PlayerState restart() {
        PlayerState ps = playerRepo.getSingletonForUpdate();
        if (ps.getCurrentQueueId() == null) {
            throw new ApiException("INVALID_ACTION", "当前没有正在播放的歌曲");
        }
        ps.setState("playing");
        ps.setPositionMs(0);
        ps.setSeekSequence(ps.getSeekSequence() + 1);
        return playerRepo.save(ps);
    }

    @Transactional
    public PlayerState setVolume(int volume) {
        PlayerState ps = playerRepo.getSingletonForUpdate();
        ps.setVolume(Math.max(0, Math.min(100, volume)));
        return playerRepo.save(ps);
    }

    @Transactional
    public PlayerState setMuted(boolean muted) {
        PlayerState ps = playerRepo.getSingletonForUpdate();
        ps.setMuted(muted);
        return playerRepo.save(ps);
    }

    /** 原/伴唱切换（详设§9.4）。仅 A 类（有伴唱轨）有意义。 */
    @Transactional
    public PlayerState setVocalMode(String mode) {
        if (!"original".equals(mode) && !"accompaniment".equals(mode)) {
            throw new ApiException("INVALID_ACTION", "无效的原伴唱模式：" + mode);
        }
        PlayerState ps = playerRepo.getSingletonForUpdate();
        ps.setVocalMode(mode);
        return playerRepo.save(ps);
    }

    /** 当前歌曲原/伴唱标记反转时，交换前两条音轨的语义并持久保存。 */
    @Transactional
    public PlayerState swapVocalTracks() {
        PlayerState ps = playerRepo.getSingletonForUpdate();
        if (ps.getCurrentQueueId() == null) {
            throw new ApiException("INVALID_ACTION", "当前没有正在播放的歌曲");
        }
        QueueItem current = queueRepo.findById(ps.getCurrentQueueId())
                .orElseThrow(() -> new ApiException("QUEUE_ITEM_NOT_FOUND", "当前队列项不存在"));
        SongFile file = fileRepo.findBySongIdAndValidTrueOrderByPriorityDesc(current.getSongId()).stream()
                .findFirst()
                .orElseThrow(() -> new ApiException("FILE_NOT_FOUND", "当前歌曲没有可用文件源"));
        if (file.getAudioLayout() != AudioLayout.DUAL_CHANNEL && file.getAudioTracks() < 2) {
            throw new ApiException("INVALID_ACTION", "当前歌曲没有可交换的双音轨");
        }
        if (file.getAudioLayout() == AudioLayout.NORMAL_STEREO) {
            // Preserve the legacy two-track behavior for rows created before
            // V17, while making the persisted layout explicit.
            file.setAudioLayout(AudioLayout.DUAL_TRACK);
        }
        file.setAudioLayoutSource(AudioLayoutSource.MANUAL);
        file.swapOriginalAndAccompaniment();
        // 用户手动交换即人工确认，标 HIGH，后续复核列表不再显示
        file.setVocalConfidence("HIGH");
        fileRepo.save(file);
        return ps;
    }

    /**
     * TV 离线超时：清空等待队列并停止当前播放（不写历史），
     * 避免 TV 下次上线时快照带着未播完的歌而自动续播。返回是否有内容被清空。
     */
    @Transactional
    public boolean clearOnTvOffline() {
        queueRepo.lockQueueMutation();
        PlayerState ps = playerRepo.getSingletonForUpdate();
        List<QueueItem> waiting = queueRepo.findByStatusOrderByOrderIndexAsc(QueueService.WAITING);
        if (waiting.isEmpty() && ps.getCurrentQueueId() == null) {
            return false;
        }
        markCurrent(ps, QueueService.SKIPPED, false);
        queueRepo.deleteAll(waiting);
        ps.setCurrentQueueId(null);
        ps.setState("idle");
        ps.setPositionMs(0);
        playerRepo.save(ps);
        return true;
    }

    // ---- 内部 ----

    /** 标记当前播放行为指定终态，并写历史。 */
    private void markCurrent(PlayerState ps, String endStatus, boolean recordHistory) {
        Long cur = ps.getCurrentQueueId();
        if (cur == null) return;
        queueRepo.findById(cur).ifPresent(item -> {
            if (QueueService.PLAYING.equals(item.getStatus())) {
                item.setStatus(endStatus);
                item.setPlayedAt(OffsetDateTime.now());
                queueRepo.save(item);
                if (recordHistory) {
                    recordHistory(item);
                }
            }
        });
    }

    private void recordHistory(QueueItem item) {
        if (historyRepo.existsByQueueId(item.getId())) return;
        PlayHistory h = new PlayHistory();
        h.setQueueId(item.getId());
        h.setSongId(item.getSongId());
        h.setPlayedBy(item.getOrderedBy());
        historyRepo.save(h);
        songRepo.findById(item.getSongId()).ifPresent(s -> {
            s.setPlayCount(s.getPlayCount() + 1);
            songRepo.save(s);
        });
    }

    /** 推进到下一首等待歌曲；无则进入 idle（待机）。 */
    private void advanceToNext(PlayerState ps) {
        List<QueueItem> waiting = queueRepo.findByStatusOrderByOrderIndexAsc(QueueService.WAITING);
        if (waiting.isEmpty()) {
            ps.setCurrentQueueId(null);
            ps.setState("idle");
            ps.setPositionMs(0);
            return;
        }

        Set<Long> songIds = waiting.stream()
                .map(QueueItem::getSongId)
                .filter(java.util.Objects::nonNull)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        Map<Long, Song> songs = new HashMap<>();
        songRepo.findAllById(songIds).forEach(song -> {
            if (song != null && song.getId() != null) songs.put(song.getId(), song);
        });
        Set<Long> playableSongIds = availabilityPolicy.playableSongIds(songs.values());

        for (QueueItem nextItem : waiting) {
            if (!playableSongIds.contains(nextItem.getSongId())) {
                nextItem.setStatus(QueueService.SKIPPED);
                nextItem.setPlayedAt(OffsetDateTime.now());
                queueRepo.save(nextItem);
                continue;
            }
            nextItem.setStatus(QueueService.PLAYING);
            queueRepo.save(nextItem);
            ps.setCurrentQueueId(nextItem.getId());
            ps.setState("playing");
            ps.setPositionMs(0);
            return;
        }
        ps.setCurrentQueueId(null);
        ps.setState("idle");
        ps.setPositionMs(0);
    }
}
