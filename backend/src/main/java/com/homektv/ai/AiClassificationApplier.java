package com.homektv.ai;

import com.homektv.domain.Song;
import com.homektv.library.ArtistCreditService;
import com.homektv.library.MediaClassifier;
import com.homektv.library.PinyinUtil;
import com.homektv.repo.SongRepository;
import com.homektv.web.ApiException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * 将 AI 分类结果应用到歌曲实体上，包括语言、年代、流派、主题、年龄段、演唱形式等字段的归一化与合并。
 *
 * Applies AI classification results to a Song entity, normalizing and merging fields
 * such as language, era, genres, themes, age range, and vocal form.
 */
@Service
public class AiClassificationApplier {
    private static final java.util.Set<String> LANGUAGES = java.util.Set.of("国语", "粤语", "闽南语", "英语", "日语", "韩语", "纯音乐", "其他", "未知");
    private static final java.util.Set<String> VOCAL_FORMS = java.util.Set.of("独唱", "对唱", "合唱", "组合", "未知");
    private static final java.util.Set<String> ARTIST_GENDERS = java.util.Set.of("男歌手", "女歌手", "组合", "未知");
    private final SongRepository songRepository;
    private final AiConfigService configService;
    private final ArtistCreditService artistCreditService;
    private com.homektv.library.CatalogRevisionService catalogRevisionService;

    @Autowired(required = false)
    void setCatalogRevisionService(com.homektv.library.CatalogRevisionService catalogRevisionService) {
        this.catalogRevisionService = catalogRevisionService;
    }

    /**
     * 通过构造注入 SongRepository。
     *
     * Constructor-injected SongRepository.
     *
     * @param songRepository 歌曲数据访问接口 / song data access interface
     */
    public AiClassificationApplier(SongRepository songRepository, AiConfigService configService) {
        this(songRepository, configService, null);
    }

    @Autowired
    public AiClassificationApplier(SongRepository songRepository, AiConfigService configService,
                                   ArtistCreditService artistCreditService) {
        this.songRepository = songRepository;
        this.configService = configService;
        this.artistCreditService = artistCreditService;
    }

    /**
     * 将 AI 分类结果应用到指定歌曲：归一化各分类字段，合并标签，并回写数据库。
     *
     * Applies the AI classification result to the song identified by {@code songId}:
     * normalizes each classification field, merges tags, and persists the updated entity.
     *
     * @param songId 歌曲主键 ID / song primary key
     * @param result AI 分类结果 / AI classification result
     * @return 持久化后的歌曲实体 / persisted song entity
     */
    public Song apply(Long songId, AiSongClassification result) {
        return apply(songId, result, true);
    }

    public boolean applyAuto(Long songId, AiSongClassification result) {
        Song before = songRepository.findById(songId)
                .orElseThrow(() -> new ApiException("SONG_NOT_FOUND", "歌曲不存在"));
        String previousArtist = before.getArtist();
        boolean changed = applyValues(before, result, false);
        if (changed) {
            Song saved = songRepository.save(before);
            syncArtistCredits(previousArtist, saved);
            if (catalogRevisionService != null) catalogRevisionService.bumpIfChanged(true);
        }
        return changed;
    }

    private Song apply(Long songId, AiSongClassification result, boolean reviewed) {
        Song song = songRepository.findById(songId)
                .orElseThrow(() -> new ApiException("SONG_NOT_FOUND", "歌曲不存在"));
        String previousArtist = song.getArtist();
        applyValues(song, result, reviewed);
        Song saved = songRepository.save(song);
        syncArtistCredits(previousArtist, saved);
        if (catalogRevisionService != null) catalogRevisionService.bumpIfChanged(true);
        return saved;
    }

    private void syncArtistCredits(String previousArtist, Song saved) {
        if (artistCreditService != null && !java.util.Objects.equals(previousArtist, saved.getArtist())) {
            artistCreditService.replace(saved.getId(), saved.getArtist());
        }
    }

