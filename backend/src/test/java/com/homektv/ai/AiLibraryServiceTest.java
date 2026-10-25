package com.homektv.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.MissingNode;
import com.homektv.domain.AiAnalysisTask;
import com.homektv.domain.MediaImportRecord;
import com.homektv.domain.Song;
import com.homektv.domain.Playlist;
import com.homektv.domain.PlaylistSong;
import com.homektv.library.AssetWriter;
import com.homektv.library.AssetCleanupService;
import com.homektv.musicsource.CoverImageNormalizer;
import com.homektv.repo.AiAnalysisTaskRepository;
import com.homektv.repo.MediaImportRecordRepository;
import com.homektv.repo.PlaylistRepository;
import com.homektv.repo.PlaylistSongRepository;
import com.homektv.repo.SongRepository;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import org.mockito.ArgumentCaptor;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.doThrow;

class AiLibraryServiceTest {
    @Test
    void createTaskPersistsConfiguredRoleAndDispatchesAfterTransactionCommit() {
        SongRepository songs = mock(SongRepository.class);
        AiAnalysisTaskRepository tasks = mock(AiAnalysisTaskRepository.class);
        AiAnalysisWorker worker = mock(AiAnalysisWorker.class);
        AiConfigService config = taskConfig(true);
        when(songs.findById(51L)).thenReturn(Optional.of(song(51L, "待分析", "歌手")));
        when(tasks.saveAndFlush(any(AiAnalysisTask.class))).thenAnswer(invocation -> {
            AiAnalysisTask task = invocation.getArgument(0);
            ReflectionTestUtils.setField(task, "id", 151L);
            return task;
        });
        AiLibraryService service = taskService(tasks, songs, mock(MediaImportRecordRepository.class), worker, config);

        TransactionSynchronizationManager.setActualTransactionActive(true);
        TransactionSynchronizationManager.initSynchronization();
        try {
            AiAnalysisTask created = service.createTask(51L);

            assertThat(created.getModel()).isEqualTo("bulk-model");
            assertThat(created.getModelRole()).isEqualTo("BULK");
            assertThat(created.getTargetType()).isEqualTo("SONG");
            assertThat(created.getTargetId()).isEqualTo(51L);
            verify(worker, never()).analyze(151L);
            List<TransactionSynchronization> callbacks = TransactionSynchronizationManager.getSynchronizations();
            assertThat(callbacks).hasSize(1);
            callbacks.forEach(TransactionSynchronization::afterCommit);
            verify(worker).analyze(151L);
        } finally {
            if (TransactionSynchronizationManager.isSynchronizationActive()) {
                TransactionSynchronizationManager.clearSynchronization();
            }
            TransactionSynchronizationManager.setActualTransactionActive(false);
        }
    }

    @Test
    void createTaskRejectsMissingSongsAndActiveTasksBeforeSaving() {
        SongRepository missingSongs = mock(SongRepository.class);
        AiAnalysisTaskRepository missingTasks = mock(AiAnalysisTaskRepository.class);
        when(missingSongs.findById(1L)).thenReturn(Optional.empty());
        AiLibraryService missingService = taskService(missingTasks, missingSongs,
                mock(MediaImportRecordRepository.class), mock(AiAnalysisWorker.class), taskConfig(false));

        assertThatThrownBy(() -> missingService.createTask(1L)).hasMessageContaining("歌曲不存在");
        verifyNoInteractions(missingTasks);

        SongRepository existingSongs = mock(SongRepository.class);
        AiAnalysisTaskRepository existingTasks = mock(AiAnalysisTaskRepository.class);
        when(existingSongs.findById(2L)).thenReturn(Optional.of(song(2L, "已有任务", "歌手")));
        when(existingTasks.existsBySongIdAndStatusIn(eq(2L), anyList())).thenReturn(true);
        AiLibraryService existingService = taskService(existingTasks, existingSongs,
                mock(MediaImportRecordRepository.class), mock(AiAnalysisWorker.class), taskConfig(false));

        assertThatThrownBy(() -> existingService.createTask(2L)).hasMessageContaining("已有待处理");
        verify(existingTasks, never()).saveAndFlush(any(AiAnalysisTask.class));
    }

    @Test
    void createTaskDistinguishesConcurrentDuplicateFromOtherConstraintFailures() {
        SongRepository duplicateSongs = mock(SongRepository.class);
        AiAnalysisTaskRepository duplicateTasks = mock(AiAnalysisTaskRepository.class);
        when(duplicateSongs.findById(3L)).thenReturn(Optional.of(song(3L, "并发重复", "歌手")));
        when(duplicateTasks.saveAndFlush(any(AiAnalysisTask.class)))
                .thenThrow(new DataIntegrityViolationException("duplicate active task"));
        when(duplicateTasks.findFirstBySongIdAndStatusInOrderByCreatedAtDesc(eq(3L), anyList()))
                .thenReturn(Optional.of(new AiAnalysisTask()));
        AiLibraryService duplicateService = taskService(duplicateTasks, duplicateSongs,
                mock(MediaImportRecordRepository.class), mock(AiAnalysisWorker.class), taskConfig(false));

        assertThatThrownBy(() -> duplicateService.createTask(3L)).hasMessageContaining("已有待处理");

        SongRepository unrelatedSongs = mock(SongRepository.class);
        AiAnalysisTaskRepository unrelatedTasks = mock(AiAnalysisTaskRepository.class);
        DataIntegrityViolationException unrelated = new DataIntegrityViolationException("other constraint");
        when(unrelatedSongs.findById(4L)).thenReturn(Optional.of(song(4L, "其他约束", "歌手")));
        when(unrelatedTasks.saveAndFlush(any(AiAnalysisTask.class))).thenThrow(unrelated);
        when(unrelatedTasks.findFirstBySongIdAndStatusInOrderByCreatedAtDesc(eq(4L), anyList()))
                .thenReturn(Optional.empty());
        AiLibraryService unrelatedService = taskService(unrelatedTasks, unrelatedSongs,
                mock(MediaImportRecordRepository.class), mock(AiAnalysisWorker.class), taskConfig(false));

        assertThatThrownBy(() -> unrelatedService.createTask(4L)).isSameAs(unrelated);
    }

    @Test
    void createImportTaskKeepsImportIdentitySeparateFromSongIdentity() {
        MediaImportRecordRepository imports = mock(MediaImportRecordRepository.class);
        AiAnalysisTaskRepository tasks = mock(AiAnalysisTaskRepository.class);
        AiAnalysisWorker worker = mock(AiAnalysisWorker.class);
        when(imports.findById(61L)).thenReturn(Optional.of(new MediaImportRecord()));
        when(tasks.saveAndFlush(any(AiAnalysisTask.class))).thenAnswer(invocation -> {
            AiAnalysisTask task = invocation.getArgument(0);
            ReflectionTestUtils.setField(task, "id", 161L);
            return task;
        });
        AiLibraryService service = taskService(tasks, mock(SongRepository.class), imports, worker, taskConfig(true));

        AiAnalysisTask created = service.createImportTask(61L);

        assertThat(created.getSongId()).isNull();
        assertThat(created.getTargetType()).isEqualTo("IMPORT_RECORD");
        assertThat(created.getTargetId()).isEqualTo(61L);
        assertThat(created.getModel()).isEqualTo("bulk-model");
        assertThat(created.getModelRole()).isEqualTo("BULK");
        verify(worker).analyze(161L);
    }

    @Test
    void createImportTaskRejectsMissingOrAlreadyQueuedRecords() {
        MediaImportRecordRepository missingImports = mock(MediaImportRecordRepository.class);
        AiAnalysisTaskRepository missingTasks = mock(AiAnalysisTaskRepository.class);
        AiLibraryService missingService = taskService(missingTasks, mock(SongRepository.class), missingImports,
                mock(AiAnalysisWorker.class), taskConfig(false));

        assertThatThrownBy(() -> missingService.createImportTask(70L)).hasMessageContaining("导入记录不存在");
        verifyNoInteractions(missingTasks);

        MediaImportRecordRepository existingImports = mock(MediaImportRecordRepository.class);
        AiAnalysisTaskRepository existingTasks = mock(AiAnalysisTaskRepository.class);
        when(existingImports.findById(71L)).thenReturn(Optional.of(new MediaImportRecord()));
        when(existingTasks.existsByTargetTypeAndTargetIdAndStatusIn(eq("IMPORT_RECORD"), eq(71L), anyList()))
                .thenReturn(true);
        AiLibraryService existingService = taskService(existingTasks, mock(SongRepository.class), existingImports,
                mock(AiAnalysisWorker.class), taskConfig(false));

        assertThatThrownBy(() -> existingService.createImportTask(71L)).hasMessageContaining("已有待处理");
        verify(existingTasks, never()).saveAndFlush(any(AiAnalysisTask.class));
    }

    @Test
    void createImportTaskMapsOnlyAnActiveUniqueIndexConflictToDuplicateTask() {
        MediaImportRecordRepository imports = mock(MediaImportRecordRepository.class);
        AiAnalysisTaskRepository duplicateTasks = mock(AiAnalysisTaskRepository.class);
        when(imports.findById(72L)).thenReturn(Optional.of(new MediaImportRecord()));
        when(duplicateTasks.saveAndFlush(any(AiAnalysisTask.class)))
                .thenThrow(new DataIntegrityViolationException("duplicate import task"));
        when(duplicateTasks.existsByTargetTypeAndTargetIdAndStatusIn(eq("IMPORT_RECORD"), eq(72L), anyList()))
                .thenReturn(false, true);
        AiLibraryService duplicateService = taskService(duplicateTasks, mock(SongRepository.class), imports,
                mock(AiAnalysisWorker.class), taskConfig(false));

        assertThatThrownBy(() -> duplicateService.createImportTask(72L)).hasMessageContaining("已有待处理");

        MediaImportRecordRepository unrelatedImports = mock(MediaImportRecordRepository.class);
        AiAnalysisTaskRepository unrelatedTasks = mock(AiAnalysisTaskRepository.class);
        DataIntegrityViolationException unrelated = new DataIntegrityViolationException("foreign key failure");
        when(unrelatedImports.findById(73L)).thenReturn(Optional.of(new MediaImportRecord()));
        when(unrelatedTasks.saveAndFlush(any(AiAnalysisTask.class))).thenThrow(unrelated);
        when(unrelatedTasks.existsByTargetTypeAndTargetIdAndStatusIn(eq("IMPORT_RECORD"), eq(73L), anyList()))
                .thenReturn(false, false);
        AiLibraryService unrelatedService = taskService(unrelatedTasks, mock(SongRepository.class), unrelatedImports,
                mock(AiAnalysisWorker.class), taskConfig(false));

        assertThatThrownBy(() -> unrelatedService.createImportTask(73L)).isSameAs(unrelated);
    }

    @Test
    void createUnclassifiedTasksSkipsAnalyzedAndActiveSongsAndStopsAfterEmptyNextPage() {
        SongRepository songs = mock(SongRepository.class);
        AiAnalysisTaskRepository tasks = mock(AiAnalysisTaskRepository.class);
        AiAnalysisWorker worker = mock(AiAnalysisWorker.class);
        Song analyzed = song(1L, "已分析", "歌手");
        analyzed.setAiAnalyzedAt(OffsetDateTime.now());
        Song active = song(2L, "有活动任务", "歌手");
        Song eligible = song(3L, "待分析", "歌手");
        when(songs.findAll(any(Pageable.class))).thenAnswer(invocation -> {
            Pageable page = invocation.getArgument(0);
            return page.getPageNumber() == 0
                    ? new PageImpl<>(List.of(analyzed, active, eligible), page, 501)
                    : new PageImpl<>(List.of(), page, 501);
        });
        when(songs.findById(3L)).thenReturn(Optional.of(eligible));
        when(tasks.existsBySongIdAndStatusIn(eq(2L), anyList())).thenReturn(true);
        when(tasks.existsBySongIdAndStatusIn(eq(3L), anyList())).thenReturn(false);
        when(tasks.saveAndFlush(any(AiAnalysisTask.class))).thenAnswer(invocation -> {
            AiAnalysisTask task = invocation.getArgument(0);
            ReflectionTestUtils.setField(task, "id", 103L);
            return task;
        });
        AiLibraryService service = taskService(tasks, songs, mock(MediaImportRecordRepository.class), worker,
                taskConfig(false));

        List<AiAnalysisTask> created = service.createUnclassifiedTasks(10);

        assertThat(created).hasSize(1).first().satisfies(task -> assertThat(task.getSongId()).isEqualTo(3L));
        verify(songs, times(2)).findAll(any(Pageable.class));
        verify(tasks, never()).existsBySongIdAndStatusIn(eq(1L), anyList());
        verify(worker).analyze(103L);
    }

