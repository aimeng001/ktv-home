package com.homektv.library;

import com.homektv.domain.Song;
import com.homektv.domain.SongFile;
import com.homektv.repo.SongFileRepository;
import com.homektv.web.ApiException;
import org.springframework.stereotype.Service;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Single source of truth for the boundary between an indexed song and a
 * playable song.
 *
 * Fast Index deliberately creates searchable rows before FFprobe finishes.
 * Those rows remain visible to library management, but must not enter the
 * queue or be streamed until a valid, probed media source exists.
 */
@Service
public class SongAvailabilityPolicy {

    public static final String SONG_NOT_READY = "SONG_NOT_READY";
    public static final String PENDING_PROBE = MediaClassifier.PENDING_PROBE;

    private final SongFileRepository fileRepository;

    public SongAvailabilityPolicy(SongFileRepository fileRepository) {
        this.fileRepository = fileRepository;
    }

    /** Returns true only for an acknowledged song with a ready media source. */
    public boolean isPlayable(Song song) {
        return song != null
                && song.getId() != null
                && "ok".equalsIgnoreCase(song.getStatus())
                && fileRepository.existsReadyFile(song.getId());
    }

    /**
     * Returns the playable subset of a batch of songs with one readiness query.
     * The song status check remains here so callers cannot accidentally bypass
     * the shared availability policy.
     */
    public Set<Long> playableSongIds(Collection<Song> songs) {
        if (songs == null || songs.isEmpty()) return Set.of();
        Set<Long> candidates = new LinkedHashSet<>();
        for (Song song : songs) {
            if (song != null && song.getId() != null && "ok".equalsIgnoreCase(song.getStatus())) {
                candidates.add(song.getId());
            }
        }
        if (candidates.isEmpty()) return Set.of();
        Set<Long> ready = fileRepository.findSongIdsWithReadyFile(candidates);
        return ready == null ? Set.of() : Set.copyOf(ready);
    }
    /** Rejects a Fast Index or otherwise unavailable song at a play boundary. */
    public void requirePlayable(Song song) {
        if (!isPlayable(song)) {
            throw new ApiException(SONG_NOT_READY, "歌曲正在扫描或媒体探测未完成，暂时不能播放");
        }
    }

    /** File-level check used by the stream endpoint after it loads a file row. */
    public boolean isReadyFile(SongFile file) {
        return isReadyMediaFile(file);
    }

    /**
     * 就绪判定唯一实现，供播放边界与只读 DTO 共用，避免两处判定漂移。
     *
     * Single implementation of the readiness rule, shared by the playback boundary
     * and by read-side DTOs so the two can never drift apart.
     */
    public static boolean isReadyMediaFile(SongFile file) {
        return file != null
                && file.isValid()
                && !file.isProbePending()
                && file.getMediaType() != null
                && !file.getMediaType().isBlank()
                && !PENDING_PROBE.equalsIgnoreCase(file.getMediaType());
    }
}
