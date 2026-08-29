package com.homektv.library;

import com.homektv.domain.AudioLayout;
import com.homektv.domain.Song;
import com.homektv.domain.SongFile;
import com.homektv.repo.SongFileRepository;
import com.homektv.repo.SongRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/** Keeps the denormalized Song media summary derived from its playable files. */
@Service
public class SongProjectionService {

    private final SongRepository songRepository;
    private final SongFileRepository fileRepository;

    public SongProjectionService(SongRepository songRepository,
                                 SongFileRepository fileRepository) {
        this.songRepository = songRepository;
        this.fileRepository = fileRepository;
    }

    /**
     * Recomputes only from a successfully probed valid primary file.  A missing
     * or pending file is intentionally not allowed to erase the last known
     * media projection; availability reconciliation owns that state.
     */
    @Transactional
    public boolean recompute(Long songId) {
        if (songId == null) return false;
        Song song = songRepository.findById(songId).orElse(null);
        if (song == null) return false;

        List<SongFile> files = fileRepository
                .findBySongIdAndValidTrueOrderByPriorityDesc(songId);
        if (files == null || files.isEmpty()) return false;

        SongFile primary = files.stream()
                .filter(Objects::nonNull)
                .filter(SongFile::isValid)
                .filter(file -> !file.isProbePending())
                .filter(file -> file.getMediaType() != null
                        && !file.getMediaType().isBlank()
                        && !MediaClassifier.PENDING_PROBE.equals(file.getMediaType()))
                .sorted(Comparator.comparingInt(SongFile::getPriority)
                        .reversed()
                        .thenComparing(SongFile::getFilePath,
                                Comparator.nullsLast(String::compareTo)))
                .findFirst()
                .orElse(null);
        if (primary == null) return false;

        String mediaType = primary.getMediaType();
        if (primary.getAudioLayout() == AudioLayout.DUAL_CHANNEL
                && MediaClassifier.MV.equals(mediaType)) {
            mediaType = MediaClassifier.KTV_VIDEO;
        }
        boolean hasVocal = primary.getAudioTracks() >= 2
                || primary.getAudioLayout() == AudioLayout.DUAL_CHANNEL;

        if (Objects.equals(song.getMediaType(), mediaType)
                && song.isHasVocalTrack() == hasVocal) {
            return false;
        }
        song.setMediaType(mediaType);
        song.setHasVocalTrack(hasVocal);
        songRepository.save(song);
        return true;
    }
}
