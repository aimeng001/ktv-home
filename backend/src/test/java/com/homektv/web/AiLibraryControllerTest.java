package com.homektv.web;

import com.homektv.ai.AiLibraryService;
import com.homektv.domain.Playlist;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.mock.web.MockMultipartFile;

import java.util.List;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

class AiLibraryControllerTest {

    @Test
    void savesPreviewWithOneRequestAndExistingFieldNames() throws Exception {
        AiLibraryService service = mock(AiLibraryService.class);
        Playlist playlist = new Playlist();
        playlist.setName("聚会歌单");
        when(service.savePlaylistFromPreview("聚会歌单", "说明", "AI 策划", true, List.of(1L, 2L)))
                .thenReturn(playlist);
        MockMvc mvc = standaloneSetup(new AiLibraryController(service)).build();

        mvc.perform(post("/api/admin/ai/playlists/from-preview")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"聚会歌单","description":"说明","theme":"AI 策划",
                                 "publicVisible":true,"songIds":[1,2]}
                                """))
                .andExpect(status().isOk());

        verify(service).savePlaylistFromPreview(eq("聚会歌单"), eq("说明"), eq("AI 策划"), eq(true), eq(List.of(1L, 2L)));
    }

    @Test
    void appliesTheUnclassifiedTaskDefaultWhenBodyOrLimitIsMissing() {
        AiLibraryService service = mock(AiLibraryService.class);
        when(service.createUnclassifiedTasks(anyInt())).thenReturn(List.of());
        AiLibraryController controller = new AiLibraryController(service);

        var noBody = controller.createUnclassified(null);
        var noLimit = controller.createUnclassified(new AiLibraryController.BatchRequest(null));
        var explicitLimit = controller.createUnclassified(new AiLibraryController.BatchRequest(12));

        verify(service, times(2)).createUnclassifiedTasks(50);
        verify(service).createUnclassifiedTasks(12);
        org.assertj.core.api.Assertions.assertThat(noBody).containsEntry("created", 0).containsEntry("tasks", List.of());
        org.assertj.core.api.Assertions.assertThat(noLimit).containsEntry("created", 0).containsEntry("tasks", List.of());
        org.assertj.core.api.Assertions.assertThat(explicitLimit).containsEntry("created", 0).containsEntry("tasks", List.of());
    }

    @Test
    void defaultsPlaylistGenerationAndPreviewLimitsButPreservesExplicitLimits() {
        AiLibraryService service = mock(AiLibraryService.class);
        AiLibraryController controller = new AiLibraryController(service);

        controller.generatePlaylist(new AiLibraryController.GeneratePlaylistRequest("歌单", "流行", null));
        controller.generatePlaylist(new AiLibraryController.GeneratePlaylistRequest("歌单", "流行", 25));
        controller.previewPlaylist(new AiLibraryController.PlaylistPreviewRequest("适合聚会", null));
        controller.previewPlaylist(new AiLibraryController.PlaylistPreviewRequest("适合聚会", 18));

        verify(service).generatePlaylist("歌单", "流行", 100);
        verify(service).generatePlaylist("歌单", "流行", 25);
        verify(service).previewPlaylist("适合聚会", 100);
        verify(service).previewPlaylist("适合聚会", 18);
    }

    @Test
    void treatsNullPreviewVisibilityAsPublicAndPreservesExplicitValues() {
        AiLibraryService service = mock(AiLibraryService.class);
        AiLibraryController controller = new AiLibraryController(service);
        List<Long> songIds = List.of(1L, 2L);

        controller.savePlaylistFromPreview(new AiLibraryController.SavePlaylistFromPreviewRequest(
                "歌单", "说明", "主题", null, songIds));
        controller.savePlaylistFromPreview(new AiLibraryController.SavePlaylistFromPreviewRequest(
                "公开歌单", "说明", "主题", true, songIds));
        controller.savePlaylistFromPreview(new AiLibraryController.SavePlaylistFromPreviewRequest(
                "私有歌单", "说明", "主题", false, songIds));

        verify(service).savePlaylistFromPreview("歌单", "说明", "主题", true, songIds);
        verify(service).savePlaylistFromPreview("公开歌单", "说明", "主题", true, songIds);
        verify(service).savePlaylistFromPreview("私有歌单", "说明", "主题", false, songIds);
    }

    @Test
    void delegatesTaskAndPlaylistEndpointsWithoutChangingRequestIdentity() {
        AiLibraryService service = mock(AiLibraryService.class);
        AiLibraryController controller = new AiLibraryController(service);
        List<Long> songIds = List.of(3L, 4L);
        MockMultipartFile cover = new MockMultipartFile("file", "cover.jpg", "image/jpeg", new byte[]{1});

        controller.tasks();
        controller.create(new AiLibraryController.CreateTaskRequest(11L));
        controller.createImportTask(new AiLibraryController.CreateImportTaskRequest(12L));
        controller.repair();
        controller.repairProgress("batch-a");
        controller.pauseRepair("batch-a");
        controller.resumeRepair("batch-a");
        controller.retryFailedRepair("batch-a");
        controller.retry(13L);
        controller.apply(14L, null);
        controller.playlists();
        controller.playlist(15L);
        controller.createPlaylist(new AiLibraryController.SavePlaylistRequest("新歌单", "说明", "主题", true));
        controller.updatePlaylist(16L, new AiLibraryController.SavePlaylistRequest("修改歌单", "说明", "主题", false));
        controller.deletePlaylist(17L);
        controller.addSong(18L, new AiLibraryController.PlaylistSongRequest(19L));
        controller.removeSong(18L, 19L);
        controller.uploadCover(18L, cover);
        controller.reorderSongs(18L, new AiLibraryController.ReorderSongsRequest(songIds));

        verify(service).listTasks();
        verify(service).createTask(11L);
        verify(service).createImportTask(12L);
        verify(service).createRepairBatch();
        verify(service).repairProgress("batch-a");
        verify(service).pauseRepairBatch("batch-a");
        verify(service).resumeRepairBatch("batch-a");
        verify(service).retryFailedRepairBatch("batch-a");
        verify(service).retry(13L);
        verify(service).apply(14L, null);
        verify(service).listPlaylists();
        verify(service).playlistDetail(15L);
        verify(service).savePlaylist(null, "新歌单", "说明", "主题", true);
        verify(service).savePlaylist(16L, "修改歌单", "说明", "主题", false);
        verify(service).deletePlaylist(17L);
        verify(service).addPlaylistSong(18L, 19L);
        verify(service).removePlaylistSong(18L, 19L);
        verify(service).uploadPlaylistCover(18L, cover);
        verify(service).reorderPlaylistSongs(18L, songIds);
    }
}
