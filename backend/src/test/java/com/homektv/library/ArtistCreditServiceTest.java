package com.homektv.library;

import com.homektv.domain.SongArtist;
import com.homektv.repo.SongArtistRepository;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
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

        var calls = org.mockito.Mockito.inOrder(repository);
        calls.verify(repository).findBySongIdOrderByArtistOrder(8L);
        calls.verify(repository).deleteBySongId(8L);
        calls.verify(repository).flush();
        calls.verify(repository).saveAll(any());
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

    @Test
    void createsInitialCreditsWithoutReadingOrDeletingRowsThatCannotExistYet() {
        SongArtistRepository repository = mock(SongArtistRepository.class);
        when(repository.saveAll(any())).thenAnswer(invocation -> invocation.getArgument(0));

        ArtistCreditService service = new ArtistCreditService(repository);
        service.createInitial(17L, "单依纯_王子异", List.of());

        @SuppressWarnings("unchecked")
        org.mockito.ArgumentCaptor<Iterable<SongArtist>> saved =
                (org.mockito.ArgumentCaptor<Iterable<SongArtist>>) (org.mockito.ArgumentCaptor<?>)
                        org.mockito.ArgumentCaptor.forClass(Iterable.class);
        verify(repository).saveAll(saved.capture());
        List<SongArtist> credits = new ArrayList<>();
        saved.getValue().forEach(credits::add);

        assertThat(credits).extracting(SongArtist::getArtistName)
                .containsExactly("单依纯", "王子异");
        assertThat(credits).extracting(SongArtist::getArtistOrder).containsExactly(0, 1);
        assertThat(credits).allSatisfy(credit -> {
            assertThat(credit.getSongId()).isEqualTo(17L);
            assertThat(credit.getArtistKey()).isNotBlank();
            assertThat(credit.getArtistPy()).isNotBlank();
            assertThat(credit.getArtistInit()).isNotBlank();
        });
        verify(repository, never()).findBySongIdOrderByArtistOrder(anyLong());
        verify(repository, never()).deleteBySongId(anyLong());
        verify(repository, never()).flush();
    }

    @Test
    void createsInitialCreditsUsingKnownArtistParsingWithoutReplacementQueries() {
        SongArtistRepository repository = mock(SongArtistRepository.class);
        when(repository.saveAll(any())).thenAnswer(invocation -> invocation.getArgument(0));

        ArtistCreditService service = new ArtistCreditService(repository);
        service.createInitial(18L, "张庭 钟丽缇 王祖蓝",
                List.of("张庭", "钟丽缇", "王祖蓝"));

        @SuppressWarnings("unchecked")
        org.mockito.ArgumentCaptor<Iterable<SongArtist>> saved =
                (org.mockito.ArgumentCaptor<Iterable<SongArtist>>) (org.mockito.ArgumentCaptor<?>)
                        org.mockito.ArgumentCaptor.forClass(Iterable.class);
        verify(repository).saveAll(saved.capture());
        List<SongArtist> credits = new ArrayList<>();
        saved.getValue().forEach(credits::add);
        assertThat(credits).extracting(SongArtist::getArtistName)
                .containsExactly("张庭", "钟丽缇", "王祖蓝");
        verify(repository, never()).findBySongIdOrderByArtistOrder(anyLong());
        verify(repository, never()).deleteBySongId(anyLong());
        verify(repository, never()).flush();
    }
}
