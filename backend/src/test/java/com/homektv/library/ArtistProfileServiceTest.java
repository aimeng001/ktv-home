package com.homektv.library;

import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.sql.ResultSet;
import java.util.List;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
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
    void backfillStopsForNullRowsAndNonAdvancingLastName() {
        JdbcTemplate nullRowsJdbc = mock(JdbcTemplate.class);
        when(nullRowsJdbc.query(anyString(), org.mockito.ArgumentMatchers.<RowMapper<String>>any(),
                org.mockito.ArgumentMatchers.any(Object[].class))).thenReturn(null);
        assertThat(new ArtistProfileService(nullRowsJdbc).backfillFromSongs()).isZero();

        JdbcTemplate nullNameJdbc = mock(JdbcTemplate.class);
        when(nullNameJdbc.query(anyString(), org.mockito.ArgumentMatchers.<RowMapper<String>>any(),
                org.mockito.ArgumentMatchers.any(Object[].class))).thenReturn(Collections.singletonList(null));
        assertThat(new ArtistProfileService(nullNameJdbc).backfillFromSongs()).isEqualTo(1);
        verify(nullNameJdbc).query(anyString(), org.mockito.ArgumentMatchers.<RowMapper<String>>any(),
                org.mockito.ArgumentMatchers.any(Object[].class));

        JdbcTemplate repeatedNameJdbc = mock(JdbcTemplate.class);
        when(repeatedNameJdbc.query(anyString(), org.mockito.ArgumentMatchers.<RowMapper<String>>any(),
                org.mockito.ArgumentMatchers.any(Object[].class))).thenReturn(List.of(""));
        assertThat(new ArtistProfileService(repeatedNameJdbc).backfillFromSongs()).isEqualTo(1);
        verify(repeatedNameJdbc).query(anyString(), org.mockito.ArgumentMatchers.<RowMapper<String>>any(),
                org.mockito.ArgumentMatchers.any(Object[].class));
    }

    @Test
    void backfillReturnsZeroWhenTheDatabaseIsUnavailable() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.query(anyString(), org.mockito.ArgumentMatchers.<RowMapper<String>>any(),
                org.mockito.ArgumentMatchers.any(Object[].class)))
                .thenThrow(new DataAccessResourceFailureException("database unavailable"));

        assertThat(new ArtistProfileService(jdbc).backfillFromSongs()).isZero();
    }

    @Test
    void ensureProfilesSkipsNullAndEmptyInputs() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        ArtistProfileService service = new ArtistProfileService(jdbc);

        service.ensureProfiles(null);
        service.ensureProfiles(List.of("", " \t "));

        verifyNoInteractions(jdbc);
    }

    @Test
    void ensureProfilesKeepsSongIndexUsableWhenProfileTableIsUnavailable() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        org.mockito.Mockito.doThrow(new DataAccessResourceFailureException("table not migrated"))
                .when(jdbc).batchUpdate(anyString(), anyCollection(), anyInt(), org.mockito.ArgumentMatchers.any());

        assertThatCode(() -> new ArtistProfileService(jdbc).ensureProfiles(List.of("周杰伦")))
                .doesNotThrowAnyException();
    }

    @Test
    void findSkipsNullAndBlankKeysAndMapsTheFirstMatchingProfile() throws Exception {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        ArtistProfileService service = new ArtistProfileService(jdbc);
        ArtistProfileService.Profile profile = new ArtistProfileService.Profile(
                "zhoujielun", "周杰伦", "PERSON", null, null, null, "PENDING");
        ResultSet row = profileRow();
        when(jdbc.query(anyString(), org.mockito.ArgumentMatchers.<RowMapper<ArtistProfileService.Profile>>any(),
                org.mockito.ArgumentMatchers.eq("zhoujielun"))).thenAnswer(invocation -> List.of(
                invocation.<RowMapper<ArtistProfileService.Profile>>getArgument(1).mapRow(row, 0)));

        assertThat(service.find(null)).isEmpty();
        assertThat(service.find(" \t ")).isEmpty();
        assertThat(service.find("zhoujielun")).contains(profile);
        verify(jdbc).query(anyString(), org.mockito.ArgumentMatchers.<RowMapper<ArtistProfileService.Profile>>any(),
                org.mockito.ArgumentMatchers.eq("zhoujielun"));
    }

    @Test
    void findReturnsEmptyWhenAValidKeyHasNoProfile() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.query(anyString(), org.mockito.ArgumentMatchers.<RowMapper<ArtistProfileService.Profile>>any(),
                org.mockito.ArgumentMatchers.eq("missing"))).thenReturn(List.of());

        assertThat(new ArtistProfileService(jdbc).find("missing")).isEmpty();
    }

    @Test
    void avatarReadyUpdateReportsWhetherTheProfileWasChanged() {
        JdbcTemplate updatedJdbc = mock(JdbcTemplate.class);
        when(updatedJdbc.update(anyString(), org.mockito.ArgumentMatchers.any(Object[].class))).thenReturn(1);
        JdbcTemplate unchangedJdbc = mock(JdbcTemplate.class);
        when(unchangedJdbc.update(anyString(), org.mockito.ArgumentMatchers.any(Object[].class))).thenReturn(0);

        assertThat(new ArtistProfileService(updatedJdbc)
                .setAvatarReady("zhoujielun", "QQ", "artist-covers/zhou.jpg")).isTrue();
        assertThat(new ArtistProfileService(unchangedJdbc)
                .setAvatarReady("zhoujielun", "QQ", "artist-covers/zhou.jpg")).isFalse();
    }

    @Test
    void claimedAvatarUpdatesReportSuccessForTheCurrentWorker() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.update(anyString(), org.mockito.ArgumentMatchers.any(Object[].class))).thenReturn(1, 1);
        ArtistProfileService service = new ArtistProfileService(jdbc);

        assertThat(service.markAvatarReadyIfClaimed("zhoujielun", "QQ", "1",
                "artist-covers/avatar.jpg", 7L, "lease-token")).isTrue();
        assertThat(service.markAvatarStatusIfClaimed("zhoujielun", "RETRY", "timeout",
                7L, "lease-token")).isTrue();
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
    void pendingAvatarPageSkipsProfilesThatAlreadyHaveAJob() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.query(anyString(), org.mockito.ArgumentMatchers.<RowMapper<String>>any(),
                org.mockito.ArgumentMatchers.any(Object[].class)))
                .thenReturn(List.of());

        new ArtistProfileService(jdbc).pendingAvatarKeys(10_000);

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc).query(sql.capture(), org.mockito.ArgumentMatchers.<RowMapper<String>>any(),
                org.mockito.ArgumentMatchers.any(Object[].class));
        assertThat(sql.getValue())
                .contains("NOT EXISTS")
                .contains("artist_avatar_jobs");
    }

    @Test
    void profileAndAvatarLookupsClampLimitsAndReturnRows() throws Exception {
        ArtistProfileService.Profile profile = new ArtistProfileService.Profile(
                "zhoujielun", "周杰伦", "PERSON", null, null, null, "PENDING");
        JdbcTemplate profileJdbc = mock(JdbcTemplate.class);
        ResultSet profileRow = profileRow();
        when(profileJdbc.query(anyString(),
                org.mockito.ArgumentMatchers.<RowMapper<ArtistProfileService.Profile>>any(),
                org.mockito.ArgumentMatchers.any(Object[].class))).thenAnswer(invocation -> List.of(
                invocation.<RowMapper<ArtistProfileService.Profile>>getArgument(1).mapRow(profileRow, 0)));
        assertThat(new ArtistProfileService(profileJdbc).unresolvedProfiles(0)).containsExactly(profile);
        ArgumentCaptor<Object[]> profileArguments = ArgumentCaptor.forClass(Object[].class);
        verify(profileJdbc).query(anyString(),
                org.mockito.ArgumentMatchers.<RowMapper<ArtistProfileService.Profile>>any(),
                profileArguments.capture());
        assertThat(profileArguments.getValue()).containsExactly(1);

        JdbcTemplate pendingJdbc = mock(JdbcTemplate.class);
        ResultSet pendingRow = mock(ResultSet.class);
        when(pendingRow.getString(1)).thenReturn("zhoujielun");
        when(pendingJdbc.query(anyString(), org.mockito.ArgumentMatchers.<RowMapper<String>>any(),
                org.mockito.ArgumentMatchers.any(Object[].class))).thenAnswer(invocation -> List.of(
                invocation.<RowMapper<String>>getArgument(1).mapRow(pendingRow, 0)));
        assertThat(new ArtistProfileService(pendingJdbc).pendingAvatarKeys(-5)).containsExactly("zhoujielun");
        ArgumentCaptor<Object[]> pendingArguments = ArgumentCaptor.forClass(Object[].class);
        verify(pendingJdbc).query(anyString(), org.mockito.ArgumentMatchers.<RowMapper<String>>any(),
                pendingArguments.capture());
        assertThat(pendingArguments.getValue()).containsExactly(1);

        JdbcTemplate unresolvedJdbc = mock(JdbcTemplate.class);
        ResultSet unresolvedRow = mock(ResultSet.class);
        when(unresolvedRow.getString(1)).thenReturn("zhoujielun");
        when(unresolvedJdbc.query(anyString(), org.mockito.ArgumentMatchers.<RowMapper<String>>any(),
                org.mockito.ArgumentMatchers.any(Object[].class))).thenAnswer(invocation -> List.of(
                invocation.<RowMapper<String>>getArgument(1).mapRow(unresolvedRow, 0)));
        assertThat(new ArtistProfileService(unresolvedJdbc).unresolvedAvatarKeys(50_000))
                .containsExactly("zhoujielun");
        ArgumentCaptor<Object[]> unresolvedArguments = ArgumentCaptor.forClass(Object[].class);
        verify(unresolvedJdbc).query(anyString(), org.mockito.ArgumentMatchers.<RowMapper<String>>any(),
                unresolvedArguments.capture());
        assertThat(unresolvedArguments.getValue()).containsExactly(10_000);
    }

    @Test
    void unresolvedAvatarLookupsFailClosedWhenTheDatabaseIsUnavailable() {
        JdbcTemplate profileJdbc = mock(JdbcTemplate.class);
        when(profileJdbc.query(anyString(),
                org.mockito.ArgumentMatchers.<RowMapper<ArtistProfileService.Profile>>any(),
                org.mockito.ArgumentMatchers.any(Object[].class)))
                .thenThrow(new DataAccessResourceFailureException("database unavailable"));
        assertThat(new ArtistProfileService(profileJdbc).unresolvedProfiles(50)).isEmpty();

        JdbcTemplate pendingJdbc = mock(JdbcTemplate.class);
        when(pendingJdbc.query(anyString(), org.mockito.ArgumentMatchers.<RowMapper<String>>any(),
                org.mockito.ArgumentMatchers.any(Object[].class)))
                .thenThrow(new DataAccessResourceFailureException("database unavailable"));
        assertThat(new ArtistProfileService(pendingJdbc).pendingAvatarKeys(50)).isEmpty();

        JdbcTemplate unresolvedJdbc = mock(JdbcTemplate.class);
        when(unresolvedJdbc.query(anyString(), org.mockito.ArgumentMatchers.<RowMapper<String>>any(),
                org.mockito.ArgumentMatchers.any(Object[].class)))
                .thenThrow(new DataAccessResourceFailureException("database unavailable"));
        assertThat(new ArtistProfileService(unresolvedJdbc).unresolvedAvatarKeys(50)).isEmpty();
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

    @Test
    void manualGenderUpdateRefreshesDirectoryProjection() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        ArtistDirectoryProjectionService projection = mock(ArtistDirectoryProjectionService.class);
        when(jdbc.update(anyString(), org.mockito.ArgumentMatchers.any(Object[].class))).thenReturn(1);

        new ArtistProfileService(jdbc, projection).setGender("周杰伦", "男歌手");

        verify(projection).requestRefreshAfterCommit();
    }

    @Test
    void genderUpdateSkipsInvalidValuesAndDoesNotRefreshAnUnchangedProfile() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        ArtistDirectoryProjectionService projection = mock(ArtistDirectoryProjectionService.class);
        when(jdbc.update(anyString(), org.mockito.ArgumentMatchers.any(Object[].class))).thenReturn(0);
        ArtistProfileService service = new ArtistProfileService(jdbc, projection);

        service.setGender(null, "男歌手");
        service.setGender("周杰伦", null);
        service.setGender("周杰伦", " \t ");
        verifyNoInteractions(jdbc, projection);

        service.setGender("周杰伦", "男歌手");
        verify(projection, org.mockito.Mockito.never()).requestRefreshAfterCommit();
    }

    @Test
    void genderUpdateWithNoProjectionStillPersistsTheValue() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.update(anyString(), org.mockito.ArgumentMatchers.any(Object[].class))).thenReturn(1);

        new ArtistProfileService(jdbc).setGender("周杰伦", "男歌手");

        verify(jdbc).update(anyString(), org.mockito.ArgumentMatchers.any(Object[].class));
    }

    private static ResultSet profileRow() throws Exception {
        ResultSet row = mock(ResultSet.class);
        when(row.getString("artist_key")).thenReturn("zhoujielun");
        when(row.getString("display_name")).thenReturn("周杰伦");
        when(row.getString("artist_kind")).thenReturn("PERSON");
        when(row.getString("avatar_path")).thenReturn(null);
        when(row.getString("avatar_provider")).thenReturn(null);
        when(row.getString("avatar_external_id")).thenReturn(null);
        when(row.getString("avatar_status")).thenReturn("PENDING");
        return row;
    }
}
