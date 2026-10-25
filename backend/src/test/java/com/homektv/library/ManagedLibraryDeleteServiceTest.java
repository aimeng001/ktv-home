package com.homektv.library;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.homektv.config.AppProperties;
import com.homektv.domain.ManagedDeleteOperation;
import com.homektv.domain.SongFile;
import com.homektv.repo.ManagedDeleteOperationRepository;
import com.homektv.repo.SongRepository;
import com.homektv.web.ApiException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.List;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ManagedLibraryDeleteServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void stagesManagedFilesAndCanRestoreThem() throws Exception {
        Path library = Files.createDirectory(tempDir.resolve("music"));
        Path data = Files.createDirectory(tempDir.resolve("data"));
        Path source = library.resolve("song.mkv");
        Files.writeString(source, "original");

        AppProperties props = properties(library, data);
        ManagedDeleteOperationRepository operations = mock(ManagedDeleteOperationRepository.class);
        Map<String, com.homektv.domain.ManagedDeleteOperation> stored = new HashMap<>();
        when(operations.saveAndFlush(any())).thenAnswer(invocation -> {
            var operation = invocation.<com.homektv.domain.ManagedDeleteOperation>getArgument(0);
            stored.put(operation.getOperationId(), operation);
            return operation;
        });
        when(operations.findById(any())).thenAnswer(invocation ->
                Optional.ofNullable(stored.get(invocation.getArgument(0))));
        ManagedLibraryDeleteService service = new ManagedLibraryDeleteService(
                props, operations, mock(SongRepository.class), new ObjectMapper());

        SongFile file = new SongFile();
        file.setFilePath(source.toString());
        String operationId = service.prepare(7L, List.of(file));
        service.stage(operationId);

        assertThat(source).doesNotExist();
        assertThat(Files.exists(data.resolve(".ktv-trash").resolve(operationId))).isTrue();

        service.rollback(operationId);

        assertThat(source).exists().hasContent("original");
        try (var entries = Files.list(data.resolve(".ktv-trash").resolve(operationId))) {
            assertThat(entries.count()).isZero();
        }
    }

    @Test
    void rejectsManagedDeletePathOutsideTheConfiguredLibrary() throws Exception {
        Path library = Files.createDirectory(tempDir.resolve("music"));
        Path data = Files.createDirectory(tempDir.resolve("data"));
        Path outside = Files.createFile(tempDir.resolve("outside.mkv"));
        ManagedLibraryDeleteService service = new ManagedLibraryDeleteService(
                properties(library, data), mock(ManagedDeleteOperationRepository.class),
                mock(SongRepository.class), new ObjectMapper());
        SongFile file = new SongFile();
        file.setFilePath(outside.toString());

        assertThatThrownBy(() -> service.prepare(7L, List.of(file)))
                .isInstanceOf(com.homektv.web.ApiException.class)
                .hasFieldOrPropertyWithValue("code", "INVALID_LIBRARY_PATH");
    }

    @Test
    void refusesToOverwriteAnExistingTrashTarget() throws Exception {
        Path library = Files.createDirectory(tempDir.resolve("music"));
        Path data = Files.createDirectory(tempDir.resolve("data"));
        Path source = library.resolve("song.mkv");
        Files.writeString(source, "original");
        ManagedDeleteOperationRepository operations = mock(ManagedDeleteOperationRepository.class);
        Map<String, com.homektv.domain.ManagedDeleteOperation> stored = new HashMap<>();
        when(operations.saveAndFlush(any())).thenAnswer(invocation -> {
            var operation = invocation.<com.homektv.domain.ManagedDeleteOperation>getArgument(0);
            stored.put(operation.getOperationId(), operation);
            return operation;
        });
        when(operations.findById(any())).thenAnswer(invocation ->
                Optional.ofNullable(stored.get(invocation.getArgument(0))));
        ObjectMapper mapper = new ObjectMapper();
        ManagedLibraryDeleteService service = new ManagedLibraryDeleteService(
                properties(library, data), operations, mock(SongRepository.class), mapper);
        SongFile file = new SongFile();
        file.setFilePath(source.toString());
        String operationId = service.prepare(7L, List.of(file));
        var captured = org.mockito.ArgumentCaptor.forClass(com.homektv.domain.ManagedDeleteOperation.class);
        org.mockito.Mockito.verify(operations).saveAndFlush(captured.capture());
        List<Map<String, String>> manifest = mapper.readValue(captured.getValue().getManifest(),
                new TypeReference<>() {});
        Path trash = Path.of(manifest.get(0).get("trashPath"));
        Files.createDirectories(trash.getParent());
        Files.writeString(trash, "do-not-overwrite");

        assertThatThrownBy(() -> service.stage(operationId))
                .isInstanceOf(com.homektv.web.ApiException.class);
        assertThat(source).exists().hasContent("original");
        assertThat(trash).hasContent("do-not-overwrite");
    }

    @Test
    void transactionCompletionPurgesCommittedDeletesAndRestoresRolledBackDeletes() throws Exception {
        Path library = Files.createDirectory(tempDir.resolve("music"));
        Path data = Files.createDirectory(tempDir.resolve("data"));
        Path committedSource = Files.writeString(library.resolve("committed.mkv"), "committed media");
        Path rolledBackSource = Files.writeString(library.resolve("rolled-back.mkv"), "restored media");
        ManagedDeleteOperationRepository operations = mock(ManagedDeleteOperationRepository.class);
        Map<String, com.homektv.domain.ManagedDeleteOperation> stored = new HashMap<>();
        when(operations.saveAndFlush(any())).thenAnswer(invocation -> {
            var operation = invocation.<com.homektv.domain.ManagedDeleteOperation>getArgument(0);
            stored.put(operation.getOperationId(), operation);
            return operation;
        });
        when(operations.findById(any())).thenAnswer(invocation ->
                Optional.ofNullable(stored.get(invocation.getArgument(0))));
        ManagedLibraryDeleteService service = new ManagedLibraryDeleteService(
                properties(library, data), operations, mock(SongRepository.class), new ObjectMapper());

        String committedOperation = service.prepare(11L, List.of(file(committedSource)));
        service.stage(committedOperation);
        TransactionSynchronizationManager.initSynchronization();
        try {
            service.registerCompletion(committedOperation);
            synchronization().afterCompletion(TransactionSynchronization.STATUS_COMMITTED);
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
        assertThat(committedSource).doesNotExist();
        assertThat(stored.get(committedOperation).getStatus())
                .isEqualTo(com.homektv.domain.ManagedDeleteOperation.PURGED);

        String rolledBackOperation = service.prepare(12L, List.of(file(rolledBackSource)));
        service.stage(rolledBackOperation);
        TransactionSynchronizationManager.initSynchronization();
        try {
            service.registerCompletion(rolledBackOperation);
            synchronization().afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK);
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
        assertThat(rolledBackSource).exists().hasContent("restored media");
        assertThat(stored.get(rolledBackOperation).getStatus())
                .isEqualTo(com.homektv.domain.ManagedDeleteOperation.ROLLED_BACK);
    }

    @Test
    void completionCallbackCannotBeRegisteredOutsideATransaction() throws Exception {
        Path library = Files.createDirectory(tempDir.resolve("music"));
        Path data = Files.createDirectory(tempDir.resolve("data"));
        ManagedLibraryDeleteService service = new ManagedLibraryDeleteService(
                properties(library, data), mock(ManagedDeleteOperationRepository.class),
                mock(SongRepository.class), new ObjectMapper());

        assertThatThrownBy(() -> service.registerCompletion("operation"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("数据库事务");
    }

    @Test
    void prepareAcceptsNullFileListAndExternalModeRejectsBeforeRepositoryAccess() throws Exception {
        Path library = Files.createDirectory(tempDir.resolve("music"));
        Path data = Files.createDirectory(tempDir.resolve("data"));
        Fixture fixture = fixture(library, data);

        String operationId = fixture.service().prepare(21L, null);

        assertThat(fixture.operations().findById(operationId)).isPresent()
                .get().extracting(ManagedDeleteOperation::getManifest).isEqualTo("[]");

        AppProperties externalProps = properties(library, data);
        externalProps.setLibraryMode(LibraryMode.EXTERNAL_READ_ONLY);
        ManagedDeleteOperationRepository externalOperations = mock(ManagedDeleteOperationRepository.class);
        ManagedLibraryDeleteService externalService = new ManagedLibraryDeleteService(
                externalProps, externalOperations, mock(SongRepository.class), new ObjectMapper());
        assertThatThrownBy(() -> externalService.prepare(22L, null))
                .isInstanceOf(ApiException.class)
                .hasFieldOrPropertyWithValue("code", "EXTERNAL_READ_ONLY");
        org.mockito.Mockito.verifyNoInteractions(externalOperations);
    }

    @Test
    void stageIsIdempotentForStagedOperationAndRejectsOtherStates() throws Exception {
        Path library = Files.createDirectory(tempDir.resolve("music"));
        Path data = Files.createDirectory(tempDir.resolve("data"));
        Fixture fixture = fixture(library, data);
        String operationId = fixture.service().prepare(23L, List.of());
        fixture.stored().get(operationId).setStatus(ManagedDeleteOperation.STAGED);

        fixture.service().stage(operationId);
        fixture.stored().get(operationId).setStatus(ManagedDeleteOperation.COMMITTED);

        assertThatThrownBy(() -> fixture.service().stage(operationId))
                .isInstanceOf(ApiException.class)
                .hasFieldOrPropertyWithValue("code", "DELETE_OPERATION_INVALID");
    }

    @Test
    void stageMarksAlreadyMissingManagedFileAsStagedWithoutCreatingTrashFile() throws Exception {
        Path library = Files.createDirectory(tempDir.resolve("music"));
        Path data = Files.createDirectory(tempDir.resolve("data"));
        Path source = Files.writeString(library.resolve("already-gone.mkv"), "original");
        Fixture fixture = fixture(library, data);
        String operationId = fixture.service().prepare(24L, List.of(file(source)));
        Files.delete(source);

        fixture.service().stage(operationId);

        assertThat(source).doesNotExist();
        assertThat(fixture.stored().get(operationId).getStatus()).isEqualTo(ManagedDeleteOperation.STAGED);
        try (var trashEntries = Files.walk(data.resolve(".ktv-trash").resolve(operationId))) {
            assertThat(trashEntries.count()).isEqualTo(1);
        }
    }

    @Test
    void recoveryRestoresFilesForExistingSongsAndPurgesFilesForDeletedSongs() throws Exception {
        Path library = Files.createDirectory(tempDir.resolve("music"));
        Path data = Files.createDirectory(tempDir.resolve("data"));
        Path retainedSource = Files.writeString(library.resolve("retained.mkv"), "retained");
        Path deletedSource = Files.writeString(library.resolve("deleted.mkv"), "deleted");
        Fixture fixture = fixture(library, data);
        String retainedOperation = fixture.service().prepare(25L, List.of(file(retainedSource)));
        String deletedOperation = fixture.service().prepare(26L, List.of(file(deletedSource)));
        fixture.service().stage(retainedOperation);
        fixture.service().stage(deletedOperation);
        when(fixture.songs().existsById(25L)).thenReturn(true);
        when(fixture.songs().existsById(26L)).thenReturn(false);

        fixture.service().recoverPendingOperations();

        assertThat(retainedSource).exists().hasContent("retained");
        assertThat(fixture.stored().get(retainedOperation).getStatus())
                .isEqualTo(ManagedDeleteOperation.ROLLED_BACK);
        assertThat(deletedSource).doesNotExist();
        assertThat(fixture.stored().get(deletedOperation).getStatus()).isEqualTo(ManagedDeleteOperation.PURGED);
    }

    @Test
    void prepareRejectsInvalidManagedPathsAndStagingInsideTheLibrary() throws Exception {
        Path library = Files.createDirectory(tempDir.resolve("music"));
        Path data = Files.createDirectory(tempDir.resolve("data"));
        Fixture fixture = fixture(library, data);
        SongFile missingPath = new SongFile();
        SongFile blankPath = new SongFile();
        blankPath.setFilePath("   ");

        assertThatThrownBy(() -> fixture.service().prepare(27L, List.of(missingPath)))
                .isInstanceOf(ApiException.class)
                .hasFieldOrPropertyWithValue("code", "INVALID_LIBRARY_PATH");
        assertThatThrownBy(() -> fixture.service().prepare(28L, List.of(blankPath)))
                .isInstanceOf(ApiException.class)
                .hasFieldOrPropertyWithValue("code", "INVALID_LIBRARY_PATH");

        AppProperties missingRootProps = properties(tempDir.resolve("missing-library"), data);
        ManagedLibraryDeleteService missingRootService = new ManagedLibraryDeleteService(
                missingRootProps, mock(ManagedDeleteOperationRepository.class),
                mock(SongRepository.class), new ObjectMapper());
        assertThatThrownBy(() -> missingRootService.prepare(29L, null))
                .isInstanceOf(ApiException.class)
                .hasFieldOrPropertyWithValue("code", "INVALID_LIBRARY_PATH");

        ManagedLibraryDeleteService nestedDataService = new ManagedLibraryDeleteService(
                properties(library, library.resolve("data")), mock(ManagedDeleteOperationRepository.class),
                mock(SongRepository.class), new ObjectMapper());
        assertThatThrownBy(() -> nestedDataService.prepare(30L, null))
                .isInstanceOf(ApiException.class)
                .hasFieldOrPropertyWithValue("code", "INVALID_LIBRARY_PATH");

        assertThatThrownBy(() -> fixture.service().prepare(37L,
                List.of(file(tempDir.resolve("not-created-outside.mkv")))))
                .isInstanceOf(ApiException.class)
                .hasFieldOrPropertyWithValue("code", "INVALID_LIBRARY_PATH");
    }

    @Test
    void rollbackAndCompletionAreSafeForMissingAndTerminalOperations() throws Exception {
        Path library = Files.createDirectory(tempDir.resolve("music"));
        Path data = Files.createDirectory(tempDir.resolve("data"));
        Fixture fixture = fixture(library, data);

        fixture.service().rollback("missing");
        fixture.service().complete("missing");
        String operationId = fixture.service().prepare(31L, List.of());
        fixture.service().rollback(operationId);
        assertThat(fixture.stored().get(operationId).getStatus()).isEqualTo(ManagedDeleteOperation.ROLLED_BACK);
        fixture.service().rollback(operationId);
        fixture.stored().get(operationId).setStatus(ManagedDeleteOperation.PURGED);
        fixture.service().rollback(operationId);
        fixture.service().complete(operationId);

        assertThat(fixture.stored().get(operationId).getStatus()).isEqualTo(ManagedDeleteOperation.PURGED);
    }

    @Test
    void rollbackPreservesStagedTrashWhenAReplacementSourceAlreadyExists() throws Exception {
        Path library = Files.createDirectory(tempDir.resolve("music"));
        Path data = Files.createDirectory(tempDir.resolve("data"));
        Path source = Files.writeString(library.resolve("song.mkv"), "original");
        Fixture fixture = fixture(library, data);
        String operationId = fixture.service().prepare(32L, List.of(file(source)));
        fixture.service().stage(operationId);
        Path trash = data.resolve(".ktv-trash").resolve(operationId).resolve("0-song.mkv");
        Files.writeString(source, "replacement");

        fixture.service().rollback(operationId);

        assertThat(source).hasContent("replacement");
        assertThat(trash).hasContent("original");
        assertThat(fixture.stored().get(operationId).getStatus())
                .isEqualTo(ManagedDeleteOperation.RECOVERY_REQUIRED);
    }

    @Test
    void stageFailureAfterMovingEarlierFilesRestoresThoseFiles() throws Exception {
        Path library = Files.createDirectory(tempDir.resolve("music"));
        Path data = Files.createDirectory(tempDir.resolve("data"));
        Path source = Files.writeString(library.resolve("first.mkv"), "original");
        Path directory = Files.createDirectory(library.resolve("second-directory"));
        Fixture fixture = fixture(library, data);
        String operationId = fixture.service().prepare(33L, List.of(file(source), file(directory)));

        assertThatThrownBy(() -> fixture.service().stage(operationId))
                .isInstanceOf(ApiException.class)
                .hasFieldOrPropertyWithValue("code", "DELETE_LIBRARY_FILE_FAILED");

        assertThat(source).exists().hasContent("original");
        assertThat(directory).isDirectory();
        assertThat(fixture.stored().get(operationId).getStatus()).isEqualTo(ManagedDeleteOperation.ROLLED_BACK);
    }

    @Test
    void stageRecognizesFileAlreadyMovedBeforeJournalStatusWasUpdated() throws Exception {
        Path library = Files.createDirectory(tempDir.resolve("music"));
        Path data = Files.createDirectory(tempDir.resolve("data"));
        Path source = Files.writeString(library.resolve("interrupted.mkv"), "original");
        Fixture fixture = fixture(library, data);
        String operationId = fixture.service().prepare(38L, List.of(file(source)));
        var captured = org.mockito.ArgumentCaptor.forClass(ManagedDeleteOperation.class);
        org.mockito.Mockito.verify(fixture.operations()).saveAndFlush(captured.capture());
        List<Map<String, String>> entries = new ObjectMapper().readValue(captured.getValue().getManifest(),
                new TypeReference<>() {});
        Path trash = Path.of(entries.get(0).get("trashPath"));
        Files.createDirectories(trash.getParent());
        Files.move(source, trash);

        fixture.service().stage(operationId);

        assertThat(source).doesNotExist();
        assertThat(trash).hasContent("original");
        assertThat(fixture.stored().get(operationId).getStatus()).isEqualTo(ManagedDeleteOperation.STAGED);
    }

    @Test
    void stageAndRollbackHandleCorruptJournalAndUnstagedMissingTrash() throws Exception {
        Path library = Files.createDirectory(tempDir.resolve("music"));
        Path data = Files.createDirectory(tempDir.resolve("data"));
        Fixture fixture = fixture(library, data);
        ManagedDeleteOperation corrupt = operation("corrupt", 39L, ManagedDeleteOperation.PREPARED, "{");
        fixture.stored().put(corrupt.getOperationId(), corrupt);

        assertThatThrownBy(() -> fixture.service().stage(corrupt.getOperationId()))
                .isInstanceOf(ApiException.class)
                .hasFieldOrPropertyWithValue("code", "DELETE_OPERATION_INVALID");

        Path source = Files.writeString(library.resolve("not-staged.mkv"), "still here");
        String operationId = fixture.service().prepare(40L, List.of(file(source)));
        fixture.service().rollback(operationId);

        assertThat(source).hasContent("still here");
        assertThat(fixture.stored().get(operationId).getStatus()).isEqualTo(ManagedDeleteOperation.ROLLED_BACK);
    }

    @Test
    void prepareReportsManifestSerializationFailureAndRecoveryLeavesJournalOnRepositoryFailure() throws Exception {
        Path library = Files.createDirectory(tempDir.resolve("music"));
        Path data = Files.createDirectory(tempDir.resolve("data"));
        ObjectMapper brokenMapper = mock(ObjectMapper.class);
        when(brokenMapper.writeValueAsString(any())).thenThrow(new JsonProcessingException("synthetic failure") {});
        ManagedLibraryDeleteService service = new ManagedLibraryDeleteService(
                properties(library, data), mock(ManagedDeleteOperationRepository.class),
                mock(SongRepository.class), brokenMapper);

        assertThatThrownBy(() -> service.prepare(41L, List.of()))
                .isInstanceOf(ApiException.class)
                .hasFieldOrPropertyWithValue("code", "DELETE_OPERATION_INVALID");

        Fixture fixture = fixture(library, data);
        ManagedDeleteOperation pending = operation("repository-failure", 42L,
                ManagedDeleteOperation.PREPARED, "[]");
        fixture.stored().put(pending.getOperationId(), pending);
        when(fixture.songs().existsById(42L)).thenThrow(new IllegalStateException("repository unavailable"));

        fixture.service().recoverPendingOperations();

        assertThat(pending.getStatus()).isEqualTo(ManagedDeleteOperation.PREPARED);
    }

    @Test
    void completeMarksTamperedManifestForRecoveryAndKeepsCommittedRecordOnPurgeFailure() throws Exception {
        Path library = Files.createDirectory(tempDir.resolve("music"));
        Path data = Files.createDirectory(tempDir.resolve("data"));
        Fixture fixture = fixture(library, data);
        Path missingSource = library.resolve("gone.mkv");
        ManagedDeleteOperation outsideTrash = operation("outside-trash", 34L, ManagedDeleteOperation.STAGED,
                manifest(missingSource, tempDir.resolve("outside-trash.mkv")));
        fixture.stored().put(outsideTrash.getOperationId(), outsideTrash);
        fixture.service().complete(outsideTrash.getOperationId());
        assertThat(outsideTrash.getStatus()).isEqualTo(ManagedDeleteOperation.RECOVERY_REQUIRED);

        Path otherOperationTrash = data.resolve(".ktv-trash").resolve("different-operation").resolve("0-gone.mkv");
        ManagedDeleteOperation wrongOperation = operation("wrong-operation", 35L, ManagedDeleteOperation.STAGED,
                manifest(missingSource, otherOperationTrash));
        fixture.stored().put(wrongOperation.getOperationId(), wrongOperation);
        fixture.service().complete(wrongOperation.getOperationId());
        assertThat(wrongOperation.getStatus()).isEqualTo(ManagedDeleteOperation.RECOVERY_REQUIRED);

        Path purgeFailureSource = Files.writeString(library.resolve("purge-failure.mkv"), "source media");
        String purgeFailureId = fixture.service().prepare(36L, List.of(file(purgeFailureSource)));
        fixture.service().stage(purgeFailureId);
        ManagedDeleteOperation purgeFailure = fixture.stored().get(purgeFailureId);
        List<Map<String, String>> stagedEntries = new ObjectMapper().readValue(purgeFailure.getManifest(),
                new TypeReference<>() {});
        Path trashFile = Path.of(stagedEntries.getFirst().get("trashPath"));
        Files.delete(trashFile);
        Files.createDirectory(trashFile);
        Path preservedChild = trashFile.resolve("preserve.txt");
        Files.writeString(preservedChild, "keep");
        fixture.service().complete(purgeFailureId);

        assertThat(purgeFailure.getStatus()).isEqualTo(ManagedDeleteOperation.COMMITTED);
        assertThat(preservedChild).hasContent("keep");
    }

    private static SongFile file(Path path) {
        SongFile file = new SongFile();
        file.setFilePath(path.toString());
        return file;
    }

    private static TransactionSynchronization synchronization() {
        return TransactionSynchronizationManager.getSynchronizations().getFirst();
    }

    private AppProperties properties(Path library, Path data) {
        AppProperties props = new AppProperties();
        props.setLibraryMode(LibraryMode.MANAGED);
        props.setKtvLibraryPath(library.toString());
        props.setDataPath(data.toString());
        return props;
    }

    private Fixture fixture(Path library, Path data) {
        ManagedDeleteOperationRepository operations = mock(ManagedDeleteOperationRepository.class);
        SongRepository songs = mock(SongRepository.class);
        Map<String, ManagedDeleteOperation> stored = new HashMap<>();
        when(operations.saveAndFlush(any())).thenAnswer(invocation -> {
            ManagedDeleteOperation operation = invocation.getArgument(0);
            stored.put(operation.getOperationId(), operation);
            return operation;
        });
        when(operations.findById(any())).thenAnswer(invocation ->
                Optional.ofNullable(stored.get(invocation.getArgument(0))));
        when(operations.findByStatusInOrderByCreatedAtAsc(any())).thenAnswer(invocation -> {
            Collection<String> statuses = invocation.getArgument(0);
            return stored.values().stream().filter(operation -> statuses.contains(operation.getStatus())).toList();
        });
        return new Fixture(new ManagedLibraryDeleteService(properties(library, data), operations, songs,
                new ObjectMapper()), operations, songs, stored);
    }

    private static ManagedDeleteOperation operation(String id, Long songId, String status, String manifest) {
        ManagedDeleteOperation operation = new ManagedDeleteOperation();
        operation.setOperationId(id);
        operation.setSongId(songId);
        operation.setStatus(status);
        operation.setManifest(manifest);
        return operation;
    }

    private static String manifest(Path source, Path trash) throws Exception {
        return new ObjectMapper().writeValueAsString(List.of(Map.of(
                "sourcePath", source.toAbsolutePath().normalize().toString(),
                "trashPath", trash.toAbsolutePath().normalize().toString())));
    }

    private record Fixture(ManagedLibraryDeleteService service,
                           ManagedDeleteOperationRepository operations,
                           SongRepository songs,
                           Map<String, ManagedDeleteOperation> stored) {}
}