    @Test
    void createUnclassifiedTasksCapsBatchAtFiveHundred() {
        SongRepository songs = mock(SongRepository.class);
        AiAnalysisTaskRepository tasks = mock(AiAnalysisTaskRepository.class);
        AiAnalysisWorker worker = mock(AiAnalysisWorker.class);
        List<Song> catalogue = java.util.stream.LongStream.rangeClosed(1, 501)
                .mapToObj(id -> song(id, "歌曲" + id, "歌手"))
                .toList();
        when(songs.findAll(any(Pageable.class))).thenAnswer(invocation -> {
            Pageable page = invocation.getArgument(0);
            int start = Math.toIntExact(page.getOffset());
            int end = Math.min(start + page.getPageSize(), catalogue.size());
            return new PageImpl<>(catalogue.subList(start, end), page, catalogue.size());
        });
        when(songs.findById(anyLong())).thenAnswer(invocation -> Optional.of(catalogue.get(
                Math.toIntExact(invocation.getArgument(0, Long.class)) - 1)));
        when(tasks.saveAndFlush(any(AiAnalysisTask.class))).thenAnswer(invocation -> {
            AiAnalysisTask task = invocation.getArgument(0);
            ReflectionTestUtils.setField(task, "id", task.getSongId() + 1_000L);
            return task;
        });
        AiLibraryService service = taskService(tasks, songs, mock(MediaImportRecordRepository.class), worker,
                taskConfig(false));

        List<AiAnalysisTask> created = service.createUnclassifiedTasks(501);

        assertThat(created).hasSize(500);
        verify(songs, times(1)).findAll(any(Pageable.class));
        verify(tasks, times(500)).saveAndFlush(any(AiAnalysisTask.class));
        verify(worker, times(500)).analyze(anyLong());
    }

    @Test
    void createUnclassifiedTasksUsesOneAsTheMinimumLimit() {
        SongRepository songs = mock(SongRepository.class);
        AiAnalysisTaskRepository tasks = mock(AiAnalysisTaskRepository.class);
        AiAnalysisWorker worker = mock(AiAnalysisWorker.class);
        Song candidate = song(1201L, "最小批次", "歌手");
        when(songs.findAll(any(Pageable.class))).thenReturn(new PageImpl<>(List.of(candidate)));
        when(songs.findById(1201L)).thenReturn(Optional.of(candidate));
        when(tasks.saveAndFlush(any(AiAnalysisTask.class))).thenAnswer(invocation -> {
            AiAnalysisTask task = invocation.getArgument(0);
            ReflectionTestUtils.setField(task, "id", 1202L);
            return task;
        });
        AiLibraryService service = taskService(tasks, songs, mock(MediaImportRecordRepository.class), worker,
                taskConfig(false));

        assertThat(service.createUnclassifiedTasks(0)).hasSize(1);
        verify(worker).analyze(1202L);
    }

    @Test
    void createUnclassifiedTasksHandlesMissingPagesAndStopsAtTheRequestedCount() {
        SongRepository missingPageSongs = mock(SongRepository.class);
        AiAnalysisTaskRepository missingPageTasks = mock(AiAnalysisTaskRepository.class);
        when(missingPageSongs.findAll(any(Pageable.class))).thenReturn(null);
        AiLibraryService missingPageService = taskService(missingPageTasks, missingPageSongs,
                mock(MediaImportRecordRepository.class), mock(AiAnalysisWorker.class), taskConfig(false));
        assertThat(missingPageService.createUnclassifiedTasks(5)).isEmpty();
        verifyNoInteractions(missingPageTasks);

        SongRepository songs = mock(SongRepository.class);
        AiAnalysisTaskRepository tasks = mock(AiAnalysisTaskRepository.class);
        AiAnalysisWorker worker = mock(AiAnalysisWorker.class);
        Song first = song(1221L, "限制内", "歌手");
        Song second = song(1222L, "限制外", "歌手");
        when(songs.findAll(any(Pageable.class))).thenReturn(new PageImpl<>(List.of(first, second)));
        when(songs.findById(1221L)).thenReturn(Optional.of(first));
        when(tasks.saveAndFlush(any(AiAnalysisTask.class))).thenAnswer(invocation -> {
            AiAnalysisTask task = invocation.getArgument(0);
            ReflectionTestUtils.setField(task, "id", 1223L);
            return task;
        });
        AiLibraryService service = taskService(tasks, songs, mock(MediaImportRecordRepository.class), worker,
                taskConfig(false));

        assertThat(service.createUnclassifiedTasks(1)).extracting(AiAnalysisTask::getSongId).containsExactly(1221L);
        verify(songs, never()).findById(1222L);
        verify(worker).analyze(1223L);
    }

    @Test
    void createTaskDispatchesImmediatelyWhenSynchronizationIsActiveWithoutAnActiveTransaction() {
        SongRepository songs = mock(SongRepository.class);
        AiAnalysisTaskRepository tasks = mock(AiAnalysisTaskRepository.class);
        AiAnalysisWorker worker = mock(AiAnalysisWorker.class);
        when(songs.findById(1211L)).thenReturn(Optional.of(song(1211L, "并发状态", "歌手")));
        when(tasks.saveAndFlush(any(AiAnalysisTask.class))).thenAnswer(invocation -> {
            AiAnalysisTask task = invocation.getArgument(0);
            ReflectionTestUtils.setField(task, "id", 1212L);
            return task;
        });
        AiLibraryService service = taskService(tasks, songs, mock(MediaImportRecordRepository.class), worker,
                taskConfig(false));

        TransactionSynchronizationManager.setActualTransactionActive(false);
        TransactionSynchronizationManager.initSynchronization();
        try {
            service.createTask(1211L);

            verify(worker).analyze(1212L);
            assertThat(TransactionSynchronizationManager.getSynchronizations()).isEmpty();
        } finally {
            if (TransactionSynchronizationManager.isSynchronizationActive()) {
                TransactionSynchronizationManager.clearSynchronization();
            }
            TransactionSynchronizationManager.setActualTransactionActive(false);
        }
    }

    @Test
    void listTasksEnrichesSongAndImportTargetsWithSafeFallbacks() {
        AiAnalysisTaskRepository tasks = mock(AiAnalysisTaskRepository.class);
        SongRepository songs = mock(SongRepository.class);
        MediaImportRecordRepository imports = mock(MediaImportRecordRepository.class);
        AiAnalysisTask songTask = batchTask(201L, "review");
        songTask.setSongId(11L);
        AiAnalysisTask missingSongTask = batchTask(202L, "failed");
        missingSongTask.setSongId(13L);
        AiAnalysisTask importTask = batchTask(203L, "review");
        importTask.setTargetType("IMPORT_RECORD");
        importTask.setTargetId(22L);
        AiAnalysisTask missingImportTask = batchTask(204L, "failed");
        missingImportTask.setTargetType("IMPORT_RECORD");
        missingImportTask.setTargetId(24L);
        AiAnalysisTask fallbackImportTask = batchTask(205L, "review");
        fallbackImportTask.setTargetType("IMPORT_RECORD");
        fallbackImportTask.setTargetId(25L);
        when(tasks.findTop100ByOrderByCreatedAtDesc())
                .thenReturn(List.of(songTask, missingSongTask, importTask, missingImportTask, fallbackImportTask));

        Song song = song(11L, " ", null);
        when(songs.findAllById(List.of(11L, 13L))).thenReturn(List.of(song));
        MediaImportRecord record = new MediaImportRecord();
        record.setId(22L);
        record.setParsedTitle(" ");
        record.setSourceFilename("待导入.mkv");
        record.setParsedArtist(null);
        MediaImportRecord emptyRecord = new MediaImportRecord();
        emptyRecord.setId(25L);
        emptyRecord.setSourceFilename("  ");
        emptyRecord.setParsedTitle(" ");
        emptyRecord.setParsedArtist(" ");
        when(imports.findAllById(List.of(22L, 24L, 25L))).thenReturn(List.of(record, emptyRecord));

        AiLibraryService service = taskService(tasks, songs, imports, mock(AiAnalysisWorker.class), taskConfig(false));

        List<AiAnalysisTask> result = service.listTasks();

        assertThat(result).containsExactly(songTask, missingSongTask, importTask, missingImportTask, fallbackImportTask);
        assertThat(songTask.getTargetTitle()).isEqualTo("未命名歌曲");
        assertThat(songTask.getTargetArtist()).isEqualTo("未知歌手");
        assertThat(importTask.getTargetTitle()).isEqualTo("待导入.mkv");
        assertThat(importTask.getTargetArtist()).isEqualTo("待识别歌手");
        assertThat(missingSongTask.getTargetTitle()).isNull();
        assertThat(missingImportTask.getTargetTitle()).isNull();
        assertThat(fallbackImportTask.getTargetTitle()).isEqualTo("导入记录");
        assertThat(fallbackImportTask.getTargetArtist()).isEqualTo("待识别歌手");
        verify(songs).findAllById(List.of(11L, 13L));
        verify(imports).findAllById(List.of(22L, 24L, 25L));
    }

    @Test
    void listPlaylistsReturnsEmptyForNullRowsAndChunksSummariesByFiveHundredIds() {
        PlaylistRepository emptyRepository = mock(PlaylistRepository.class);
        when(emptyRepository.findAllByOrderByUpdatedAtDesc()).thenReturn(null);
        AiLibraryService emptyService = new AiLibraryService(mock(AiAnalysisTaskRepository.class),
                mock(SongRepository.class), emptyRepository, mock(PlaylistSongRepository.class),
                mock(AiAnalysisWorker.class), new ObjectMapper(), mock(AiConfigService.class),
                mock(AssetWriter.class), mock(AiClassificationApplier.class), mock(OpenAiCompatibleClient.class),
                mock(MediaImportRecordRepository.class), mock(AiTaskDispatchFailureRecorder.class));
        assertThat(emptyService.listPlaylists()).isEmpty();
        PlaylistRepository noPlaylists = mock(PlaylistRepository.class);
        when(noPlaylists.findAllByOrderByUpdatedAtDesc()).thenReturn(List.of());
        AiLibraryService noPlaylistsService = new AiLibraryService(mock(AiAnalysisTaskRepository.class),
                mock(SongRepository.class), noPlaylists, mock(PlaylistSongRepository.class),
                mock(AiAnalysisWorker.class), new ObjectMapper(), mock(AiConfigService.class),
                mock(AssetWriter.class), mock(AiClassificationApplier.class), mock(OpenAiCompatibleClient.class),
                mock(MediaImportRecordRepository.class), mock(AiTaskDispatchFailureRecorder.class));
        assertThat(noPlaylistsService.listPlaylists()).isEmpty();

        PlaylistRepository playlists = mock(PlaylistRepository.class);
        PlaylistSongRepository playlistSongs = mock(PlaylistSongRepository.class);
        List<Playlist> catalogue = new ArrayList<>();
        for (long id = 1; id <= 501; id++) {
            Playlist playlist = new Playlist();
            playlist.setId(id);
            playlist.setName("歌单" + id);
            if (id == 1) playlist.setCoverPath("playlist-covers/cover.webp");
            catalogue.add(playlist);
        }
        catalogue.add(new Playlist());
        when(playlists.findAllByOrderByUpdatedAtDesc()).thenReturn(catalogue);
        List<Integer> batchSizes = new ArrayList<>();
        when(playlistSongs.summarizeByPlaylistIdIn(any())).thenAnswer(invocation -> {
            List<Long> ids = invocation.getArgument(0);
            batchSizes.add(ids.size());
            if (ids.size() == 500) {
                PlaylistSongRepository.PlaylistSummaryProjection populated =
                        mock(PlaylistSongRepository.PlaylistSummaryProjection.class);
                when(populated.getPlaylistId()).thenReturn(1L);
                when(populated.getSongCount()).thenReturn(5L);
                when(populated.getManualCount()).thenReturn(2L);
                PlaylistSongRepository.PlaylistSummaryProjection nullCounts =
                        mock(PlaylistSongRepository.PlaylistSummaryProjection.class);
                when(nullCounts.getPlaylistId()).thenReturn(2L);
                when(nullCounts.getSongCount()).thenReturn(null);
                when(nullCounts.getManualCount()).thenReturn(null);
                PlaylistSongRepository.PlaylistSummaryProjection nullId =
                        mock(PlaylistSongRepository.PlaylistSummaryProjection.class);
                when(nullId.getPlaylistId()).thenReturn(null);
                return Arrays.asList(populated, null, nullCounts, nullId);
            }
            return null;
        });
        AiLibraryService service = new AiLibraryService(mock(AiAnalysisTaskRepository.class),
                mock(SongRepository.class), playlists, playlistSongs, mock(AiAnalysisWorker.class),
                new ObjectMapper(), mock(AiConfigService.class), mock(AssetWriter.class),
                mock(AiClassificationApplier.class), mock(OpenAiCompatibleClient.class),
                mock(MediaImportRecordRepository.class), mock(AiTaskDispatchFailureRecorder.class));

        List<Map<String, Object>> result = service.listPlaylists();

        assertThat(result).hasSize(502);
        assertThat(result.get(0)).containsEntry("songCount", 5L).containsEntry("manualCount", 2L)
                .containsEntry("coverUrl", "/api/playlists/1/cover");
        assertThat(result.get(1)).containsEntry("songCount", 0L).containsEntry("manualCount", 0L);
        assertThat(result.get(500)).containsEntry("id", 501L).containsEntry("songCount", 0L);
        assertThat(result.get(501)).containsEntry("id", null).containsEntry("songCount", 0L);
        assertThat(batchSizes).containsExactly(500, 1);
    }

