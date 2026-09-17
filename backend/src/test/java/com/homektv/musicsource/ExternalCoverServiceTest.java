package com.homektv.musicsource;

import com.homektv.library.AssetWriter;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.function.Supplier;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ExternalCoverServiceTest {

    @Test
    void cacheIsWrittenOnlyAfterTheGuardedHttpOperationCompletes() throws Exception {
        AssetWriter writer = mock(AssetWriter.class);
        ProviderCallGuard guard = mock(ProviderCallGuard.class);
        CoverImageNormalizer normalizer = mock(CoverImageNormalizer.class);
        HttpClient client = mock(HttpClient.class);
        HttpResponse<InputStream> response = mock(HttpResponse.class);

        when(response.statusCode()).thenReturn(200);
        when(response.headers()).thenReturn(HttpHeaders.of(Map.of(), (name, value) -> true));
        when(response.body()).thenReturn(new ByteArrayInputStream(new byte[]{1}));
        when(client.send(any(HttpRequest.class), org.mockito.ArgumentMatchers
                .<HttpResponse.BodyHandler<InputStream>>any())).thenReturn(response);
        when(normalizer.normalize(any(byte[].class))).thenReturn(new byte[]{2});
        when(writer.writeArtistCover("artist-key", new byte[]{2}, "jpg"))
                .thenReturn("artist-covers/avatar.jpg");

        doAnswer(invocation -> {
            Supplier<?> operation = invocation.getArgument(1);
            Object result = operation.get();
            verifyNoInteractions(writer);
            return result;
        }).when(guard).call(eq(MusicProvider.QQ), any());

        ExternalCoverService service = new ExternalCoverService(writer, guard, normalizer, client);

        org.assertj.core.api.Assertions.assertThat(service.downloadArtistAvatar(
                MusicProvider.QQ, "https://y.gtimg.cn/avatar.jpg", "artist-key", Duration.ofSeconds(1)))
                .isEqualTo("artist-covers/avatar.jpg");

        verify(writer).writeArtistCover("artist-key", new byte[]{2}, "jpg");
    }
}
