package com.homektv.queue;

import com.homektv.domain.AppUser;
import com.homektv.domain.PlayerState;
import com.homektv.domain.QueueItem;
import com.homektv.domain.Song;
import com.homektv.repo.AppUserRepository;
import com.homektv.repo.PlayerStateRepository;
import com.homektv.repo.QueueItemRepository;
import com.homektv.repo.SongRepository;
import com.homektv.repo.SongFileRepository;
import com.homektv.web.dto.AudioLayoutDto;
import com.homektv.web.dto.QueueSnapshot;
import com.homektv.web.dto.SongDto;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 组装队列+播放状态快照（详设§11.1 / §4.2 sync_full）。
 */
@Service
public class SnapshotService {

    private final PlayerStateRepository playerRepo;
    private final QueueItemRepository queueRepo;
    private final SongRepository songRepo;
    private final AppUserRepository userRepo;
    private final com.homektv.ws.WsBroadcaster broadcaster;
    private final SongFileRepository songFileRepo;

    @Autowired
    public SnapshotService(PlayerStateRepository playerRepo, QueueItemRepository queueRepo,
                           SongRepository songRepo, AppUserRepository userRepo,
                           com.homektv.ws.WsBroadcaster broadcaster, SongFileRepository songFileRepo) {
        this.playerRepo = playerRepo;
        this.queueRepo = queueRepo;
        this.songRepo = songRepo;
        this.userRepo = userRepo;
        this.broadcaster = broadcaster;
        this.songFileRepo = songFileRepo;
    }

    /** Compatibility constructor for isolated service tests. */
    public SnapshotService(PlayerStateRepository playerRepo, QueueItemRepository queueRepo,
                           SongRepository songRepo, AppUserRepository userRepo,
                           com.homektv.ws.WsBroadcaster broadcaster) {
        this(playerRepo, queueRepo, songRepo, userRepo, broadcaster, null);
    }

    @Transactional(readOnly = true)
    public QueueSnapshot snapshot() {
        PlayerState ps = playerRepo.getSingleton();
        List<QueueItem> waiting = queueRepo.findByStatusOrderByOrderIndexAsc(QueueService.WAITING);

        // 批量取歌曲与昵称，避免 N+1
        Map<Long, Song> songs = new HashMap<>();
        Map<Long, String> nicks = new HashMap<>();

        QueueSnapshot.NowPlaying nowPlaying = null;
        if (ps.getCurrentQueueId() != null) {
            QueueItem cur = queueRepo.findById(ps.getCurrentQueueId()).orElse(null);
            if (cur != null) {
                Song song = songOf(songs, cur.getSongId());
                nowPlaying = new QueueSnapshot.NowPlaying(
                        cur.getId(),
                        song != null ? SongDto.from(song) : null,
                        nickOf(nicks, cur.getOrderedBy()));
            }
        }

        List<QueueSnapshot.QueueEntry> list = waiting.stream()
                .map(q -> {
                    Song song = songOf(songs, q.getSongId());
                    return new QueueSnapshot.QueueEntry(
                            q.getId(),
                            song != null ? SongDto.from(song) : null,
                            q.getOrderedBy(),
                            nickOf(nicks, q.getOrderedBy()),
                            q.getStatus());
                })
                .toList();

        AudioLayoutDto audioLayout = AudioLayoutDto.normalStereo();
        if (songFileRepo != null && ps.getCurrentQueueId() != null) {
            QueueItem current = queueRepo.findById(ps.getCurrentQueueId()).orElse(null);
            if (current != null) {
                audioLayout = songFileRepo.findBySongIdAndValidTrueOrderByPriorityDesc(current.getSongId())
                        .stream()
                        .findFirst()
                        .map(AudioLayoutDto::from)
                        .orElseGet(AudioLayoutDto::normalStereo);
            }
        }

        return new QueueSnapshot(nowPlaying, list, ps.getState(), ps.getVolume(),
                ps.isMuted(), ps.getVocalMode(), audioLayout,
                broadcaster.isTvOnline(), broadcaster.h5Count());
    }

    private Song songOf(Map<Long, Song> cache, Long id) {
        if (id == null) return null;
        return cache.computeIfAbsent(id, k -> songRepo.findById(k).orElse(null));
    }

    private String nickOf(Map<Long, String> cache, Long userId) {
        if (userId == null) return null;
        return cache.computeIfAbsent(userId,
                k -> userRepo.findById(k).map(AppUser::getNickname).orElse(null));
    }
}
