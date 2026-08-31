package com.homektv.library;

import com.homektv.domain.Song;
import com.homektv.repo.SongRepository;
import org.springframework.stereotype.Service;

/**
 * Repairs application-owned song artist associations created by older schema
 * versions. The source Song row and the NAS file are never modified.
 */
@Service
public class ArtistCreditReconciliationService {
    private static final int PAGE_SIZE = 500;

    private final SongRepository songs;
    private final ArtistCreditService credits;

    public ArtistCreditReconciliationService(SongRepository songs, ArtistCreditService credits) {
        this.songs = songs;
        this.credits = credits;
    }

    /**
     * Walks only valid songs in bounded pages so a legacy library can be
     * repaired without materializing the full catalogue in application heap.
     */
    public int reconcileValidSongs() {
        int processed = 0;
        long lastId = 0L;
        long maxId = songs.findMaxIdByStatus("ok");
        if (maxId <= 0L) return 0;
        while (true) {
            var page = songs.findValidSongsAfterId("ok", lastId, maxId, PAGE_SIZE + 1);
            if (page == null || page.isEmpty()) return processed;
            int accepted = 0;
            long nextLastId = lastId;
            for (Song song : page) {
                if (song == null || song.getId() == null) {
                    continue;
                }
                if (accepted >= PAGE_SIZE || song.getId() > maxId || song.getId() <= lastId) {
                    continue;
                }
                credits.replace(song.getId(), song.getArtist());
                processed++;
                accepted++;
                nextLastId = Math.max(nextLastId, song.getId());
            }
            if (accepted == 0 || nextLastId <= lastId || page.size() <= PAGE_SIZE) return processed;
            lastId = nextLastId;
        }
    }
}
