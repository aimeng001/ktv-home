package com.homektv.playback;

import com.homektv.domain.SongFile;
import com.homektv.library.SongAvailabilityPolicy;
import com.homektv.repo.SongFileRepository;
import com.homektv.web.ApiException;
import com.homektv.web.dto.PlaybackDescriptor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Selects the current native or live-pipe source without depending on legacy cache storage. */
@Service
public class PlaybackResolutionService {
    private final SongFileRepository files;

    public PlaybackResolutionService(SongFileRepository files) {
        this.files = files;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public PlaybackDescriptor resolve(Long sourceFileId, boolean forceTranscode) {
        SongFile source = files.findById(sourceFileId).orElseThrow(
                () -> new ApiException("FILE_NOT_FOUND", "歌曲文件不存在"));
        return resolveReady(source, forceTranscode);
    }

    /** Reuses a source already loaded by a snapshot, avoiding a second database lookup. */
    public PlaybackDescriptor resolveReady(SongFile source, boolean forceTranscode) {
        if (!SongAvailabilityPolicy.isReadyMediaFile(source)) {
            throw new ApiException(SongAvailabilityPolicy.SONG_NOT_READY, "媒体探测尚未完成");
        }
        if (forceTranscode && !PlaybackTransportPolicy.isVideo(source)) {
            throw new ApiException("LIVE_TRANSCODE_VIDEO_REQUIRED", "仅视频媒体支持实时转码回退");
        }
        return PlaybackTransportPolicy.requiresLiveTranscode(source, forceTranscode)
                ? PlaybackDescriptor.liveTranscode(source)
                : PlaybackDescriptor.nativeSource(source);
    }
}
