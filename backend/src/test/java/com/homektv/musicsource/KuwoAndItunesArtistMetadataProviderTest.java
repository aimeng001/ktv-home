package com.homektv.musicsource;

import com.fasterxml.jackson.core.JsonParser;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class KuwoAndItunesArtistMetadataProviderTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void kuwoParsesSingleQuotedJsonAndUpgradesPicUrlToHttps() throws Exception {
        String singleQuoted = """
                {'ARTISTPIC':'','BASEPICPATH':'http://img1.kuwo.cn/star/starheads/','HIT':'2','abslist':[
                  {'AARTIST':'Jay&nbsp;Chou','ARTIST':'周杰伦','ARTISTID':'336','PICPATH':'240/s4s56/58/291211030.jpg'},
                  {'AARTIST':'','ARTIST':'无头像','ARTISTID':'999','PICPATH':''}
                ]}
                """;
        HttpClient client = mock(HttpClient.class);
        when(client.send(any(HttpRequest.class),
                org.mockito.ArgumentMatchers.<HttpResponse.BodyHandler<InputStream>>any()))
                .thenAnswer(inv -> textResponse(200, singleQuoted));

        MusicSourceHttp http = new MusicSourceHttp(
                mapper.copy().enable(JsonParser.Feature.ALLOW_SINGLE_QUOTES),
                MusicProvider.KUWO,
                Set.of("search.kuwo.cn", "artistpicserver.kuwo.cn"),
                client,
                null);
        KuwoArtistMetadataProvider provider = new KuwoArtistMetadataProvider(http);

        List<ExternalArtist> artists = provider.search("周杰伦", 5, Duration.ofSeconds(3));

        assertThat(artists).hasSize(2);
        assertThat(artists.get(0)).satisfies(artist -> {
            assertThat(artist.provider()).isEqualTo(MusicProvider.KUWO);
            assertThat(artist.externalId()).isEqualTo("336");
            assertThat(artist.displayName()).isEqualTo("周杰伦");
            assertThat(artist.aliases()).containsExactly("Jay Chou");
            assertThat(artist.avatarUrl()).isEqualTo("https://img1.kuwo.cn/star/starheads/240/s4s56/58/291211030.jpg");
        });
        assertThat(artists.get(1).avatarUrl()).isNull();
    }

    @Test
    void itunesQueriesArtistApiAndExtractsOfficialAppleMusicPortraitViaMediaChannel() throws Exception {
        HttpClient client = mock(HttpClient.class);
        when(client.send(any(HttpRequest.class),
                org.mockito.ArgumentMatchers.<HttpResponse.BodyHandler<InputStream>>any()))
                .thenAnswer(inv -> {
                    String uri = inv.getArgument(0, HttpRequest.class).uri().toString();
                    if (uri.contains("itunes.apple.com/search")) {
                        return textResponse(200, """
                                {
                                  "resultCount": 1,
                                  "results": [{
                                    "wrapperType": "artist",
                                    "artistId": 300117743,
                                    "artistName": "周杰倫",
                                    "artistLinkUrl": "https://music.apple.com/cn/artist/300117743"
                                  }]
                                }
                                """);
                    }
                    if (uri.contains("music.apple.com/cn/artist/300117743")) {
                        return textResponse(200, """
                                <html><head>
                                  <meta property="og:image" content="https://is1-ssl.mzstatic.com/image/thumb/AMCArtistImages211/v4/6d/53/0e/jay/1200x630cw.png" />
                                </head><body></body></html>
                                """);
                    }
                    return textResponse(404, "");
                });

        MusicSourceHttp http = new MusicSourceHttp(
                mapper, MusicProvider.ITUNES, Set.of("itunes.apple.com", "music.apple.com"), client, null);
        ItunesArtistMetadataProvider provider = new ItunesArtistMetadataProvider(http);

        List<ExternalArtist> artists = provider.search("周杰伦", 5, Duration.ofSeconds(3));

        assertThat(artists).singleElement().satisfies(artist -> {
            assertThat(artist.provider()).isEqualTo(MusicProvider.ITUNES);
            assertThat(artist.externalId()).isEqualTo("300117743");
            assertThat(artist.displayName()).isEqualTo("周杰倫");
            assertThat(artist.avatarUrl())
                    .isEqualTo("https://is1-ssl.mzstatic.com/image/thumb/AMCArtistImages211/v4/6d/53/0e/jay/1200x630cw.png");
        });
    }

    @Test
    void itunesIgnoresGenericAppleMusicBrandCards() {
        String html = """
                <html><head>
                  <meta property="og:image" content="https://is1-ssl.mzstatic.com/image/thumb/static-apple-music-logo.png" />
                </head></html>
                """;
        assertThat(ItunesArtistMetadataProvider.extractPortraitUrl(html)).isNull();
    }

    @SuppressWarnings("unchecked")
    private static HttpResponse<InputStream> textResponse(int status, String body) {
        HttpResponse<InputStream> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(status);
        when(response.headers()).thenReturn(HttpHeaders.of(Map.of(), (k, v) -> true));
        when(response.body()).thenReturn(new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8)));
        return response;
    }
}
