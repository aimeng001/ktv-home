package com.homektv.library;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class IktvSongReferenceMatcherTest {
    private final IktvSongReferenceMatcher matcher = new IktvSongReferenceMatcher();

    @Test
    void matchesNineDigitNumberOnlyPathAndFullIdentity() {
        IktvSongReferenceMatcher.HomeSong home = home("", "晴天", "周杰伦", "song4001/100000075", "");
        IktvSongReferenceMatcher.ReferenceSong reference = reference(1, "100000075", "晴天", "周杰伦", "MV");

        IktvSongReferenceMatcher.Result result = matcher.match(home, List.of(reference));

        assertThat(result.status()).isEqualTo(IktvSongReferenceMatcher.Status.MATCHED);
        assertThat(result.method()).isEqualTo(IktvSongReferenceMatcher.Method.CATALOG_NUMBER);
        assertThat(result.suggestedCatalogNumber()).isEqualTo("100000075");
    }

    @Test
    void numericParentDirectoryProvidesNumberClueAndMustValidateIdentity() {
        IktvSongReferenceMatcher.HomeSong home = home("", "晴天", "周杰伦", "song1/60002434/晴天.mkv", "");
        IktvSongReferenceMatcher.ReferenceSong reference = reference(2, "60002434", "晴天", "周杰伦", "MV");

        IktvSongReferenceMatcher.Result result = matcher.match(home, List.of(reference));

        assertThat(result.status()).isEqualTo(IktvSongReferenceMatcher.Status.MATCHED);
        assertThat(result.method()).isEqualTo(IktvSongReferenceMatcher.Method.CATALOG_NUMBER);
    }

    @Test
    void dateLikeDirectoryDoesNotBecomeCatalogNumberClue() {
        IktvSongReferenceMatcher.HomeSong home = home("", "晴天", "周杰伦",
                "20261109/晴天.mkv", "");
        IktvSongReferenceMatcher.ReferenceSong reference = reference(
                21, "123456789", "晴天", "周杰伦", "MV");

        IktvSongReferenceMatcher.Result result = matcher.match(home, List.of(reference));

        assertThat(result.status()).isEqualTo(IktvSongReferenceMatcher.Status.MATCHED);
        assertThat(result.method()).isEqualTo(IktvSongReferenceMatcher.Method.TITLE_AND_ARTIST);
    }

    @Test
    void ambiguousNineDigitFilenamePrefixFallsBackOnlyToUniqueFullIdentity() {
        IktvSongReferenceMatcher.HomeSong home = home(
                "", "晴天", "周杰伦", "", "/library/123456789-周杰伦-晴天.mkv");
        IktvSongReferenceMatcher.ReferenceSong unique = reference(22, "123456789", "晴天", "周杰伦", "MV");

        IktvSongReferenceMatcher.Result result = matcher.match(home, List.of(unique));

        assertThat(result.status()).isEqualTo(IktvSongReferenceMatcher.Status.MATCHED);
        assertThat(result.method()).isEqualTo(IktvSongReferenceMatcher.Method.TITLE_AND_ARTIST);
    }

    @Test
    void dateLikeFilenamePrefixIsNotAStoredCatalogNumber() {
        IktvSongReferenceMatcher.HomeSong home = home(
                "", "晴天", "周杰伦", "", "/library/20240512-周杰伦-晴天.mkv");
        IktvSongReferenceMatcher.ReferenceSong unique = reference(23, "123456789", "晴天", "周杰伦", "MV");

        IktvSongReferenceMatcher.Result result = matcher.match(home, List.of(unique));

        assertThat(result.status()).isEqualTo(IktvSongReferenceMatcher.Status.MATCHED);
        assertThat(result.method()).isEqualTo(IktvSongReferenceMatcher.Method.TITLE_AND_ARTIST);
    }

    @Test
    void doesNotFallBackToTitleWhenCatalogNumberIdentityConflicts() {
        IktvSongReferenceMatcher.HomeSong home = home("123456789", "晴天", "周杰伦", "", "");
        IktvSongReferenceMatcher.ReferenceSong wrongNumber = reference(3, "123456789", "七里香", "周杰伦", "MV");
        IktvSongReferenceMatcher.ReferenceSong matchingTitleOtherNumber = reference(4, "987654321", "晴天", "周杰伦", "MV");

        IktvSongReferenceMatcher.Result result = matcher.match(home, List.of(wrongNumber, matchingTitleOtherNumber));

        assertThat(result.status()).isEqualTo(IktvSongReferenceMatcher.Status.NUMBER_IDENTITY_MISMATCH);
        assertThat(result.candidate()).isNull();
    }

    @Test
    void usesTitleAndCompleteArtistOnlyWhenReferenceIdentityAndVersionAreUnique() {
        IktvSongReferenceMatcher.HomeSong home = home("", " 晴天 ", "周杰伦", "", "");
        IktvSongReferenceMatcher.ReferenceSong unique = reference(5, "123456789", "晴天", "周杰伦", "MV");

        IktvSongReferenceMatcher.Result result = matcher.match(home, List.of(unique));

        assertThat(result.status()).isEqualTo(IktvSongReferenceMatcher.Status.MATCHED);
        assertThat(result.method()).isEqualTo(IktvSongReferenceMatcher.Method.TITLE_AND_ARTIST);
    }

    @Test
    void leavesMultipleEditionsUnmatchedWithoutAnExactVersionClue() {
        IktvSongReferenceMatcher.HomeSong home = home("", "晴天", "周杰伦", "", "");
        List<IktvSongReferenceMatcher.ReferenceSong> editions = List.of(
                reference(6, "123456789", "晴天", "周杰伦", "MV"),
                reference(7, "123456790", "晴天", "周杰伦", "现场版"));

        IktvSongReferenceMatcher.Result result = matcher.match(home, editions);

        assertThat(result.status()).isEqualTo(IktvSongReferenceMatcher.Status.AMBIGUOUS_VERSION);
        assertThat(result.candidate()).isNull();
    }

    @Test
    void recordsExistingNumberVersusPathNumberConflictWithoutSelectingReference() {
        IktvSongReferenceMatcher.HomeSong home = home(
                "00123456", "晴天", "周杰伦", "", "/library/00987654-周杰伦-晴天.mkv");
        IktvSongReferenceMatcher.ReferenceSong reference = reference(8, "00123456", "晴天", "周杰伦", "MV");

        IktvSongReferenceMatcher.Result result = matcher.match(home, List.of(reference));

        assertThat(result.status()).isEqualTo(IktvSongReferenceMatcher.Status.NUMBER_CONFLICT);
        assertThat(result.candidate()).isNull();
    }

    private static IktvSongReferenceMatcher.HomeSong home(String number, String title, String artist,
                                                           String relativePath, String filePath) {
        return new IktvSongReferenceMatcher.HomeSong(number, title, artist, relativePath, filePath,
                Set.of());
    }

    private static IktvSongReferenceMatcher.ReferenceSong reference(long id, String number,
                                                                    String title, String artist,
                                                                    String edition) {
        return new IktvSongReferenceMatcher.ReferenceSong(id, number, title, artist, edition,
                "", "", "", List.of("抖音神曲"), false);
    }
}
