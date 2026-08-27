package com.homektv.diagnostics;

import org.junit.jupiter.api.Test;

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
}
