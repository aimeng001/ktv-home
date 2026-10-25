package com.homektv.library;

import com.homektv.domain.SongFile;

import java.time.OffsetDateTime;
import java.util.Objects;

/** Pure comparison policy for the separate media and sidecar snapshots used by scans. */
final class LibrarySnapshotPolicy {
    private LibrarySnapshotPolicy() {}

    static Change classify(Facts stored, Facts current, boolean currentLyricReadable,
                           boolean currentLyricPresent) {
        if (stored == null || stored.probePending() || !mediaMatches(stored, current)) {
            return Change.MEDIA_CHANGED;
        }

        if (stored.lyricSnapshotVersion() == null) {
            if (!currentLyricReadable) return Change.LYRIC_ONLY;
            return currentLyricPresent ? Change.MEDIA_CHANGED : Change.UNCHANGED;
        }
        if (!currentLyricReadable || !lyricMatches(stored, current)) return Change.LYRIC_ONLY;
        return Change.UNCHANGED;
    }

    static Facts fromSongFile(SongFile file) {
        return file == null ? null : new Facts(file.getFileSize(), file.getFileMtime(), file.getFileIdentity(),
                file.getMediaMtime(), file.getMediaFileIdentity(), file.getLyricSnapshotVersion(),
                file.getLyricSize(), file.getLyricMtime(), file.getLyricFileIdentity(), file.isProbePending());
    }

    static OffsetDateTime normalizeMtime(OffsetDateTime value) {
        if (value == null) return null;
        return value.withNano((value.getNano() / 1_000) * 1_000);
    }

    private static boolean mediaMatches(Facts stored, Facts current) {
        boolean hasIndependentSnapshot = stored.mediaMtime() != null || stored.mediaIdentity() != null;
        OffsetDateTime oldMtime = hasIndependentSnapshot ? stored.mediaMtime() : stored.fileMtime();
        String oldIdentity = hasIndependentSnapshot ? stored.mediaIdentity() : stored.fileIdentity();
        OffsetDateTime currentMtime = hasIndependentSnapshot ? current.mediaMtime() : current.fileMtime();
        String currentIdentity = hasIndependentSnapshot ? current.mediaIdentity() : current.fileIdentity();
        return stored.size() == current.size()
                && sameMtime(oldMtime, currentMtime)
                && sameFileIdentity(oldIdentity, currentIdentity);
    }

    private static boolean lyricMatches(Facts stored, Facts current) {
        return Objects.equals(stored.lyricSize(), current.lyricSize())
                && sameNullableMtime(stored.lyricMtime(), current.lyricMtime())
                && sameFileIdentity(stored.lyricIdentity(), current.lyricIdentity());
    }

    private static boolean sameNullableMtime(OffsetDateTime left, OffsetDateTime right) {
        return left == null && right == null || sameMtime(left, right);
    }

    private static boolean sameFileIdentity(String stored, String current) {
        return stored == null && current == null
                || stored != null && current != null && stored.equals(current);
    }

    private static boolean sameMtime(OffsetDateTime left, OffsetDateTime right) {
        return left != null && right != null && mtimeKey(left) == mtimeKey(right);
    }

    private static long mtimeKey(OffsetDateTime value) {
        var instant = value.toInstant();
        return instant.getEpochSecond() * 1_000_000L + instant.getNano() / 1_000;
    }

    enum Change { UNCHANGED, LYRIC_ONLY, MEDIA_CHANGED }

    record Facts(long size, OffsetDateTime fileMtime, String fileIdentity,
                 OffsetDateTime mediaMtime, String mediaIdentity,
                 Integer lyricSnapshotVersion, Long lyricSize, OffsetDateTime lyricMtime,
                 String lyricIdentity, boolean probePending) {}
}
