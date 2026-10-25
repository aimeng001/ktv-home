package com.homektv.musicsource;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.homektv.domain.Song;
import com.homektv.library.ArtistCreditService;
import com.homektv.library.CatalogRevisionService;
import com.homektv.repo.SongRepository;
import com.homektv.web.ApiException;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class MusicMetadataApplyServiceBoundaryTest {

    @Test
    void absentOrEmptyFieldSelectionDefaultsToAllowedExternalFields() {
        List<MusicMetadataApplyService.ApplyRequest> requests = Arrays.asList(
                null, new MusicMetadataApplyService.ApplyRequest(null),
                new MusicMetadataApplyService.ApplyRequest(Set.of()));

        for (int index = 0; index < requests.size(); index++) {
            Fixture fixture = fixture(song(70L + index), track("Track", List.of("Artist A", "Artist B"),
                    "Album", "2020", List.of("Alias"), null));
            if (index == 0) {
                when(fixture.storage.track(MusicProvider.QQ, "ext", false)).thenReturn(Optional.empty());
                when(fixture.search.detail(MusicProvider.QQ, "ext", false)).thenReturn(fixture.track);
            }

            MusicMetadataApplyService.ApplyResult result = fixture.service.apply(
                    fixture.song.getId(), MusicProvider.QQ, "ext", requests.get(index));

            assertThat(result.appliedFields()).containsExactly("title", "artist", "album", "releaseDate", "aliases");
            assertThat(fixture.song.getTitle()).isEqualTo("Track");
            assertThat(fixture.song.getArtist()).isEqualTo("Artist A / Artist B");
            if (index == 0) verify(fixture.search).detail(MusicProvider.QQ, "ext", false);
            verify(fixture.artistCredits).replace(fixture.song.getId(), fixture.track.artists());
            verify(fixture.storage).saveMatch(fixture.song.getId(), fixture.track, 1);
            verify(fixture.storage).markApplied(fixture.song.getId(), MusicProvider.QQ, "ext");
        }
    }

    @Test
    void manualOverridesForceOnlyTheirLockedFieldsAndOtherLockedFieldsAreReported() {
        Song song = song(7L);
        song.setMetadataLocks(new String[]{"title", "artist", "aliases", "cover"});
        ExternalTrack track = track("Provider title", List.of("Provider artist"), "Provider album",
                "2024", List.of("Provider alias"), "https://cover.invalid/cover.jpg");
        Fixture fixture = fixture(song, track);
        Map<String, String> overrides = Map.of("title", "  Reviewed title  ", "aliases", "A,A，B\n C、");

        MusicMetadataApplyService.ApplyResult result = fixture.service.apply(7L, MusicProvider.QQ, "ext",
                new MusicMetadataApplyService.ApplyRequest(
                        Set.of("title", "artist", "album", "releaseDate", "aliases", "cover"), overrides));

        assertThat(song.getTitle()).isEqualTo("Reviewed title");
        assertThat(song.getArtist()).isEqualTo("Old artist");
        assertThat(song.getAlbum()).isEqualTo("Provider album");
        assertThat(song.getReleaseDate()).isEqualTo("2024");
        assertThat(song.getAliases()).containsExactly("A", "B", "C");
        assertThat(result.appliedFields()).containsExactly("title", "album", "releaseDate", "aliases");
        assertThat(result.skippedLockedFields()).containsExactly("artist", "cover");
        assertThat(song.getMetadataLocks()).contains("title", "artist", "aliases", "cover");
        assertThat(song.getMetadataProvenance()).contains("MANUAL_REVIEW", "QQ", "ext");
        verify(fixture.cover, never()).download(any(), anyString(), anyString(), any());
        verify(fixture.artistCredits, never()).replace(eq(7L), anyString());
    }

    @Test
    void downloadsRequestedUnlockedCoverAndKeepsStructuredArtistCredits() {
        Fixture fixture = fixture(song(8L), track("Title", List.of("Singer A", "Singer B"),
                null, null, List.of(), "https://cover.invalid/cover.jpg"));
        when(fixture.cover.download(eq(MusicProvider.QQ), anyString(), anyString(), any(Duration.class)))
                .thenReturn("/data/covers/8.jpg");
        CatalogRevisionService revision = mock(CatalogRevisionService.class);
        fixture.service.setCatalogRevisionService(revision);

        MusicMetadataApplyService.ApplyResult result = fixture.service.apply(8L, MusicProvider.QQ, "ext",
                new MusicMetadataApplyService.ApplyRequest(Set.of("artist", "cover")));

        assertThat(result.appliedFields()).containsExactly("artist", "cover");
        assertThat(fixture.song.getArtist()).isEqualTo("Singer A / Singer B");
        assertThat(fixture.song.getCoverPath()).isEqualTo("/data/covers/8.jpg");
        verify(fixture.cover).download(eq(MusicProvider.QQ), eq("https://cover.invalid/cover.jpg"),
                eq(fixture.song.getFingerprint()), eq(Duration.ofSeconds(5)));
        verify(fixture.artistCredits).replace(8L, fixture.track.artists());
        verify(revision).bumpIfChanged(true);
    }

    @Test
    void artistOverrideUsesTheReviewedCreditInsteadOfProviderCreditParts() {
        Fixture fixture = fixture(song(9L), track("Title", List.of("Provider A", "Provider B"),
                null, null, List.of(), null));

        fixture.service.apply(9L, MusicProvider.QQ, "ext",
                new MusicMetadataApplyService.ApplyRequest(Set.of("artist"), Map.of("artist", "  Chosen Singer  ")));

        assertThat(fixture.song.getArtist()).isEqualTo("Chosen Singer");
        verify(fixture.artistCredits).replace(9L, "Chosen Singer");
    }

    @Test
    void explicitArtistOverrideCanReplaceAnExistingArtistLock() {
        Song song = song(31L);
        song.setMetadataLocks(new String[]{"artist"});
        Fixture fixture = fixture(song, track("Provider", List.of("Provider artist"), null, null, List.of(), null));

        MusicMetadataApplyService.ApplyResult result = fixture.service.apply(31L, MusicProvider.QQ, "ext",
                new MusicMetadataApplyService.ApplyRequest(Set.of("artist"), Map.of("artist", "Reviewed artist")));

        assertThat(song.getArtist()).isEqualTo("Reviewed artist");
        assertThat(result.skippedLockedFields()).isEmpty();
        verify(fixture.artistCredits).replace(31L, "Reviewed artist");
    }

    @Test
    void blankOrMissingProviderFieldsDoNotOverwriteExistingValues() {
        Fixture fixture = fixture(song(10L), track("  ", List.of(), null, null, List.of(), null));

        MusicMetadataApplyService.ApplyResult result = fixture.service.apply(10L, MusicProvider.QQ, "ext",
                new MusicMetadataApplyService.ApplyRequest(
                        Set.of("title", "artist", "album", "releaseDate", "aliases", "cover")));

        assertThat(fixture.song.getTitle()).isEqualTo("Old title");
        assertThat(fixture.song.getArtist()).isEqualTo("Old artist");
        assertThat(fixture.song.getAlbum()).isEqualTo("Old album");
        assertThat(fixture.song.getReleaseDate()).isEqualTo("1999");
        assertThat(fixture.song.getAliases()).isEmpty();
        assertThat(result.appliedFields()).containsExactly("aliases");
        assertThat(result.skippedLockedFields()).isEmpty();
        verifyNoInteractions(fixture.cover, fixture.artistCredits);
    }

    @Test
    void rejectsMissingSongUnsupportedFieldAndDuplicateFingerprintBeforeWrites() {
        Fixture missing = fixture(song(11L), track("Title", List.of("Artist"), null, null, List.of(), null));
        when(missing.songs.findById(99L)).thenReturn(Optional.empty());
        assertApiCode(() -> missing.service.apply(99L, MusicProvider.QQ, "ext", null), "SONG_NOT_FOUND");
        verifyNoInteractions(missing.storage, missing.search);

        Fixture unsupported = fixture(song(12L), track("Title", List.of("Artist"), null, null, List.of(), null));
        assertApiCode(() -> unsupported.service.apply(12L, MusicProvider.QQ, "ext",
                new MusicMetadataApplyService.ApplyRequest(Set.of("subtitle"))), "EXTERNAL_FIELDS_INVALID");
        verify(unsupported.songs, never()).save(any(Song.class));
        verify(unsupported.storage, never()).saveMatch(anyLong(), any(), anyDouble());

        Fixture duplicate = fixture(song(13L), track("Duplicate title", List.of("Artist"), null, null, List.of(), null));
        Song other = song(999L);
        other.setTitle("Existing duplicate");
        when(duplicate.songs.findByFingerprint(anyString())).thenReturn(Optional.of(other));
        assertApiCode(() -> duplicate.service.apply(13L, MusicProvider.QQ, "ext",
                new MusicMetadataApplyService.ApplyRequest(Set.of("title"))), "SONG_FINGERPRINT_CONFLICT");
        verify(duplicate.songs, never()).save(any(Song.class));
        verify(duplicate.storage, never()).saveMatch(anyLong(), any(), anyDouble());
    }

    @Test
    void matchingSongFingerprintIsNotTreatedAsAConflict() {
        Fixture fixture = fixture(song(14L), track("Renamed title", List.of("Artist"), null, null, List.of(), null));
        when(fixture.songs.findByFingerprint(anyString())).thenReturn(Optional.of(fixture.song));

        MusicMetadataApplyService.ApplyResult result = fixture.service.applyManual(14L,
                new MusicMetadataApplyService.ApplyRequest(Set.of("title"), Map.of("title", "Renamed title")));

        assertThat(result.title()).isEqualTo("Renamed title");
        verify(fixture.songs).save(fixture.song);
    }

    @Test
    void lockedTitleWithoutOverrideAndUnrequestedArtistRemainUnchanged() {
        Song song = song(23L);
        song.setMetadataLocks(new String[]{"title"});
        Fixture fixture = fixture(song, track("Provider title", List.of("Provider artist"),
                "Provider album", null, List.of(), null));

        MusicMetadataApplyService.ApplyResult result = fixture.service.apply(23L, MusicProvider.QQ, "ext",
                new MusicMetadataApplyService.ApplyRequest(Set.of("title", "album")));

        assertThat(song.getTitle()).isEqualTo("Old title");
        assertThat(song.getArtist()).isEqualTo("Old artist");
        assertThat(song.getAlbum()).isEqualTo("Provider album");
        assertThat(result.skippedLockedFields()).containsExactly("title");
        assertThat(result.appliedFields()).containsExactly("album");
        verifyNoInteractions(fixture.artistCredits);
    }

    @Test
    void lockedAliasesWithoutOverrideAreSkipped() {
        Song song = song(24L);
        song.setMetadataLocks(new String[]{"aliases"});
        Fixture fixture = fixture(song, track("Provider", List.of(), null, null, List.of("Provider alias"), null));

        MusicMetadataApplyService.ApplyResult result = fixture.service.apply(24L, MusicProvider.QQ, "ext",
                new MusicMetadataApplyService.ApplyRequest(Set.of("aliases")));

        assertThat(song.getAliases()).isEmpty();
        assertThat(result.skippedLockedFields()).containsExactly("aliases");
        assertThat(result.appliedFields()).isEmpty();
    }

    @Test
    void currentSongFingerprintIsAllowedDuringExternalApplyButOtherSongConflictsInManualApply() {
        Fixture sameSong = fixture(song(25L), track("Renamed", List.of("Artist"), null, null, List.of(), null));
        when(sameSong.songs.findByFingerprint(anyString())).thenReturn(Optional.of(sameSong.song));
        MusicMetadataApplyService.ApplyResult result = sameSong.service.apply(25L, MusicProvider.QQ, "ext",
                new MusicMetadataApplyService.ApplyRequest(Set.of("title")));
        assertThat(result.title()).isEqualTo("Renamed");

        Fixture duplicate = fixture(song(26L), track("Duplicate", List.of(), null, null, List.of(), null));
        when(duplicate.songs.findByFingerprint(anyString())).thenReturn(Optional.of(song(900L)));
        assertApiCode(() -> duplicate.service.applyManual(26L,
                new MusicMetadataApplyService.ApplyRequest(Set.of("title"), Map.of("title", "Duplicate"))),
                "SONG_FINGERPRINT_CONFLICT");
        verify(duplicate.songs, never()).save(any(Song.class));
    }

    @Test
    void manualRequestUsesOverridesWhenFieldsAreNullOrEmptyAndRejectsNullOverrides() {
        Fixture nullFields = fixture(song(27L), track("Provider", List.of(), null, null, List.of(), null));
        MusicMetadataApplyService.ApplyResult nullFieldsResult = nullFields.service.applyManual(27L,
                new MusicMetadataApplyService.ApplyRequest(null, Map.of("title", "From null fields")));
        assertThat(nullFieldsResult.title()).isEqualTo("From null fields");

        Fixture emptyFields = fixture(song(28L), track("Provider", List.of(), null, null, List.of(), null));
        MusicMetadataApplyService.ApplyResult emptyFieldsResult = emptyFields.service.applyManual(28L,
                new MusicMetadataApplyService.ApplyRequest(Set.of(), Map.of("album", "From empty fields")));
        assertThat(emptyFieldsResult.album()).isEqualTo("From empty fields");

        Fixture nullOverrides = fixture(song(29L), track("Provider", List.of(), null, null, List.of(), null));
        assertApiCode(() -> nullOverrides.service.applyManual(29L,
                new MusicMetadataApplyService.ApplyRequest(Set.of("title"), null)), "MANUAL_METADATA_EMPTY");
    }

    @Test
    void manualValidationFiltersUnrequestedValuesAndRejectsEmptyUnsupportedAndOversizeInput() {
        Fixture fixture = fixture(song(15L), track("Provider", List.of(), null, null, List.of(), null));
        assertApiCode(() -> fixture.service.applyManual(15L, null), "EXTERNAL_FIELDS_INVALID");
        assertApiCode(() -> fixture.service.applyManual(15L,
                new MusicMetadataApplyService.ApplyRequest(Set.of("cover"), Map.of("cover", "ignored"))),
                "EXTERNAL_FIELDS_INVALID");
        assertApiCode(() -> fixture.service.applyManual(15L,
                new MusicMetadataApplyService.ApplyRequest(Set.of("title"), Map.of())), "MANUAL_METADATA_EMPTY");

        Map<String, String> absentAndFiltered = new HashMap<>();
        absentAndFiltered.put("title", null);
        absentAndFiltered.put("album", "not requested");
        absentAndFiltered.put("unknown", "not editable");
        assertApiCode(() -> fixture.service.applyManual(15L,
                new MusicMetadataApplyService.ApplyRequest(Set.of("title"), absentAndFiltered)), "MANUAL_METADATA_EMPTY");
        assertApiCode(() -> fixture.service.applyManual(15L,
                new MusicMetadataApplyService.ApplyRequest(Set.of("title"), Map.of("title", "  "))),
                "MANUAL_METADATA_EMPTY");
        assertApiCode(() -> fixture.service.applyManual(15L,
                new MusicMetadataApplyService.ApplyRequest(Set.of("title"), Map.of("title", "x".repeat(501)))),
                "EXTERNAL_OVERRIDE_INVALID");
        assertApiCode(() -> fixture.service.applyManual(15L,
                new MusicMetadataApplyService.ApplyRequest(Set.of("aliases"), Map.of("aliases", "x".repeat(1001)))),
                "EXTERNAL_OVERRIDE_INVALID");
        verify(fixture.songs, never()).save(any(Song.class));
    }

    @Test
    void manualMetadataAcceptsLengthLimitsAndNormalizesAliasSeparatorsAndDuplicates() {
        Fixture fixture = fixture(song(16L), track("Provider", List.of(), null, null, List.of(), null));
        String maxTitle = "t".repeat(500);
        String maxAliases = "a".repeat(1000);

        fixture.service.applyManual(16L, new MusicMetadataApplyService.ApplyRequest(Set.of("title", "aliases"),
                Map.of("title", maxTitle, "aliases", maxAliases)));

        assertThat(fixture.song.getTitle()).hasSize(500);
        assertThat(fixture.song.getAliases()).containsExactly(maxAliases);

        Fixture aliases = fixture(song(17L), track("Provider", List.of(), null, null, List.of(), null));
        aliases.service.applyManual(17L, new MusicMetadataApplyService.ApplyRequest(Set.of("aliases"),
                Map.of("aliases", ", A, B，A\n C、,D ")));
        assertThat(aliases.song.getAliases()).containsExactly("A", "B", "C", "D");

        Fixture limited = fixture(song(18L), track("Provider", List.of(), null, null, List.of(), null));
        String manyAliases = String.join(",", java.util.stream.IntStream.rangeClosed(1, 25)
                .mapToObj(index -> "alias" + index).toList());
        limited.service.applyManual(18L, new MusicMetadataApplyService.ApplyRequest(Set.of("aliases"),
                Map.of("aliases", manyAliases)));
        assertThat(limited.song.getAliases()).hasSize(20).startsWith("alias1").endsWith("alias20");

        Fixture separatorOnly = fixture(song(30L), track("Provider", List.of(), null, null, List.of(), null));
        MusicMetadataApplyService.ApplyResult separatorResult = separatorOnly.service.applyManual(30L,
                new MusicMetadataApplyService.ApplyRequest(Set.of("aliases"), Map.of("aliases", ",，、\n")));
        assertThat(separatorResult.appliedFields()).containsExactly("aliases");
        assertThat(separatorOnly.song.getAliases()).isEmpty();
    }

    @Test
    void manualArtistUpdateReplacesCreditsAndBumpsRevisionWhileTitleUpdateDoesNotReplaceCredits() {
        Fixture artist = fixture(song(19L), track("Provider", List.of(), null, null, List.of(), null));
        artist.service.setCatalogRevisionService(artist.revisions);
        artist.service.applyManual(19L, new MusicMetadataApplyService.ApplyRequest(Set.of("artist"),
                Map.of("artist", "Reviewed artist")));
        assertThat(artist.song.getArtist()).isEqualTo("Reviewed artist");
        verify(artist.artistCredits).replace(19L, "Reviewed artist");
        verify(artist.revisions).bumpIfChanged(true);

        Fixture title = fixture(song(20L), track("Provider", List.of(), null, null, List.of(), null));
        title.service.setCatalogRevisionService(title.revisions);
        title.service.applyManual(20L, new MusicMetadataApplyService.ApplyRequest(Set.of("title"),
                Map.of("title", "Reviewed title")));
        verifyNoInteractions(title.artistCredits);
        verify(title.revisions).bumpIfChanged(true);
    }

    @Test
    void invalidExistingProvenanceFallsBackToEmptyJsonForExternalAndManualWrites() {
        Fixture external = fixture(song(21L), track("Provider", List.of("Artist"), null, null, List.of(), null));
        external.song.setMetadataProvenance("not-json");
        external.service.apply(21L, MusicProvider.QQ, "ext",
                new MusicMetadataApplyService.ApplyRequest(Set.of("title")));
        assertThat(external.song.getMetadataProvenance()).isEqualTo("{}");

        Fixture manual = fixture(song(22L), track("Provider", List.of(), null, null, List.of(), null));
        manual.song.setMetadataProvenance("not-json");
        manual.service.applyManual(22L, new MusicMetadataApplyService.ApplyRequest(Set.of("title"),
                Map.of("title", "Reviewed")));
        assertThat(manual.song.getMetadataProvenance()).isEqualTo("{}");
    }

    private static Fixture fixture(Song song, ExternalTrack track) {
        SongRepository songs = mock(SongRepository.class);
        MusicSourceSearchService search = mock(MusicSourceSearchService.class);
        ExternalTrackStorage storage = mock(ExternalTrackStorage.class);
        ExternalCoverService cover = mock(ExternalCoverService.class);
        MusicSourceConfigService config = mock(MusicSourceConfigService.class);
        ArtistCreditService artistCredits = mock(ArtistCreditService.class);
        CatalogRevisionService revisions = mock(CatalogRevisionService.class);
        when(songs.findById(song.getId())).thenReturn(Optional.of(song));
        when(songs.findByFingerprint(anyString())).thenReturn(Optional.empty());
        when(songs.save(any(Song.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(storage.track(any(), anyString(), anyBoolean())).thenReturn(Optional.of(track));
        when(config.getConfig()).thenReturn(new MusicSourceConfig(true, Set.of(MusicProvider.QQ),
                20, 5, 6, 2, 100, 0.9));
        MusicMetadataApplyService service = new MusicMetadataApplyService(songs, search, storage, cover,
                config, new ObjectMapper(), artistCredits);
        return new Fixture(song, track, songs, search, storage, cover, artistCredits, revisions, service);
    }

    private static Song song(long id) {
        Song song = new Song();
        song.setId(id);
        song.setTitle("Old title");
        song.setArtist("Old artist");
        song.setAlbum("Old album");
        song.setReleaseDate("1999");
        song.setDurationMs(200_000);
        song.setFingerprint("old-fingerprint-" + id);
        song.setMetadataProvenance("{}");
        return song;
    }

    private static ExternalTrack track(String title, List<String> artists, String album, String releaseDate,
                                       List<String> aliases, String coverUrl) {
        return new ExternalTrack(MusicProvider.QQ, "ext", title, artists, album, 200_000, releaseDate,
                aliases, coverUrl, "AVAILABLE", null);
    }

    private static void assertApiCode(org.assertj.core.api.ThrowableAssert.ThrowingCallable action, String code) {
        assertThatThrownBy(action).isInstanceOfSatisfying(ApiException.class,
                error -> assertThat(error.getCode()).isEqualTo(code));
    }

    private record Fixture(Song song, ExternalTrack track, SongRepository songs, MusicSourceSearchService search,
                           ExternalTrackStorage storage, ExternalCoverService cover,
                           ArtistCreditService artistCredits, CatalogRevisionService revisions,
                           MusicMetadataApplyService service) { }
}
