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
import com.homektv.library.CatalogRevisionService;
import com.homektv.library.SongAvailabilityPolicy;
import com.homektv.playback.PlaybackResolutionService;
import com.homektv.common.ApiException;
import com.homektv.common.PlaybackStateChangedEvent;
import com.homektv.common.PlayerControlChangedEvent;
import com.homektv.common.QueueSnapshotInvalidatedEvent;
import com.homektv.contract.AudioLayoutDto;
import com.homektv.contract.PlaybackDescriptor;
import com.homektv.contract.QueueSnapshot;
import com.homektv.contract.SongDto;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

/** 组装队列+播放状态快照（详设§11.1 / §4.2 sync_full）。 */
@Service
public class SnapshotService {

    private record CachedBaseSnapshot(QueueSnapshot base, long generation, long catalogRevision) {}

    private final PlayerStateRepository playerRepo;
    private final QueueItemRepository queueRepo;
    private final SongRepository songRepo;
    private final AppUserRepository userRepo;
    private final com.homektv.ws.WsBroadcaster broadcaster;
    private final SongFileRepository songFileRepo;
    private final PlaybackResolutionService playbackResolution;
    @Autowired(required = false)
    private PlatformTransactionManager transactionManager;
    @Autowired(required = false)
    private PlaybackService playbackService;
    @Autowired(required = false)
    private CatalogRevisionService catalogRevisionService;
    private com.homektv.library.ArtistProfileService artistProfileService;
    /** Monotonic wire revision used to reject an older HTTP response. */
    private final AtomicLong snapshotRevision = new AtomicLong(System.currentTimeMillis());
    private final AtomicLong cacheGeneration = new AtomicLong(0L);
    private final Object rebuildLock = new Object();
    private volatile CachedBaseSnapshot cachedSnapshot;
    private volatile Boolean cacheEnabledOverride;