    @Test
    void savePlaylistNormalizesNewAndExistingPlaylistsAndRejectsInvalidTargets() {
        PlaylistRepository playlists = mock(PlaylistRepository.class);
        Playlist existing = new Playlist();
        existing.setId(301L);
        when(playlists.findById(301L)).thenReturn(Optional.of(existing));
        when(playlists.save(any(Playlist.class))).thenAnswer(invocation -> invocation.getArgument(0));
        AiLibraryService service = new AiLibraryService(mock(AiAnalysisTaskRepository.class),
                mock(SongRepository.class), playlists, mock(PlaylistSongRepository.class),
                mock(AiAnalysisWorker.class), new ObjectMapper(), mock(AiConfigService.class),
                mock(AssetWriter.class), mock(AiClassificationApplier.class), mock(OpenAiCompatibleClient.class),
                mock(MediaImportRecordRepository.class), mock(AiTaskDispatchFailureRecorder.class));

        Playlist created = service.savePlaylist(null, "  新歌单  ", null, "  ", false);
        Playlist nullTheme = service.savePlaylist(null, "另一个歌单", "说明", null, true);
        Playlist updated = service.savePlaylist(301L, "  更新后  ", " 描述 ", " 流行 ", true);

        assertThat(created.getName()).isEqualTo("新歌单");
        assertThat(created.getDescription()).isEmpty();
        assertThat(created.getTheme()).isNull();
        assertThat(created.isPublicVisible()).isFalse();
        assertThat(nullTheme.getTheme()).isNull();
        assertThat(updated).isSameAs(existing);
        assertThat(updated.getName()).isEqualTo("更新后");
        assertThat(updated.getDescription()).isEqualTo("描述");
        assertThat(updated.getTheme()).isEqualTo("流行");
        assertThat(updated.isPublicVisible()).isTrue();
        assertThatThrownBy(() -> service.savePlaylist(null, "  ", null, null, true))
                .hasMessageContaining("歌单名称不能为空");
        assertThatThrownBy(() -> service.savePlaylist(null, null, null, null, true))
                .hasMessageContaining("歌单名称不能为空");
        assertThatThrownBy(() -> service.savePlaylist(302L, "有效名称", null, null, true))
                .hasMessageContaining("歌单不存在");
        verify(playlists).findById(302L);
    }

    @Test
    void retryRejectsActiveTasksAndResetsFailedTasksBeforeDispatch() {
        AiAnalysisTaskRepository tasks = mock(AiAnalysisTaskRepository.class);
        AiAnalysisWorker worker = mock(AiAnalysisWorker.class);
        AiAnalysisTask pending = batchTask(401L, "pending");
        AiAnalysisTask processing = batchTask(402L, "processing");
        AiAnalysisTask failed = batchTask(403L, "failed");
        failed.setErrorMessage("temporary failure");
        when(tasks.findById(401L)).thenReturn(Optional.of(pending));
        when(tasks.findById(402L)).thenReturn(Optional.of(processing));
        when(tasks.findById(403L)).thenReturn(Optional.of(failed));
        when(tasks.findById(404L)).thenReturn(Optional.empty());
        AiLibraryService service = taskService(tasks, mock(SongRepository.class),
                mock(MediaImportRecordRepository.class), worker, taskConfig(false));

        assertThatThrownBy(() -> service.retry(401L)).hasMessageContaining("不能重复分析");
        assertThatThrownBy(() -> service.retry(402L)).hasMessageContaining("不能重复分析");
        assertThatThrownBy(() -> service.retry(404L)).hasMessageContaining("任务不存在");
        assertThat(service.retry(403L)).isSameAs(failed);

        assertThat(failed.getStatus()).isEqualTo("pending");
        assertThat(failed.getErrorMessage()).isNull();
        verify(tasks).save(failed);
        verify(worker).analyze(403L);
    }

    @Test
    void applyUsesStoredSongClassificationAndPersistsAppliedStatus() throws Exception {
        AiAnalysisTaskRepository tasks = mock(AiAnalysisTaskRepository.class);
        SongRepository songs = mock(SongRepository.class);
        Song song = song(405L, "旧标题", "未知歌手");
        AiAnalysisTask task = batchTask(406L, "review");
        task.setSongId(405L);
        AiSongClassification classification = new AiSongClassification("新标题", "新歌手", "国语", "2000年代",
                List.of("流行"), List.of("怀旧"), "全年龄", "独唱", List.of(), "人工确认", 0.99,
                0.99, 0.99, 0.99, 0.99, "组合", Map.of());
        task.setResultJson(new ObjectMapper().writeValueAsString(classification));
        when(tasks.findById(406L)).thenReturn(Optional.of(task));
        when(songs.findById(405L)).thenReturn(Optional.of(song));
        when(songs.save(any(Song.class))).thenAnswer(invocation -> invocation.getArgument(0));
        AiClassificationApplier applier = new AiClassificationApplier(songs, taskConfig(false));
        AiLibraryService service = new AiLibraryService(tasks, songs, mock(PlaylistRepository.class),
                mock(PlaylistSongRepository.class), mock(AiAnalysisWorker.class), new ObjectMapper(),
                taskConfig(false), mock(AssetWriter.class), applier, mock(OpenAiCompatibleClient.class),
                mock(MediaImportRecordRepository.class), mock(AiTaskDispatchFailureRecorder.class));

        Object applied = service.apply(406L, null);

        assertThat(applied).isSameAs(song);
        assertThat(song.getTitle()).isEqualTo("新标题");
        assertThat(song.getArtist()).isEqualTo("新歌手");
        assertThat(song.getLanguage()).isEqualTo("国语");
        assertThat(song.getTags()).contains("流行", "怀旧");
        assertThat(task.getStatus()).isEqualTo("applied");
        assertThat(task.getResultJson()).contains("新标题", "新歌手");
        verify(tasks).save(task);
    }

    @Test
    void applyImportClassificationRejectsInvalidStateAndIdentityThenPersistsConfirmedValues() {
        AiAnalysisTaskRepository tasks = mock(AiAnalysisTaskRepository.class);
        MediaImportRecordRepository imports = mock(MediaImportRecordRepository.class);
        AiAnalysisTask wrongState = batchTask(411L, "failed");
        AiAnalysisTask missingImport = batchTask(412L, "review");
        missingImport.setTargetType("IMPORT_RECORD");
        missingImport.setTargetId(413L);
        AiAnalysisTask blankTitle = batchTask(414L, "review");
        blankTitle.setTargetType("IMPORT_RECORD");
        blankTitle.setTargetId(415L);
        AiAnalysisTask valid = batchTask(416L, "review");
        valid.setTargetType("IMPORT_RECORD");
        valid.setTargetId(417L);
        MediaImportRecord record = new MediaImportRecord();
        record.setId(417L);
        when(tasks.findById(411L)).thenReturn(Optional.of(wrongState));
        when(tasks.findById(412L)).thenReturn(Optional.of(missingImport));
        when(tasks.findById(414L)).thenReturn(Optional.of(blankTitle));
        when(tasks.findById(416L)).thenReturn(Optional.of(valid));
        when(imports.findById(413L)).thenReturn(Optional.empty());
        when(imports.findById(415L)).thenReturn(Optional.of(new MediaImportRecord()));
        when(imports.findById(417L)).thenReturn(Optional.of(record));
        when(imports.save(record)).thenReturn(record);
        AiLibraryService service = taskService(tasks, mock(SongRepository.class), imports,
                mock(AiAnalysisWorker.class), taskConfig(false));
        AiSongClassification missingTitle = new AiSongClassification("  ", "歌手", null, null,
                List.of(), List.of(), null, null, List.of(), null, 0.8,
                0.8, 0.8, 0.8, 0.8, null, Map.of());
        AiSongClassification nullTitle = new AiSongClassification(null, "歌手", null, null,
                List.of(), List.of(), null, null, List.of(), null, 0.8,
                0.8, 0.8, 0.8, 0.8, null, Map.of());
        AiSongClassification confirmed = new AiSongClassification("  确认歌名  ", "  确认歌手  ", null, null,
                List.of(), List.of(), null, null, List.of(), "人工确认", 0.8,
                0.8, 0.8, 0.8, 0.8, null, Map.of());

        assertThatThrownBy(() -> service.apply(411L, confirmed)).hasMessageContaining("不处于待确认状态");
        assertThatThrownBy(() -> service.apply(412L, confirmed)).hasMessageContaining("导入记录不存在");
        assertThatThrownBy(() -> service.apply(414L, missingTitle)).hasMessageContaining("歌名不能为空");
        assertThatThrownBy(() -> service.apply(414L, nullTitle)).hasMessageContaining("歌名不能为空");
        assertThat(service.apply(416L, confirmed)).isSameAs(record);

        assertThat(record.getParsedTitle()).isEqualTo("确认歌名");
        assertThat(record.getParsedArtist()).isEqualTo("确认歌手");
        assertThat(record.getReason()).contains("人工确认 AI 文件身份：人工确认");
        assertThat(valid.getStatus()).isEqualTo("applied");
        verify(imports).save(record);
        verify(tasks).save(valid);
        verify(tasks, never()).save(wrongState);
    }

    @Test
    void applyImportClassificationDefaultsMissingArtistAndReasonToEmptyText() {
        AiAnalysisTaskRepository tasks = mock(AiAnalysisTaskRepository.class);
        MediaImportRecordRepository imports = mock(MediaImportRecordRepository.class);
        AiAnalysisTask task = batchTask(1421L, "review");
        task.setTargetType("IMPORT_RECORD");
        task.setTargetId(1422L);
        MediaImportRecord record = new MediaImportRecord();
        when(tasks.findById(1421L)).thenReturn(Optional.of(task));
        when(imports.findById(1422L)).thenReturn(Optional.of(record));
        when(imports.save(record)).thenReturn(record);
        AiLibraryService service = taskService(tasks, mock(SongRepository.class), imports,
                mock(AiAnalysisWorker.class), taskConfig(false));
        AiSongClassification classification = new AiSongClassification("确定歌名", null, null, null,
                List.of(), List.of(), null, null, List.of(), null, 0.8,
                0.8, 0.8, 0.8, 0.8, null, Map.of());

        assertThat(service.apply(1421L, classification)).isSameAs(record);

        assertThat(record.getParsedTitle()).isEqualTo("确定歌名");
        assertThat(record.getParsedArtist()).isEmpty();
        assertThat(record.getReason()).isEqualTo("人工确认 AI 文件身份：");
        assertThat(task.getStatus()).isEqualTo("applied");
    }

    @Test
    void previewPlaylistRejectsMissingConfigurationBlankInstructionsAndEmptyCatalogues() throws Exception {
        AiConfigService disabledConfig = mock(AiConfigService.class);
        doThrow(new com.homektv.web.ApiException("AI_DISABLED", "AI disabled"))
                .when(disabledConfig).requireConfigured();
        OpenAiCompatibleClient disabledClient = mock(OpenAiCompatibleClient.class);
        AiLibraryService disabledService = previewService(mock(SongRepository.class), disabledClient,
                disabledConfig, new ObjectMapper());
        assertThatThrownBy(() -> disabledService.previewPlaylist("怀旧歌单", 10))
                .hasMessageContaining("请先配置 AI 模型");
        verifyNoInteractions(disabledClient);

        OpenAiCompatibleClient blankInstructionClient = mock(OpenAiCompatibleClient.class);
        AiLibraryService blankInstructionService = previewService(mock(SongRepository.class),
                blankInstructionClient, previewConfig(""), new ObjectMapper());
        assertThatThrownBy(() -> blankInstructionService.previewPlaylist("  ", 10))
                .hasMessageContaining("请描述想要的歌单");
        assertThatThrownBy(() -> blankInstructionService.previewPlaylist(null, 10))
                .hasMessageContaining("请描述想要的歌单");
        verifyNoInteractions(blankInstructionClient);

        SongRepository emptySongs = mock(SongRepository.class);
        when(emptySongs.findAll(any(Pageable.class))).thenReturn(new PageImpl<>(List.of()));
        OpenAiCompatibleClient emptyClient = mock(OpenAiCompatibleClient.class);
        AiLibraryService emptyService = previewService(emptySongs, emptyClient, previewConfig(""), new ObjectMapper());
        assertThatThrownBy(() -> emptyService.previewPlaylist("怀旧歌单", 0))
                .hasMessageContaining("没有可用于生成歌单的歌曲");
        verifyNoInteractions(emptyClient);

        SongRepository unavailablePage = mock(SongRepository.class);
        when(unavailablePage.findAll(any(Pageable.class))).thenReturn(null);
        assertThatThrownBy(() -> previewService(unavailablePage, mock(OpenAiCompatibleClient.class),
                previewConfig(""), new ObjectMapper()).previewPlaylist("怀旧歌单", 10))
                .hasMessageContaining("没有可用于生成歌单的歌曲");
    }

    @Test
    void previewPlaylistRetriesInvalidJsonWithReasoningModelAndFiltersSelectionIds() throws Exception {
        SongRepository songs = mock(SongRepository.class);
        OpenAiCompatibleClient client = mock(OpenAiCompatibleClient.class);
        ObjectMapper mapper = new ObjectMapper();
        when(songs.findAll(any(Pageable.class))).thenReturn(new PageImpl<>(List.of(
                song(501L, "第一首", "甲"), song(502L, "第二首", "乙"), song(503L, "第三首", "丙"))));
        when(client.completeJsonPromptOnly(anyString(), anyString(), anyString(), anyInt()))
                .thenReturn(mapper.readTree("{\"intent\":\"聚会\"}"))
                .thenThrow(new OpenAiCompatibleClient.AiProviderException("AI_JSON_INVALID", "bad json", null))
                .thenReturn(mapper.readTree("{\"name\":\"聚会精选\",\"songIds\":[501,501,999,{\"song_id\":502},{\"id\":503},{}] }"));
        AiLibraryService service = previewService(songs, client, previewConfig("reasoning-model"), mapper);

        Map<String, Object> result = service.previewPlaylist("轻松聚会", 2);

        assertThat(result).containsEntry("name", "聚会精选").containsEntry("limit", 2)
                .containsEntry("songIds", List.of(501L, 502L)).containsEntry("selectedCount", 2);
        ArgumentCaptor<String> roles = ArgumentCaptor.forClass(String.class);
        verify(client, times(3)).completeJsonPromptOnly(roles.capture(), anyString(), anyString(), anyInt());
        assertThat(roles.getAllValues()).containsExactly("BULK", "BULK", "REASONING");
    }

