package com.homektv.musicsource;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

class MusicMetadataProviderParsingTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void kugouParserSupportsProviderFieldVariantsAndDurationUnits() throws Exception {
        KugouMusicMetadataProvider provider = new KugouMusicMetadataProvider(
                mapper, new ProviderCallGuard(), mock(MusicSourceConfigService.class));
        JsonNode songs = mapper.readTree("""
                [
                  {"hash":"one","songname":"晴天","authors":[{"author_name":"周杰伦"}],"sizable_cover":"http://img.kugou.com/{size}/cover.jpg","duration":180,"album_name":"叶惠美"},
                  {"FileHash":"two","SongName":"光辉岁月","authors":[],"singername":"Beyond / 黄家驹","Image":"https://evil.example/cover.jpg","Duration":240,"AlbumName":"光辉岁月"},
                  {"audio_id":"three","song_name":"成都","singer_name":"赵雷","timelength":205000},
                  {"songname":"missing id"}
                ]
                """);

        var tracks = provider.parseSongs(songs, 10);

        assertThat(tracks).hasSize(3);
        assertThat(tracks.get(0).externalId()).isEqualTo("one");
        assertThat(tracks.get(0).durationMs()).isEqualTo(180_000);
        assertThat(tracks.get(0).artists()).containsExactly("周杰伦");
        assertThat(tracks.get(0).coverUrl()).isEqualTo("https://img.kugou.com/400/cover.jpg");
        assertThat(tracks.get(1).artists()).containsExactly("Beyond", "黄家驹");
        assertThat(tracks.get(1).durationMs()).isEqualTo(240_000);
        assertThat(tracks.get(1).coverUrl()).isNull();
        assertThat(tracks.get(2).durationMs()).isEqualTo(205_000);
        assertThat(provider.parseSongs(mapper.readTree("{}"), 10)).isEmpty();
        assertThat(provider.parseSongs(songs, 1)).hasSize(1);
    }

    @Test
    void kugouParserSupportsAndroidGatewayListsFormatWithOriSongNameAndFileName() throws Exception {
        KugouMusicMetadataProvider provider = new KugouMusicMetadataProvider(
                mapper, new ProviderCallGuard(), mock(MusicSourceConfigService.class));
        JsonNode songs = mapper.readTree("""
                [
                  {
                    "FileHash": "B3A52A7A958BF0AED0EBFBA2E9A818B7",
                    "FileName": "周杰伦 - 晴天",
                    "OriSongName": "晴天",
                    "SingerName": "周杰伦",
                    "AlbumName": "叶惠美",
                    "Duration": 269,
                    "PublishDate": "2003-07-31",
                    "Image": "http://imge.kugou.com/stdmusic/{size}/20230920/晴天.jpg"
                  },
                  {
                    "FileHash": "1F7E7659B9FFF4FB6F71562A61BBC25F",
                    "FileName": "陈奕迅 - 十年",
                    "SingerName": "陈奕迅",
                    "Duration": 205,
                    "trans_param": {
                      "union_cover": "http://imge.kugou.com/stdmusic/{size}/20240101/十年.jpg"
                    }
                  }
                ]
                """);

        var tracks = provider.parseSongs(songs, 10);

        assertThat(tracks).hasSize(2);
        assertThat(tracks.get(0).title()).isEqualTo("晴天");
        assertThat(tracks.get(0).artists()).containsExactly("周杰伦");
        assertThat(tracks.get(0).album()).isEqualTo("叶惠美");
        assertThat(tracks.get(0).coverUrl()).isEqualTo("https://imge.kugou.com/stdmusic/400/20230920/晴天.jpg");
        assertThat(tracks.get(0).durationMs()).isEqualTo(269_000);

        assertThat(tracks.get(1).title()).isEqualTo("十年");
        assertThat(tracks.get(1).artists()).containsExactly("陈奕迅");
        assertThat(tracks.get(1).coverUrl()).isEqualTo("https://imge.kugou.com/stdmusic/400/20240101/十年.jpg");
    }

    @Test
    void kugouSignatureIsIndependentOfParameterInsertionOrderAndIncludesBody() {
        Map<String, Object> first = new LinkedHashMap<>();
        first.put("keyword", "晴天");
        first.put("page", 1);
        first.put("signature", "ignored");
        Map<String, Object> second = new LinkedHashMap<>();
        second.put("signature", "different");
        second.put("page", 1);
        second.put("keyword", "晴天");

        String signature = KugouMusicMetadataProvider.androidSignature(first, "body");

        assertThat(signature).hasSize(32).matches("[0-9a-f]{32}");
        assertThat(signature).isEqualTo(KugouMusicMetadataProvider.androidSignature(second, "body"));
        assertThat(signature).isNotEqualTo(KugouMusicMetadataProvider.androidSignature(second, "changed"));
    }

    @Test
    void qqParserSupportsNestedAndFlatFieldsAndSecondBasedDurations() throws Exception {
        QqMusicMetadataProvider provider = new QqMusicMetadataProvider(mapper, new ProviderCallGuard());
        JsonNode songs = mapper.readTree("""
                [
                  {"songmid":"mid-1","songname":"晴天","singer":[{"name":"周杰伦"}],"album":{"name":"叶惠美","mid":"album-1"},"interval":180,"pubtime":1704067200,"grp":[{"title":"现场版"}]},
                  {"mid":"mid-2","title":"成都","singername":"赵雷/陈粒","albumname":"成都","duration":240},
                  {"songmid":"mid-3","songname":"未知时长","singername":"歌手"},
                  {"name":"missing id","singer":[]}
                ]
                """);

        var tracks = provider.parseSongs(songs, 10);

        assertThat(tracks).hasSize(3);
        assertThat(tracks.get(0).externalId()).isEqualTo("mid-1");
        assertThat(tracks.get(0).durationMs()).isEqualTo(180_000);
        assertThat(tracks.get(0).artists()).containsExactly("周杰伦");
        assertThat(tracks.get(0).album()).isEqualTo("叶惠美");
        assertThat(tracks.get(0).coverUrl()).contains("album-1.jpg");
        assertThat(tracks.get(0).releaseDate()).isEqualTo("2024-01-01");
        assertThat(tracks.get(0).aliases()).containsExactly("现场版");
        assertThat(tracks.get(1).artists()).containsExactly("赵雷", "陈粒");
        assertThat(tracks.get(1).durationMs()).isEqualTo(240_000);
        assertThat(tracks.get(1).coverUrl()).isNull();
        assertThat(tracks.get(2).durationMs()).isNull();
        assertThat(provider.parseSongs(mapper.readTree("null"), 10)).isEmpty();
        assertThat(provider.parseSongs(songs, 1)).hasSize(1);
    }

    @Test
    void neteaseParserSupportsLegacyAndCurrentFieldsAvailabilityAndDateUnits() throws Exception {
        NeteaseMusicMetadataProvider provider = new NeteaseMusicMetadataProvider(mapper, new ProviderCallGuard());
        JsonNode songs = mapper.readTree("""
                [
                  {"id":123,"name":"晴天","al":{"name":"叶惠美","picUrl":"http://p1.music.126.net/cover.jpg"},"ar":[{"name":"周杰伦"}],"alia":["现场版"],"dt":180000,"publishTime":1704067200000,"fee":4},
                  {"id":456,"name":"成都","album":{"name":"单曲"},"artists":[{"name":"赵雷"}],"alias":["Live"],"duration":205000,"fee":0},
                  {"id":789,"name":"未知时长","artists":[]},
                  {"name":"missing id"}
                ]
                """);

        var tracks = provider.parseSongs(songs, 10);

        assertThat(tracks).hasSize(3);
        assertThat(tracks.get(0).externalId()).isEqualTo("123");
        assertThat(tracks.get(0).durationMs()).isEqualTo(180_000);
        assertThat(tracks.get(0).coverUrl()).isEqualTo("https://p1.music.126.net/cover.jpg");
        assertThat(tracks.get(0).releaseDate()).isEqualTo("2024-01-01");
        assertThat(tracks.get(0).aliases()).containsExactly("现场版");
        assertThat(tracks.get(0).availability()).isEqualTo("UNAVAILABLE");
        assertThat(tracks.get(1).durationMs()).isEqualTo(205_000);
        assertThat(tracks.get(1).album()).isEqualTo("单曲");
        assertThat(tracks.get(1).availability()).isEqualTo("AVAILABLE");
        assertThat(tracks.get(2).durationMs()).isNull();
        assertThat(provider.parseSongs(mapper.readTree("[]"), 10)).isEmpty();
        assertThat(provider.parseSongs(songs, 1)).hasSize(1);
        assertThatThrownBy(() -> provider.detail("invalid-id", Duration.ofSeconds(1)))
                .isInstanceOfSatisfying(MusicSourceException.class,
                        failure -> assertThat(failure).hasMessage("网易云歌曲 ID 无效"));
    }
}
