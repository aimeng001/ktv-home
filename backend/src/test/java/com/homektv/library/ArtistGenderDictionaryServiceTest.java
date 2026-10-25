package com.homektv.library;

import com.homektv.web.ApiException;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ParameterizedPreparedStatementSetter;
import org.springframework.jdbc.core.RowMapper;
import org.mockito.ArgumentCaptor;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.sql.ResultSet;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ArtistGenderDictionaryServiceTest {
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final ArtistGenderDictionaryService service = new ArtistGenderDictionaryService(jdbc);

    @Test
    void nullAndEmptyImportsAreNoOps() {
        assertThat(service.importEntries(null)).isZero();
        assertThat(service.importEntries(List.of())).isZero();
        verifyNoInteractions(jdbc);
    }

    @Test
    void rejectsNullRowsBeforeTouchingDatabase() {
        assertThatThrownBy(() -> service.importEntries(
                Arrays.asList((ArtistGenderDictionaryService.DictionaryEntry) null)))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("记录不能为空");
        verifyNoInteractions(jdbc);
    }

    @Test
    void rejectsMissingBlankAndOversizedNamesAndGenders() {
        assertThatThrownBy(() -> service.importEntries(List.of(entry(null, "男歌手", List.of()))))
                .isInstanceOf(ApiException.class).hasMessageContaining("歌手名称不能为空");
        assertThatThrownBy(() -> service.importEntries(List.of(entry("   ", "男歌手", List.of()))))
                .isInstanceOf(ApiException.class).hasMessageContaining("歌手名称不能为空");
        assertThatThrownBy(() -> service.importEntries(List.of(entry("歌手".repeat(61), "男歌手", List.of()))))
                .isInstanceOf(ApiException.class).hasMessageContaining("不能超过 120 个字符");
        assertThatThrownBy(() -> service.importEntries(List.of(entry("歌手", null, List.of()))))
                .isInstanceOf(ApiException.class).hasMessageContaining("歌手分类不能为空");
        assertThatThrownBy(() -> service.importEntries(List.of(entry("歌手", "  ", List.of()))))
                .isInstanceOf(ApiException.class).hasMessageContaining("歌手分类不能为空");
        assertThatThrownBy(() -> service.importEntries(List.of(entry("歌手", "男歌手".repeat(61), List.of()))))
                .isInstanceOf(ApiException.class).hasMessageContaining("不能超过 120 个字符");
        verifyNoInteractions(jdbc);
    }

    @Test
    void rejectsNullBlankAndExcessiveAliasesBeforeTouchingDatabase() {
        assertThatThrownBy(() -> service.importEntries(List.of(entry("歌手", "男歌手", Arrays.asList((String) null)))))
                .isInstanceOf(ApiException.class).hasMessageContaining("歌手别名不能为空");
        assertThatThrownBy(() -> service.importEntries(List.of(entry("歌手", "男歌手", List.of("  ")))))
                .isInstanceOf(ApiException.class).hasMessageContaining("歌手别名不能为空");
        List<String> aliases = IntStream.range(0, 33).mapToObj(i -> "别名" + i).toList();
        assertThatThrownBy(() -> service.importEntries(List.of(entry("歌手", "男歌手", aliases))))
                .isInstanceOf(ApiException.class).hasMessageContaining("最多包含 32 个别名");
        verifyNoInteractions(jdbc);
    }

    @Test
    void trimsAndDeduplicatesAliasesAndAllowsMissingAliasList() {
        stubQueryRows(List.of());

        int imported = service.importEntries(List.of(
                entry(" 歌手 ", "男歌手", null),
                entry("歌手", "男歌手", List.of("歌手"))));

        assertThat(imported).isEqualTo(1);
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Collection> rows = ArgumentCaptor.forClass(Collection.class);
        verify(jdbc).batchUpdate(sql.capture(), rows.capture(), eq(1),
                any(ParameterizedPreparedStatementSetter.class));
        assertThat(sql.getValue()).contains("'ADMIN'")
                .contains("ON CONFLICT (alias_key) DO UPDATE SET");
        assertThat(rows.getValue()).hasSize(1);
    }

    @Test
    void allowsAnExistingAliasWhenItAlreadyPointsToTheSameArtist() {
        stubExistingAlias("歌手");

        assertThat(service.importEntries(List.of(entry("歌手", "男歌手", List.of())))).isEqualTo(1);
        verify(jdbc).batchUpdate(anyString(), any(Collection.class), eq(1),
                any(ParameterizedPreparedStatementSetter.class));
    }

    @Test
    void rejectsAnExistingAliasBoundToAnotherArtist() {
        stubExistingAlias("其他歌手");

        assertThatThrownBy(() -> service.importEntries(List.of(entry("歌手", "男歌手", List.of()))))
                .isInstanceOf(ApiException.class).hasMessageContaining("已绑定到其他歌手");
        verify(jdbc, never()).batchUpdate(anyString(), any(Collection.class), anyInt(),
                any(ParameterizedPreparedStatementSetter.class));
    }

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

    @Test
    void rejectsOneAliasAssignedDifferentGendersForTheSameArtist() {
        assertThatThrownBy(() -> service.importEntries(List.of(
                entry("同一歌手", "男歌手", List.of("共同别名")),
                entry("同一歌手", "女歌手", List.of("共同别名")))))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("本次导入包含冲突");
        verifyNoInteractions(jdbc);
    }

    @Test
    void seedsBundledDictionaryUsingNonOverwritingInsert() {
        stubQueryRows(List.of());

        int inserted = service.seedBuiltin();

        assertThat(inserted).isBetween(1, ArtistGenderDictionaryService.MAX_IMPORT_ENTRIES);
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc).batchUpdate(sql.capture(), any(Collection.class), eq(inserted),
                any(ParameterizedPreparedStatementSetter.class));
        assertThat(sql.getValue()).contains("'BUILTIN'")
                .contains("ON CONFLICT (alias_key) DO NOTHING");
    }

    @Test
    void builtinReaderSkipsBlankAndCommentLinesAndAcceptsRowsWithoutAliases() {
        stubQueryRows(List.of());
        ArtistGenderDictionaryService csvService = serviceForCsv(
                "display_name,gender,aliases\n\n  # comment\n歌手,男歌手\n");

        assertThat(csvService.seedBuiltin()).isEqualTo(1);
    }

    @Test
    void builtinReaderAcceptsAValidFirstRowWithoutAHeader() {
        stubQueryRows(List.of());
        ArtistGenderDictionaryService csvService = serviceForCsv("歌手,男歌手\n");

        assertThat(csvService.seedBuiltin()).isEqualTo(1);
    }

    @Test
    void builtinReaderFailsClosedForMalformedRowsAndUnreadableResources() throws IOException {
        ArtistGenderDictionaryService malformedCsv = serviceForCsv("malformed-row\n");
        assertThatThrownBy(malformedCsv::seedBuiltin)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("无法读取内置歌手分类词典")
                .cause().isInstanceOf(IOException.class);
        verifyNoInteractions(jdbc);

        Resource unreadable = mock(Resource.class);
        when(unreadable.getInputStream()).thenThrow(new IOException("test read failure"));
        ArtistGenderDictionaryService unreadableCsv = new ArtistGenderDictionaryService(jdbc, unreadable);
        assertThatThrownBy(unreadableCsv::seedBuiltin)
                .isInstanceOf(IllegalStateException.class)
                .cause().isInstanceOf(IOException.class);
        verifyNoInteractions(jdbc);
    }

    private static ArtistGenderDictionaryService.DictionaryEntry entry(
            String name, String gender, List<String> aliases) {
        return new ArtistGenderDictionaryService.DictionaryEntry(name, gender, aliases);
    }

    private ArtistGenderDictionaryService serviceForCsv(String csv) {
        return new ArtistGenderDictionaryService(jdbc,
                new ByteArrayResource(csv.getBytes(StandardCharsets.UTF_8)));
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private void stubQueryRows(List<?> rows) {
        when(jdbc.query(anyString(), any(RowMapper.class), any(Object[].class))).thenReturn((List) rows);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private void stubExistingAlias(String canonicalKey) {
        doAnswer(invocation -> {
            RowMapper mapper = invocation.getArgument(1);
            String aliasKey = invocation.getArgument(2);
            ResultSet rs = mock(ResultSet.class);
            when(rs.getString("alias_key")).thenReturn(aliasKey);
            when(rs.getString("canonical_key")).thenReturn(canonicalKey);
            return List.of(mapper.mapRow(rs, 0));
        }).when(jdbc).query(anyString(), any(RowMapper.class), any(Object[].class));
    }
}