    @Test
    void previewPlaylistUsesEmptyResultDefaultsWhenProviderReturnsNoSelection() throws Exception {
        SongRepository songs = mock(SongRepository.class);
        OpenAiCompatibleClient client = mock(OpenAiCompatibleClient.class);
        ObjectMapper mapper = new ObjectMapper();
        when(songs.findAll(any(Pageable.class))).thenReturn(new PageImpl<>(List.of(song(601L, "歌曲", "歌手"))));
        JsonNode intent = mapper.readTree("{\"intent\":\"测试\"}");
        when(client.completeJsonPromptOnly(anyString(), anyString(), anyString(), anyInt()))
                .thenReturn(intent).thenAnswer(invocation -> null);

        Map<String, Object> result = previewService(songs, client, previewConfig("reasoning-model"), mapper)
                .previewPlaylist("测试", -1);

        assertThat(result).containsEntry("name", "AI 歌单").containsEntry("description", "")
                .containsEntry("songIds", List.of()).containsEntry("reasons", mapper.createArrayNode())
                .containsEntry("limit", 1).containsEntry("selectedCount", 0);
        verify(client, times(3)).completeJsonPromptOnly(anyString(), anyString(), anyString(), anyInt());
    }

    @Test
    void previewPlaylistSkipsInvalidNestedMetadataAndRejectsNonArraySongIds() throws Exception {
        SongRepository songs = mock(SongRepository.class);
        OpenAiCompatibleClient client = mock(OpenAiCompatibleClient.class);
        ObjectMapper mapper = new ObjectMapper();
        when(songs.findAll(any(Pageable.class))).thenReturn(new PageImpl<>(List.of(song(611L, "候选歌曲", "歌手"))));
        when(client.completeJsonPromptOnly(anyString(), anyString(), anyString(), anyInt())).thenReturn(
                mapper.readTree("{\"intent\":\"嵌套响应\"}"),
                mapper.readTree("{\"playlist\":{\"name\":\"无 ID 的说明\"},\"result\":{\"name\":\"有效嵌套\",\"songIds\":\"不是数组\"}}"));

        Map<String, Object> result = previewService(songs, client, previewConfig(""), mapper)
                .previewPlaylist("嵌套响应", 10);

        assertThat(result).containsEntry("name", "有效嵌套").containsEntry("songIds", List.of())
                .containsEntry("selectedCount", 0);
    }

    @Test
    void previewPlaylistPropagatesProviderFailuresOutsideTheJsonRecoveryCodes() throws Exception {
        SongRepository songs = mock(SongRepository.class);
        OpenAiCompatibleClient client = mock(OpenAiCompatibleClient.class);
        ObjectMapper mapper = new ObjectMapper();
        when(songs.findAll(any(Pageable.class))).thenReturn(new PageImpl<>(List.of(song(612L, "候选歌曲", "歌手"))));
        OpenAiCompatibleClient.AiProviderException unavailable =
                new OpenAiCompatibleClient.AiProviderException("AI_UNAVAILABLE", "provider unavailable", null);
        when(client.completeJsonPromptOnly(anyString(), anyString(), anyString(), anyInt()))
                .thenReturn(mapper.readTree("{\"intent\":\"测试\"}")).thenThrow(unavailable);

        assertThatThrownBy(() -> previewService(songs, client, previewConfig("reasoning-model"), mapper)
                .previewPlaylist("测试", 1)).isSameAs(unavailable);
        verify(client, times(2)).completeJsonPromptOnly(anyString(), anyString(), anyString(), anyInt());
    }

    @Test
    void previewPlaylistDoesNotRecoverJsonErrorsWithoutAReasoningModel() throws Exception {
        for (String reasoningModel : Arrays.asList(null, "")) {
            SongRepository songs = mock(SongRepository.class);
            OpenAiCompatibleClient client = mock(OpenAiCompatibleClient.class);
            ObjectMapper mapper = new ObjectMapper();
            when(songs.findAll(any(Pageable.class)))
                    .thenReturn(new PageImpl<>(List.of(song(614L, "候选歌曲", "歌手"))));
            OpenAiCompatibleClient.AiProviderException invalid =
                    new OpenAiCompatibleClient.AiProviderException("AI_JSON_INVALID", "invalid", null);
            when(client.completeJsonPromptOnly(anyString(), anyString(), anyString(), anyInt()))
                    .thenReturn(mapper.readTree("{\"intent\":\"测试\"}")).thenThrow(invalid);

            assertThatThrownBy(() -> previewService(songs, client, previewConfig(reasoningModel), mapper)
                    .previewPlaylist("测试", 1)).isSameAs(invalid);
            verify(client, times(2)).completeJsonPromptOnly(anyString(), anyString(), anyString(), anyInt());
        }
    }

    @Test
    void previewPlaylistHandlesMissingNodeAsAnEmptyProviderResult() throws Exception {
        SongRepository songs = mock(SongRepository.class);
        OpenAiCompatibleClient client = mock(OpenAiCompatibleClient.class);
        ObjectMapper mapper = new ObjectMapper();
        when(songs.findAll(any(Pageable.class))).thenReturn(new PageImpl<>(List.of(song(613L, "候选歌曲", "歌手"))));
        when(client.completeJsonPromptOnly(anyString(), anyString(), anyString(), anyInt())).thenReturn(
                mapper.readTree("{\"intent\":\"测试\"}"), MissingNode.getInstance(), mapper.nullNode());

        Map<String, Object> result = previewService(songs, client, previewConfig("reasoning-model"), mapper)
                .previewPlaylist("测试", 1);

        assertThat(result).containsEntry("name", "AI 歌单").containsEntry("songIds", List.of());
        verify(client, times(3)).completeJsonPromptOnly(anyString(), anyString(), anyString(), anyInt());
    }

    @Test
    void pauseRepairBatchPausesAndReportsAllStatusCategories() {
        String batchId = "pause-batch";
        AiAnalysisTaskRepository tasks = mock(AiAnalysisTaskRepository.class);
        List<AiAnalysisTask> batch = List.of(batchTask(701L, "auto_applied"), batchTask(702L, "review"),
                batchTask(703L, "failed"), batchTask(704L, "applied"), batchTask(705L, "paused"),
                batchTask(706L, "pending"), batchTask(707L, "processing"));
        when(tasks.findByBatchIdOrderByCreatedAtAsc(batchId)).thenReturn(batch);
        AiLibraryService service = taskService(tasks, mock(SongRepository.class),
                mock(MediaImportRecordRepository.class), mock(AiAnalysisWorker.class), taskConfig(false));

        Map<String, Object> result = service.pauseRepairBatch(batchId);

        assertThat(result).containsEntry("batchId", batchId).containsEntry("total", 7)
                .containsEntry("completed", 4L).containsEntry("failed", 1L).containsEntry("review", 1L)
                .containsEntry("paused", 1L).containsEntry("running", true);
        verify(tasks).pauseBatch(batchId);
        verify(tasks, times(2)).findByBatchIdOrderByCreatedAtAsc(batchId);
    }

    @Test
    void deletePlaylistRemovesThePlaylistAndSchedulesItsCoverForCleanup() {
        PlaylistRepository playlists = mock(PlaylistRepository.class);
        AssetCleanupService cleanup = mock(AssetCleanupService.class);
        Playlist playlist = new Playlist();
        playlist.setId(711L);
        playlist.setCoverPath("playlist-covers/711.webp");
        when(playlists.findById(711L)).thenReturn(Optional.of(playlist));
        AiLibraryService service = service(mock(SongRepository.class), playlists,
                mock(PlaylistSongRepository.class), mock(OpenAiCompatibleClient.class), new ObjectMapper());
        service.setAssetCleanupService(cleanup);

        service.deletePlaylist(711L);

        verify(playlists).delete(playlist);
        verify(cleanup).afterCommitIfUnreferenced("playlist-covers/711.webp");
    }

    @Test
    void deletePlaylistStillDeletesWhenOptionalCleanupServiceIsUnavailable() {
        PlaylistRepository playlists = mock(PlaylistRepository.class);
        Playlist playlist = new Playlist();
        when(playlists.findById(712L)).thenReturn(Optional.of(playlist));
        AiLibraryService service = service(mock(SongRepository.class), playlists,
                mock(PlaylistSongRepository.class), mock(OpenAiCompatibleClient.class), new ObjectMapper());

        service.deletePlaylist(712L);

        verify(playlists).delete(playlist);
    }

    @Test
    void savePlaylistFromPreviewRejectsInvalidSelectionListsBeforeDatabaseAccess() {
        SongRepository songs = mock(SongRepository.class);
        PlaylistRepository playlists = mock(PlaylistRepository.class);
        PlaylistSongRepository playlistSongs = mock(PlaylistSongRepository.class);
        AiLibraryService service = service(songs, playlists, playlistSongs,
                mock(OpenAiCompatibleClient.class), new ObjectMapper());

        assertThatThrownBy(() -> service.savePlaylistFromPreview(" ", "", "", true, List.of(1L)))
                .hasMessageContaining("歌单名称不能为空");
        assertThatThrownBy(() -> service.savePlaylistFromPreview(null, "", "", true, List.of(1L)))
                .hasMessageContaining("歌单名称不能为空");
        assertThatThrownBy(() -> service.savePlaylistFromPreview("歌单", "", "", true, null))
                .hasMessageContaining("至少选择一首歌曲");
        assertThatThrownBy(() -> service.savePlaylistFromPreview("歌单", "", "", true, List.of()))
                .hasMessageContaining("至少选择一首歌曲");
        assertThatThrownBy(() -> service.savePlaylistFromPreview("歌单", "", "", true,
                Arrays.asList(1L, null))).hasMessageContaining("歌曲 ID 无效");
        assertThatThrownBy(() -> service.savePlaylistFromPreview("歌单", "", "", true, List.of(0L)))
                .hasMessageContaining("歌曲 ID 无效");
        assertThatThrownBy(() -> service.savePlaylistFromPreview("歌单", "", "", true,
                java.util.stream.LongStream.rangeClosed(1, 101).boxed().toList()))
                .hasMessageContaining("最多包含 100 首");
        verifyNoInteractions(songs, playlists, playlistSongs);
    }

    @Test
    void savePlaylistFromPreviewDefaultsMissingDescriptionAndTheme() {
        SongRepository songs = mock(SongRepository.class);
        PlaylistRepository playlists = mock(PlaylistRepository.class);
        PlaylistSongRepository playlistSongs = mock(PlaylistSongRepository.class);
        Playlist playlist = new Playlist();
        playlist.setId(1251L);
        when(songs.findAllById(List.of(1252L))).thenReturn(List.of(song(1252L, "选中歌曲", "歌手")));
        when(playlists.save(any(Playlist.class))).thenAnswer(invocation -> {
            Playlist saved = invocation.getArgument(0);
            saved.setId(playlist.getId());
            return saved;
        });
        AiLibraryService service = service(songs, playlists, playlistSongs,
                mock(OpenAiCompatibleClient.class), new ObjectMapper());

        Playlist saved = service.savePlaylistFromPreview("  预览歌单  ", null, "  ", true, List.of(1252L));
        Playlist nullTheme = service.savePlaylistFromPreview("空主题", null, null, true, List.of(1252L));

        assertThat(saved.getName()).isEqualTo("预览歌单");
        assertThat(saved.getDescription()).isEmpty();
        assertThat(saved.getTheme()).isEqualTo("AI 策划");
        assertThat(saved.isAiGenerated()).isTrue();
        assertThat(nullTheme.getTheme()).isEqualTo("AI 策划");
        ArgumentCaptor<List<PlaylistSong>> associations = ArgumentCaptor.forClass(List.class);
        verify(playlistSongs, times(2)).saveAll(associations.capture());
        assertThat(associations.getAllValues().getFirst()).singleElement().satisfies(item -> {
            assertThat(item.getSortOrder()).isZero();
            assertThat(item.isManual()).isFalse();
        });
    }

    @Test
    void uploadPlaylistCoverRejectsMissingOversizedAndUnsupportedImages() {
        PlaylistRepository playlists = mock(PlaylistRepository.class);
        Playlist playlist = new Playlist();
        playlist.setId(721L);
        when(playlists.findById(721L)).thenReturn(Optional.of(playlist));
        AssetWriter writer = mock(AssetWriter.class);
        AiLibraryService service = service(mock(SongRepository.class), playlists,
                mock(PlaylistSongRepository.class), mock(OpenAiCompatibleClient.class), new ObjectMapper(), writer);
        MultipartFile oversized = mock(MultipartFile.class);
        when(oversized.isEmpty()).thenReturn(false);
        when(oversized.getSize()).thenReturn(5L * 1024 * 1024 + 1);
        MultipartFile unsupported = mock(MultipartFile.class);
        when(unsupported.isEmpty()).thenReturn(false);
        when(unsupported.getSize()).thenReturn(1L);
        when(unsupported.getContentType()).thenReturn("application/octet-stream");
        MultipartFile missingContentType = mock(MultipartFile.class);
        when(missingContentType.isEmpty()).thenReturn(false);
        when(missingContentType.getSize()).thenReturn(1L);

        assertThatThrownBy(() -> service.uploadPlaylistCover(721L, null)).hasMessageContaining("请选择封面图片");
        assertThatThrownBy(() -> service.uploadPlaylistCover(721L,
                new MockMultipartFile("file", "empty.png", "image/png", new byte[0])))
                .hasMessageContaining("请选择封面图片");
        assertThatThrownBy(() -> service.uploadPlaylistCover(721L, oversized)).hasMessageContaining("不能超过 5MB");
        assertThatThrownBy(() -> service.uploadPlaylistCover(721L, unsupported))
                .hasMessageContaining("仅支持 JPG、PNG 或 WebP");
        assertThatThrownBy(() -> service.uploadPlaylistCover(721L, missingContentType))
                .hasMessageContaining("仅支持 JPG、PNG 或 WebP");
        verifyNoInteractions(writer);
        verify(playlists, never()).save(any(Playlist.class));
    }

