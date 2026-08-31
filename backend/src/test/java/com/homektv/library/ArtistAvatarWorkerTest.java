package com.homektv.library;

import com.homektv.musicsource.ExternalCoverService;
import com.homektv.musicsource.ExternalArtist;
import com.homektv.musicsource.ArtistMetadataProvider;
import com.homektv.musicsource.MusicProvider;
import com.homektv.musicsource.MusicSourceConfig;
import com.homektv.musicsource.MusicSourceConfigService;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.dao.DataAccessResourceFailureException;

import java.sql.ResultSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ArtistAvatarWorkerTest {

    @Test
    void configurationRetryCanDiscoverAllUnresolvedProfilesWithinTheSafetyCap() {
        ArtistProfileService profiles = mock(ArtistProfileService.class);
        when(profiles.unresolvedAvatarKeys(10_000)).thenReturn(List.of("zhoujielun"));
        ArtistAvatarWorker worker = new ArtistAvatarWorker(
                mock(JdbcTemplate.class), profiles, mock(MusicSourceConfigService.class),
                mock(ExternalCoverService.class), mock(AssetWriter.class), List.of(), Runnable::run);

        assertThat(worker.unresolvedKeys()).containsExactly("zhoujielun");
        verify(profiles).unresolvedAvatarKeys(10_000);
    }

    @Test
    void marksProfileSkippedWhenSelectedProviderHasNoArtistAvatarAdapter() throws Exception {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        ArtistProfileService profiles = mock(ArtistProfileService.class);
        MusicSourceConfigService config = mock(MusicSourceConfigService.class);
        ExternalCoverService covers = mock(ExternalCoverService.class);
        ResultSet row = mock(ResultSet.class);
        when(row.getLong("id")).thenReturn(1L);
        when(row.getString("artist_key")).thenReturn("zhoujielun");
        when(row.getString("provider")).thenReturn("KUGOU");
        when(row.getString("claim_token")).thenReturn("lease-token");
        when(profiles.find("zhoujielun")).thenReturn(Optional.of(new ArtistProfileService.Profile(
                "zhoujielun", "周杰伦", "PERSON", null, null, null, "PENDING")));
        when(config.getConfig()).thenReturn(new MusicSourceConfig(
                true, Set.of(MusicProvider.KUGOU), 20, 5, 6, 1, 1500, 0.95));
        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(1);
        AtomicInteger claimCount = new AtomicInteger();
        when(jdbc.query(anyString(), org.mockito.ArgumentMatchers.<RowMapper<Object>>any(), any(Object[].class)))
                .thenAnswer(invocation -> {
                    RowMapper<Object> mapper = invocation.getArgument(1);
                    return claimCount.getAndIncrement() == 0
                            ? List.of(mapper.mapRow(row, 0)) : List.of();
                });

        ArtistAvatarWorker worker = new ArtistAvatarWorker(
                jdbc, profiles, config, covers, mock(AssetWriter.class), List.of(), Runnable::run);

        worker.process();

        verify(profiles).markAvatarStatusIfClaimed(eq("zhoujielun"), eq("SKIPPED"),
                org.mockito.ArgumentMatchers.contains("暂不支持"), eq(1L), eq("lease-token"));
        assertThat(claimCount).hasValue(2);
    }

    @Test
    void reclaimsExpiredProcessingJobAndFinishesOnlyWithItsLeaseToken() throws Exception {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        ArtistProfileService profiles = mock(ArtistProfileService.class);
        ExternalCoverService covers = mock(ExternalCoverService.class);
        AssetWriter assets = mock(AssetWriter.class);
        ResultSet row = mock(ResultSet.class);
        when(row.getLong("id")).thenReturn(7L);
        when(row.getString("artist_key")).thenReturn("zhoujielun");
        when(row.getString("provider")).thenReturn("QQ");
        when(row.getString("claim_token")).thenReturn("lease-token");
        when(profiles.find("zhoujielun")).thenReturn(Optional.of(new ArtistProfileService.Profile(
                "zhoujielun", "周杰伦", "PERSON", "artist-covers/avatar.jpg", "QQ", "1", "READY")));
        when(assets.isReadableCache("artist-covers/avatar.jpg")).thenReturn(true);
        AtomicInteger claimCount = new AtomicInteger();
        when(jdbc.query(anyString(), org.mockito.ArgumentMatchers.<RowMapper<Object>>any(), any(Object[].class)))
                .thenAnswer(invocation -> {
                    RowMapper<Object> mapper = invocation.getArgument(1);
                    return claimCount.getAndIncrement() == 0
                            ? List.of(mapper.mapRow(row, 0)) : List.of();
                });
        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(1);

        ArtistAvatarWorker worker = new ArtistAvatarWorker(
                jdbc, profiles, mock(MusicSourceConfigService.class), covers, assets, List.of(), Runnable::run);

        worker.process();

        org.mockito.ArgumentCaptor<String> sql = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(jdbc, org.mockito.Mockito.atLeastOnce()).update(sql.capture(), any(Object[].class));
        assertThat(sql.getAllValues()).anyMatch(value -> value.contains("claim_token=?"));
        verify(assets).isReadableCache("artist-covers/avatar.jpg");
        assertThat(claimCount).hasValue(2);
    }

    @Test
    void missingCachedAvatarIsRequeuedInsteadOfBeingMarkedAsComplete() throws Exception {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        ArtistProfileService profiles = mock(ArtistProfileService.class);
        MusicSourceConfigService config = mock(MusicSourceConfigService.class);
        AssetWriter assets = mock(AssetWriter.class);
        ResultSet row = claimRow("zhoujielun", "QQ", "lease-token");
        when(profiles.find("zhoujielun")).thenReturn(Optional.of(new ArtistProfileService.Profile(
                "zhoujielun", "周杰伦", "PERSON", "artist-covers/missing.jpg", "QQ", "1", "READY")));
        when(assets.isReadableCache("artist-covers/missing.jpg")).thenReturn(false);
        when(config.getConfig()).thenReturn(MusicSourceConfig.defaults());
        stubOneClaimThenEmpty(jdbc, row);
        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(1);

        new ArtistAvatarWorker(jdbc, profiles, config, mock(ExternalCoverService.class), assets,
                List.of(), Runnable::run).process();

        verify(profiles).markAvatarStatusIfClaimed(eq("zhoujielun"), eq("RETRY"),
                org.mockito.ArgumentMatchers.contains("缓存头像不存在"), eq(1L), eq("lease-token"));
    }

    @Test
    void staleWorkerResultUsesAUniqueCacheKeyAndIsDiscardedWhenClaimIsLost() throws Exception {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        ArtistProfileService profiles = mock(ArtistProfileService.class);
        MusicSourceConfigService config = mock(MusicSourceConfigService.class);
        ExternalCoverService covers = mock(ExternalCoverService.class);
        AssetWriter assets = mock(AssetWriter.class);
        ArtistMetadataProvider provider = mock(ArtistMetadataProvider.class);
        ResultSet row = claimRow("zhoujielun", "QQ", "lease-token");
        when(profiles.find("zhoujielun")).thenReturn(Optional.of(new ArtistProfileService.Profile(
                "zhoujielun", "周杰伦", "PERSON", null, null, null, "PENDING")));
        when(config.getConfig()).thenReturn(new MusicSourceConfig(
                true, Set.of(MusicProvider.QQ), 20, 5, 6, 1, 1500, 0.95));
        when(provider.provider()).thenReturn(MusicProvider.QQ);
        when(provider.search(eq("周杰伦"), eq(5), any())).thenReturn(List.of(
                new ExternalArtist(MusicProvider.QQ, "1", "周杰伦", List.of(), "https://y.gtimg.cn/1.jpg")));
        when(covers.downloadArtistAvatar(eq(MusicProvider.QQ), eq("https://y.gtimg.cn/1.jpg"),
                eq("zhoujielun:lease-token"), any())).thenReturn("artist-covers/stale.jpg");
        when(profiles.markAvatarReadyIfClaimed("zhoujielun", "QQ", "1", "artist-covers/stale.jpg",
                1L, "lease-token")).thenReturn(false);
        stubOneClaimThenEmpty(jdbc, row);
        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(1);

        new ArtistAvatarWorker(jdbc, profiles, config, covers, assets, List.of(provider), Runnable::run).process();

        verify(assets).deleteReadableCache("artist-covers/stale.jpg");
        verify(profiles).markAvatarReadyIfClaimed("zhoujielun", "QQ", "1", "artist-covers/stale.jpg",
                1L, "lease-token");
    }

    @Test
    void oneWorkerRunClaimsAtMostOneBoundedBatch() throws Exception {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        ArtistProfileService profiles = mock(ArtistProfileService.class);
        ResultSet row = claimRow("zhoujielun", "QQ", "lease-token");
        AtomicInteger claimCount = new AtomicInteger();
        when(profiles.find("zhoujielun")).thenReturn(Optional.empty());
        when(jdbc.query(anyString(), org.mockito.ArgumentMatchers.<RowMapper<Object>>any(), any(Object[].class)))
                .thenAnswer(invocation -> {
                    RowMapper<Object> mapper = invocation.getArgument(1);
                    return claimCount.incrementAndGet() <= 101 ? List.of(mapper.mapRow(row, 0)) : List.of();
                });
        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(1);

        new ArtistAvatarWorker(jdbc, profiles, mock(MusicSourceConfigService.class),
                mock(ExternalCoverService.class), mock(AssetWriter.class), List.of(), Runnable::run).process();

        assertThat(claimCount).hasValue(100);
    }

    @Test
    void profileDatabaseFailureIsDeferredInsteadOfBeingConvertedToMissingProfile() throws Exception {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        ArtistProfileService profiles = mock(ArtistProfileService.class);
        ResultSet row = claimRow("zhoujielun", "QQ", "lease-token");
        when(profiles.find("zhoujielun"))
                .thenThrow(new DataAccessResourceFailureException("database unavailable"));
        stubOneClaimThenEmpty(jdbc, row);
        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(1);

        ArtistAvatarWorker worker = new ArtistAvatarWorker(
                jdbc, profiles, mock(MusicSourceConfigService.class), mock(ExternalCoverService.class),
                mock(AssetWriter.class), List.of(), Runnable::run);

        assertThatCode(worker::process).doesNotThrowAnyException();
        org.mockito.ArgumentCaptor<String> sql = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(jdbc, org.mockito.Mockito.atLeastOnce()).update(sql.capture(), any(Object[].class));
        assertThat(sql.getAllValues()).anyMatch(value -> value.contains("status='PENDING'"));
    }

    private static ResultSet claimRow(String artistKey, String provider, String token) throws Exception {
        ResultSet row = mock(ResultSet.class);
        when(row.getLong("id")).thenReturn(1L);
        when(row.getString("artist_key")).thenReturn(artistKey);
        when(row.getString("provider")).thenReturn(provider);
        when(row.getString("claim_token")).thenReturn(token);
        return row;
    }

    private static void stubOneClaimThenEmpty(JdbcTemplate jdbc, ResultSet row) {
        AtomicInteger claimCount = new AtomicInteger();
        when(jdbc.query(anyString(), org.mockito.ArgumentMatchers.<RowMapper<Object>>any(), any(Object[].class)))
                .thenAnswer(invocation -> {
                    RowMapper<Object> mapper = invocation.getArgument(1);
                    return claimCount.getAndIncrement() == 0
                            ? List.of(mapper.mapRow(row, 0)) : List.of();
                });
    }
}
