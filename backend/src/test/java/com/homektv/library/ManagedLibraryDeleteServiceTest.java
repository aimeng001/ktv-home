package com.homektv.library;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.homektv.config.AppProperties;
import com.homektv.domain.SongFile;
import com.homektv.repo.ManagedDeleteOperationRepository;
import com.homektv.repo.SongRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
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

    private AppProperties properties(Path library, Path data) {
        AppProperties props = new AppProperties();
        props.setLibraryMode(LibraryMode.MANAGED);
        props.setKtvLibraryPath(library.toString());
        props.setDataPath(data.toString());
        return props;
    }
}
