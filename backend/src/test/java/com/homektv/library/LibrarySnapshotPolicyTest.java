package com.homektv.library;

import com.homektv.domain.SongFile;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;

import static org.assertj.core.api.Assertions.assertThat;

class LibrarySnapshotPolicyTest {
    private static final OffsetDateTime MEDIA_MTIME = OffsetDateTime.parse("2026-10-01T10:00:00.123456Z");
    private static final OffsetDateTime LYRIC_MTIME = OffsetDateTime.parse("2026-10-01T10:01:00.000000Z");

    @Test
    void separateUnchangedSnapshotsDoNotRequestProbeOrLyricRefresh() {
        var stored = facts(10, MEDIA_MTIME, "media-1", 1, 20L, LYRIC_MTIME, "lyric-1", false);
        var current = facts(10, MEDIA_MTIME, "media-1", 1, 20L, LYRIC_MTIME, "lyric-1", false);

        assertThat(LibrarySnapshotPolicy.classify(stored, current, true, true))
                .isEqualTo(LibrarySnapshotPolicy.Change.UNCHANGED);
    }

    @Test
    void sidecarOnlyChangeDoesNotRequestMediaProbe() {
        var stored = facts(10, MEDIA_MTIME, "media-1", 1, 20L, LYRIC_MTIME, "lyric-1", false);
        var current = facts(10, MEDIA_MTIME, "media-1", 1, 21L, LYRIC_MTIME.plusSeconds(1), "lyric-2", false);

        assertThat(LibrarySnapshotPolicy.classify(stored, current, true, true))
                .isEqualTo(LibrarySnapshotPolicy.Change.LYRIC_ONLY);
    }

    @Test
    void mediaChangeTakesPriorityOverSidecarChange() {
        var stored = facts(10, MEDIA_MTIME, "media-1", 1, 20L, LYRIC_MTIME, "lyric-1", false);
        var current = facts(10, MEDIA_MTIME.plusSeconds(5), "media-2", 1,
                21L, LYRIC_MTIME.plusSeconds(1), "lyric-2", false);

        assertThat(LibrarySnapshotPolicy.classify(stored, current, true, true))
                .isEqualTo(LibrarySnapshotPolicy.Change.MEDIA_CHANGED);
    }

    @Test
    void legacyRowsKeepCompositeSnapshotSemantics() {
        var stored = new LibrarySnapshotPolicy.Facts(10, MEDIA_MTIME, "legacy-composite", null, null,
                null, null, null, null, false);
        var noSidecar = new LibrarySnapshotPolicy.Facts(10, MEDIA_MTIME, "legacy-composite",
                MEDIA_MTIME, "media-1", 1, null, null, null, false);
        var sidecar = facts(10, MEDIA_MTIME, "media-1", 1, 20L, LYRIC_MTIME, "lyric-1", false);

        assertThat(LibrarySnapshotPolicy.classify(stored, noSidecar, true, false))
                .isEqualTo(LibrarySnapshotPolicy.Change.UNCHANGED);
        assertThat(LibrarySnapshotPolicy.classify(stored, sidecar, true, true))
                .isEqualTo(LibrarySnapshotPolicy.Change.MEDIA_CHANGED);
    }

    @Test
    void unreadableSidecarRequestsOnlyALyricRefresh() {
        var stored = facts(10, MEDIA_MTIME, "media-1", 1, 20L, LYRIC_MTIME, "lyric-1", false);
        var current = facts(10, MEDIA_MTIME, "media-1", 1, null, null, null, false);

        assertThat(LibrarySnapshotPolicy.classify(stored, current, false, true))
                .isEqualTo(LibrarySnapshotPolicy.Change.LYRIC_ONLY);
    }

    @Test
    void legacyRowsUseReadabilityAndPresenceWhenLyricSnapshotVersionIsUnknown() {
        var stored = new LibrarySnapshotPolicy.Facts(10, MEDIA_MTIME, "legacy-composite", null, null,
                null, null, null, null, false);
        var current = new LibrarySnapshotPolicy.Facts(10, MEDIA_MTIME, "legacy-composite",
                MEDIA_MTIME, "media-1", null, null, null, null, false);

        assertThat(LibrarySnapshotPolicy.classify(stored, current, false, false))
                .isEqualTo(LibrarySnapshotPolicy.Change.LYRIC_ONLY);
        assertThat(LibrarySnapshotPolicy.classify(stored, current, true, true))
                .isEqualTo(LibrarySnapshotPolicy.Change.MEDIA_CHANGED);
    }

