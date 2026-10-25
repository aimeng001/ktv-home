package com.homektv.musicsource;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MusicMetadataProviderHttpTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final Duration timeout = Duration.ofSeconds(3);

    @Test
    void kugouUsesMobileSearchInfoAndClampsPageSize() throws Exception {
        MusicSourceHttp http = mock(MusicSourceHttp.class);
        when(http.get(anyString(), any(), any())).thenReturn(json("""
                {"status":1,"error_code":0,"data":{"info":[
                  {"hash":"hash-1","songname":"晴天","authors":[{"author_name":"周杰伦"}],"duration":180}
                ]}}
                """));
        MusicSourceConfigService config = mock(MusicSourceConfigService.class);
        when(config.getOrCreateKugouDeviceId()).thenReturn("device-1");
        KugouMusicMetadataProvider provider = new KugouMusicMetadataProvider(http, config);

        var tracks = provider.search("周杰伦 晴天", 80, timeout);

        assertThat(tracks).hasSize(1);
        assertThat(tracks.getFirst().externalId()).isEqualTo("hash-1");
        assertThat(tracks.getFirst().durationMs()).isEqualTo(180_000);
        org.mockito.ArgumentCaptor<String> urls = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(http).get(urls.capture(), any(), any());
        assertThat(urls.getValue()).startsWith("https://gateway.kugou.com/v3/search/song?")
                .contains("pagesize=50", "keyword=%E5%91%A8%E6%9D%B0%E4%BC%A6+%E6%99%B4%E5%A4%A9");
    }

    @Test
    void kugouFallsBackToWebSearchForMobileRestrictionAndUsesListsPayload() throws Exception {
        MusicSourceHttp http = mock(MusicSourceHttp.class);
        when(http.get(anyString(), any(), any())).thenReturn(
                json("{\"status\":0,\"error_code\":152}"),
                json("""
                        {"status":1,"error_code":0,"data":{"lists":[
                          {"FileHash":"hash-web","SongName":"成都","singername":"赵雷","Duration":205}
                        ]}}
                        """));
        MusicSourceConfigService config = mock(MusicSourceConfigService.class);
        when(config.getOrCreateKugouDeviceId()).thenReturn("device-1");
        KugouMusicMetadataProvider provider = new KugouMusicMetadataProvider(http, config);

        var tracks = provider.search("成都", 7, timeout);

        assertThat(tracks).hasSize(1);
        assertThat(tracks.getFirst().externalId()).isEqualTo("hash-web");
        assertThat(tracks.getFirst().artists()).containsExactly("赵雷");
        org.mockito.ArgumentCaptor<String> urls = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(http, times(2)).get(urls.capture(), any(), any());
        assertThat(urls.getAllValues()).anyMatch(url -> url.startsWith("https://songsearch.kugou.com/song_search_v2?"));
        assertThat(urls.getAllValues().get(1)).contains("pagesize=7", "keyword=%E6%88%90%E9%83%BD");
    }

    @Test
    void kugouDistinguishesMobileAndFallbackErrorsAndMissingDetails() throws Exception {
        MusicSourceHttp mobileFailureHttp = mock(MusicSourceHttp.class);
        when(mobileFailureHttp.get(anyString(), any(), any())).thenReturn(json("{\"status\":0,\"error_code\":500}"));
        KugouMusicMetadataProvider mobileFailure = kugou(mobileFailureHttp);
        assertThatThrownBy(() -> mobileFailure.search("x", 1, timeout))
                .isInstanceOfSatisfying(MusicSourceException.class,
                        failure -> assertThat(failure).hasMessage("酷狗搜索暂时不可用"));

        MusicSourceHttp fallbackFailureHttp = mock(MusicSourceHttp.class);
        when(fallbackFailureHttp.get(anyString(), any(), any())).thenReturn(
                json("{\"status\":0,\"error_code\":152}"),
                json("{\"status\":0,\"error_code\":500}"));
        KugouMusicMetadataProvider fallbackFailure = kugou(fallbackFailureHttp);
        assertThatThrownBy(() -> fallbackFailure.search("x", 1, timeout))
                .isInstanceOfSatisfying(MusicSourceException.class,
                        failure -> assertThat(failure).hasMessage("酷狗公开搜索暂时不可用"));

        MusicSourceHttp emptyHttp = mock(MusicSourceHttp.class);
        when(emptyHttp.get(anyString(), any(), any())).thenReturn(json("{\"status\":1,\"error_code\":0,\"data\":{\"info\":[]}}"));
        assertThatThrownBy(() -> kugou(emptyHttp).detail("missing", timeout))
                .isInstanceOfSatisfying(MusicSourceException.class,
                        failure -> assertThat(failure).hasMessage("歌曲详情不存在"));
    }

    @Test
    void qqSearchParsesResultsAndDetailEncodesIdAndRejectsEmptyResult() throws Exception {
        MusicSourceHttp http = mock(MusicSourceHttp.class);
        when(http.get(anyString(), any(), any())).thenReturn(
                json("""
                        {"data":{"song":{"list":[{"songmid":"mid-1","songname":"晴天","singer":[{"name":"周杰伦"}]}]}}}
                        """),
                json("{\"data\":[{\"songmid\":\"mid 2\",\"songname\":\"成都\"}] }"),
                json("{\"data\":[]}"));
        QqMusicMetadataProvider provider = new QqMusicMetadataProvider(http);

        var tracks = provider.search("晴天", 4, timeout);
        assertThat(tracks).hasSize(1);
        assertThat(tracks.getFirst().externalId()).isEqualTo("mid-1");
        assertThat(provider.detail("mid 2", timeout).externalId()).isEqualTo("mid 2");
        assertThatThrownBy(() -> provider.detail("missing", timeout))
                .isInstanceOfSatisfying(MusicSourceException.class,
                        failure -> assertThat(failure).hasMessage("歌曲详情不存在"));

        org.mockito.ArgumentCaptor<String> urls = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(http, times(3)).get(urls.capture(), any(), any());
        assertThat(urls.getAllValues().get(0)).startsWith("https://c.y.qq.com/soso/fcgi-bin/client_search_cp?")
                .contains("n=4", "w=%E6%99%B4%E5%A4%A9");
        assertThat(urls.getAllValues().get(1)).contains("songmid=mid+2");
    }

    @Test
    void neteaseSearchAndDetailUseExpectedFormsAndRejectEmptyDetail() throws Exception {
        MusicSourceHttp http = mock(MusicSourceHttp.class);
        when(http.form(anyString(), any(), any(), any())).thenReturn(
                json("""
                        {"result":{"songs":[{"id":123,"name":"晴天","ar":[{"name":"周杰伦"}],"dt":180000}]}}
                        """),
                json("{\"songs\":[{\"id\":456,\"name\":\"成都\",\"artists\":[{\"name\":\"赵雷\"}]}]}"),
                json("{\"songs\":[]}"));
        NeteaseMusicMetadataProvider provider = new NeteaseMusicMetadataProvider(mapper, http);

        var tracks = provider.search("晴天", 60, timeout);
        assertThat(tracks).hasSize(1);
        assertThat(tracks.getFirst().externalId()).isEqualTo("123");
        assertThat(provider.detail("456", timeout).externalId()).isEqualTo("456");
        assertThatThrownBy(() -> provider.detail("789", timeout))
                .isInstanceOfSatisfying(MusicSourceException.class,
                        failure -> assertThat(failure).hasMessage("歌曲详情不存在"));

        org.mockito.ArgumentCaptor<String> urls = org.mockito.ArgumentCaptor.forClass(String.class);
        @SuppressWarnings("unchecked")
        org.mockito.ArgumentCaptor<Map<String, String>> forms = org.mockito.ArgumentCaptor.forClass(Map.class);
        verify(http, times(3)).form(urls.capture(), forms.capture(), any(), any());
        assertThat(urls.getAllValues().get(0)).isEqualTo("https://interfacepc.music.163.com/eapi/cloudsearch/pc");
        assertThat(forms.getAllValues().get(0)).containsKey("params");
        assertThat(urls.getAllValues().get(1)).isEqualTo("https://music.163.com/weapi/v3/song/detail");
        assertThat(forms.getAllValues().get(1).keySet()).containsExactlyInAnyOrder("params", "encSecKey");
    }

    private KugouMusicMetadataProvider kugou(MusicSourceHttp http) {
        MusicSourceConfigService config = mock(MusicSourceConfigService.class);
        when(config.getOrCreateKugouDeviceId()).thenReturn("device-1");
        return new KugouMusicMetadataProvider(http, config);
    }

    private JsonNode json(String value) throws Exception {
        return mapper.readTree(value);
    }
}
