package com.homektv.web.dto;

import com.homektv.domain.SongFile;
import com.homektv.library.MediaClassifier;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 歌曲详情必须逐文件暴露「是否已完成探测」，否则客户端只能按 priority 盲选，
 * 可能选中服务端已知不能播放的文件行。
 *
 * The song detail must tell clients per file whether the media is ready, so a
 * higher-priority row that has not finished probing can never be selected blindly.
 */
class SongDetailReadyFlagTest {

    @Test
    void probedFileIsReportedReady() {
        SongFile file = file(11L);
        file.setMediaType("AUDIO");
        file.setProbePending(false);
        file.setValid(true);

        assertThat(SongDetailDto.FileSourceDto.from(file).ready()).isTrue();
    }

    @Test
    void fileStillWaitingForProbeIsReportedNotReady() {
        SongFile file = file(12L);
        file.setMediaType(MediaClassifier.PENDING_PROBE);
        file.setProbePending(true);
        file.setValid(true);

        assertThat(SongDetailDto.FileSourceDto.from(file).ready()).isFalse();
    }

    @Test
    void fileWithoutAProbedMediaTypeIsReportedNotReady() {
        SongFile file = file(13L);
        file.setMediaType(null);
        file.setProbePending(false);
        file.setValid(true);

        assertThat(SongDetailDto.FileSourceDto.from(file).ready()).isFalse();
    }

    @Test
    void invalidFileIsReportedNotReady() {
        SongFile file = file(14L);
        file.setMediaType("AUDIO");
        file.setProbePending(false);
        file.setValid(false);

        assertThat(SongDetailDto.FileSourceDto.from(file).ready()).isFalse();
    }

    private static SongFile file(long id) {
        SongFile file = new SongFile();
        file.setId(id);
        file.setSongId(7L);
        file.setFilePath("/music/song-" + id + ".mkv");
        file.setFormat("matroska");
        file.setPriority(100);
        return file;
    }
}
