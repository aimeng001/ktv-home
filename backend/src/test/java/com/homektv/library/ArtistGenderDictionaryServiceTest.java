package com.homektv.library;

import com.homektv.web.ApiException;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

class ArtistGenderDictionaryServiceTest {
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final ArtistGenderDictionaryService service = new ArtistGenderDictionaryService(jdbc);

    @Test
    void rejectsOversizedImportBeforeTouchingDatabase() {
        List<ArtistGenderDictionaryService.DictionaryEntry> entries = java.util.stream.IntStream.range(0, 5_001)
                .mapToObj(index -> new ArtistGenderDictionaryService.DictionaryEntry(
                        "歌手" + index, "男歌手", List.of()))
                .toList();

        assertThatThrownBy(() -> service.importEntries(entries))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("最多导入");
        verifyNoInteractions(jdbc);
    }

    @Test
    void rejectsInvalidGenderAndControlCharacters() {
        assertThatThrownBy(() -> service.importEntries(List.of(
                new ArtistGenderDictionaryService.DictionaryEntry("歌手", "未知性别", List.of()))))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("歌手类型");
        assertThatThrownBy(() -> service.importEntries(List.of(
                new ArtistGenderDictionaryService.DictionaryEntry("歌手\n恶意", "男歌手", List.of()))))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("控制字符");
        verifyNoInteractions(jdbc);
    }

    @Test
    void rejectsConflictingAliasesWithinOneImport() {
        assertThatThrownBy(() -> service.importEntries(List.of(
                new ArtistGenderDictionaryService.DictionaryEntry("甲歌手", "男歌手", List.of("共同别名")),
                new ArtistGenderDictionaryService.DictionaryEntry("乙歌手", "女歌手", List.of("共同别名")))))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("冲突");
        verifyNoInteractions(jdbc);
    }
}
