package com.homektv.library;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.homektv.config.AppProperties;
import com.homektv.domain.Song;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Single domain policy &amp; write helper for Song metadata mutations across
 * admin edits, reparse, external scraping, and AI classification.
 */
@Component
public class SongMetadataWritePolicy {

    private static final ObjectMapper SHARED_MAPPER = new ObjectMapper();

    private final AppProperties appProperties;
    private SettingService settingService;
    private ArtistCreditService artistCreditService;
    private ArtistDirectoryProjectionService artistDirectoryProjectionService;
    private CatalogRevisionService catalogRevisionService;

    public SongMetadataWritePolicy() {
        this(null);
    }

    @Autowired
    public SongMetadataWritePolicy(AppProperties appProperties) {
        this.appProperties = appProperties;
    }

    @Autowired(required = false)
    void setSettingService(SettingService settingService) {
        this.settingService = settingService;
    }

    @Autowired(required = false)
    void setArtistCreditService(ArtistCreditService artistCreditService) {
        this.artistCreditService = artistCreditService;
    }

    @Autowired(required = false)
    void setArtistDirectoryProjectionService(ArtistDirectoryProjectionService artistDirectoryProjectionService) {
        this.artistDirectoryProjectionService = artistDirectoryProjectionService;
    }

    @Autowired(required = false)
    void setCatalogRevisionService(CatalogRevisionService catalogRevisionService) {
        this.catalogRevisionService = catalogRevisionService;
    }

    public boolean isFilenameMetadataPreferred() {
        boolean configured = appProperties != null && appProperties.isStructuredFilenameMetadataPreferred();
        return settingService != null ? settingService.isFilenameMetadataPreferred(configured) : configured;
    }

    public static boolean hasFilenameProvenance(Song song) {
        return hasFilenameProvenance(song, null);
    }

    public static boolean hasFilenameProvenance(Song song, String field) {
        if (song == null) return false;
        String raw = song.getMetadataProvenance();
        if (raw == null || raw.isBlank()) return false;
        try {
            Map<String, Object> provenance = SHARED_MAPPER.readValue(raw, new TypeReference<>() {});
            if (field != null) {
                Object entry = provenance.get(field);
                return entry instanceof Map<?, ?> map && "filename".equals(map.get("source"));
            }
            for (Object entry : provenance.values()) {
                if (entry instanceof Map<?, ?> map && "filename".equals(map.get("source"))) {
                    return true;
                }
            }
            return false;
        } catch (Exception ignored) {
            return false;
        }
    }

    public static boolean isProtectedField(Song song, String field, boolean filenamePreferred) {
        if (song == null) return true;
        if (song.isMetadataLocked(field)) return true;
        if (!filenamePreferred) return false;
        if ("title".equals(field) || "artist".equals(field)) {
            return hasFilenameProvenance(song, field);
        }
        if ("language".equals(field)) {
            return hasFilenameProvenance(song)
                    && song.getLanguage() != null
                    && !song.getLanguage().isBlank()
                    && !"未知".equals(song.getLanguage());
        }
        return false;
    }

    public static void applyTitleWithPinyin(Song song, String title) {
        String clean = title.trim();
        song.setTitle(clean);
        song.setTitlePy(PinyinUtil.fullPinyin(clean));
        song.setTitleInit(PinyinUtil.initials(clean));
    }

    public static void applyArtistWithPinyin(Song song, String artist) {
        String clean = artist.trim();
        song.setArtist(clean);
        song.setArtistPy(PinyinUtil.fullPinyin(clean));
        song.setArtistInit(PinyinUtil.initials(clean));
    }

    public static boolean promoteUnrecognizedIfValid(Song song) {
        if (song == null || !"unrecognized".equals(song.getStatus())) return false;
        boolean validIdentity = song.getTitle() != null && !song.getTitle().isBlank() && !"未识别".equals(song.getTitle())
                && song.getArtist() != null && !song.getArtist().isBlank() && !"未知歌手".equals(song.getArtist());
        if (!validIdentity) return false;
        song.setStatus("ok");
        return true;
    }

    public void notifyAfterMetadataMutation(Song saved, boolean artistChanged, boolean artistGenderChanged,
                                            boolean promotedToOk, boolean catalogChanged) {
        if (saved != null && artistChanged && artistCreditService != null) {
            artistCreditService.replace(saved.getId(), saved.getArtist());
        }
        if ((artistChanged || artistGenderChanged || promotedToOk) && artistDirectoryProjectionService != null) {
            artistDirectoryProjectionService.requestRefreshAfterCommit();
        }
        if (catalogChanged && catalogRevisionService != null) {
            catalogRevisionService.bumpIfChanged(true);
        }
    }
}
