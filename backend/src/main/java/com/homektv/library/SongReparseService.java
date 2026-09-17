package com.homektv.library;

import com.homektv.domain.Song;
import com.homektv.domain.SongFile;
import com.homektv.repo.SongFileRepository;
import com.homektv.repo.SongRepository;
import com.homektv.web.ApiException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

@Service
public class SongReparseService {
    private final SongRepository songRepository;
    private final SongFileRepository fileRepository;
    private final ArtistCreditService artistCreditService;

    public SongReparseService(SongRepository songRepository, SongFileRepository fileRepository) {
        this(songRepository, fileRepository, null);
    }

    @Autowired
    public SongReparseService(SongRepository songRepository, SongFileRepository fileRepository,
                              ArtistCreditService artistCreditService) {
        this.songRepository = songRepository;
        this.fileRepository = fileRepository;
        this.artistCreditService = artistCreditService;
    }

    @Transactional(readOnly = true)
    public List<Preview> preview(List<Long> songIds, String rule) {
        validateRule(rule);
        List<String> knownArtists = existingArtistNames();
        List<Preview> result = new ArrayList<>();
        for (Long songId : songIds) {
            Song song = songRepository.findById(songId).orElse(null);
            if (song == null) continue;
            SongFile file = fileRepository.findBySongIdAndValidTrueOrderByPriorityDesc(songId).stream().findFirst()
                    .orElseGet(() -> fileRepository.findBySongIdOrderByPriorityDesc(songId).stream().findFirst().orElse(null));
            if (file == null) {
                result.add(new Preview(songId, song.getTitle(), song.getArtist(), null, null, null, "", false, "没有文件源"));
                continue;
            }
            String filename = Path.of(file.getFilePath()).getFileName().toString();
            ParsedMeta parsed = FilenameParser.parse(filename, rule, knownArtists);
            String artist = parsed.artist().isBlank() ? "未知歌手" : parsed.artist();
            result.add(new Preview(songId, song.getTitle(), song.getArtist(), filename,
                    parsed.title(), artist, parsed.catalogNumber(), parsed.recognized(),
                    parsed.recognized() ? null : "文件名无法识别"));
        }
        return result;
    }

    @Transactional
    public ApplyResult apply(List<Long> songIds, String rule) {
        List<Preview> previews = preview(songIds, rule);
        List<String> knownArtists = artistCreditService == null
                ? List.of() : existingArtistNames();
        int updated = 0;
        int skipped = 0;
        for (Preview preview : previews) {
            if (!preview.recognized() || preview.proposedTitle() == null || preview.proposedTitle().isBlank()) {
                skipped++;
                continue;
            }
            Song song = songRepository.findById(preview.songId()).orElse(null);
            if (song == null) { skipped++; continue; }
            song.setTitle(preview.proposedTitle());
            song.setArtist(preview.proposedArtist());
            song.setTitlePy(PinyinUtil.fullPinyin(preview.proposedTitle()));
            song.setTitleInit(PinyinUtil.initials(preview.proposedTitle()));
            song.setArtistPy(PinyinUtil.fullPinyin(preview.proposedArtist()));
            song.setArtistInit(PinyinUtil.initials(preview.proposedArtist()));
            // Reparse is authoritative for filename-derived metadata.  A file
            // that no longer has the commercial prefix must clear the old
            // number; retaining it would make a rename look like the old
            // catalogue identity on every subsequent export/search.
            song.setCatalogNumber(preview.catalogNumber());
            String fingerprint = MediaClassifier.fingerprint(preview.proposedArtist(), preview.proposedTitle(), song.getDurationMs());
            songRepository.findByFingerprint(fingerprint).filter(other -> !other.getId().equals(song.getId()))
                    .ifPresent(other -> { throw new ApiException("FINGERPRINT_CONFLICT", "重解析结果与歌曲 #" + other.getId() + " 重复"); });
            song.setFingerprint(fingerprint);
            song.lockMetadata("title");
            song.lockMetadata("artist");
            song.setStatus("ok");
            songRepository.save(song);
            if (artistCreditService != null) {
                if (knownArtists.isEmpty()) {
                    artistCreditService.replace(song.getId(), song.getArtist());
                } else {
                    artistCreditService.replace(song.getId(), song.getArtist(), knownArtists);
                }
            }
            updated++;
        }
        return new ApplyResult(updated, skipped, previews);
    }

    private void validateRule(String rule) {
        if (!"artist_title".equals(rule) && !"title_artist".equals(rule)) {
            throw new ApiException("INVALID_REPARSE_RULE", "不支持的重解析规则");
        }
    }

    private List<String> existingArtistNames() {
        List<String> artists = songRepository.findDistinctArtistByStatus("ok");
        return artists == null ? List.of() : artists;
    }

    public record Preview(Long songId, String currentTitle, String currentArtist, String filename,
                          String proposedTitle, String proposedArtist, String catalogNumber,
                          boolean recognized, String error) {
        /** Compatibility constructor for clients that do not expose catalog numbers yet. */
        public Preview(Long songId, String currentTitle, String currentArtist, String filename,
                       String proposedTitle, String proposedArtist, boolean recognized, String error) {
            this(songId, currentTitle, currentArtist, filename, proposedTitle, proposedArtist,
                    "", recognized, error);
        }
    }
    public record ApplyResult(int updated, int skipped, List<Preview> items) {}
}