    @Test
    void uploadPlaylistCoverNormalizesImagesAndTranslatesProcessingFailures() throws Exception {
        PlaylistRepository playlists = mock(PlaylistRepository.class);
        Playlist playlist = new Playlist();
        playlist.setId(722L);
        when(playlists.findById(722L)).thenReturn(Optional.of(playlist));
        when(playlists.save(any(Playlist.class))).thenAnswer(invocation -> invocation.getArgument(0));
        AssetWriter writer = mock(AssetWriter.class);
        when(writer.writePlaylistCover(eq(722L), any(), eq("jpg")))
                .thenReturn("playlist-covers/722.jpg");
        CoverImageNormalizer normalizer = mock(CoverImageNormalizer.class);
        when(normalizer.normalize(any())).thenReturn(new byte[]{9})
                .thenThrow(new com.homektv.web.ApiException("IMAGE_BUSY", "busy"));
        AiLibraryService service = service(mock(SongRepository.class), playlists,
                mock(PlaylistSongRepository.class), mock(OpenAiCompatibleClient.class), new ObjectMapper(), writer);
        service.setCoverImageNormalizer(normalizer);
        MockMultipartFile upload = new MockMultipartFile("file", "cover.webp", "image/webp", new byte[]{1, 2});

        assertThat(service.uploadPlaylistCover(722L, upload).getCoverPath()).isEqualTo("playlist-covers/722.jpg");
        assertThatThrownBy(() -> service.uploadPlaylistCover(722L, upload))
                .hasMessageContaining("封面图片无法识别或尺寸过大");
        verify(writer).writePlaylistCover(eq(722L), argThat(bytes -> Arrays.equals(bytes, new byte[]{9})), eq("jpg"));
        verify(playlists, times(1)).save(playlist);
    }

    @Test
    void playlistCandidateOmitsBlankMetadataAndIgnoresMalformedProvenance() {
        Song song = song(631L, null, null);
        song.setLanguage("未知");
        song.setVocalForm("未知");
        song.setAliases(null);
        song.setTags(new String[]{" ", null, "流行", "流行"});
        song.setAiGenres(new String[0]);
        song.setAiThemes(null);
        song.setMetadataProvenance("{invalid-json");

        Map<String, Object> candidate = service().playlistCandidate(song);

        assertThat(candidate).containsEntry("id", 631L).containsEntry("title", "").containsEntry("artist", "")
                .containsEntry("metadataScraped", false).containsEntry("tags", List.of("流行"));
        assertThat(candidate).doesNotContainKeys("language", "vocalForm", "aliases", "genres", "themes",
                "durationSeconds", "metadataSources");
        song.setMetadataProvenance(null);
        assertThat(service().playlistCandidate(song)).containsEntry("metadataScraped", false);
        song.setMetadataProvenance("  ");
        assertThat(service().playlistCandidate(song)).containsEntry("metadataScraped", false);
    }

    @Test
    void playlistDetailRepresentsMissingSongsAndReturnsExistingSongMetadata() {
        PlaylistRepository playlists = mock(PlaylistRepository.class);
        PlaylistSongRepository items = mock(PlaylistSongRepository.class);
        SongRepository songs = mock(SongRepository.class);
        Playlist playlist = new Playlist();
        playlist.setId(641L);
        playlist.setName("详情歌单");
        when(playlists.findById(641L)).thenReturn(Optional.of(playlist));
        when(items.findByPlaylistIdOrderBySortOrder(641L))
                .thenReturn(List.of(playlistSong(641L, 1L, 0), playlistSong(641L, 2L, 1)));
        when(songs.findById(1L)).thenReturn(Optional.of(song(1L, "保留歌曲", "歌手甲")));
        when(songs.findById(2L)).thenReturn(Optional.empty());
        Map<String, Object> detail = service(songs, playlists, items,
                mock(OpenAiCompatibleClient.class), new ObjectMapper()).playlistDetail(641L);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> listedSongs = (List<Map<String, Object>>) detail.get("songs");
        assertThat(detail).containsEntry("id", 641L).containsEntry("name", "详情歌单").containsEntry("coverUrl", null);
        assertThat(listedSongs).hasSize(2);
        assertThat(listedSongs.get(0)).containsEntry("songId", 1L).containsEntry("title", "保留歌曲")
                .containsEntry("artist", "歌手甲").containsEntry("manual", false).containsEntry("sortOrder", 0);
        assertThat(listedSongs.get(1)).containsEntry("songId", 2L).containsEntry("title", "歌曲已删除")
                .containsEntry("artist", "");
        playlist.setCoverPath("playlist-covers/641.jpg");
        assertThat(service(songs, playlists, items, mock(OpenAiCompatibleClient.class), new ObjectMapper())
                .playlistDetail(641L)).containsEntry("coverUrl", "/api/playlists/641/cover");
    }

    @Test
    void reorderPlaylistSongsRejectsIncompleteDuplicateAndForeignIds() {
        PlaylistRepository playlists = mock(PlaylistRepository.class);
        PlaylistSongRepository items = mock(PlaylistSongRepository.class);
        Playlist playlist = new Playlist();
        when(playlists.findById(651L)).thenReturn(Optional.of(playlist));
        when(items.findByPlaylistIdOrderBySortOrder(651L)).thenReturn(
                List.of(playlistSong(651L, 1L, 0), playlistSong(651L, 2L, 1)));
        AiLibraryService service = service(mock(SongRepository.class), playlists, items,
                mock(OpenAiCompatibleClient.class), new ObjectMapper());

        assertThatThrownBy(() -> service.reorderPlaylistSongs(651L, null)).hasMessageContaining("列表不完整");
        assertThatThrownBy(() -> service.reorderPlaylistSongs(651L, List.of(1L))).hasMessageContaining("列表不完整");
        assertThatThrownBy(() -> service.reorderPlaylistSongs(651L, List.of(1L, 1L))).hasMessageContaining("包含重复项");
        assertThatThrownBy(() -> service.reorderPlaylistSongs(651L, List.of(1L, 3L))).hasMessageContaining("不属于该歌单");
        verify(items, never()).saveAll(anyList());
    }

    @Test
    void uploadPlaylistCoverMapsSupportedMimeTypesAndReportsReadFailures() throws Exception {
        PlaylistRepository playlists = mock(PlaylistRepository.class);
        Playlist playlist = new Playlist();
        playlist.setId(661L);
        when(playlists.findById(661L)).thenReturn(Optional.of(playlist));
        when(playlists.save(any(Playlist.class))).thenAnswer(invocation -> invocation.getArgument(0));
        AssetWriter writer = mock(AssetWriter.class);
        when(writer.writePlaylistCover(eq(661L), any(), anyString())).thenAnswer(invocation ->
                "playlist-covers/661." + invocation.getArgument(2, String.class));
        AiLibraryService service = service(mock(SongRepository.class), playlists,
                mock(PlaylistSongRepository.class), mock(OpenAiCompatibleClient.class), new ObjectMapper(), writer);

        assertThat(service.uploadPlaylistCover(661L,
                new MockMultipartFile("file", "a.jpg", "image/jpeg", new byte[]{1})).getCoverPath())
                .isEqualTo("playlist-covers/661.jpg");
        assertThat(service.uploadPlaylistCover(661L,
                new MockMultipartFile("file", "b.jpg", "image/jpg", new byte[]{1})).getCoverPath())
                .isEqualTo("playlist-covers/661.jpg");
        assertThat(service.uploadPlaylistCover(661L,
                new MockMultipartFile("file", "c.webp", "image/webp", new byte[]{1})).getCoverPath())
                .isEqualTo("playlist-covers/661.webp");

        MultipartFile unreadable = mock(MultipartFile.class);
        when(unreadable.isEmpty()).thenReturn(false);
        when(unreadable.getSize()).thenReturn(1L);
        when(unreadable.getContentType()).thenReturn("image/jpeg");
        when(unreadable.getBytes()).thenThrow(new java.io.IOException("read failure"));
        assertThatThrownBy(() -> service.uploadPlaylistCover(661L, unreadable)).hasMessageContaining("封面保存失败");
        verify(playlists, times(3)).save(playlist);
        verify(writer, times(3)).writePlaylistCover(eq(661L), any(), anyString());
    }

    @Test
    void playlistCandidateContainsTrustedScrapedMetadata() {
        Song song = new Song();
        song.setId(36L);
        song.setTitle("刮削歌名");
        song.setArtist("刮削歌手");
        song.setLanguage("粤语");
        song.setVocalForm("对唱");
        song.setAlbum("平台专辑");
        song.setReleaseDate("1999-01-01");
        song.setAliases(new String[]{"歌曲别名"});
        song.setTags(new String[]{"聚会"});
        song.setAiGenres(new String[]{"流行"});
        song.setAiThemes(new String[]{"怀旧"});
        song.setAiEra("90年代");
        song.setDurationMs(210_000);
        song.setMetadataProvenance("""
                {"title":{"source":"QQ","externalId":"qq-36","trusted":true},
                 "album":{"source":"NETEASE","externalId":"ne-36","trusted":true},
                  "aliases":{"source":"UNTRUSTED","trusted":false},
                  "cover":{"source":"  ","trusted":true}}
                """);

        Map<String, Object> candidate = service().playlistCandidate(song);

        assertThat(candidate).containsEntry("id", 36L)
                .containsEntry("album", "平台专辑")
                .containsEntry("releaseDate", "1999-01-01")
                .containsEntry("metadataScraped", true)
                .containsEntry("durationSeconds", 210L);
        assertThat(candidate.get("aliases")).isEqualTo(java.util.List.of("歌曲别名"));
        assertThat(candidate.get("genres")).isEqualTo(java.util.List.of("流行"));
        assertThat(candidate.get("themes")).isEqualTo(java.util.List.of("怀旧"));
        assertThat(candidate.get("metadataSources")).isEqualTo(Map.of("title", "QQ", "album", "NETEASE"));
    }

    @Test
    void playlistPreviewConsidersPreferredSongsBeyondTheFirstFiveThousandRows() throws Exception {
        SongRepository songRepository = mock(SongRepository.class);
        OpenAiCompatibleClient aiClient = mock(OpenAiCompatibleClient.class);
        ObjectMapper objectMapper = new ObjectMapper();

        when(songRepository.findAll(any(Pageable.class))).thenAnswer(invocation -> {
            Pageable pageable = invocation.getArgument(0, Pageable.class);
            int firstId = pageable.getPageNumber() * pageable.getPageSize() + 1;
            int lastId = Math.min(firstId + pageable.getPageSize() - 1, 5002);
            List<Song> content = java.util.stream.IntStream.rangeClosed(firstId, lastId)
                    .mapToObj(id -> {
                        Song song = song(id, "歌曲" + id, "歌手");
                        if (id == 5001) {
                            song.setAiAnalyzedAt(OffsetDateTime.parse("2026-01-01T00:00:00Z"));
                        }
                        return song;
                    })
                    .toList();
            return new PageImpl<>(content, pageable, 5002);
        });
        when(aiClient.completeJsonPromptOnly(anyString(), anyString(), anyString(), anyInt())).thenReturn(
                objectMapper.readTree("{\"intent\":\"优先可信元数据\"}"),
                objectMapper.readTree("{\"name\":\"边界歌单\",\"songIds\":[5001]}"));

        Map<String, Object> result = service(songRepository, mock(PlaylistRepository.class),
                mock(PlaylistSongRepository.class), aiClient, objectMapper)
                .previewPlaylist("优先可信元数据", 1);

        assertThat(result).containsEntry("songIds", List.of(5001L))
                .containsEntry("selectedCount", 1)
                .containsEntry("candidateCount", 5000);
    }

    @Test
    void playlistPreviewAcceptsFewerSongsAndCommonNestedResponse() throws Exception {
        SongRepository songRepository = mock(SongRepository.class);
        OpenAiCompatibleClient aiClient = mock(OpenAiCompatibleClient.class);
        ObjectMapper objectMapper = new ObjectMapper();
        Song first = song(1L, "第一首", "歌手甲");
        Song second = song(2L, "第二首", "歌手乙");
        Song third = song(3L, "第三首", "歌手丙");
        when(songRepository.findAll(any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(first, second, third)));
        when(songRepository.findAll()).thenThrow(new AssertionError("unpaged song query is forbidden"));
        when(aiClient.completeJsonPromptOnly(anyString(), anyString(), anyString(), anyInt())).thenReturn(
                objectMapper.readTree("{\"intent\":\"轻松聚会\",\"maxSongs\":100}"),
                objectMapper.readTree("{\"playlist\":{\"playlistName\":\"少而精\",\"songs\":[{\"songId\":1},{\"id\":2},{\"id\":999}]}}"));

        Map<String, Object> result = service(songRepository, mock(PlaylistRepository.class),
                mock(PlaylistSongRepository.class), aiClient, objectMapper).previewPlaylist("轻松聚会", 500);

        assertThat(result).containsEntry("name", "少而精")
                .containsEntry("limit", 100)
                .containsEntry("selectedCount", 2);
        assertThat(result.get("songIds")).isEqualTo(List.of(1L, 2L));
        verify(songRepository, never()).findAll();
    }