    @Autowired
    public SnapshotService(PlayerStateRepository playerRepo, QueueItemRepository queueRepo,
                           SongRepository songRepo, AppUserRepository userRepo,
                           com.homektv.ws.WsBroadcaster broadcaster,
                           SongFileRepository songFileRepo,
                           PlaybackResolutionService playbackResolution) {
        this.playerRepo = playerRepo;
        this.queueRepo = queueRepo;
        this.songRepo = songRepo;
        this.userRepo = userRepo;
        this.broadcaster = broadcaster;
        this.songFileRepo = songFileRepo;
        this.playbackResolution = playbackResolution;
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

    void setCacheEnabledForTest(boolean enabled) {
        this.cacheEnabledOverride = enabled;
        if (!enabled) {
            invalidateCache();
        }
    }

    void setPlaybackService(PlaybackService playbackService) {
        this.playbackService = playbackService;
    }

    @Autowired(required = false)
    void setArtistProfileService(com.homektv.library.ArtistProfileService artistProfileService) {
        this.artistProfileService = artistProfileService;
    }

    public void invalidateCache() {
        cacheGeneration.incrementAndGet();
        cachedSnapshot = null;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onQueueSnapshotInvalidated(QueueSnapshotInvalidatedEvent event) {
        invalidateCache();
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onPlaybackStateChanged(PlaybackStateChangedEvent event) {
        invalidateCache();
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onPlayerControlChanged(PlayerControlChangedEvent event) {
        if (event == null) {
            invalidateCache();
            return;
        }
        CachedBaseSnapshot current = cachedSnapshot;
        if (current == null || cacheGeneration.get() != current.generation()) {
            invalidateCache();
            return;
        }
        Long cachedQueueId = current.base().playing() != null ? current.base().playing().queueId() : null;
        if (!Objects.equals(cachedQueueId, event.currentQueueId())) {
            invalidateCache();
            return;
        }
        synchronized (rebuildLock) {
            CachedBaseSnapshot latest = cachedSnapshot;
            if (latest == null || cacheGeneration.get() != latest.generation()) {
                invalidateCache();
                return;
            }
            QueueSnapshot base = latest.base();
            QueueSnapshot patched = new QueueSnapshot(
                    base.playing(),
                    base.list(),
                    event.state(),
                    event.volume(),
                    event.muted(),
                    event.vocalMode(),
                    base.audioLayout(),
                    base.tvOnline(),
                    base.connectedPhones(),
                    event.positionMs(),
                    event.seekSequence(),
                    base.stateRevision(),
                    base.playback());
            long nextGen = cacheGeneration.incrementAndGet();
            cachedSnapshot = new CachedBaseSnapshot(patched, nextGen, latest.catalogRevision());
        }
    }

    private boolean isCacheEnabled() {
        Boolean override = cacheEnabledOverride;
        return override != null ? override : transactionManager != null;
    }

    /**
     * Capture one ordered, consistent wire view. When caching is enabled,
     * unchanged reads materialize from the committed base snapshot with zero DB
     * queries and zero lock contention; misses rebuild inside REPEATABLE_READ.
     */
    public QueueSnapshot snapshot() {
        if (!isCacheEnabled()) {
            synchronized (this) {
                CapturedDbState captured;
                if (transactionManager == null) {
                    captured = captureDbState();
                } else {
                    TransactionTemplate tx = new TransactionTemplate(transactionManager);
                    tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
                    tx.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
                    tx.setReadOnly(true);
                    captured = tx.execute(status -> captureDbState());
                }
                return materializeWireSnapshot(finishSnapshotResolution(captured));
            }
        }
        long currentRevision = currentCatalogRevision();
        CachedBaseSnapshot hit = cachedSnapshot;
        if (hit != null
                && hit.catalogRevision() == currentRevision
                && cacheGeneration.get() == hit.generation()) {
            return materializeWireSnapshot(hit.base());
        }
        synchronized (rebuildLock) {
            currentRevision = currentCatalogRevision();
            hit = cachedSnapshot;
            if (hit != null
                    && hit.catalogRevision() == currentRevision
                    && cacheGeneration.get() == hit.generation()) {
                return materializeWireSnapshot(hit.base());
            }
            long genBefore = cacheGeneration.get();
            CapturedDbState captured;
            if (transactionManager == null) {
                captured = captureDbState();
            } else {
                TransactionTemplate tx = new TransactionTemplate(transactionManager);
                tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
                tx.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
                tx.setReadOnly(true);
                captured = tx.execute(status -> captureDbState());
            }
            // Run potential on-demand FFprobe codec resolution after closing REPEATABLE_READ DB transaction.
            QueueSnapshot built = finishSnapshotResolution(captured);
            if (built != null && cacheGeneration.get() == genBefore && currentCatalogRevision() == currentRevision) {
                cachedSnapshot = new CachedBaseSnapshot(built, genBefore, currentRevision);
            }
            return materializeWireSnapshot(built);
        }
    }

    private long currentCatalogRevision() {
        return catalogRevisionService != null ? catalogRevisionService.current() : 0L;
    }

    private QueueSnapshot materializeWireSnapshot(QueueSnapshot base) {
        long stateRevision = snapshotRevision.incrementAndGet();
        Long currentQueueId = base.playing() != null ? base.playing().queueId() : null;
        long livePositionMs = playbackService != null
                ? playbackService.resolveLivePositionMs(currentQueueId, base.state(), base.positionMs())
                : base.positionMs();
        return new QueueSnapshot(
                base.playing(),
                base.list(),
                base.state(),
                base.volume(),
                base.muted(),
                base.vocalMode(),
                base.audioLayout(),
                broadcaster.isTvOnline(),
                broadcaster.connectedPhonesCount(),
                livePositionMs,
                base.seekSequence(),
                stateRevision,
                base.playback());
    }

    private record CapturedDbState(
            QueueSnapshot.NowPlaying nowPlaying,
            List<QueueSnapshot.QueueEntry> list,
            String state,
            int volume,
            boolean muted,
            String vocalMode,
            AudioLayoutDto audioLayout,
            long positionMs,
            long seekSequence,
            SongFile sourceToResolve
    ) {}

    private CapturedDbState captureDbState() {
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
        Map<String, String> avatarUrls = artistProfileService == null || songs.isEmpty()
                ? null
                : artistProfileService.resolveAvatarUrlsForCredits(
                        songs.values().stream()
                                .filter(song -> song != null && song.getArtist() != null)
                                .map(Song::getArtist)
                                .toList());
        Map<Long, String> nicks = loadNicknames(userIds);
        QueueSnapshot.NowPlaying nowPlaying = null;
        if (current != null) {
            Song song = songs.get(current.getSongId());
            String orderedByNick = nicks.get(current.getOrderedBy());
            if (current.isAutoPlay() && (orderedByNick == null || orderedByNick.isBlank())) {
                orderedByNick = "随机公播";
            }
            nowPlaying = new QueueSnapshot.NowPlaying(
                    current.getId(),
                    toSnapshotSongDto(song, avatarUrls),
                    orderedByNick,
                    current.isAutoPlay());
        }

        List<QueueSnapshot.QueueEntry> list = waiting.stream()
                .map(q -> {
                    Song song = songs.get(q.getSongId());
                    return new QueueSnapshot.QueueEntry(q.getId(),
                            toSnapshotSongDto(song, avatarUrls),
                            q.getOrderedBy(), nicks.get(q.getOrderedBy()), q.getStatus());
                })
                .toList();

        AudioLayoutDto audioLayout = AudioLayoutDto.normalStereo();
        SongFile source = null;
        if (songFileRepo != null && current != null) {
            List<SongFile> sources = songFileRepo.findBySongIdAndValidTrueOrderByPriorityDesc(current.getSongId());
            source = sources.stream()
                    .filter(SongAvailabilityPolicy::isReadyMediaFile)
                    .findFirst().orElse(null);
            if (source != null) {
                audioLayout = AudioLayoutDto.from(source);
            }
        }

        return new CapturedDbState(nowPlaying, list, ps.getState(), ps.getVolume(),
                ps.isMuted(), ps.getVocalMode(), audioLayout,
                ps.getPositionMs(), ps.getSeekSequence(), source);
    }

    private QueueSnapshot finishSnapshotResolution(CapturedDbState captured) {
        if (captured == null) return null;
        PlaybackDescriptor playback = PlaybackDescriptor.idle();
        SongFile source = captured.sourceToResolve();
        if (source != null && playbackResolution != null) {
            try {
                playback = playbackResolution.resolveReady(source, false);
            } catch (ApiException failure) {
                playback = PlaybackDescriptor.failed(source, failure.getCode(), failure.getMessage());
            }
        }
        return new QueueSnapshot(captured.nowPlaying(), captured.list(), captured.state(),
                captured.volume(), captured.muted(), captured.vocalMode(), captured.audioLayout(),
                false, 0L, captured.positionMs(), captured.seekSequence(), 0L, playback);
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

    private static SongDto toSnapshotSongDto(Song song, Map<String, String> avatarUrls) {
        if (song == null) return null;
        SongDto dto = SongDto.from(song);
        if (avatarUrls == null) return dto;
        return dto.withArtistAvatarUrl(song.getArtist() == null ? null : avatarUrls.get(song.getArtist()));
    }
}
