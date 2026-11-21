package com.homektv.musicsource;

import java.time.Duration;
import java.util.List;

public interface MusicMetadataProvider {
    MusicProvider provider();
    List<ExternalTrack> search(String keyword, int limit, Duration timeout);
    ExternalTrack detail(String externalId, Duration timeout);

    default ProviderHealth health(Duration timeout) {
        long started = System.nanoTime();
        List<ExternalTrack> tracks = search("周杰伦", 1, timeout);
        long latency = (System.nanoTime() - started) / 1_000_000;
        if (tracks == null || tracks.isEmpty()) {
            return new ProviderHealth(provider(), false, latency, "未返回有效歌曲元数据");
        }
        return new ProviderHealth(provider(), true, latency, null);
    }

    record ProviderHealth(MusicProvider provider, boolean healthy, long latencyMs, String error) {}
}