    private boolean applyValues(Song song, AiSongClassification result, boolean reviewed) {
        boolean changed = false;
        song.setAiLanguage(validLanguage(result.language()) ? result.language() : "未知");
        song.setAiEra(normalize(result.era()));
        song.setAiGenres(clean(result.genres()));
        song.setAiThemes(clean(result.themes()));
        song.setAiAgeRange(normalize(result.ageRange()));
        song.setAiVocalForm(validVocalForm(result.vocalForm()) ? result.vocalForm() : "未知");
        song.setAiAnalyzedAt(OffsetDateTime.now());
        if (!song.isMetadataLocked("tags")) {
            song.setTags(mergeTags(song.getTags(), result));
        }
        double classificationThreshold = configService.resolve().classificationThreshold();
        if (!song.isMetadataLocked("artistGender") && validArtistGender(result.artistGender())
                && !"未知".equals(result.artistGender())
                && (reviewed || result.confidence() >= classificationThreshold)) {
            song.setArtistGender(result.artistGender());
            changed = true;
        }
        if (!song.isMetadataLocked("language") && validLanguage(result.language())
                && (reviewed || result.languageConfidence() >= classificationThreshold)) {
            song.setLanguage(result.language());
            changed = true;
        }
        if (!song.isMetadataLocked("vocalForm") && validVocalForm(result.vocalForm())
                && (reviewed || result.vocalFormConfidence() >= classificationThreshold)) {
            song.setVocalForm(result.vocalForm());
            changed = true;
        }
        if (canApplyIdentity(song, result, reviewed)) {
            String title = song.isMetadataLocked("title") ? song.getTitle() : result.title().trim();
            String artist = song.isMetadataLocked("artist") ? song.getArtist() : result.artist().trim();
            String fingerprint = MediaClassifier.fingerprint(artist, title, song.getDurationMs());
            songRepository.findByFingerprint(fingerprint).filter(existing -> !existing.getId().equals(song.getId()))
                    .ifPresent(existing -> { throw new ApiException("AI_IDENTITY_CONFLICT", "建议身份与歌曲 #" + existing.getId() + " 重复，请在审核页合并或保留独立歌曲"); });
            if (!song.isMetadataLocked("title")) {
                song.setTitle(title);
                song.setTitlePy(PinyinUtil.fullPinyin(title));
                song.setTitleInit(PinyinUtil.initials(title));
            }
            if (!song.isMetadataLocked("artist")) {
                song.setArtist(artist);
                song.setArtistPy(PinyinUtil.fullPinyin(artist));
                song.setArtistInit(PinyinUtil.initials(artist));
            }
            song.setFingerprint(fingerprint);
            changed = true;
        }
        song.setNeedsAiOptimization(false);
        return changed || reviewed;
    }

    private boolean canApplyIdentity(Song song, AiSongClassification result, boolean reviewed) {
        if (result.title() == null || result.title().isBlank() || result.artist() == null || result.artist().isBlank()) return false;
        if (song.isMetadataLocked("title") && song.isMetadataLocked("artist")) return false;
        if (reviewed) return true;
        double threshold = configService.resolve().identityThreshold();
        if (result.titleConfidence() < threshold || result.artistConfidence() < threshold) return false;
        boolean unknown = "未知歌手".equals(song.getArtist()) || "unrecognized".equals(song.getStatus());
        boolean swapped = result.title().trim().equalsIgnoreCase(song.getArtist())
                && result.artist().trim().equalsIgnoreCase(song.getTitle());
        return unknown || swapped;
    }

    private boolean validLanguage(String value) { return value != null && LANGUAGES.contains(value); }
    private boolean validVocalForm(String value) { return value != null && VOCAL_FORMS.contains(value); }
    private boolean validArtistGender(String value) { return value != null && ARTIST_GENDERS.contains(value); }

    // 合并现有标签与 AI 分类结果中的流派、主题、年代、演唱形式，去重并过滤空值和"未知"。
    // Merges current tags with genres, themes, era, and vocal form from the AI result,
    // deduplicating and filtering out nulls, blanks, and "未知".
    private String[] mergeTags(String[] current, AiSongClassification result) {
        LinkedHashSet<String> tags = new LinkedHashSet<>();
        if (current != null) tags.addAll(Arrays.asList(current));
        tags.addAll(result.genres());
        tags.addAll(result.themes());
        if (result.era() != null) tags.add(result.era());
        if (result.vocalForm() != null) tags.add(result.vocalForm());
        tags.removeIf(value -> value == null || value.isBlank() || "未知".equals(value));
        return tags.toArray(String[]::new);
    }

    // 对字符串列表做 trim、去空、去重，最多保留前 10 项。
    // Trims, removes blanks, deduplicates, and returns at most 10 entries.
    private String[] clean(List<String> values) {
        return values.stream().map(String::trim).filter(value -> !value.isBlank()).distinct().limit(10).toArray(String[]::new);
    }

    // 归一化字符串值：空值返回"未知"，否则 trim。
    // Normalizes a string value: returns "未知" for null/blank, otherwise trims.
    private String normalize(String value) {
        return value == null || value.isBlank() ? "未知" : value.trim();
    }
}
