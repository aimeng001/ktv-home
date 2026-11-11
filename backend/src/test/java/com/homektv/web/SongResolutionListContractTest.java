package com.homektv.web;

import com.homektv.domain.Song;
import com.homektv.domain.SongFile;
import com.homektv.library.AssetWriter;
import com.homektv.library.SongSearchService;
import com.homektv.repo.SongFileRepository;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;

import java.lang.reflect.Proxy;
import java.util.Collection;
import java.util.List;
import java.util.Set;

import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

class SongResolutionListContractTest {

    @Test
    void searchReturnsStoredResolutionForReadyVideoSourcesUsingOnlyPageSongIds() throws Exception {
        Song song = song(41L, "MV");
        SongFile highPriorityUnprobed = file(41L, "KTV_VIDEO", "3840x2160", 10, true);
        SongFile readyVideo = file(41L, "MV", "1920x1080", 5, false);
        SongFile audioOnly = file(41L, "AUDIO", "7680x4320", 20, false);

        SongSearchService search = searchReturning(song);
        SongFileRepository files = filesReturning(
                List.of(highPriorityUnprobed, audioOnly, readyVideo), Set.of(41L));

        SongController controller = new SongController(search, null, files, (AssetWriter) null);
        MockMvc mvc = standaloneSetup(controller).build();

        mvc.perform(get("/api/songs").param("acronym", "HKTK"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].resolution").value("1920x1080"));
    }

    @Test
    void searchDoesNotClaimResolutionWhenNoReadyVideoSourceHasOne() throws Exception {
        Song song = song(42L, "KTV_VIDEO");
        SongFile videoWithoutResolution = file(42L, "KTV_VIDEO", null, 10, false);

        SongSearchService search = searchReturning(song);
        SongFileRepository files = filesReturning(List.of(videoWithoutResolution), Set.of(42L));

        SongController controller = new SongController(search, null, files, (AssetWriter) null);
        MockMvc mvc = standaloneSetup(controller).build();

        mvc.perform(get("/api/songs").param("acronym", "HKTK"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].resolution").value(nullValue()));
    }

    private static Song song(Long id, String mediaType) {
        Song song = new Song();
        song.setId(id);
        song.setTitle("测试歌曲");
        song.setArtist("测试歌手");
        song.setMediaType(mediaType);
        song.setFingerprint("song-" + id);
        return song;
    }

    private static SongSearchService searchReturning(Song song) {
        return new SongSearchService(null) {
            @Override
            public List<Song> search(String keyword, String mediaType, int page) {
                return List.of(song);
            }
        };
    }

    private static SongFileRepository filesReturning(List<SongFile> result, Set<Long> expectedSongIds) {
        return (SongFileRepository) Proxy.newProxyInstance(
                SongFileRepository.class.getClassLoader(),
                new Class<?>[]{SongFileRepository.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("findBySongIdInAndValidTrueOrderByPriorityDesc")) {
                        Collection<?> requestedIds = (Collection<?>) args[0];
                        if (!Set.copyOf(requestedIds).equals(expectedSongIds)) {
                            throw new AssertionError("resolution lookup must be bounded to the returned page IDs");
                        }
                        return result;
                    }
                    if (method.getReturnType() == boolean.class) return false;
                    if (method.getReturnType() == long.class) return 0L;
                    if (method.getReturnType() == int.class) return 0;
                    return null;
                }
        );
    }

    private static SongFile file(Long songId, String mediaType, String resolution,
                                 int priority, boolean probePending) {
        SongFile file = new SongFile();
        file.setSongId(songId);
        file.setFilePath("fixture-" + songId + "-" + mediaType + "-" + priority);
        file.setFormat("mp4");
        file.setMediaType(mediaType);
        file.setResolution(resolution);
        file.setPriority(priority);
        file.setProbePending(probePending);
        return file;
    }
}
