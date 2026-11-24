package com.homektv.web;

import com.homektv.config.AppProperties;
import com.homektv.domain.SongFile;
import com.homektv.library.SongAvailabilityPolicy;
import com.homektv.repo.SongFileRepository;
import com.homektv.repo.SongRepository;
import com.homektv.repo.SongSearchRepository;
import com.homektv.resource.ResourceBudget;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class PerformanceContractsTest {

    @TempDir Path temp;

    @Test
    void searchSqlEliminatesFullTableLpadScanAndDoubleDeduplication() throws Exception {
        assertThat(SongSearchRepository.SEARCH_SQL)
                .doesNotContain("LPAD(s.id::text");
        assertThat(SongSearchRepository.SHORT_SEARCH_SQL)
                .doesNotContain("LPAD(s.id::text");
        assertThat(SongSearchRepository.SINGLE_INITIAL_SEARCH_SQL)
                .doesNotContain("\n                UNION\n")
                .contains("UNION ALL");

        Path v73 = Path.of("src/main/resources/db/migration/V73__performance_hot_path_indexes.sql");
        String sql = Files.readString(v73);
        assertThat(sql)
                .contains("idx_songs_ok_catalog_number")
                .contains("idx_songs_ok_title_len_play")
                .contains("idx_song_files_active_ready");

        Path songRepoSource = Path.of("src/main/java/com/homektv/repo/SongRepository.java");
        assertThat(Files.readString(songRepoSource))
                .doesNotContain("LPAD(s.id::text");
    }

    @Test
    void staticRangeStreamCachesResolvedFileMetadataAndEmitsEtagAndLastModified() throws Exception {
        Path media = temp.resolve("demo.mp4");
        Files.write(media, new byte[4096]);

        AppProperties props = new AppProperties();
        props.setKtvLibraryPath(temp.toString());

        SongFile sf = new SongFile();
        sf.setId(77L);
        sf.setFilePath(media.toString());
        sf.setFormat("mp4");
        sf.setMediaType("KTV_VIDEO");
        sf.setValid(true);
        sf.setProbePending(false);

        SongFileRepository repo = mock(SongFileRepository.class);
        when(repo.findById(77L)).thenReturn(Optional.of(sf));

        StreamController controller = new StreamController(
                repo, props, new SongAvailabilityPolicy(repo), "ffmpeg",
                ProcessBuilder::start, new ResourceBudget(2, 1, 2, 1, 8, () -> false));

        ResponseEntity<StreamingResponseBody> first = controller.stream(77L, "bytes=0-1023");
        ResponseEntity<StreamingResponseBody> second = controller.stream(77L, "bytes=1024-2047");

        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.PARTIAL_CONTENT);
        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.PARTIAL_CONTENT);
        assertThat(first.getHeaders().getETag()).isNotBlank().startsWith("W/\"77-4096-");
        assertThat(first.getHeaders().getLastModified()).isGreaterThan(0L);
        verify(repo, times(1)).findById(77L);

        // Touching the file size invalidates the cached metadata and forces a fresh lookup.
        Files.write(media, new byte[8192]);
        ResponseEntity<StreamingResponseBody> third = controller.stream(77L, "bytes=0-511");
        assertThat(third.getStatusCode()).isEqualTo(HttpStatus.PARTIAL_CONTENT);
        assertThat(third.getHeaders().getFirst(HttpHeaders.CONTENT_RANGE)).isEqualTo("bytes 0-511/8192");
        verify(repo, times(2)).findById(77L);
    }
}
