package com.homektv.diagnostics;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class MountInfoParserTest {

    @Test
    void selectsLongestContainingMountAndUsesItsMountOptions() {
        List<String> lines = List.of(
                "29 23 0:25 / / rw,relatime - overlay overlay rw",
                "40 29 0:44 / /media ro,nosuid,nodev - cifs //nas/music rw,vers=3.1.1",
                "41 40 0:45 / /media/karaoke rw,nosuid - cifs //nas/karaoke rw,vers=3.1.1");

        assertThat(MountInfoParser.inspect(lines, "/media/song.mkv").status())
                .isEqualTo(MountInfoParser.Status.READ_ONLY);
        assertThat(MountInfoParser.inspect(lines, "/media/karaoke/song.mkv").status())
                .isEqualTo(MountInfoParser.Status.READ_WRITE);
    }

    @Test
    void decodesKernelEscapesAndDoesNotUseSubstringMatching() {
        List<String> lines = List.of(
                "40 29 0:44 / /media/KTV\\040Songs ro,nosuid - cifs //nas/music ro",
                "41 29 0:45 / /media/KTV rw - tmpfs tmpfs rw");

        assertThat(MountInfoParser.inspect(lines, "/media/KTV Songs/a.mkv").status())
                .isEqualTo(MountInfoParser.Status.READ_ONLY);
        assertThat(MountInfoParser.inspect(lines, "/media/KTV-other/a.mkv").status())
                .isEqualTo(MountInfoParser.Status.UNKNOWN);
    }

    @Test
    void malformedOrMissingMountInformationFailsClosedAsUnknown() {
        assertThat(MountInfoParser.inspect(List.of("not mountinfo"), "/media/song.mkv").status())
                .isEqualTo(MountInfoParser.Status.UNKNOWN);
        assertThat(MountInfoParser.inspect(List.of(), "/media/song.mkv").status())
                .isEqualTo(MountInfoParser.Status.UNKNOWN);
    }

    @Test
    void nullInputsAndMountsWithoutReadOnlyOrReadWriteOptionsStayUnknown() {
        List<String> lines = List.of("40 29 0:44 / /media noatime - ext4 /dev/sda rw");

        assertThat(MountInfoParser.inspect(null, "/media/song.mkv").status())
                .isEqualTo(MountInfoParser.Status.UNKNOWN);
        assertThat(MountInfoParser.inspect(lines, null).status())
                .isEqualTo(MountInfoParser.Status.UNKNOWN);
        assertThat(MountInfoParser.inspect(lines, " \t ").status())
                .isEqualTo(MountInfoParser.Status.UNKNOWN);
        assertThat(MountInfoParser.inspect(lines, "/media/").status())
                .isEqualTo(MountInfoParser.Status.UNKNOWN);
        assertThat(MountInfoParser.inspect(lines, "/media").mountPoint()).isEqualTo("/media");
    }

    @Test
    void rejectsMalformedRecordsAtFieldAndSeparatorBoundaries() {
        List<String> malformed = Arrays.asList(
                null,
                " \t ",
                "40 29 0:44 / /media ro nosuid nodev foo bar",
                "40 29 0:44 / /media ro x x - cifs");

        for (String line : malformed) {
            assertThat(MountInfoParser.inspect(Arrays.asList(line), "/media/song.mkv").status())
                    .isEqualTo(MountInfoParser.Status.UNKNOWN);
        }
    }

    @Test
    void decodesMultipleKernelEscapesAndNormalizesEscapedSeparatorsAndTargetPaths() {
        List<String> lines = List.of(
                "40 29 0:44 / /media/K\\040TV\\134Set/ ro,nosuid - cifs //nas/music rw");

        MountInfoParser.Inspection decoded = MountInfoParser.inspect(
                lines, "/media/K TV/Set\\song.mkv/");

        assertThat(decoded.status()).isEqualTo(MountInfoParser.Status.READ_ONLY);
        assertThat(decoded.mountPoint()).isEqualTo("/media/K TV/Set");

        List<String> trailingSeparator = List.of(
                "41 29 0:45 / /media/trailing\\ rw - ext4 /dev/sdb rw");
        assertThat(MountInfoParser.inspect(trailingSeparator, "/media/trailing/song.mkv").status())
                .isEqualTo(MountInfoParser.Status.READ_WRITE);
    }

    @Test
    void preservesMalformedEscapeSequencesInsteadOfDecodingThemAsOctal() {
        List<String> lines = List.of(
                "40 29 0:44 / /media/K\\08V ro - cifs //nas/music rw",
                "41 29 0:45 / /media/L\\x20 ro - cifs //nas/other rw",
                "42 29 0:46 / /media/M\\:00 ro - cifs //nas/third rw",
                "43 29 0:47 / /media/N\\01x ro - cifs //nas/fourth rw",
                "44 29 0:48 / /media/P\\!00 ro - cifs //nas/fifth rw");

        assertThat(MountInfoParser.inspect(lines, "/media/K/08V/song.mkv").status())
                .isEqualTo(MountInfoParser.Status.READ_ONLY);
        assertThat(MountInfoParser.inspect(lines, "/media/L/x20/song.mkv").status())
                .isEqualTo(MountInfoParser.Status.READ_ONLY);
        assertThat(MountInfoParser.inspect(lines, "/media/M/:00/song.mkv").status())
                .isEqualTo(MountInfoParser.Status.READ_ONLY);
        assertThat(MountInfoParser.inspect(lines, "/media/N/01x/song.mkv").status())
                .isEqualTo(MountInfoParser.Status.READ_ONLY);
        assertThat(MountInfoParser.inspect(lines, "/media/P/!00/song.mkv").status())
                .isEqualTo(MountInfoParser.Status.READ_ONLY);
    }
}
