package com.homektv.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.homektv.common.ArtistCreditParser;
import com.homektv.common.LibraryMode;
import com.homektv.config.AppProperties;
import com.homektv.domain.SongFile;
import com.homektv.library.AssetWriter;
import com.homektv.library.FilenameParser;
import com.homektv.library.PinyinUtil;
import com.homektv.library.SongAvailabilityPolicy;
import com.homektv.repo.SongFileRepository;
import com.homektv.resource.ResourceBudget;
import com.homektv.ws.WsBroadcaster;
import com.homektv.ws.WsEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class PerformanceRound2ContractsTest {

    @TempDir
    Path tempDir;

    @Test
    void v74DropsDeadGlobalTrgmIndexAndOptimizesTriggersWithoutLowerLikeBypass() throws Exception {
        String v74 = Files.readString(Path.of("src/main/resources/db/migration/V74__search_terms_bloat_and_trigger_optimization.sql"));
        assertThat(v74)
                .contains("DROP INDEX IF EXISTS idx_song_search_terms_lower_trgm")
                .contains("trg_song_artists_search_terms_insert")
                .contains("REFERENCING NEW TABLE AS new_rows")
                .contains("ktv_rebuild_song_search_terms(p_song_id BIGINT)");

        String browseSql = Files.readString(Path.of("src/main/java/com/homektv/library/CategorySongBrowseRepository.java"));
        String songRepo = Files.readString(Path.of("src/main/java/com/homektv/repo/SongRepository.java"));
        assertThat(browseSql).contains("s.title ILIKE '%' || ? || '%'")
                .doesNotContain("LOWER(s.title) LIKE '%'")
                .doesNotContain("LOWER(s.artist) LIKE '%'");
        assertThat(songRepo).contains("s.title ILIKE '%' || :keyword || '%'")
                .doesNotContain("LOWER(s.title) LIKE '%' || LOWER(:keyword)")
                .doesNotContain("LOWER(s.artist) LIKE '%' || LOWER(:keyword)");
    }

    @Test
    void jvmMicroHotpathsProduceExactResultsAcrossRepeatedCalls() {
        assertThat(PinyinUtil.fullPinyin("周杰伦")).isEqualTo("zhoujielun");
        assertThat(PinyinUtil.fullPinyin("周杰伦")).isEqualTo("zhoujielun");
        assertThat(PinyinUtil.initials("G.E.M.邓紫棋")).isEqualTo("gemdzq");
        assertThat(ArtistCreditParser.key("周杰伦  J.  Chou ")).isEqualTo("周杰伦j.chou");
        assertThat(ArtistCreditParser.parse("周杰伦 & 费玉清")).containsExactly("周杰伦", "费玉清");
        assertThat(FilenameParser.parse("12345-周杰伦-晴天-国语-流行.mp4").title()).isEqualTo("晴天");
    }

    @Test
    void assetWriterBucketsNewAssetsAndReadsLegacyFlatPaths() throws Exception {
        Path dataRoot = Files.createDirectory(tempDir.resolve("data"));
        AppProperties props = new AppProperties();
        props.setDataPath(dataRoot.toString());
        props.setKtvLibraryPath(tempDir.resolve("music").toString());
        props.setSourceLibraryPath(tempDir.resolve("source").toString());
        props.setLibraryMode(LibraryMode.MANAGED);

        AssetWriter writer = new AssetWriter(props);
        String cover = writer.writeCover("fp-abcdef", new byte[]{1, 2, 3}, "jpg");
        String lyric = writer.writeLyric("fp-abcdef", "[00:01.00]test");
        assertThat(cover).isEqualTo("covers/fp/fp-abcdef.jpg");
        assertThat(lyric).isEqualTo("lyrics/fp/fp-abcdef.lrc");
        assertThat(writer.isReadableCache(cover)).isTrue();
        assertThat(writer.isReadableCache(lyric)).isTrue();

        // Legacy flat paths remain readable & deletable with zero migration needed.
        Path legacyCover = dataRoot.resolve("covers/legacy.jpg");
        Files.write(legacyCover, new byte[]{9, 8});
        assertThat(writer.isReadableCache("covers/legacy.jpg")).isTrue();
        writer.deleteReadableCache("covers/legacy.jpg");
        assertThat(legacyCover).doesNotExist();
    }

    @Test
    void wsBroadcasterExceptSkipsOriginatingTvSession() throws Exception {
        WsBroadcaster broadcaster = new WsBroadcaster(new ObjectMapper());
        WebSocketSession tv = mock(WebSocketSession.class);
        when(tv.getId()).thenReturn("tv-1");
        when(tv.getAttributes()).thenReturn(Map.of("client_type", "tv"));
        when(tv.isOpen()).thenReturn(true);

        WebSocketSession h5 = mock(WebSocketSession.class);
        when(h5.getId()).thenReturn("h5-1");
        when(h5.getAttributes()).thenReturn(Map.of("client_type", "h5"));
        when(h5.isOpen()).thenReturn(true);

        CountDownLatch h5Received = new CountDownLatch(1);
        doAnswer(inv -> { h5Received.countDown(); return null; }).when(h5).sendMessage(any(TextMessage.class));

        broadcaster.register(tv);
        broadcaster.register(h5);
        broadcaster.broadcastPlaybackExcept("tv-1", WsEvent.of(WsEvent.PROGRESS, Map.of("position_ms", 1234L)));

        assertThat(h5Received.await(2, TimeUnit.SECONDS)).isTrue();
        verify(tv, never()).sendMessage(any(TextMessage.class));
    }

    @Test
    void streamControllerCleanupHookRemovesPreBodyEntryBeforeAsyncWriteStarts() throws Exception {
        Path media = tempDir.resolve("video.rmvb");
        Files.write(media, new byte[128]);
        AppProperties props = new AppProperties();
        props.setKtvLibraryPath(tempDir.toString());

        SongFile sf = new SongFile();
        sf.setId(91L);
        sf.setFilePath(media.toString());
        sf.setFormat("rmvb");
        sf.setMediaType("KTV_VIDEO");
        sf.setValid(true);
        sf.setProbePending(false);

        SongFileRepository repo = mock(SongFileRepository.class);
        when(repo.findById(91L)).thenReturn(Optional.of(sf));

        Process fakeProcess = mock(Process.class);
        when(fakeProcess.getInputStream()).thenReturn(java.io.InputStream.nullInputStream());
        when(fakeProcess.getErrorStream()).thenReturn(java.io.InputStream.nullInputStream());

        StreamController controller = new StreamController(
                repo, props, new SongAvailabilityPolicy(repo), "ffmpeg",
                builder -> fakeProcess, new ResourceBudget(2, 1, 2, 1, 8, () -> false));

        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/stream/91");
        req.addHeader("X-Playback-Session", "pre-body-leak-test");
        controller.stream(91L, null, null, true, null, false, false, req);

        Object cleanupAttr = req.getAttribute(TranscodeAsyncLifecycleInterceptor.CLEANUP_ATTRIBUTE);
        assertThat(cleanupAttr).isInstanceOf(Runnable.class);

        var field = StreamController.class.getDeclaredField("activeTranscodesByClient");
        field.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<String, ?> activeMap = (Map<String, ?>) field.get(controller);
        assertThat(activeMap).containsKey("session:pre-body-leak-test");

        // Invoking the async lifecycle cleanup hook without ever running StreamingResponseBody removes the entry.
        ((Runnable) cleanupAttr).run();
        assertThat(activeMap).doesNotContainKey("session:pre-body-leak-test");
    }
}
