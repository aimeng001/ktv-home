package com.homektv.library;

import com.homektv.domain.Song;
import com.homektv.domain.SongFile;
import com.homektv.repo.SongFileRepository;
import com.homektv.web.ApiException;
import org.springframework.stereotype.Service;

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

    /** Rejects a Fast Index or otherwise unavailable song at a play boundary. */
    public void requirePlayable(Song song) {
        if (!isPlayable(song)) {
            throw new ApiException(SONG_NOT_READY, "歌曲正在扫描或媒体探测未完成，暂时不能播放");
        }
    }

    /** File-level check used by the stream endpoint after it loads a file row. */
    public boolean isReadyFile(SongFile file) {
        return file != null
                && file.isValid()
                && !file.isProbePending()
                && file.getMediaType() != null
                && !file.getMediaType().isBlank()
                && !PENDING_PROBE.equalsIgnoreCase(file.getMediaType());
    }
}
