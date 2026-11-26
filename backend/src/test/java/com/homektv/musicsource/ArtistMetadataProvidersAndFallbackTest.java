package com.homektv.musicsource;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ArtistMetadataProvidersAndFallbackTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void neteasePrefersPicUrlOverGreyPlaceholderImg1v1Url() throws Exception {
        String json = """
                {
                  "result": {
                    "artists": [
                      {
                        "id": 6452,
                        "name": "周杰伦",
                        "alias": ["Jay Chou"],
                        "img1v1Url": "http://p1.music.126.net/6y-UleORITEDbvrOLV0Q8A==/5639395138885805.jpg",
                        "picUrl": "http://p2.music.126.net/real-hash/109951169123456789.jpg"
                      },
                      {
                        "id": 9999,
                        "name": "无头像歌手",
                        "img1v1Url": "http://p1.music.126.net/6y-UleORITEDbvrOLV0Q8A==/5639395138885805.jpg"
                      }
                    ]
                  }
                }
                """;
        NeteaseArtistMetadataProvider provider = new NeteaseArtistMetadataProvider(mapper, (MusicSourceHttp) null);

        List<ExternalArtist> parsed = provider.parseArtists(mapper.readTree(json).path("result").path("artists"), 5);

        assertThat(parsed).hasSize(2);
        assertThat(parsed.get(0).avatarUrl())
                .isEqualTo("https://p2.music.126.net/real-hash/109951169123456789.jpg");
        assertThat(parsed.get(0).aliases()).containsExactly("Jay Chou");
        assertThat(parsed.get(1).avatarUrl()).isNull();
    }

    @Test
    void qqFallsBackToMusicuRpcWhenSmartboxIsEmpty() throws Exception {
        HttpClient client = mock(HttpClient.class);
        AtomicInteger call = new AtomicInteger();
        when(client.send(any(HttpRequest.class),
                org.mockito.ArgumentMatchers.<HttpResponse.BodyHandler<InputStream>>any()))
                .thenAnswer(inv -> {
                    HttpRequest req = inv.getArgument(0);
                    String body = call.getAndIncrement() == 0
                            ? """
                              {"data":{"singer":{"itemlist":[]}}}
                              """
                            : """
                              {"req":{"data":{"body":{"singer":{"list":[
                                {"singerMid":"0025NhlN2yWrP4","singerName":"周杰伦","singerPic":"http://y.gtimg.cn/music/photo_new/T001R300x300M0000025NhlN2yWrP4.jpg"}
                              ]}}}}}
                              """;
                    assertThat(req.uri().getHost()).isIn("c.y.qq.com", "u.y.qq.com");
                    return jsonResponse(body);
                });
        MusicSourceHttp http = new MusicSourceHttp(
                mapper, MusicProvider.QQ, Set.of("c.y.qq.com", "u.y.qq.com"), client, null);
        QqArtistMetadataProvider provider = new QqArtistMetadataProvider(mapper, http);

        List<ExternalArtist> artists = provider.search("周杰伦 (Jay Chou)", 5, Duration.ofSeconds(3));

        assertThat(artists).singleElement().satisfies(artist -> {
            assertThat(artist.displayName()).isEqualTo("周杰伦");
            assertThat(artist.avatarUrl())
                    .isEqualTo("https://y.gtimg.cn/music/photo_new/T001R300x300M0000025NhlN2yWrP4.jpg");
        });
    }

    @Test
    void kugouParsesSingerEndpointAndFallsBackToSongAuthors() throws Exception {
        HttpClient client = mock(HttpClient.class);
        AtomicInteger call = new AtomicInteger();
        when(client.send(any(HttpRequest.class),
                org.mockito.ArgumentMatchers.<HttpResponse.BodyHandler<InputStream>>any()))
                .thenAnswer(inv -> {
                    String body = call.getAndIncrement() == 0
                            ? """
                              {"status":1,"errcode":0,"data":{"info":[]}}
                              """
                            : """
                              {"status":1,"error_code":0,"data":{"info":[
                                {"authors":[{"author_id":"3520","author_name":"周杰伦","sizable_avatar":"http://singerimg.kugou.com/uploadpic/softhead/{size}/20210101/20210101.jpg"}]}
                              ]}}
                              """;
                    return jsonResponse(body);
                });
        MusicSourceConfigService config = mock(MusicSourceConfigService.class);
        when(config.getOrCreateKugouDeviceId()).thenReturn("DEVICE123");
        MusicSourceHttp http = new MusicSourceHttp(
                mapper, MusicProvider.KUGOU,
                Set.of("mobilecdnbj.kugou.com", "mobilecdn.kugou.com", "gateway.kugou.com"),
                client, null);
        KugouArtistMetadataProvider provider = new KugouArtistMetadataProvider(http, config);

        List<ExternalArtist> artists = provider.search("周杰伦", 5, Duration.ofSeconds(3));

        assertThat(artists).singleElement().satisfies(artist -> {
            assertThat(artist.provider()).isEqualTo(MusicProvider.KUGOU);
            assertThat(artist.displayName()).isEqualTo("周杰伦");
            assertThat(artist.avatarUrl())
                    .isEqualTo("https://singerimg.kugou.com/uploadpic/softhead/400/20210101/20210101.jpg");
        });
    }

    @Test
    void fallbackChainExtractsDeezerWikipediaBaikeAndWebSearchCandidates() throws Exception {
        HttpClient client = mock(HttpClient.class);
        when(client.send(any(HttpRequest.class),
                org.mockito.ArgumentMatchers.<HttpResponse.BodyHandler<InputStream>>any()))
                .thenAnswer(inv -> {
                    String uri = inv.getArgument(0, HttpRequest.class).uri().toString();
                    if (uri.contains("api.deezer.com")) {
                        return jsonResponse("""
                                {"data":[{"id":1,"name":"Adele","picture_xl":"https://e-cdns-images.dzcdn.net/images/artist/abc/1000x1000.jpg"}]}
                                """);
                    }
                    if (uri.contains("wikipedia.org")) {
                        return jsonResponse("""
                                {"title":"陈奕迅","description":"香港男歌手","originalimage":{"source":"https://upload.wikimedia.org/eason.jpg"}}
                                """);
                    }
                    if (uri.contains("baike.baidu.com")) {
                        return jsonResponse("""
                                {"title":"国风堂","desc":"华语原创音乐社团、歌手组合","image":"https://bkimg.cdn.bcebos.com/pic/gft.jpg"}
                                """);
                    }
                    if (uri.contains("image.baidu.com")) {
                        return jsonResponse("""
                                {"data":[{"middleURL":"https://img1.baidu.com/it/artist.jpg"}]}
                                """);
                    }
                    return jsonResponse("");
                });
        ProviderCallGuard guard = new ProviderCallGuard();
        MusicSourceConfigService config = mock(MusicSourceConfigService.class);
        when(config.isAvatarFallbackEnabled()).thenReturn(true);
        ArtistAvatarFallbackChain chain = new ArtistAvatarFallbackChain(client, mapper, guard, config, 400);

        assertThat(chain.searchDeezer("Adele", "Adele", Duration.ofSeconds(2)))
                .containsExactly("https://e-cdns-images.dzcdn.net/images/artist/abc/1000x1000.jpg");
        assertThat(chain.searchWikipedia("陈奕迅", "陈奕迅", "zh", Duration.ofSeconds(2)))
                .containsExactly("https://upload.wikimedia.org/eason.jpg");
        assertThat(chain.searchBaiduBaike("国风堂", "国风堂", Duration.ofSeconds(2)))
                .containsExactly("https://bkimg.cdn.bcebos.com/pic/gft.jpg");
        assertThat(chain.searchBaiduImages("小众歌手", Duration.ofSeconds(2)))
                .containsExactly("https://img1.baidu.com/it/artist.jpg");
    }

    @Test
    void kugouUsesGatewayWithMobilecdnRouterAndEnrichesSingerInfoWhenAvatarMissing() throws Exception {
        HttpClient client = mock(HttpClient.class);
        List<HttpRequest> requests = new java.util.ArrayList<>();
        when(client.send(any(HttpRequest.class),
                org.mockito.ArgumentMatchers.<HttpResponse.BodyHandler<InputStream>>any()))
                .thenAnswer(inv -> {
                    HttpRequest req = inv.getArgument(0);
                    requests.add(req);
                    String uri = req.uri().toString();
                    if (uri.contains("/api/v3/search/singer")) {
                        return jsonResponse("""
                                {"status":1,"errcode":0,"data":[{"singername":"周杰伦","singerid":3520}]}
                                """);
                    }
                    if (uri.contains("/api/v3/singer/info")) {
                        return jsonResponse("""
                                {"status":1,"errcode":0,"data":{"singerid":3520,"singername":"周杰伦",
                                  "imgurl":"http://singerimg.kugou.com/uploadpic/softhead/{size}/20260324/jay.jpg"}}
                                """);
                    }
                    return jsonResponse("{}");
                });
        MusicSourceConfigService config = mock(MusicSourceConfigService.class);
        MusicSourceHttp http = new MusicSourceHttp(
                mapper, MusicProvider.KUGOU, Set.of("gateway.kugou.com"), client, null);
        KugouArtistMetadataProvider provider = new KugouArtistMetadataProvider(http, config);

        List<ExternalArtist> artists = provider.search("周杰伦", 5, Duration.ofSeconds(3));

        assertThat(artists).singleElement().satisfies(artist -> {
            assertThat(artist.externalId()).isEqualTo("3520");
            assertThat(artist.displayName()).isEqualTo("周杰伦");
            assertThat(artist.avatarUrl())
                    .isEqualTo("https://singerimg.kugou.com/uploadpic/softhead/400/20260324/jay.jpg");
        });
        assertThat(requests).hasSize(2);
        assertThat(requests.get(0).uri().toString()).startsWith("https://gateway.kugou.com/api/v3/search/singer");
        assertThat(requests.get(0).headers().firstValue("x-router")).contains("mobilecdn.kugou.com");
        assertThat(requests.get(1).uri().toString()).startsWith("https://gateway.kugou.com/api/v3/singer/info");
        assertThat(requests.get(1).headers().firstValue("x-router")).contains("mobilecdn.kugou.com");
    }

    @Test
    void kugouSongAuthorsFallbackReadsListsAndSingersField() throws Exception {
        HttpClient client = mock(HttpClient.class);
        when(client.send(any(HttpRequest.class),
                org.mockito.ArgumentMatchers.<HttpResponse.BodyHandler<InputStream>>any()))
                .thenAnswer(inv -> {
                    String uri = inv.getArgument(0, HttpRequest.class).uri().toString();
                    if (uri.contains("/api/v3/search/singer")) {
                        return jsonResponse("""
                                {"status":1,"errcode":0,"data":[]}
                                """);
                    }
                    return jsonResponse("""
                            {"status":1,"error_code":0,"data":{"lists":[
                              {"SongName":"发如雪","Singers":[{"id":3520,"name":"周杰伦"}],
                               "Image":"http://imge.kugou.com/stdmusic/{size}/20250125/jay.jpg"}
                            ]}}
                            """);
                });
        MusicSourceConfigService config = mock(MusicSourceConfigService.class);
        when(config.getOrCreateKugouDeviceId()).thenReturn("DEVICE123");
        MusicSourceHttp http = new MusicSourceHttp(
                mapper, MusicProvider.KUGOU, Set.of("gateway.kugou.com"), client, null);
        KugouArtistMetadataProvider provider = new KugouArtistMetadataProvider(http, config);

        List<ExternalArtist> artists = provider.search("周杰伦", 5, Duration.ofSeconds(3));

        assertThat(artists).singleElement().satisfies(artist -> {
            assertThat(artist.externalId()).isEqualTo("3520");
            assertThat(artist.displayName()).isEqualTo("周杰伦");
            assertThat(artist.avatarUrl())
                    .isEqualTo("https://imge.kugou.com/stdmusic/400/20250125/jay.jpg");
        });
    }

    @Test
    void qqStopsAfterMusicuWithoutIssuingThirdSongSearchRequest() throws Exception {
        HttpClient client = mock(HttpClient.class);
        AtomicInteger callCount = new AtomicInteger();
        when(client.send(any(HttpRequest.class),
                org.mockito.ArgumentMatchers.<HttpResponse.BodyHandler<InputStream>>any()))
                .thenAnswer(inv -> {
                    callCount.incrementAndGet();
                    String uri = inv.getArgument(0, HttpRequest.class).uri().toString();
                    if (uri.contains("smartbox_new.fcg")) {
                        return jsonResponse("""
                                {"data":{"singer":{"itemlist":[]}}}
                                """);
                    }
                    if (uri.contains("musicu.fcg")) {
                        return jsonResponse("""
                                {"code":0,"req":{"code":500003,"subcode":860100001}}
                                """);
                    }
                    throw new AssertionError("unexpected 3rd call: " + uri);
                });
        MusicSourceHttp http = new MusicSourceHttp(
                mapper, MusicProvider.QQ, Set.of("c.y.qq.com", "u.y.qq.com"), client, null);
        QqArtistMetadataProvider provider = new QqArtistMetadataProvider(mapper, http);

        List<ExternalArtist> artists = provider.search("周杰伦", 5, Duration.ofSeconds(3));

        assertThat(artists).isEmpty();
        assertThat(callCount).hasValue(2);
    }

    @Test
    void fallbackChainOpensCircuitAfterThreeConsecutiveTimeoutsAndSkipsBlockedSource() throws Exception {
        HttpClient client = mock(HttpClient.class);
        AtomicInteger deezerCalls = new AtomicInteger();
        when(client.send(any(HttpRequest.class),
                org.mockito.ArgumentMatchers.<HttpResponse.BodyHandler<InputStream>>any()))
                .thenAnswer(inv -> {
                    String uri = inv.getArgument(0, HttpRequest.class).uri().toString();
                    if (uri.contains("api.deezer.com")) {
                        deezerCalls.incrementAndGet();
                        throw new java.io.IOException("connect timed out");
                    }
                    return jsonResponse("{}");
                });
        ProviderCallGuard guard = new ProviderCallGuard(() -> new MusicSourceConfig(
                true, Set.of(MusicProvider.QQ), 20, 5, 6, 1, 1500, 0.95, 3000,
                Set.of(AvatarFallbackProvider.DEEZER), 2000));
        MusicSourceConfigService config = mock(MusicSourceConfigService.class);
        when(config.getConfig()).thenReturn(new MusicSourceConfig(
                true, Set.of(MusicProvider.QQ), 20, 5, 6, 1, 1500, 0.95, 3000,
                Set.of(AvatarFallbackProvider.DEEZER), 2000));
        ArtistAvatarFallbackChain chain = new ArtistAvatarFallbackChain(client, mapper, guard, config, 2000);

        for (int i = 0; i < 3; i++) {
            assertThat(chain.findCandidateUrls("Adele", Duration.ofMillis(200))).isEmpty();
        }
        assertThat(deezerCalls).hasValue(3);

        org.assertj.core.api.Assertions.assertThatThrownBy(
                () -> chain.findCandidateUrls("Adele", Duration.ofMillis(200)))
                .isInstanceOf(ProviderRateLimitedException.class);
        assertThat(deezerCalls).hasValue(3);

        chain.resetCircuitBreakers();
        assertThat(chain.hasAvailableProvider()).isTrue();
    }

    @Test
    void fallbackChainTriesBaiduBaikeBeforeOverseasSources() throws Exception {
        HttpClient client = mock(HttpClient.class);
        List<String> hosts = new java.util.ArrayList<>();
        when(client.send(any(HttpRequest.class),
                org.mockito.ArgumentMatchers.<HttpResponse.BodyHandler<InputStream>>any()))
                .thenAnswer(inv -> {
                    String uri = inv.getArgument(0, HttpRequest.class).uri().toString();
                    hosts.add(java.net.URI.create(uri).getHost());
                    if (uri.contains("baike.baidu.com")) {
                        return jsonResponse("""
                                {"title":"周杰伦","desc":"华语流行乐男歌手","image":"https://bkimg.cdn.bcebos.com/pic/jay.jpg"}
                                """);
                    }
                    throw new java.io.IOException("overseas host blocked");
                });
        ProviderCallGuard guard = new ProviderCallGuard();
        MusicSourceConfigService config = mock(MusicSourceConfigService.class);
        when(config.isAvatarFallbackEnabled()).thenReturn(true);
        ArtistAvatarFallbackChain chain = new ArtistAvatarFallbackChain(client, mapper, guard, config, 400);

        List<String> urls = chain.findCandidateUrls("周杰伦", Duration.ofSeconds(2));

        assertThat(urls).containsExactly("https://bkimg.cdn.bcebos.com/pic/jay.jpg");
        assertThat(hosts).containsExactly("baike.baidu.com");
    }

    @Test
    void fallbackChainAbortsStalledResponseStreamWithinTimeout() throws Exception {
        HttpClient client = mock(HttpClient.class);
        InputStream stalledStream = new InputStream() {
            @Override
            public int read() throws java.io.IOException {
                try {
                    Thread.sleep(5_000);
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                }
                return -1;
            }
        };
        HttpResponse<InputStream> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(200);
        when(response.headers()).thenReturn(HttpHeaders.of(Map.of(), (k, v) -> true));
        when(response.body()).thenReturn(stalledStream);
        when(client.send(any(HttpRequest.class),
                org.mockito.ArgumentMatchers.<HttpResponse.BodyHandler<InputStream>>any())).thenReturn(response);

        ProviderCallGuard guard = new ProviderCallGuard();
        MusicSourceConfigService config = mock(MusicSourceConfigService.class);
        when(config.isAvatarFallbackEnabled()).thenReturn(true);
        ArtistAvatarFallbackChain chain = new ArtistAvatarFallbackChain(client, mapper, guard, config, 400);

        List<String> urls = chain.searchBaiduBaike("周杰伦", "周杰伦", Duration.ofMillis(100));

        assertThat(urls).isEmpty();
    }

    private static HttpResponse<InputStream> jsonResponse(String json) {
        HttpResponse<InputStream> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(200);
        when(response.headers()).thenReturn(HttpHeaders.of(Map.of(), (k, v) -> true));
        when(response.body()).thenReturn(new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8)));
        return response;
    }
}
