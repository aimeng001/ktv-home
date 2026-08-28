package com.homektv.library;

import com.homektv.domain.SongArtist;
import com.homektv.repo.SongArtistRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.List;

/** Keeps independent artist search credits synchronized with the legacy Song artist text. */
@Service
public class ArtistCreditService {
    private final SongArtistRepository repository;

    public ArtistCreditService(SongArtistRepository repository) {
        this.repository = repository;
    }

    @Transactional
    public void replace(long songId, String artistCredit) {
        replace(songId, ArtistCreditParser.parse(artistCredit));
    }

    @Transactional
    public void replace(long songId, Collection<String> artistNames) {
        List<String> normalized = ArtistCreditParser.normalize(artistNames);
        List<SongArtist> existing = repository.findBySongIdOrderByArtistOrder(songId);
        if (same(existing, normalized)) return;

        repository.deleteBySongId(songId);
        if (normalized.isEmpty()) return;

        List<SongArtist> next = new java.util.ArrayList<>(normalized.size());
        for (int index = 0; index < normalized.size(); index++) {
            String name = normalized.get(index);
            SongArtist row = new SongArtist();
            row.setSongId(songId);
            row.setArtistName(name);
            row.setArtistKey(ArtistCreditParser.key(name));
            row.setArtistPy(PinyinUtil.fullPinyin(name));
            row.setArtistInit(PinyinUtil.initials(name));
            row.setArtistOrder(index);
            next.add(row);
        }
        repository.saveAll(next);
    }

    private static boolean same(List<SongArtist> existing, List<String> names) {
        if (existing == null || existing.size() != names.size()) return false;
        for (int index = 0; index < names.size(); index++) {
            SongArtist row = existing.get(index);
            String name = names.get(index);
            if (row == null || !ArtistCreditParser.key(name).equals(row.getArtistKey())
                    || !name.equals(row.getArtistName())
                    || !PinyinUtil.fullPinyin(name).equals(row.getArtistPy())
                    || !PinyinUtil.initials(name).equals(row.getArtistInit())) return false;
        }
        return true;
    }
}
