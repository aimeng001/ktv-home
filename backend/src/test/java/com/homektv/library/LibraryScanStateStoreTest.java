package com.homektv.library;

import com.homektv.config.AppProperties;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.RowMapper;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class LibraryScanStateStoreTest {

    @TempDir
    Path tempDir;

    @Test
    void needsBootstrapChecksCurrentRootIdentityModePolicyAndState() throws Exception {
        Path root = Files.createDirectory(tempDir.resolve("library"));
        AppProperties props = properties(root);
        String normalizedRoot = root.toAbsolutePath().normalize().toString();
        String identity = LibraryIdentity.resolve(root).persistedValue();

        assertThat(storeWithSnapshot(snapshot(normalizedRoot, identity, "MANAGED",
                LibraryScanStateStore.METADATA_POLICY_VERSION, LibraryScanStateStore.State.COMPLETED))
                .needsBootstrap(props)).isFalse();
        assertThat(storeWithSnapshot(snapshot(normalizedRoot, identity, "MANAGED",
                LibraryScanStateStore.METADATA_POLICY_VERSION - 1, LibraryScanStateStore.State.COMPLETED))
                .needsBootstrap(props)).isTrue();
        assertThat(storeWithSnapshot(snapshot(normalizedRoot, identity, "MANAGED",
                LibraryScanStateStore.METADATA_POLICY_VERSION, LibraryScanStateStore.State.PARTIAL))
                .needsBootstrap(props)).isTrue();

        assertThat(storeWithSnapshot(snapshot(normalizedRoot + "-other", identity, "MANAGED",
                LibraryScanStateStore.METADATA_POLICY_VERSION, LibraryScanStateStore.State.COMPLETED))
                .needsBootstrap(props)).isFalse();
        assertThat(storeWithSnapshot(snapshot(normalizedRoot, identity, "EXTERNAL_READ_ONLY",
                LibraryScanStateStore.METADATA_POLICY_VERSION, LibraryScanStateStore.State.COMPLETED))
                .needsBootstrap(props)).isFalse();
        assertThat(storeWithSnapshot(snapshot(normalizedRoot, tempDir.resolve("other-library") + "|different-volume", "MANAGED",
                LibraryScanStateStore.METADATA_POLICY_VERSION, LibraryScanStateStore.State.COMPLETED))
                .needsBootstrap(props)).isFalse();
        assertThat(storeWithSnapshot(snapshot(normalizedRoot, null, "MANAGED",
                LibraryScanStateStore.METADATA_POLICY_VERSION, LibraryScanStateStore.State.COMPLETED))
                .needsBootstrap(props)).isFalse();
    }

    @Test
    void needsBootstrapWhenNoPriorStateExists() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        doThrow(new EmptyResultDataAccessException(1)).when(jdbc)
                .queryForObject(anyString(), any(RowMapper.class), any(Object[].class));

        assertThat(new LibraryScanStateStore(jdbc).needsBootstrap(properties(tempDir.resolve("library"))))
                .isTrue();
    }

    @Test
    void tryClaimReturnsTheFencedLeaseOrNoClaimWhenAnotherWorkerOwnsIt() throws Exception {
        Path root = Files.createDirectory(tempDir.resolve("claim-root"));
        AppProperties props = properties(root);
        UUID scanId = UUID.randomUUID();
        UUID ownerId = UUID.randomUUID();

        JdbcTemplate claimedJdbc = mock(JdbcTemplate.class);
        when(claimedJdbc.update(anyString(), any(Object[].class))).thenReturn(1);
        doReturn(List.of(new LibraryScanStateStore.Claim(scanId, ownerId, 12L))).when(claimedJdbc)
                .query(anyString(), any(org.springframework.jdbc.core.PreparedStatementSetter.class),
                        any(RowMapper.class));
        assertThat(new LibraryScanStateStore(claimedJdbc).tryClaim(props, "MANUAL"))
                .contains(new LibraryScanStateStore.Claim(scanId, ownerId, 12L));

        JdbcTemplate busyJdbc = mock(JdbcTemplate.class);
        when(busyJdbc.update(anyString(), any(Object[].class))).thenReturn(1);
        doReturn(List.of()).when(busyJdbc)
                .query(anyString(), any(org.springframework.jdbc.core.PreparedStatementSetter.class),
                        any(RowMapper.class));
        assertThat(new LibraryScanStateStore(busyJdbc).tryClaim(props, "MANUAL"))
                .isEmpty();
    }

    @Test
    void findMapsThePersistedScanStateRow() throws Exception {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        ResultSet rs = mock(ResultSet.class);
        UUID scanId = UUID.randomUUID();
        UUID ownerId = UUID.randomUUID();
        OffsetDateTime updatedAt = OffsetDateTime.now();
        when(rs.getString("library_key")).thenReturn("active");
        when(rs.getString("configured_root")).thenReturn("D:/library");
        when(rs.getString("root_identity")).thenReturn("D:/library|volume-id");
        when(rs.getString("library_mode")).thenReturn("MANAGED");
        when(rs.getInt("metadata_policy_version")).thenReturn(1);
        when(rs.getString("state")).thenReturn("RUNNING");
        when(rs.getString("phase")).thenReturn("MEDIA_PROBE");
        when(rs.getObject("scan_id", UUID.class)).thenReturn(scanId);
        when(rs.getObject("owner_id", UUID.class)).thenReturn(ownerId);
        when(rs.getLong("generation")).thenReturn(9L);
        when(rs.getLong("discovered_files")).thenReturn(20L);
        when(rs.getLong("indexed_files")).thenReturn(18L);
        when(rs.getLong("probe_completed_files")).thenReturn(7L);
        when(rs.getLong("probe_pending_files")).thenReturn(2L);
        when(rs.getString("error_code")).thenReturn(null);
        when(rs.getString("error_message")).thenReturn(null);
        when(rs.getObject("updated_at", OffsetDateTime.class)).thenReturn(updatedAt);
        doAnswer(invocation -> {
            RowMapper<?> mapper = invocation.getArgument(1);
            return mapper.mapRow(rs, 0);
        }).when(jdbc).queryForObject(anyString(), any(RowMapper.class), any(Object[].class));

        var found = new LibraryScanStateStore(jdbc).find();

        assertThat(found).hasValueSatisfying(snapshot -> assertThat(snapshot)
                .extracting(LibraryScanStateStore.Snapshot::libraryKey,
                        LibraryScanStateStore.Snapshot::configuredRoot,
                        LibraryScanStateStore.Snapshot::rootIdentity,
                        LibraryScanStateStore.Snapshot::libraryMode,
                        LibraryScanStateStore.Snapshot::state,
                        LibraryScanStateStore.Snapshot::phase,
                        LibraryScanStateStore.Snapshot::scanId,
                        LibraryScanStateStore.Snapshot::ownerId,
                        LibraryScanStateStore.Snapshot::generation,
                        LibraryScanStateStore.Snapshot::discoveredFiles,
                        LibraryScanStateStore.Snapshot::indexedFiles,
                        LibraryScanStateStore.Snapshot::probeCompletedFiles,
                        LibraryScanStateStore.Snapshot::probePendingFiles,
                        LibraryScanStateStore.Snapshot::updatedAt)
                .containsExactly("active", "D:/library", "D:/library|volume-id", "MANAGED",
                        LibraryScanStateStore.State.RUNNING, "MEDIA_PROBE", scanId, ownerId, 9L,
                        20L, 18L, 7L, 2L, updatedAt));
    }

    @Test
    void tryClaimBindsFenceAndLeaseValuesBeforeMappingTheClaim() throws Exception {
        Path root = Files.createDirectory(tempDir.resolve("bound-claim-root"));
        AppProperties props = properties(root);
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(1);
        PreparedStatement statement = mock(PreparedStatement.class);
        ResultSet rs = mock(ResultSet.class);
        UUID scanId = UUID.randomUUID();
        UUID ownerId = UUID.randomUUID();
        when(rs.getObject("scan_id", UUID.class)).thenReturn(scanId);
        when(rs.getObject("owner_id", UUID.class)).thenReturn(ownerId);
        when(rs.getLong("generation")).thenReturn(15L);
        doAnswer(invocation -> {
            org.springframework.jdbc.core.PreparedStatementSetter setter = invocation.getArgument(1);
            setter.setValues(statement);
            RowMapper<?> mapper = invocation.getArgument(2);
            return List.of(mapper.mapRow(rs, 0));
        }).when(jdbc).query(anyString(), any(org.springframework.jdbc.core.PreparedStatementSetter.class),
                any(RowMapper.class));

        assertThat(new LibraryScanStateStore(jdbc).tryClaim(props, "MANUAL"))
                .contains(new LibraryScanStateStore.Claim(scanId, ownerId, 15L));

        verify(statement).setString(4, "MANUAL");
        verify(statement).setString(5, LibraryScanStateStore.ACTIVE_LIBRARY_KEY);
        verify(statement).setString(6, root.toAbsolutePath().normalize().toString());
        verify(statement).setString(7, LibraryMode.MANAGED.name());
        verify(statement).setInt(8, LibraryScanStateStore.METADATA_POLICY_VERSION);
        verify(statement).setString(9, LibraryIdentity.resolve(root).persistedValue());
        verify(statement).setInt(10, 300);
    }

    @Test
    void legacySnapshotConstructorLeavesRootIdentityUnknown() {
        LibraryScanStateStore.Snapshot snapshot = new LibraryScanStateStore.Snapshot(
                "active", "D:/library", "MANAGED", 1, LibraryScanStateStore.State.COMPLETED,
                "COMPLETED", null, null, 4L, 3L, 3L, 2L, 0L, null, null, OffsetDateTime.now());

        assertThat(snapshot.rootIdentity()).isNull();
        assertThat(snapshot.generation()).isEqualTo(4L);
        assertThat(snapshot.state()).isEqualTo(LibraryScanStateStore.State.COMPLETED);
    }

    @Test
    void partialCompletionPersistsFailureDetailsAndDoesNotAdvanceSuccessfulTimestamp() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(1);
        LibraryScanStateStore store = new LibraryScanStateStore(jdbc);
        LibraryScanStateStore.Claim claim = new LibraryScanStateStore.Claim(
                UUID.randomUUID(), UUID.randomUUID(), 8L);
        LibraryScanService.ScanProgress progress = new LibraryScanService.ScanProgress(
                false, 0, 0, null, 0, 0, 0, 0, OffsetDateTime.now(), OffsetDateTime.now(),
                "FAILED", 0, 0, 0, 0, 0.0, null,
                LibraryScanService.ScanState.FAILED, 1, "ROOT_NOT_READABLE", "根目录无读取权限");

        assertThat(store.markCompleted(claim, new LibraryScanService.ScanResult(0, 0, 0, 0, 0), progress))
                .isTrue();

        var sql = org.mockito.ArgumentCaptor.forClass(String.class);
        var args = org.mockito.ArgumentCaptor.forClass(Object[].class);
        verify(jdbc).update(sql.capture(), args.capture());
        assertThat(sql.getValue()).contains("last_successful_scan_at = CASE");
        assertThat(args.getValue()).contains("ROOT_NOT_READABLE", "根目录无读取权限");
    }

    @Test
    void heartbeatReportsWhetherTheLeaseOwnerStillMatches() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(1, 0);
        LibraryScanStateStore store = new LibraryScanStateStore(jdbc);
        LibraryScanStateStore.Claim claim = claim();
        LibraryScanStateStore.ScanSnapshot progress = new LibraryScanStateStore.ScanSnapshot(
                "MEDIA_PROBE", 10, 8, 3, 2);

        assertThat(store.heartbeat(claim, progress)).isTrue();
        assertThat(store.heartbeat(claim, progress)).isFalse();
    }

    @Test
    void successfulCompletionHandlesNullAndExplicitCompletedProgress() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(1);
        LibraryScanStateStore store = new LibraryScanStateStore(jdbc);

        assertThat(store.markCompleted(claim(), null, null)).isTrue();
        LibraryScanService.ScanProgress progress = progress(LibraryScanService.ScanState.COMPLETED, null, null);
        assertThat(store.markCompleted(claim(), null, progress)).isTrue();

        var args = org.mockito.ArgumentCaptor.forClass(Object[].class);
        verify(jdbc, times(2)).update(anyString(), args.capture());
        assertThat(args.getAllValues()).allSatisfy(values -> {
            assertThat(values[0]).isEqualTo("COMPLETED");
            assertThat(values[2]).isEqualTo(0L);
            assertThat(values[7]).isNull();
            assertThat(values[8]).isNull();
        });
    }

    @Test
    void partialCompletionReportsFencingFailureAndDefaultsMissingErrorDetails() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(0);
        LibraryScanStateStore store = new LibraryScanStateStore(jdbc);
        LibraryScanService.ScanProgress progress = progress(LibraryScanService.ScanState.PARTIAL, null, null);

        assertThat(store.markCompleted(claim(), new LibraryScanService.ScanResult(3, 2, 1, 1, 0), progress))
                .isFalse();

        var args = org.mockito.ArgumentCaptor.forClass(Object[].class);
        verify(jdbc).update(anyString(), args.capture());
        assertThat(args.getValue()[7]).isEqualTo("SCAN_INCOMPLETE");
        assertThat(args.getValue()[8]).isEqualTo("扫描未完成");
    }

    @Test
    void failedCompletionUsesSafeFallbacksAndTruncatesUntrustedErrorText() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(1, 0);
        LibraryScanStateStore store = new LibraryScanStateStore(jdbc);

        assertThat(store.markFailed(claim(), null, "  ")).isTrue();
        String oversizedCode = "C".repeat(1001);
        String oversizedMessage = "M".repeat(1001);
        assertThat(store.markFailed(claim(), oversizedCode, oversizedMessage)).isFalse();

        var args = org.mockito.ArgumentCaptor.forClass(Object[].class);
        verify(jdbc, times(2)).update(anyString(), args.capture());
        assertThat(args.getAllValues().getFirst()).contains("SCAN_RUNTIME_ERROR", "扫描失败");
        Object[] truncated = args.getAllValues().get(1);
        assertThat((String) truncated[0]).hasSize(1000).startsWith("C");
        assertThat((String) truncated[1]).hasSize(1000).startsWith("M");
    }

    @Test
    void rebindReturnsWhetherTheStaleGenerationWasReplaced() {
        JdbcTemplate successJdbc = mock(JdbcTemplate.class);
        when(successJdbc.update(anyString(), any(Object[].class))).thenReturn(1);
        Path root = tempDir.resolve("rebind-root");
        assertThat(new LibraryScanStateStore(successJdbc).rebind(properties(root))).isTrue();

        JdbcTemplate staleJdbc = mock(JdbcTemplate.class);
        when(staleJdbc.update(anyString(), any(Object[].class))).thenReturn(1, 0);
        assertThat(new LibraryScanStateStore(staleJdbc).rebind(properties(root))).isFalse();
    }

    private static AppProperties properties(Path root) {
        AppProperties props = new AppProperties();
        props.setKtvLibraryPath(root.toString());
        props.setLibraryMode(LibraryMode.MANAGED);
        return props;
    }

    private static LibraryScanStateStore storeWithSnapshot(LibraryScanStateStore.Snapshot snapshot) {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        doReturn(snapshot).when(jdbc)
                .queryForObject(anyString(), any(RowMapper.class), any(Object[].class));
        return new LibraryScanStateStore(jdbc);
    }

    private static LibraryScanStateStore.Snapshot snapshot(
            String root, String identity, String mode, int policyVersion, LibraryScanStateStore.State state) {
        return new LibraryScanStateStore.Snapshot(LibraryScanStateStore.ACTIVE_LIBRARY_KEY, root, identity, mode,
                policyVersion, state, "COMPLETED", null, null, 1L,
                0L, 0L, 0L, 0L, null, null, OffsetDateTime.now());
    }

    private static LibraryScanStateStore.Claim claim() {
        return new LibraryScanStateStore.Claim(UUID.randomUUID(), UUID.randomUUID(), 1L);
    }

    private static LibraryScanService.ScanProgress progress(
            LibraryScanService.ScanState state, String errorCode, String errorMessage) {
        return new LibraryScanService.ScanProgress(false, 0, 0, null, 0, 0, 0, 0,
                OffsetDateTime.now(), OffsetDateTime.now(), state.name(), 0, 0, 0, 0, 0.0, null,
                state, 0, errorCode, errorMessage);
    }
}