    @Test
    void mediaSnapshotWithoutMtimeFailsClosedEvenWhenIdentityMatches() {
        var stored = new LibrarySnapshotPolicy.Facts(10, MEDIA_MTIME, "legacy-file", null, "media-1",
                1, 20L, LYRIC_MTIME, "lyric-1", false);
        var current = new LibrarySnapshotPolicy.Facts(10, MEDIA_MTIME, "other-file", null, "media-1",
                1, 20L, LYRIC_MTIME, "lyric-1", false);

        assertThat(LibrarySnapshotPolicy.classify(stored, current, true, true))
                .isEqualTo(LibrarySnapshotPolicy.Change.MEDIA_CHANGED);

        var missingCurrentMtime = new LibrarySnapshotPolicy.Facts(10, MEDIA_MTIME, "legacy-file",
                null, "media-1", 1, 20L, LYRIC_MTIME, "lyric-1", false);
        var mtimeOnlyStored = new LibrarySnapshotPolicy.Facts(10, MEDIA_MTIME, "legacy-file",
                MEDIA_MTIME, null, 1, 20L, LYRIC_MTIME, "lyric-1", false);

        assertThat(LibrarySnapshotPolicy.classify(mtimeOnlyStored, missingCurrentMtime, true, true))
                .isEqualTo(LibrarySnapshotPolicy.Change.MEDIA_CHANGED);
    }

    @Test
    void absentOrPendingStoredSnapshotAlwaysRequestsMediaProbe() {
        var current = facts(10, MEDIA_MTIME, "media-1", 1, 20L, LYRIC_MTIME, "lyric-1", false);
        var pending = facts(10, MEDIA_MTIME, "media-1", 1, 20L, LYRIC_MTIME, "lyric-1", true);

        assertThat(LibrarySnapshotPolicy.classify(null, current, true, true))
                .isEqualTo(LibrarySnapshotPolicy.Change.MEDIA_CHANGED);
        assertThat(LibrarySnapshotPolicy.classify(pending, current, true, true))
                .isEqualTo(LibrarySnapshotPolicy.Change.MEDIA_CHANGED);
    }

    @Test
    void mediaIdentityChangeRequestsProbeWhenSizeAndMtimeMatch() {
        var stored = facts(10, MEDIA_MTIME, "media-1", 1, 20L, LYRIC_MTIME, "lyric-1", false);
        var current = facts(10, MEDIA_MTIME, "media-2", 1, 20L, LYRIC_MTIME, "lyric-1", false);

        assertThat(LibrarySnapshotPolicy.classify(stored, current, true, true))
                .isEqualTo(LibrarySnapshotPolicy.Change.MEDIA_CHANGED);
    }

    @Test
    void mediaSizeChangeRequestsProbeWhenOtherSnapshotFieldsMatch() {
        var stored = facts(10, MEDIA_MTIME, "media-1", 1, 20L, LYRIC_MTIME, "lyric-1", false);
        var current = facts(11, MEDIA_MTIME, "media-1", 1, 20L, LYRIC_MTIME, "lyric-1", false);

        assertThat(LibrarySnapshotPolicy.classify(stored, current, true, true))
                .isEqualTo(LibrarySnapshotPolicy.Change.MEDIA_CHANGED);
    }

    @Test
    void lyricMtimeMissingOnEitherSideIsTreatedAsLyricOnlyChange() {
        var stored = facts(10, MEDIA_MTIME, "media-1", 1, 20L, LYRIC_MTIME, "lyric-1", false);
        var storedWithoutMtime = facts(10, MEDIA_MTIME, "media-1", 1, 20L, null, "lyric-1", false);
        var currentWithoutMtime = facts(10, MEDIA_MTIME, "media-1", 1, 20L, null, "lyric-1", false);
        var currentWithMtime = facts(10, MEDIA_MTIME, "media-1", 1, 20L, LYRIC_MTIME, "lyric-1", false);

        assertThat(LibrarySnapshotPolicy.classify(stored, currentWithoutMtime, true, true))
                .isEqualTo(LibrarySnapshotPolicy.Change.LYRIC_ONLY);
        assertThat(LibrarySnapshotPolicy.classify(storedWithoutMtime, currentWithMtime, true, true))
                .isEqualTo(LibrarySnapshotPolicy.Change.LYRIC_ONLY);
    }

