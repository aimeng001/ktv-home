package com.homektv.library;

import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ArtistProfileServiceTest {

    @Test
    void backfillQueryIgnoresArtistsBelongingOnlyToInvalidSongs() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.query(anyString(), org.mockito.ArgumentMatchers.<RowMapper<String>>any(),
                org.mockito.ArgumentMatchers.any(Object[].class)))
                .thenReturn(List.of("周杰伦"), List.of());

        new ArtistProfileService(jdbc).backfillFromSongs();

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc, org.mockito.Mockito.atLeastOnce()).query(sql.capture(),
                org.mockito.ArgumentMatchers.<RowMapper<String>>any(),
                org.mockito.ArgumentMatchers.any(Object[].class));
        assertThat(sql.getValue())
                .contains("JOIN songs s ON s.id = sa.song_id")
                .contains("WHERE s.status = 'ok'")
                .contains("artist_profiles")
                .contains("artist_name >")
                .contains("LIMIT");
    }

    @Test
    void backfillReadsNamesInBoundedKeysetPages() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        AtomicInteger calls = new AtomicInteger();
        List<String> firstPage = IntStream.rangeClosed(1, 500)
                .mapToObj(index -> "歌手" + index).toList();
        when(jdbc.query(anyString(), org.mockito.ArgumentMatchers.<RowMapper<String>>any(),
                org.mockito.ArgumentMatchers.any(Object[].class)))
                .thenAnswer(invocation -> switch (calls.getAndIncrement()) {
                    case 0 -> firstPage;
                    case 1 -> List.of("歌手501");
                    default -> List.of();
                });

        int count = new ArtistProfileService(jdbc).backfillFromSongs();

        assertThat(count).isEqualTo(501);
        assertThat(calls).hasValue(2);
    }

    @Test
    void avatarProfileUpdatesRequireTheCurrentJobClaim() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.update(anyString(), org.mockito.ArgumentMatchers.any(Object[].class))).thenReturn(0);
        ArtistProfileService service = new ArtistProfileService(jdbc);

        assertThat(service.markAvatarReadyIfClaimed("zhoujielun", "QQ", "1",
                "artist-covers/avatar.jpg", 7L, "lease-token")).isFalse();
        assertThat(service.markAvatarStatusIfClaimed("zhoujielun", "REVIEW", "not found",
                7L, "lease-token")).isFalse();

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc, org.mockito.Mockito.times(2)).update(sql.capture(),
                org.mockito.ArgumentMatchers.any(Object[].class));
        assertThat(sql.getAllValues()).allSatisfy(statement -> assertThat(statement)
                .contains("status='PROCESSING'")
                .contains("claim_token=?::uuid"));
    }

    @Test
    void findPropagatesDatabaseFailureSoWorkersCanRetryIt() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.query(anyString(),
                org.mockito.ArgumentMatchers.<RowMapper<ArtistProfileService.Profile>>any(),
                org.mockito.ArgumentMatchers.eq("zhoujielun")))
                .thenThrow(new DataAccessResourceFailureException("database unavailable"));

        assertThatThrownBy(() -> new ArtistProfileService(jdbc).find("zhoujielun"))
                .isInstanceOf(DataAccessResourceFailureException.class);
    }
}