    @Test
    void generatePlaylistKeepsManualSongsAndReplacesOnlyMatchingGeneratedSongs() {
        SongRepository songRepository = mock(SongRepository.class);
        PlaylistRepository playlistRepository = mock(PlaylistRepository.class);
        PlaylistSongRepository playlistSongRepository = mock(PlaylistSongRepository.class);
        Playlist playlist = new Playlist();
        playlist.setId(77L);
        PlaylistSong manual = playlistSong(77L, 5L, 4);
        manual.setManual(true);
        when(playlistRepository.findByName("怀旧曲单")).thenReturn(Optional.of(playlist));
        when(playlistRepository.save(any(Playlist.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(playlistSongRepository.findByPlaylistIdOrderBySortOrder(77L)).thenReturn(List.of(manual));

        Song duplicateManual = song(5L, "手工保留", "歌手甲");
        duplicateManual.setAiEra("怀旧");
        Song matchingEra = song(6L, "年代匹配", "歌手乙");
        matchingEra.setAiEra("怀旧");
        Song nonMatching = song(7L, "不匹配", "歌手丙");
        nonMatching.setAiEra("现代");
        Song matchingTag = song(8L, "标签匹配", "歌手丁");
        matchingTag.setTags(new String[]{"怀旧"});
        when(songRepository.findAll(any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(duplicateManual, matchingEra, nonMatching, matchingTag)));

        AiLibraryService service = service(songRepository, playlistRepository, playlistSongRepository,
                mock(OpenAiCompatibleClient.class), new ObjectMapper());

        Playlist result = service.generatePlaylist("  怀旧曲单  ", " 怀旧 ", 3);

        assertThat(result).isSameAs(playlist);
        assertThat(playlist.getName()).isEqualTo("怀旧曲单");
        assertThat(playlist.getTheme()).isEqualTo("怀旧");
        assertThat(playlist.getDescription()).contains("怀旧");
        assertThat(playlist.isAiGenerated()).isTrue();
        assertThat(playlist.getAiRule()).contains("\"tag\":\"怀旧\"", "\"limit\":3");
        verify(playlistRepository).lockGeneratedName("怀旧曲单");
        verify(playlistSongRepository).deleteByPlaylistIdAndManualFalse(77L);

        ArgumentCaptor<PlaylistSong> generated = ArgumentCaptor.forClass(PlaylistSong.class);
        verify(playlistSongRepository, times(2)).save(generated.capture());
        assertThat(generated.getAllValues()).extracting(PlaylistSong::getSongId).containsExactly(6L, 8L);
        assertThat(generated.getAllValues()).extracting(PlaylistSong::getSortOrder).containsExactly(5, 6);
        assertThat(manual.isManual()).isTrue();
    }

    @Test
    void generatePlaylistCreatesAnEmptyPlaylistAndClampsTheRequestedLimit() {
        SongRepository songRepository = mock(SongRepository.class);
        PlaylistRepository playlistRepository = mock(PlaylistRepository.class);
        PlaylistSongRepository playlistSongRepository = mock(PlaylistSongRepository.class);
        when(playlistRepository.findByName("空歌单")).thenReturn(Optional.empty());
        when(playlistRepository.save(any(Playlist.class))).thenAnswer(invocation -> {
            Playlist playlist = invocation.getArgument(0);
            playlist.setId(88L);
            return playlist;
        });
        when(playlistSongRepository.findByPlaylistIdOrderBySortOrder(88L)).thenReturn(List.of());
        when(songRepository.findAll(any(Pageable.class))).thenReturn(new PageImpl<>(List.of()));
        AiLibraryService service = service(songRepository, playlistRepository, playlistSongRepository,
                mock(OpenAiCompatibleClient.class), new ObjectMapper());

        Playlist result = service.generatePlaylist("空歌单", "流行", 0);

        assertThat(result.getId()).isEqualTo(88L);
        assertThat(result.isAiGenerated()).isTrue();
        assertThat(result.getAiRule()).contains("\"limit\":1");
        verify(playlistSongRepository, never()).save(any(PlaylistSong.class));
    }

    @Test
    void generatePlaylistRejectsBlankNameOrTagBeforeTouchingRepositories() {
        SongRepository songRepository = mock(SongRepository.class);
        PlaylistRepository playlistRepository = mock(PlaylistRepository.class);
        PlaylistSongRepository playlistSongRepository = mock(PlaylistSongRepository.class);
        AiLibraryService service = service(songRepository, playlistRepository, playlistSongRepository,
                mock(OpenAiCompatibleClient.class), new ObjectMapper());

        assertThatThrownBy(() -> service.generatePlaylist("  ", "流行", 10))
                .hasMessageContaining("歌单名称和 AI 标签不能为空");
        assertThatThrownBy(() -> service.generatePlaylist("歌单", "\t", 10))
                .hasMessageContaining("歌单名称和 AI 标签不能为空");
        verifyNoInteractions(songRepository, playlistRepository, playlistSongRepository);
    }

    @Test
    void generatePlaylistMatchesLanguageAgeVocalFormGenreAndThemeFields() {
        Map<String, Consumer<Song>> matchingFields = new java.util.LinkedHashMap<>();
        matchingFields.put("粤语", candidate -> candidate.setAiLanguage("粤语"));
        matchingFields.put("全年龄", candidate -> candidate.setAiAgeRange("全年龄"));
        matchingFields.put("对唱", candidate -> candidate.setAiVocalForm("对唱"));
        matchingFields.put("爵士", candidate -> candidate.setAiGenres(new String[]{"爵士"}));
        matchingFields.put("旅行", candidate -> candidate.setAiThemes(new String[]{"旅行"}));

        long playlistId = 1300L;
        for (Map.Entry<String, Consumer<Song>> entry : matchingFields.entrySet()) {
            Song candidate = song(playlistId, "符合" + entry.getKey(), "歌手");
            entry.getValue().accept(candidate);
            SongRepository songs = mock(SongRepository.class);
            PlaylistRepository playlists = mock(PlaylistRepository.class);
            PlaylistSongRepository items = mock(PlaylistSongRepository.class);
            Playlist playlist = new Playlist();
            playlist.setId(playlistId);
            when(playlists.findByName("按" + entry.getKey() + "筛选")).thenReturn(Optional.empty());
            when(playlists.save(any(Playlist.class))).thenReturn(playlist);
            when(items.findByPlaylistIdOrderBySortOrder(playlistId)).thenReturn(List.of());
            when(songs.findAll(any(Pageable.class))).thenReturn(new PageImpl<>(List.of(candidate)));
            AiLibraryService service = service(songs, playlists, items,
                    mock(OpenAiCompatibleClient.class), new ObjectMapper());

            service.generatePlaylist("按" + entry.getKey() + "筛选", entry.getKey(), 1);

            verify(items).save(argThat(item -> item.getSongId().equals(candidate.getId())));
            playlistId++;
        }
    }

    @Test
    void generatePlaylistStopsAtItsLimitAndContinuesAcrossNonFinalPages() {
        SongRepository cappedSongs = mock(SongRepository.class);
        PlaylistRepository cappedPlaylists = mock(PlaylistRepository.class);
        PlaylistSongRepository cappedItems = mock(PlaylistSongRepository.class);
        Playlist cappedPlaylist = new Playlist();
        cappedPlaylist.setId(1451L);
        when(cappedPlaylists.findByName("限量歌单")).thenReturn(Optional.empty());
        when(cappedPlaylists.save(any(Playlist.class))).thenReturn(cappedPlaylist);
        when(cappedItems.findByPlaylistIdOrderBySortOrder(1451L)).thenReturn(List.of());
        Song first = song(1452L, "第一首", "歌手");
        Song second = song(1453L, "第二首", "歌手");
        Song beyondLimit = song(1454L, "不应加入", "歌手");
        first.setTags(new String[]{"聚会"});
        second.setTags(new String[]{"聚会"});
        beyondLimit.setTags(new String[]{"聚会"});
        when(cappedSongs.findAll(any(Pageable.class))).thenReturn(new PageImpl<>(List.of(first, second, beyondLimit),
                org.springframework.data.domain.PageRequest.of(0, 500), 501));
        service(cappedSongs, cappedPlaylists, cappedItems, mock(OpenAiCompatibleClient.class), new ObjectMapper())
                .generatePlaylist("限量歌单", "聚会", 2);
        ArgumentCaptor<PlaylistSong> cappedCapture = ArgumentCaptor.forClass(PlaylistSong.class);
        verify(cappedItems, times(2)).save(cappedCapture.capture());
        assertThat(cappedCapture.getAllValues()).extracting(PlaylistSong::getSongId).containsExactly(1452L, 1453L);

        SongRepository pagedSongs = mock(SongRepository.class);
        PlaylistRepository pagedPlaylists = mock(PlaylistRepository.class);
        PlaylistSongRepository pagedItems = mock(PlaylistSongRepository.class);
        Playlist pagedPlaylist = new Playlist();
        pagedPlaylist.setId(1461L);
        when(pagedPlaylists.findByName("分页歌单")).thenReturn(Optional.empty());
        when(pagedPlaylists.save(any(Playlist.class))).thenReturn(pagedPlaylist);
        when(pagedItems.findByPlaylistIdOrderBySortOrder(1461L)).thenReturn(List.of());
        Song pageZero = song(1462L, "第一页歌曲", "歌手");
        Song pageOne = song(1463L, "第二页歌曲", "歌手");
        pageZero.setTags(new String[]{"主题"});
        pageOne.setTags(new String[]{"主题"});
        when(pagedSongs.findAll(any(Pageable.class))).thenAnswer(invocation -> {
            Pageable page = invocation.getArgument(0);
            return switch (page.getPageNumber()) {
                case 0 -> new PageImpl<>(List.of(pageZero), page, 1001);
                case 1 -> new PageImpl<>(List.of(pageOne), page, 1001);
                default -> new PageImpl<>(List.of(), page, 1001);
            };
        });

        service(pagedSongs, pagedPlaylists, pagedItems, mock(OpenAiCompatibleClient.class), new ObjectMapper())
                .generatePlaylist("分页歌单", "主题", 3);

        ArgumentCaptor<PlaylistSong> pagedCapture = ArgumentCaptor.forClass(PlaylistSong.class);
        verify(pagedItems, times(2)).save(pagedCapture.capture());
        assertThat(pagedCapture.getAllValues()).extracting(PlaylistSong::getSongId).containsExactly(1462L, 1463L);
        verify(pagedSongs, times(3)).findAll(any(Pageable.class));
    }

    @Test
    void generatePlaylistRejectsNullNamesAndIgnoresSongsWithoutMatchingMetadata() {
        AiLibraryService emptyService = service();
        assertThatThrownBy(() -> emptyService.generatePlaylist(null, "主题", 1))
                .hasMessageContaining("歌单名称和 AI 标签不能为空");
        assertThatThrownBy(() -> emptyService.generatePlaylist("歌单", null, 1))
                .hasMessageContaining("歌单名称和 AI 标签不能为空");

        SongRepository songs = mock(SongRepository.class);
        PlaylistRepository playlists = mock(PlaylistRepository.class);
        PlaylistSongRepository items = mock(PlaylistSongRepository.class);
        when(playlists.findByName("无匹配歌单")).thenReturn(Optional.empty());
        when(playlists.save(any(Playlist.class))).thenAnswer(invocation -> {
            Playlist saved = invocation.getArgument(0);
            saved.setId(1471L);
            return saved;
        });
        when(items.findByPlaylistIdOrderBySortOrder(1471L)).thenReturn(List.of());
        Song noMetadata = song(1472L, "未分类歌曲", "歌手");
        noMetadata.setAiLanguage(null);
        noMetadata.setAiEra(null);
        noMetadata.setAiAgeRange(null);
        noMetadata.setAiVocalForm(null);
        noMetadata.setAiGenres(null);
        noMetadata.setAiThemes(null);
        noMetadata.setTags(null);
        when(songs.findAll(any(Pageable.class))).thenReturn(new PageImpl<>(List.of(noMetadata)));

        service(songs, playlists, items, mock(OpenAiCompatibleClient.class), new ObjectMapper())
                .generatePlaylist("无匹配歌单", "无匹配标签", 1);

        verify(items, never()).save(any(PlaylistSong.class));
    }

    @Test
    void addingPlaylistSongInsertsAtTheEndAndIgnoresDuplicates() {
        PlaylistRepository playlists = mock(PlaylistRepository.class);
        PlaylistSongRepository items = mock(PlaylistSongRepository.class);
        SongRepository songs = mock(SongRepository.class);
        Playlist firstPlaylist = new Playlist();
        firstPlaylist.setId(1401L);
        Playlist secondPlaylist = new Playlist();
        secondPlaylist.setId(1403L);
        when(playlists.findById(1401L)).thenReturn(Optional.of(firstPlaylist));
        when(playlists.findById(1403L)).thenReturn(Optional.of(secondPlaylist));
        when(songs.findById(1402L)).thenReturn(Optional.of(song(1402L, "待加入", "歌手")));
        when(songs.findById(1405L)).thenReturn(Optional.of(song(1405L, "追加歌曲", "歌手")));
        when(songs.findById(1406L)).thenReturn(Optional.of(song(1406L, "已有歌曲", "歌手")));
        PlaylistSong firstAdded = playlistSong(1401L, 1402L, 0);
        PlaylistSong prior = playlistSong(1403L, 1406L, 7);
        PlaylistSong appended = playlistSong(1403L, 1405L, 8);
        when(items.findByPlaylistIdOrderBySortOrder(1401L)).thenReturn(List.of(), List.of(firstAdded));
        when(items.findByPlaylistIdOrderBySortOrder(1403L))
                .thenReturn(List.of(prior), List.of(prior, appended), List.of(prior, appended), List.of(prior, appended));
        AiLibraryService service = service(songs, playlists, items,
                mock(OpenAiCompatibleClient.class), new ObjectMapper());

        service.addPlaylistSong(1401L, 1402L);
        service.addPlaylistSong(1403L, 1405L);
        Map<String, Object> duplicateResult = service.addPlaylistSong(1403L, 1405L);

        assertThat(duplicateResult.get("songs")).isNotNull();
        verify(items).insertManualIfAbsent(1401L, 1402L, 0);
        verify(items).insertManualIfAbsent(1403L, 1405L, 8);
        verify(items, times(2)).insertManualIfAbsent(anyLong(), anyLong(), anyInt());
    }

    @Test
    void repairBatchKeepsBulkRoleSeparateFromConfiguredModelName() {
        SongRepository songRepository = mock(SongRepository.class);
        AiAnalysisTaskRepository taskRepository = mock(AiAnalysisTaskRepository.class);
        AiAnalysisWorker worker = mock(AiAnalysisWorker.class);
        AiConfigService configService = mock(AiConfigService.class);
        when(songRepository.findAll(any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(song(4L, "批量修复", "歌手"))));
        when(configService.isConfigured()).thenReturn(true);
        when(configService.resolve()).thenReturn(new AiConfigService.ResolvedConfig(true, "http://ai.test/v1",
                "bulk-model", "", 30, 0.97, 0.92, AiConfigService.JsonMode.AUTO, 2, 1, "secret"));

        AiLibraryService service = new AiLibraryService(taskRepository, songRepository,
                mock(PlaylistRepository.class), mock(PlaylistSongRepository.class), worker,
                new ObjectMapper(), configService, mock(AssetWriter.class),
                mock(AiClassificationApplier.class), mock(OpenAiCompatibleClient.class),
                mock(MediaImportRecordRepository.class), mock(AiTaskDispatchFailureRecorder.class));

        service.createRepairBatch();

        ArgumentCaptor<AiAnalysisTask> captor = ArgumentCaptor.forClass(AiAnalysisTask.class);
        verify(taskRepository).saveAndFlush(captor.capture());
        assertThat(captor.getValue().getModel()).isEqualTo("bulk-model");
        assertThat(captor.getValue().getModelRole()).isEqualTo("BULK");
    }

    @Test
    void repairBatchSkipsSongsWithAnExistingActiveTask() {
        SongRepository songRepository = mock(SongRepository.class);
        AiAnalysisTaskRepository taskRepository = mock(AiAnalysisTaskRepository.class);
        AiAnalysisWorker worker = mock(AiAnalysisWorker.class);
        AiConfigService configService = mock(AiConfigService.class);
        when(songRepository.findAll(any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(song(4L, "已有任务", "歌手"), song(5L, "新任务", "歌手"))));
        when(configService.isConfigured()).thenReturn(false);
        when(configService.resolve()).thenReturn(new AiConfigService.ResolvedConfig(false, "", "", "",
                30, 0.97, 0.92, AiConfigService.JsonMode.AUTO, 2, 1, ""));
        when(taskRepository.existsBySongIdAndStatusIn(eq(4L), anyList())).thenReturn(true);

        AiLibraryService service = new AiLibraryService(taskRepository, songRepository,
                mock(PlaylistRepository.class), mock(PlaylistSongRepository.class), worker,
                new ObjectMapper(), configService, mock(AssetWriter.class),
                mock(AiClassificationApplier.class), mock(OpenAiCompatibleClient.class),
                mock(MediaImportRecordRepository.class), mock(AiTaskDispatchFailureRecorder.class));

        Map<String, Object> result = service.createRepairBatch();

        assertThat(result).containsEntry("created", 1)
                .containsEntry("skippedExisting", 1);
        verify(taskRepository).existsBySongIdAndStatusIn(eq(4L), anyList());
        verify(taskRepository).existsBySongIdAndStatusIn(eq(5L), anyList());
        verify(taskRepository, times(1)).saveAndFlush(any(AiAnalysisTask.class));
        verify(worker, times(1)).analyze(any());
    }

    @Test
    void repairBatchReturnsBatchIdAndCountsTasksRejectedByTheExecutor() {
        SongRepository songRepository = mock(SongRepository.class);
        AiAnalysisTaskRepository taskRepository = mock(AiAnalysisTaskRepository.class);
        AiAnalysisWorker worker = mock(AiAnalysisWorker.class);
        AiConfigService configService = mock(AiConfigService.class);
        when(songRepository.findAll(any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(song(77L, "队列饱和", "歌手"))));
        when(configService.isConfigured()).thenReturn(false);
        when(configService.resolve()).thenReturn(new AiConfigService.ResolvedConfig(false, "", "", "",
                30, 0.97, 0.92, AiConfigService.JsonMode.AUTO, 2, 1, ""));
        when(taskRepository.saveAndFlush(any())).thenAnswer(invocation -> {
            AiAnalysisTask task = invocation.getArgument(0);
            ReflectionTestUtils.setField(task, "id", 77L);
            return task;
        });
        doThrow(new TaskRejectedException("queue full")).when(worker).analyze(77L);
        AiLibraryService service = new AiLibraryService(taskRepository, songRepository,
                mock(PlaylistRepository.class), mock(PlaylistSongRepository.class), worker,
                new ObjectMapper(), configService, mock(AssetWriter.class),
                mock(AiClassificationApplier.class), mock(OpenAiCompatibleClient.class),
                mock(MediaImportRecordRepository.class), mock(AiTaskDispatchFailureRecorder.class));

        Map<String, Object> result = service.createRepairBatch();

        assertThat(result).containsEntry("created", 1)
                .containsEntry("dispatchRejected", 1);
        assertThat(result.get("batchId")).isNotNull();
    }

    @Test
    void repairBatchStopsOnAnEmptyCataloguePageWithoutSavingOrDispatching() {
        SongRepository songs = mock(SongRepository.class);
        AiAnalysisTaskRepository tasks = mock(AiAnalysisTaskRepository.class);
        AiAnalysisWorker worker = mock(AiAnalysisWorker.class);
        AiConfigService config = taskConfig(false);
        when(songs.findAll(any(Pageable.class))).thenReturn(new PageImpl<>(List.of()));
        AiLibraryService service = new AiLibraryService(tasks, songs, mock(PlaylistRepository.class),
                mock(PlaylistSongRepository.class), worker, new ObjectMapper(), config, mock(AssetWriter.class),
                mock(AiClassificationApplier.class), mock(OpenAiCompatibleClient.class),
                mock(MediaImportRecordRepository.class), mock(AiTaskDispatchFailureRecorder.class));

        Map<String, Object> result = service.createRepairBatch();

        assertThat(result).containsEntry("created", 0).containsEntry("skippedExisting", 0)
                .containsEntry("dispatchRejected", 0);
        verifyNoInteractions(tasks, worker);

        SongRepository missingPageSongs = mock(SongRepository.class);
        when(missingPageSongs.findAll(any(Pageable.class))).thenReturn(null);
        Map<String, Object> missingPageResult = new AiLibraryService(mock(AiAnalysisTaskRepository.class),
                missingPageSongs, mock(PlaylistRepository.class), mock(PlaylistSongRepository.class),
                mock(AiAnalysisWorker.class), new ObjectMapper(), taskConfig(false), mock(AssetWriter.class),
                mock(AiClassificationApplier.class), mock(OpenAiCompatibleClient.class),
                mock(MediaImportRecordRepository.class), mock(AiTaskDispatchFailureRecorder.class)).createRepairBatch();
        assertThat(missingPageResult).containsEntry("created", 0).containsEntry("skippedExisting", 0);
    }

    @Test
    void repairBatchContinuesAcrossNonFinalPagesAndStopsAfterTheLastPage() {
        SongRepository songs = mock(SongRepository.class);
        AiAnalysisTaskRepository tasks = mock(AiAnalysisTaskRepository.class);
        AiAnalysisWorker worker = mock(AiAnalysisWorker.class);
        List<Song> catalog = List.of(song(1321L, "第一页", "歌手"), song(1322L, "第二页", "歌手"));
        when(songs.findAll(any(Pageable.class))).thenAnswer(invocation -> {
            Pageable pageable = invocation.getArgument(0);
            return switch (pageable.getPageNumber()) {
                case 0 -> new PageImpl<>(List.of(catalog.get(0)), pageable, 1001);
                case 1 -> new PageImpl<>(List.of(catalog.get(1)), pageable, 1001);
                default -> new PageImpl<>(List.of(), pageable, 1001);
            };
        });
        when(tasks.saveAndFlush(any(AiAnalysisTask.class))).thenAnswer(invocation -> {
            AiAnalysisTask task = invocation.getArgument(0);
            ReflectionTestUtils.setField(task, "id", task.getSongId() + 2000L);
            return task;
        });
        AiLibraryService service = new AiLibraryService(tasks, songs, mock(PlaylistRepository.class),
                mock(PlaylistSongRepository.class), worker, new ObjectMapper(), taskConfig(false),
                mock(AssetWriter.class), mock(AiClassificationApplier.class), mock(OpenAiCompatibleClient.class),
                mock(MediaImportRecordRepository.class), mock(AiTaskDispatchFailureRecorder.class));

        Map<String, Object> result = service.createRepairBatch();

        assertThat(result).containsEntry("created", 2).containsEntry("skippedExisting", 0);
        verify(songs, times(3)).findAll(any(Pageable.class));
        verify(worker).analyze(3321L);
        verify(worker).analyze(3322L);
    }

    @Test
    void repairBatchCountsOnlyAConcurrentActiveTaskConflictAsSkipped() {
        SongRepository songs = mock(SongRepository.class);
        AiAnalysisTaskRepository tasks = mock(AiAnalysisTaskRepository.class);
        AiAnalysisWorker worker = mock(AiAnalysisWorker.class);
        when(songs.findAll(any(Pageable.class))).thenReturn(new PageImpl<>(List.of(song(1311L, "竞态歌曲", "歌手"))));
        when(tasks.existsBySongIdAndStatusIn(eq(1311L), anyList())).thenReturn(false, true);
        when(tasks.saveAndFlush(any(AiAnalysisTask.class)))
                .thenThrow(new DataIntegrityViolationException("active task unique index"));
        AiLibraryService service = new AiLibraryService(tasks, songs, mock(PlaylistRepository.class),
                mock(PlaylistSongRepository.class), worker, new ObjectMapper(), taskConfig(false),
                mock(AssetWriter.class), mock(AiClassificationApplier.class), mock(OpenAiCompatibleClient.class),
                mock(MediaImportRecordRepository.class), mock(AiTaskDispatchFailureRecorder.class));

        Map<String, Object> result = service.createRepairBatch();

        assertThat(result).containsEntry("created", 0).containsEntry("skippedExisting", 1);
        verify(worker, never()).analyze(anyLong());

        SongRepository unrelatedSongs = mock(SongRepository.class);
        AiAnalysisTaskRepository unrelatedTasks = mock(AiAnalysisTaskRepository.class);
        DataIntegrityViolationException unrelated = new DataIntegrityViolationException("other constraint");
        when(unrelatedSongs.findAll(any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(song(1312L, "其他错误", "歌手"))));
        when(unrelatedTasks.existsBySongIdAndStatusIn(eq(1312L), anyList())).thenReturn(false, false);
        when(unrelatedTasks.saveAndFlush(any(AiAnalysisTask.class))).thenThrow(unrelated);
        AiLibraryService unrelatedService = new AiLibraryService(unrelatedTasks, unrelatedSongs,
                mock(PlaylistRepository.class), mock(PlaylistSongRepository.class), mock(AiAnalysisWorker.class),
                new ObjectMapper(), taskConfig(false), mock(AssetWriter.class), mock(AiClassificationApplier.class),
                mock(OpenAiCompatibleClient.class), mock(MediaImportRecordRepository.class),
                mock(AiTaskDispatchFailureRecorder.class));

        assertThatThrownBy(unrelatedService::createRepairBatch).isSameAs(unrelated);
    }

    @Test
    void resumeRepairBatchDispatchesOnlyPausedTasks() {
        String batchId = "resume-batch";
        AiAnalysisTask paused = batchTask(91L, "paused");
        AiAnalysisTask pending = batchTask(92L, "pending");
        AiAnalysisTaskRepository taskRepository = mock(AiAnalysisTaskRepository.class);
        AiAnalysisWorker worker = mock(AiAnalysisWorker.class);
        when(taskRepository.findByBatchIdOrderByCreatedAtAsc(batchId)).thenReturn(List.of(paused, pending));
        AiLibraryService service = new AiLibraryService(taskRepository, mock(SongRepository.class),
                mock(PlaylistRepository.class), mock(PlaylistSongRepository.class), worker,
                new ObjectMapper(), mock(AiConfigService.class), mock(AssetWriter.class),
                mock(AiClassificationApplier.class), mock(OpenAiCompatibleClient.class),
                mock(MediaImportRecordRepository.class), mock(AiTaskDispatchFailureRecorder.class));

        Map<String, Object> result = service.resumeRepairBatch(batchId);

        assertThat(result).containsEntry("batchId", batchId).containsEntry("resumed", 1);
        assertThat(paused.getStatus()).isEqualTo("pending");
        assertThat(pending.getStatus()).isEqualTo("pending");
        verify(taskRepository).saveAndFlush(paused);
        verify(worker).analyze(91L);
        verify(worker, never()).analyze(92L);
    }

    @Test
    void retryFailedRepairBatchDispatchesOnlyFailedTasks() {
        String batchId = "retry-batch";
        AiAnalysisTask failed = batchTask(93L, "failed");
        AiAnalysisTask review = batchTask(94L, "review");
        AiAnalysisTaskRepository taskRepository = mock(AiAnalysisTaskRepository.class);
        AiAnalysisWorker worker = mock(AiAnalysisWorker.class);
        when(taskRepository.findByBatchIdOrderByCreatedAtAsc(batchId)).thenReturn(List.of(failed, review));
        AiLibraryService service = new AiLibraryService(taskRepository, mock(SongRepository.class),
                mock(PlaylistRepository.class), mock(PlaylistSongRepository.class), worker,
                new ObjectMapper(), mock(AiConfigService.class), mock(AssetWriter.class),
                mock(AiClassificationApplier.class), mock(OpenAiCompatibleClient.class),
                mock(MediaImportRecordRepository.class), mock(AiTaskDispatchFailureRecorder.class));

        Map<String, Object> result = service.retryFailedRepairBatch(batchId);

        assertThat(result).containsEntry("batchId", batchId).containsEntry("retried", 1);
        assertThat(failed.getStatus()).isEqualTo("pending");
        assertThat(review.getStatus()).isEqualTo("review");
        verify(taskRepository).saveAndFlush(failed);
        verify(worker).analyze(93L);
        verify(worker, never()).analyze(94L);
    }

    @Test
    void resumeAndRetryRejectMissingRepairBatchesWithoutDispatching() {
        String batchId = "missing-batch";
        AiAnalysisTaskRepository taskRepository = mock(AiAnalysisTaskRepository.class);
        AiAnalysisWorker worker = mock(AiAnalysisWorker.class);
        when(taskRepository.findByBatchIdOrderByCreatedAtAsc(batchId)).thenReturn(List.of());
        AiLibraryService service = new AiLibraryService(taskRepository, mock(SongRepository.class),
                mock(PlaylistRepository.class), mock(PlaylistSongRepository.class), worker,
                new ObjectMapper(), mock(AiConfigService.class), mock(AssetWriter.class),
                mock(AiClassificationApplier.class), mock(OpenAiCompatibleClient.class),
                mock(MediaImportRecordRepository.class), mock(AiTaskDispatchFailureRecorder.class));

        assertThatThrownBy(() -> service.resumeRepairBatch(batchId)).hasMessageContaining("修复批次不存在");
        assertThatThrownBy(() -> service.retryFailedRepairBatch(batchId)).hasMessageContaining("修复批次不存在");
        assertThatThrownBy(() -> service.pauseRepairBatch(batchId)).hasMessageContaining("修复批次不存在");

        verifyNoInteractions(worker);
        verify(taskRepository, never()).saveAndFlush(any(AiAnalysisTask.class));
        verify(taskRepository, never()).pauseBatch(batchId);
    }

    @Test
    void addingSongBeyondPlaylistLimitIsRejected() {
        SongRepository songRepository = mock(SongRepository.class);
        PlaylistRepository playlistRepository = mock(PlaylistRepository.class);
        PlaylistSongRepository playlistSongRepository = mock(PlaylistSongRepository.class);
        Playlist playlist = new Playlist();
        when(playlistRepository.findById(9L)).thenReturn(Optional.of(playlist));
        when(songRepository.findById(101L)).thenReturn(Optional.of(song(101L, "新歌", "歌手")));
        when(playlistSongRepository.findByPlaylistIdOrderBySortOrder(9L)).thenReturn(
                java.util.stream.LongStream.rangeClosed(1, 100).mapToObj(id -> {
                    PlaylistSong item = new PlaylistSong();
                    item.setSongId(id);
                    item.setPlaylistId(9L);
                    return item;
                }).toList());

        AiLibraryService service = service(songRepository, playlistRepository, playlistSongRepository,
                mock(OpenAiCompatibleClient.class), new ObjectMapper());

        assertThatThrownBy(() -> service.addPlaylistSong(9L, 101L))
                .hasMessageContaining("最多包含 100 首");
    }

    @Test
    void previewPlaylistSaveValidatesEverySongBeforeCreatingPlaylist() {
        SongRepository songRepository = mock(SongRepository.class);
        PlaylistRepository playlistRepository = mock(PlaylistRepository.class);
        PlaylistSongRepository playlistSongRepository = mock(PlaylistSongRepository.class);
        when(songRepository.findAllById(List.of(1L, 2L))).thenReturn(List.of(song(1L, "有效", "歌手")));

        AiLibraryService service = service(songRepository, playlistRepository, playlistSongRepository,
                mock(OpenAiCompatibleClient.class), new ObjectMapper());

        assertThatThrownBy(() -> service.savePlaylistFromPreview(
                "预览歌单", "说明", "AI 策划", true, List.of(1L, 2L)))
                .hasMessageContaining("歌曲不存在");
        verify(playlistRepository, never()).save(any(Playlist.class));
        verify(playlistSongRepository, never()).saveAll(anyList());
    }

    @Test
    void previewPlaylistSaveDeduplicatesIdsAndWritesAssociationsOnce() {
        SongRepository songRepository = mock(SongRepository.class);
        PlaylistRepository playlistRepository = mock(PlaylistRepository.class);
        PlaylistSongRepository playlistSongRepository = mock(PlaylistSongRepository.class);
        Playlist playlist = new Playlist();
        when(songRepository.findAllById(List.of(1L, 2L))).thenReturn(List.of(
                song(1L, "第一首", "歌手甲"), song(2L, "第二首", "歌手乙")));
        when(playlistRepository.save(any(Playlist.class))).thenReturn(playlist);

        AiLibraryService service = service(songRepository, playlistRepository, playlistSongRepository,
                mock(OpenAiCompatibleClient.class), new ObjectMapper());

        service.savePlaylistFromPreview("预览歌单", "说明", "AI 策划", true, List.of(1L, 2L, 1L));

        ArgumentCaptor<List<PlaylistSong>> captor = ArgumentCaptor.forClass(List.class);
        verify(playlistSongRepository).saveAll(captor.capture());
        assertThat(captor.getValue()).hasSize(2)
                .extracting(PlaylistSong::getSongId)
                .containsExactly(1L, 2L);
    }

    @Test
    void removingPlaylistSongLocksThePlaylistBeforeDeleting() {
        PlaylistRepository playlists = mock(PlaylistRepository.class);
        PlaylistSongRepository playlistSongs = mock(PlaylistSongRepository.class);
        Playlist playlist = new Playlist();
        when(playlists.findById(9L)).thenReturn(Optional.of(playlist));
        AiLibraryService service = service(mock(SongRepository.class), playlists, playlistSongs,
                mock(OpenAiCompatibleClient.class), new ObjectMapper());

        service.removePlaylistSong(9L, 101L);

        verify(playlistSongs).lockPlaylist(9L);
        verify(playlistSongs).deleteByPlaylistIdAndSongId(9L, 101L);
    }

    @Test
    void replacingPlaylistCoverSchedulesThePreviousAssetForCleanup() throws Exception {
        SongRepository songs = mock(SongRepository.class);
        PlaylistRepository playlists = mock(PlaylistRepository.class);
        Playlist playlist = new Playlist();
        playlist.setId(9L);
        playlist.setCoverPath("playlist-covers/9-old.jpg");
        when(playlists.findById(9L)).thenReturn(Optional.of(playlist));
        when(playlists.save(any(Playlist.class))).thenAnswer(invocation -> invocation.getArgument(0));
        AssetWriter writer = mock(AssetWriter.class);
        when(writer.writePlaylistCover(eq(9L), any(), eq("png"))).thenReturn("playlist-covers/9-new.png");
        AssetCleanupService cleanup = mock(AssetCleanupService.class);
        AiLibraryService service = service(songs, playlists, mock(PlaylistSongRepository.class),
                mock(OpenAiCompatibleClient.class), new ObjectMapper(), writer);
        service.setAssetCleanupService(cleanup);

        service.uploadPlaylistCover(9L,
                new MockMultipartFile("file", "cover.png", "image/png", new byte[]{1, 2, 3}));

        verify(cleanup).afterCommitIfUnreferenced("playlist-covers/9-old.jpg");
    }

    @Test
    void reorderingPlaylistSongsLocksBeforeReadingTheCurrentOrder() {
        PlaylistRepository playlists = mock(PlaylistRepository.class);
        PlaylistSongRepository playlistSongs = mock(PlaylistSongRepository.class);
        SongRepository songs = mock(SongRepository.class);
        Playlist playlist = new Playlist();
        when(playlists.findById(9L)).thenReturn(Optional.of(playlist));
        PlaylistSong first = playlistSong(9L, 1L, 0);
        PlaylistSong second = playlistSong(9L, 2L, 1);
        when(playlistSongs.findByPlaylistIdOrderBySortOrder(9L)).thenReturn(List.of(first, second));
        when(songs.findById(1L)).thenReturn(Optional.of(song(1L, "一", "甲")));
        when(songs.findById(2L)).thenReturn(Optional.of(song(2L, "二", "乙")));
        AiLibraryService service = service(songs, playlists, playlistSongs,
                mock(OpenAiCompatibleClient.class), new ObjectMapper());

        service.reorderPlaylistSongs(9L, List.of(2L, 1L));

        verify(playlistSongs).lockPlaylist(9L);
        verify(playlistSongs).saveAll(any());
    }

    private PlaylistSong playlistSong(long playlistId, long songId, int sortOrder) {
        PlaylistSong item = new PlaylistSong();
        item.setPlaylistId(playlistId);
        item.setSongId(songId);
        item.setSortOrder(sortOrder);
        return item;
    }

    private Song song(long id, String title, String artist) {
        Song song = new Song();
        song.setId(id);
        song.setTitle(title);
        song.setArtist(artist);
        return song;
    }

    private static AiAnalysisTask batchTask(Long id, String status) {
        AiAnalysisTask task = new AiAnalysisTask();
        ReflectionTestUtils.setField(task, "id", id);
        task.setStatus(status);
        return task;
    }

    private AiLibraryService taskService(
            AiAnalysisTaskRepository tasks,
            SongRepository songs,
            MediaImportRecordRepository imports,
            AiAnalysisWorker worker,
            AiConfigService config) {
        return new AiLibraryService(tasks, songs, mock(PlaylistRepository.class), mock(PlaylistSongRepository.class),
                worker, new ObjectMapper(), config, mock(AssetWriter.class), mock(AiClassificationApplier.class),
                mock(OpenAiCompatibleClient.class), imports, mock(AiTaskDispatchFailureRecorder.class));
    }

    private AiConfigService taskConfig(boolean configured) {
        AiConfigService config = mock(AiConfigService.class);
        when(config.isConfigured()).thenReturn(configured);
        when(config.resolve()).thenReturn(new AiConfigService.ResolvedConfig(configured,
                configured ? "http://ai.test/v1" : "", configured ? "bulk-model" : "", "",
                30, 0.97, 0.92, AiConfigService.JsonMode.AUTO, 2, 1, configured ? "secret" : ""));
        return config;
    }

    private AiConfigService previewConfig(String reasoningModel) {
        AiConfigService config = mock(AiConfigService.class);
        when(config.resolve()).thenReturn(new AiConfigService.ResolvedConfig(true, "http://ai.test/v1",
                "bulk-model", reasoningModel, 30, 0.97, 0.92, AiConfigService.JsonMode.AUTO, 2, 1, "secret"));
        return config;
    }

    private AiLibraryService previewService(SongRepository songs, OpenAiCompatibleClient client,
                                            AiConfigService config, ObjectMapper mapper) {
        return new AiLibraryService(mock(AiAnalysisTaskRepository.class), songs, mock(PlaylistRepository.class),
                mock(PlaylistSongRepository.class), mock(AiAnalysisWorker.class), mapper, config,
                mock(AssetWriter.class), mock(AiClassificationApplier.class), client,
                mock(MediaImportRecordRepository.class), mock(AiTaskDispatchFailureRecorder.class));
    }

    private AiLibraryService service() {
        return service(mock(SongRepository.class), mock(PlaylistRepository.class),
                mock(PlaylistSongRepository.class), mock(OpenAiCompatibleClient.class), new ObjectMapper());
    }

    private AiLibraryService service(SongRepository songRepository, PlaylistRepository playlistRepository,
                                     PlaylistSongRepository playlistSongRepository,
                                     OpenAiCompatibleClient aiClient, ObjectMapper objectMapper) {
        return service(songRepository, playlistRepository, playlistSongRepository, aiClient, objectMapper,
                mock(AssetWriter.class));
    }

    private AiLibraryService service(SongRepository songRepository, PlaylistRepository playlistRepository,
                                     PlaylistSongRepository playlistSongRepository,
                                     OpenAiCompatibleClient aiClient, ObjectMapper objectMapper,
                                     AssetWriter assetWriter) {
        AiConfigService configService = mock(AiConfigService.class);
        when(configService.resolve()).thenReturn(new AiConfigService.ResolvedConfig(true, "http://ai.test/v1",
                "bulk-model", "", 30, 0.97, 0.92, AiConfigService.JsonMode.AUTO, 2, 1, "secret"));
        return new AiLibraryService(mock(AiAnalysisTaskRepository.class), songRepository,
                playlistRepository, playlistSongRepository, mock(AiAnalysisWorker.class),
                objectMapper, configService, assetWriter,
                mock(AiClassificationApplier.class), aiClient, mock(MediaImportRecordRepository.class),
                mock(AiTaskDispatchFailureRecorder.class));
    }
}
