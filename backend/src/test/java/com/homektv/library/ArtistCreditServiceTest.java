package com.homektv.library;

import com.homektv.domain.SongArtist;
import com.homektv.repo.SongArtistRepository;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ArtistCreditServiceTest {

    private final SongArtistRepository repository = mock(SongArtistRepository.class);

    @Test
    void storesOneIndependentAssociationForEachCollaborativeArtist() {
        SongArtistRepository repository = mock(SongArtistRepository.class);
        when(repository.findBySongIdOrderByArtistOrder(7L)).thenReturn(List.of());
        when(repository.saveAll(any())).thenAnswer(invocation -> invocation.getArgument(0));

        ArtistCreditService service = new ArtistCreditService(repository);
        service.replace(7L, "单依纯_王子异");

        var saved = org.mockito.ArgumentCaptor.forClass(List.class);
        verify(repository).saveAll(saved.capture());
        @SuppressWarnings("unchecked")
        List<SongArtist> credits = (List<SongArtist>) saved.getValue();
        assertThat(credits).extracting(SongArtist::getArtistName)
                .containsExactly("单依纯", "王子异");
        assertThat(credits).extracting(SongArtist::getArtistInit)
                .containsExactly("dyc", "wzy");
    }

    @Test
    void refreshesPinyinOnLegacyRowsCreatedByTheMigration() {
        SongArtist legacy = new SongArtist();
        legacy.setSongId(8L);
        legacy.setArtistName("单依纯");
        legacy.setArtistKey("单依纯");
        legacy.setArtistPy("");
        legacy.setArtistInit("");
        when(repository.findBySongIdOrderByArtistOrder(8L)).thenReturn(List.of(legacy));
        when(repository.saveAll(any())).thenAnswer(invocation -> invocation.getArgument(0));

        ArtistCreditService service = new ArtistCreditService(repository);
        service.replace(8L, "单依纯");

        verify(repository).deleteBySongId(8L);
        verify(repository).saveAll(any());
    }

    @Test
    void storesEveryKnownSpaceSeparatedArtistAsAnIndependentAssociation() {
        SongArtistRepository repository = mock(SongArtistRepository.class);
        when(repository.findBySongIdOrderByArtistOrder(9L)).thenReturn(List.of());
        when(repository.saveAll(any())).thenAnswer(invocation -> invocation.getArgument(0));

        ArtistCreditService service = new ArtistCreditService(repository);
        service.replace(9L, "张庭 钟丽缇 王祖蓝",
                List.of("张庭", "钟丽缇", "王祖蓝"));

        var saved = org.mockito.ArgumentCaptor.forClass(List.class);
        verify(repository).saveAll(saved.capture());
        @SuppressWarnings("unchecked")
        List<SongArtist> credits = (List<SongArtist>) saved.getValue();
        assertThat(credits).extracting(SongArtist::getArtistName)
                .containsExactly("张庭", "钟丽缇", "王祖蓝");
    }
}
