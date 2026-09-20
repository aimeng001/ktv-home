package com.homektv.queue;

import com.homektv.domain.AppUser;
import com.homektv.domain.PlayerState;
import com.homektv.domain.QueueItem;
import com.homektv.domain.Song;
import com.homektv.domain.SongFile;
import com.homektv.repo.AppUserRepository;
import com.homektv.repo.PlayerStateRepository;
import com.homektv.repo.QueueItemRepository;
import com.homektv.repo.SongRepository;
import com.homektv.repo.SongFileRepository;
import com.homektv.library.SongAvailabilityPolicy;
import com.homektv.playback.PlaybackVariantService;
import com.homektv.web.ApiException;
import com.homektv.web.dto.AudioLayoutDto;
import com.homektv.web.dto.PlaybackDescriptor;
import com.homektv.web.dto.QueueSnapshot;
import com.homektv.web.dto.SongDto;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

/** 组装队列+播放状态快照（详设§11.1 / §4.2 sync_full）。 */
@Service
public class SnapshotService {

    private final PlayerStateRepository playerRepo;
    private final QueueItemRepository queueRepo;
    private final SongRepository songRepo;
    private final AppUserRepository userRepo;
    private final com.homektv.ws.WsBroadcaster broadcaster;
    private final SongFileRepository songFileRepo;
    private final PlaybackVariantService playbackVariants;
    /** Monotonic wire revision used to reject an older HTTP response. */
    private final AtomicLong snapshotRevision = new AtomicLong(System.currentTimeMillis());

    @Autowired
    public SnapshotService(PlayerStateRepository playerRepo, QueueItemRepository queueRepo,
                           SongRepository songRepo, AppUserRepository userRepo,
                           com.homektv.ws.WsBroadcaster broadcaster,
                           SongFileRepository songFileRepo,
                           PlaybackVariantService playbackVariants) {
        this.playerRepo = playerRepo;
        this.queueRepo = queueRepo;
        this.songRepo = songRepo;
        this.userRepo = userRepo;
        this.broadcaster = broadcaster;
        this.songFileRepo = songFileRepo;
        this.playbackVariants = playbackVariants;
    }

    /** Compatibility constructor for isolated service tests. */
    public SnapshotService(PlayerStateRepository playerRepo, QueueItemRepository queueRepo,
                           SongRepository songRepo, AppUserRepository userRepo,
                           com.homektv.ws.WsBroadcaster broadcaster) {
        this(playerRepo, queueRepo, songRepo, userRepo, broadcaster, null, null);
    }

    /** Compatibility constructor for tests that provide the file repository only. */
    public SnapshotService(PlayerStateRepository playerRepo, QueueItemRepository queueRepo,
                           SongRepository songRepo, AppUserRepository userRepo,
                           com.homektv.ws.WsBroadcaster broadcaster,
                           SongFileRepository songFileRepo) {
        this(playerRepo, queueRepo, songRepo, userRepo, broadcaster, songFileRepo, null);
    }

    @Transactional(readOnly = true)
    public QueueSnapshot snapshot() {
        long stateRevision = snapshotRevision.incrementAndGet();
        PlayerState ps = playerRepo.getSingleton();
        long waitingCount = queueRepo.countByStatus(QueueService.WAITING);
        if (waitingCount > QueueService.MAX_WAITING_ITEMS) {
            throw new ApiException("QUEUE_TOO_LARGE", "等待队列超过同步上限");
        }
        List<QueueItem> waiting = queueRepo.findByStatusOrderByOrderIndexAsc(QueueService.WAITING);

        QueueItem current = ps.getCurrentQueueId() == null
                ? null : queueRepo.findById(ps.getCurrentQueueId()).orElse(null);
        Set<Long> songIds = new HashSet<>();
        Set<Long> userIds = new HashSet<>();
        addIds(current, songIds, userIds);
        for (QueueItem item : waiting) addIds(item, songIds, userIds);

        Map<Long, Song> songs = loadSongs(songIds);
        Map<Long, String> nicks = loadNicknames(userIds);
        QueueSnapshot.NowPlaying nowPlaying = null;
        if (current != null) {
            Song song = songs.get(current.getSongId());
            nowPlaying = new QueueSnapshot.NowPlaying(current.getId(),
                    song != null ? SongDto.from(song) : null,
                    nicks.get(current.getOrderedBy()));
        }

        List<QueueSnapshot.QueueEntry> list = waiting.stream()
                .map(q -> {
                    Song song = songs.get(q.getSongId());
                    return new QueueSnapshot.QueueEntry(q.getId(),
                            song != null ? SongDto.from(song) : null,
                            q.getOrderedBy(), nicks.get(q.getOrderedBy()), q.getStatus());
                })
                .toList();

        AudioLayoutDto audioLayout = AudioLayoutDto.normalStereo();
        PlaybackDescriptor playback = PlaybackDescriptor.idle();
        if (songFileRepo != null && current != null) {
            List<SongFile> sources = songFileRepo.findBySongIdAndValidTrueOrderByPriorityDesc(current.getSongId());
            audioLayout = sources.stream().findFirst().map(AudioLayoutDto::from)
                    .orElseGet(AudioLayoutDto::normalStereo);
            if (playbackVariants != null) {
                SongFile source = sources.stream()
                        .filter(SongAvailabilityPolicy::isReadyMediaFile)
                        .findFirst().orElse(null);
                if (source != null) {
                    try {
                        playback = playbackVariants.requiresSidecar(source)
                                ? playbackVariants.resolve(source.getId())
                                : PlaybackDescriptor.nativeSource(source);
                    } catch (ApiException failure) {
                        playback = PlaybackDescriptor.failed(source, failure.getCode(), failure.getMessage());
                    }
                }
            }
        }

        return new QueueSnapshot(nowPlaying, list, ps.getState(), ps.getVolume(),
                ps.isMuted(), ps.getVocalMode(), audioLayout,
                broadcaster.isTvOnline(), broadcaster.connectedPhonesCount(),
                ps.getPositionMs(), ps.getSeekSequence(), stateRevision, playback);
    }

    private void addIds(QueueItem item, Set<Long> songIds, Set<Long> userIds) {
        if (item == null) return;
        if (item.getSongId() != null) songIds.add(item.getSongId());
        if (item.getOrderedBy() != null) userIds.add(item.getOrderedBy());
    }

    private Map<Long, Song> loadSongs(Set<Long> ids) {
        Map<Long, Song> songs = new HashMap<>();
        if (!ids.isEmpty()) {
            Iterable<Song> found = songRepo.findAllById(ids);
            if (found != null) found.forEach(song -> songs.put(song.getId(), song));
        }
        return songs;
    }

    private Map<Long, String> loadNicknames(Set<Long> ids) {
        Map<Long, String> nicks = new HashMap<>();
        if (!ids.isEmpty()) {
            Iterable<AppUser> found = userRepo.findAllById(ids);
            if (found != null) found.forEach(user -> nicks.put(user.getId(), user.getNickname()));
        }
        return nicks;
    }
}
