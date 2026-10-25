package com.homektv.playback;

import com.homektv.domain.SongFile;
import com.homektv.repo.SongFileRepository;
import com.homektv.web.ApiException;
import com.homektv.web.dto.PlaybackDescriptor;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class PlaybackResolutionServiceTest {
    @Test
    void ordinaryAndLegacyFilesResolveToTheirNativeStreamByDefault() {
        SongFileRepository files = mock(SongFileRepository.class);
        when(files.findById(8L)).thenReturn(Optional.of(source(8L, "mp4", "MV")));
        PlaybackResolutionService service = new PlaybackResolutionService(files);

        PlaybackDescriptor descriptor = service.resolve(8L, false);

        assertThat(descriptor.kind()).isEqualTo("NATIVE");
        assertThat(descriptor.status()).isEqualTo("READY");
        assertThat(descriptor.streamUrl()).isEqualTo("/api/stream/8");
        verify(files).findById(8L);
    }

    @Test
    void forcedVideoFallbackResolvesToLivePipeWithoutVariantStorage() {
        SongFileRepository files = mock(SongFileRepository.class);
        when(files.findById(9L)).thenReturn(Optional.of(source(9L, "rmvb", "MV")));
        PlaybackResolutionService service = new PlaybackResolutionService(files);

        PlaybackDescriptor descriptor = service.resolve(9L, true);

        assertThat(descriptor.kind()).isEqualTo("LIVE_TRANSCODE");
        assertThat(descriptor.status()).isEqualTo("READY");
        assertThat(descriptor.sourceFileId()).isEqualTo(9L);
        assertThat(descriptor.variantId()).isNull();
        assertThat(descriptor.streamUrl()).isEqualTo("/api/stream/9?transcode=true");
    }

    @Test
    void forcedFallbackAlsoAcceptsKtvVideoMediaType() {
        SongFileRepository files = mock(SongFileRepository.class);
        when(files.findById(14L)).thenReturn(Optional.of(source(14L, "mkv", "KTV_VIDEO")));

        PlaybackDescriptor descriptor = new PlaybackResolutionService(files).resolve(14L, true);

        assertThat(descriptor.kind()).isEqualTo("LIVE_TRANSCODE");
        assertThat(descriptor.sourceFileId()).isEqualTo(14L);
        assertThat(descriptor.variantId()).isNull();
        assertThat(descriptor.streamUrl()).isEqualTo("/api/stream/14?transcode=true");
    }

    @Test
    void snapshotCanResolveAnAlreadyLoadedSourceWithoutAnotherRepositoryRead() {
        SongFileRepository files = mock(SongFileRepository.class);
        PlaybackResolutionService service = new PlaybackResolutionService(files);
        SongFile source = source(10L, "mp4", "MV");

        PlaybackDescriptor descriptor = service.resolveReady(source, false);

        assertThat(descriptor.kind()).isEqualTo("NATIVE");
        assertThat(descriptor.streamUrl()).isEqualTo("/api/stream/10");
        verifyNoInteractions(files);
    }

    @Test
    void forcedFallbackRejectsAudioAndUnreadySources() {
        SongFileRepository files = mock(SongFileRepository.class);
        when(files.findById(11L)).thenReturn(Optional.of(source(11L, "mp3", "AUDIO")));
        when(files.findById(12L)).thenReturn(Optional.of(source(12L, "mp4", "MV", true)));
        PlaybackResolutionService service = new PlaybackResolutionService(files);

        assertThatThrownBy(() -> service.resolve(11L, true))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("仅视频媒体支持实时转码回退");
        assertThatThrownBy(() -> service.resolve(12L, false))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("媒体探测尚未完成");
    }

    @Test
    void missingSourceRetainsTheExistingApiError() {
        SongFileRepository files = mock(SongFileRepository.class);
        when(files.findById(13L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> new PlaybackResolutionService(files).resolve(13L, false))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("歌曲文件不存在");
    }

    private static SongFile source(Long id, String format, String mediaType) {
        return source(id, format, mediaType, false);
    }

    private static SongFile source(Long id, String format, String mediaType, boolean pending) {
        SongFile source = new SongFile();
        source.setId(id);
        source.setFilePath("/source-music/song." + format);
        source.setFormat(format);
        source.setMediaType(mediaType);
        source.setProbePending(pending);
        source.setValid(true);
        source.setAudioTracks(1);
        source.setFileRole("EXTERNAL_READ_ONLY");
        return source;
    }
}