    @Test
    void lyricSnapshotsMatchWhenAllOptionalMetadataIsMissingAndDetectMtimeChanges() {
        var storedWithoutMetadata = facts(10, MEDIA_MTIME, "media-1", 1, null, null, null, false);
        var currentWithoutMetadata = facts(10, MEDIA_MTIME, "media-1", 1, null, null, null, false);

        assertThat(LibrarySnapshotPolicy.classify(storedWithoutMetadata, currentWithoutMetadata, true, false))
                .isEqualTo(LibrarySnapshotPolicy.Change.UNCHANGED);

        var stored = facts(10, MEDIA_MTIME, "media-1", 1, 20L, LYRIC_MTIME, "lyric-1", false);
        var changedMtime = facts(10, MEDIA_MTIME, "media-1", 1, 20L, LYRIC_MTIME.plusSeconds(1),
                "lyric-1", false);

        assertThat(LibrarySnapshotPolicy.classify(stored, changedMtime, true, true))
                .isEqualTo(LibrarySnapshotPolicy.Change.LYRIC_ONLY);
    }

    @Test
    void lyricIdentityChangesAreDetectedWhenEitherSideIsMissingOrValuesDiffer() {
        var stored = facts(10, MEDIA_MTIME, "media-1", 1, 20L, LYRIC_MTIME, "lyric-1", false);
        var absentStoredIdentity = facts(10, MEDIA_MTIME, "media-1", 1, 20L, LYRIC_MTIME, null, false);
        var absentCurrentIdentity = facts(10, MEDIA_MTIME, "media-1", 1, 20L, LYRIC_MTIME, null, false);
        var differentIdentity = facts(10, MEDIA_MTIME, "media-1", 1, 20L, LYRIC_MTIME, "lyric-2", false);

        assertThat(LibrarySnapshotPolicy.classify(absentStoredIdentity, stored, true, true))
                .isEqualTo(LibrarySnapshotPolicy.Change.LYRIC_ONLY);
        assertThat(LibrarySnapshotPolicy.classify(stored, absentCurrentIdentity, true, true))
                .isEqualTo(LibrarySnapshotPolicy.Change.LYRIC_ONLY);
        assertThat(LibrarySnapshotPolicy.classify(stored, differentIdentity, true, true))
                .isEqualTo(LibrarySnapshotPolicy.Change.LYRIC_ONLY);
    }

    @Test
    void songFileSnapshotConversionPreservesScanDecisionAndMissingRowsRequestProbe() {
        SongFile storedFile = new SongFile();
        storedFile.setFileSize(10L);
        storedFile.setFileMtime(MEDIA_MTIME);
        storedFile.setFileIdentity("legacy-file");
        storedFile.setMediaMtime(MEDIA_MTIME);
        storedFile.setMediaFileIdentity("media-1");
        storedFile.setLyricSnapshotVersion(1);
        storedFile.setLyricSize(20L);
        storedFile.setLyricMtime(LYRIC_MTIME);
        storedFile.setLyricFileIdentity("lyric-1");
        storedFile.setProbePending(false);
        var current = new LibrarySnapshotPolicy.Facts(10, MEDIA_MTIME, "legacy-file",
                MEDIA_MTIME, "media-1", 1, 20L, LYRIC_MTIME, "lyric-1", false);

        assertThat(LibrarySnapshotPolicy.classify(
                LibrarySnapshotPolicy.fromSongFile(storedFile), current, true, true))
                .isEqualTo(LibrarySnapshotPolicy.Change.UNCHANGED);
        assertThat(LibrarySnapshotPolicy.classify(
                LibrarySnapshotPolicy.fromSongFile(null), current, true, true))
                .isEqualTo(LibrarySnapshotPolicy.Change.MEDIA_CHANGED);
    }

    @Test
    void mtimeNormalizationKeepsMicrosecondPrecisionAndAcceptsMissingTimestamp() {
        OffsetDateTime nanosecondMtime = OffsetDateTime.parse("2026-10-01T10:00:00.123456789Z");

        assertThat(LibrarySnapshotPolicy.normalizeMtime(nanosecondMtime))
                .isEqualTo(OffsetDateTime.parse("2026-10-01T10:00:00.123456Z"));
        assertThat(LibrarySnapshotPolicy.normalizeMtime(null)).isNull();
    }

    private static LibrarySnapshotPolicy.Facts facts(long size, OffsetDateTime mediaMtime, String mediaIdentity,
                                                       Integer lyricVersion, Long lyricSize,
                                                       OffsetDateTime lyricMtime, String lyricIdentity,
                                                       boolean pending) {
        return new LibrarySnapshotPolicy.Facts(size, mediaMtime, mediaIdentity, mediaMtime, mediaIdentity,
                lyricVersion, lyricSize, lyricMtime, lyricIdentity, pending);
    }
}
