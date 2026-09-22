package com.homektv.web;

import com.homektv.library.ArtistAvatarJobService;
import com.homektv.library.ArtistGenderDictionaryService;
import com.homektv.library.ArtistGenderMatcher;
import com.homektv.library.ArtistLibraryService;
import com.homektv.library.ArtistProfileService;
import com.homektv.library.AssetWriter;
import com.homektv.library.LocalAvatarResolver;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ArtistGenderControllerTest {
    @Test
    void applyEndpointDelegatesToDatabaseMatcherWithoutCallingAi() {
        ArtistGenderMatcher matcher = mock(ArtistGenderMatcher.class);
        when(matcher.applyDictionary()).thenReturn(12);
        ArtistLibraryController controller = new ArtistLibraryController(
                mock(ArtistLibraryService.class),
                mock(LocalAvatarResolver.class),
                mock(AssetWriter.class),
                mock(ArtistProfileService.class),
                mock(ArtistAvatarJobService.class),
                mock(ArtistGenderDictionaryService.class),
                matcher);

        Map<String, Object> result = controller.applyGenderDictionary();

        assertThat(result).containsEntry("success", true).containsEntry("changed", 12);
        verify(matcher).applyDictionary();
    }

    @Test
    void importEndpointPassesAliasesToDictionaryService() {
        ArtistGenderDictionaryService dictionary = mock(ArtistGenderDictionaryService.class);
        when(dictionary.importEntries(org.mockito.ArgumentMatchers.anyList())).thenReturn(2);
        ArtistLibraryController controller = new ArtistLibraryController(
                mock(ArtistLibraryService.class), mock(LocalAvatarResolver.class), mock(AssetWriter.class),
                mock(ArtistProfileService.class), mock(ArtistAvatarJobService.class), dictionary,
                mock(ArtistGenderMatcher.class));

        Map<String, Object> result = controller.importGenderDictionary(
                new ArtistLibraryController.DictionaryImportRequest(List.of(
                        new ArtistLibraryController.DictionaryEntryRequest(
                                "周杰伦", "男歌手", List.of("周董")))));

        assertThat(result).containsEntry("imported", 2);
        verify(dictionary).importEntries(org.mockito.ArgumentMatchers.argThat(entries ->
                entries.size() == 1 && entries.iterator().next().aliases().equals(List.of("周董"))));
    }
}
