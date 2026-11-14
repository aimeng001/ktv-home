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

    @Test
    void matchesDuetArtistsWithNormalizedDelimitersAndOrder() {
        // NAS: 林二汶_岑宁儿, IKTV: 林二汶+岑宁儿
        IktvSongReferenceMatcher.HomeSong home1 = home("", "银发白", "林二汶_岑宁儿", "", "林二汶_岑宁儿-银发白-粤语-流行.mkv");
        IktvSongReferenceMatcher.ReferenceSong ref1 = reference(30, "60000001", "银发白", "林二汶+岑宁儿", "MV", "粤语", "流行");

        IktvSongReferenceMatcher.Result res1 = matcher.match(home1, List.of(ref1));
        assertThat(res1.status()).isEqualTo(IktvSongReferenceMatcher.Status.MATCHED);
        assertThat(res1.candidate()).isEqualTo(ref1);

        // NAS: 蔡依林 陶喆, IKTV: 陶喆+蔡依林 (顺序调换)
        IktvSongReferenceMatcher.HomeSong home2 = home("", "今天你要嫁给我", "蔡依林 陶喆", "", "蔡依林 陶喆-今天你要嫁给我-国语-流行.mkv");
        IktvSongReferenceMatcher.ReferenceSong ref2 = reference(31, "60000002", "今天你要嫁给我", "陶喆+蔡依林", "MV", "国语", "流行");

        IktvSongReferenceMatcher.Result res2 = matcher.match(home2, List.of(ref2));
        assertThat(res2.status()).isEqualTo(IktvSongReferenceMatcher.Status.MATCHED);
        assertThat(res2.candidate()).isEqualTo(ref2);
    }

    @Test
    void disambiguatesMultipleVersionsUsingFourSegmentFilenameLanguageAndCategory() {
        IktvSongReferenceMatcher.HomeSong home = home("", "晴天", "周杰伦", "", "T:/QBdownload/周杰伦-晴天-国语-流行.mkv");
        IktvSongReferenceMatcher.ReferenceSong cantonese = reference(40, "10000001", "晴天", "周杰伦", "MV", "粤语", "流行");
        IktvSongReferenceMatcher.ReferenceSong mandarin = reference(41, "10000002", "晴天", "周杰伦", "MV", "国语", "流行");

        IktvSongReferenceMatcher.Result result = matcher.match(home, List.of(cantonese, mandarin));

        assertThat(result.status()).isEqualTo(IktvSongReferenceMatcher.Status.MATCHED);
        assertThat(result.candidate()).isEqualTo(mandarin);
    }

    @Test
    void disambiguatesExcludingLiveWhenFilenameIsNonLive() {
        IktvSongReferenceMatcher.HomeSong home = home("", "晴天", "周杰伦", "", "周杰伦-晴天-国语-流行.mkv");
        IktvSongReferenceMatcher.ReferenceSong mv = reference(50, "10000001", "晴天", "周杰伦", "MV", "国语", "流行");
        IktvSongReferenceMatcher.ReferenceSong live = reference(51, "10000002", "晴天", "周杰伦", "现场版", "国语", "流行");

        IktvSongReferenceMatcher.Result result = matcher.match(home, List.of(mv, live));

        assertThat(result.status()).isEqualTo(IktvSongReferenceMatcher.Status.MATCHED);
        assertThat(result.candidate()).isEqualTo(mv);
    }

    @Test
    void disambiguatesPrioritizingLiveWhenFilenameContainsLive() {
        IktvSongReferenceMatcher.HomeSong home = home("", "晴天", "周杰伦", "", "周杰伦-晴天 (Live)-国语-流行.mkv");
        IktvSongReferenceMatcher.ReferenceSong mv = reference(60, "10000001", "晴天", "周杰伦", "MV", "国语", "流行");
        IktvSongReferenceMatcher.ReferenceSong live = reference(61, "10000002", "晴天", "周杰伦", "现场版", "国语", "流行");

        IktvSongReferenceMatcher.Result result = matcher.match(home, List.of(mv, live));

        assertThat(result.status()).isEqualTo(IktvSongReferenceMatcher.Status.MATCHED);
        assertThat(result.candidate()).isEqualTo(live);
    }

    private static IktvSongReferenceMatcher.HomeSong home(String number, String title, String artist,
                                                           String relativePath, String filePath) {
        return new IktvSongReferenceMatcher.HomeSong(number, title, artist, relativePath, filePath,
                Set.of());
    }

    private static IktvSongReferenceMatcher.ReferenceSong reference(long id, String number,
                                                                    String title, String artist,
                                                                    String edition) {
        return reference(id, number, title, artist, edition, "", "");
    }

    private static IktvSongReferenceMatcher.ReferenceSong reference(long id, String number,
                                                                    String title, String artist,
                                                                    String edition, String language,
                                                                    String category) {
        return new IktvSongReferenceMatcher.ReferenceSong(id, number, title, artist, edition,
                language, category, "", List.of("抖音神曲"), false);
    }
}
