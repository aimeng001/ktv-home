package com.homektv.web;

import com.homektv.domain.Favorite;
import com.homektv.domain.Song;
import com.homektv.queue.UserService;
import com.homektv.repo.FavoriteRepository;
import com.homektv.repo.SongRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

import static org.assertj.core.api.Assertions.assertThat;

class FavoriteControllerTest {
    private final List<Favorite> favorites = new ArrayList<>();
    private final Map<Long, Song> songs = new LinkedHashMap<>();
    private final AtomicInteger findByIdCalls = new AtomicInteger();
    private final AtomicInteger findAllByIdCalls = new AtomicInteger();
    private FavoriteController controller;

    @BeforeEach
    void setUp() {
        favorites.clear();
        songs.clear();
        findByIdCalls.set(0);
        findAllByIdCalls.set(0);
        controller = new FavoriteController(favoriteRepository(), songRepository(), new UserService(null) {
            @Override public Long resolveUserId(String clientToken) { return 7L; }
        });
    }

    @Test
    void addingFavoriteIsIdempotent() {
        songs.put(10L, song(10L, "晴天"));
        controller.add(10L, Map.of("clientToken", "token-1"));
        controller.add(10L, Map.of("clientToken", "token-1"));
        assertThat(favorites).hasSize(1);
    }

    @Test
    void listsSongsInFavoriteOrder() {
        songs.put(10L, song(10L, "晴天"));
        songs.put(11L, song(11L, "后来"));
        favorites.add(favorite(11L));
        favorites.add(favorite(10L));
        assertThat(controller.list("token-1")).extracting("title").containsExactly("后来", "晴天");
    }

    @Test
    void listsFavoritesWithOneBatchSongLookup() {
        songs.put(10L, song(10L, "晴天"));
        songs.put(11L, song(11L, "后来"));
        favorites.add(favorite(11L));
        favorites.add(favorite(10L));

        assertThat(controller.list("token-1")).hasSize(2);
        assertThat(findAllByIdCalls).hasValue(1);
        assertThat(findByIdCalls).hasValue(0);
    }

    @Test
    void pagedListDoesNotLoadTheWholeFavoriteCollection() {
        songs.put(10L, song(10L, "晴天"));
        favorites.add(favorite(10L));

        assertThat(controller.list("token-1", 0, 1)).extracting("title").containsExactly("晴天");
    }

    @Test
    void removingFavoriteIsSafeWhenAlreadyMissing() {
        assertThat(controller.remove(10L, "token-1")).containsEntry("favorite", false);
        assertThat(favorites).isEmpty();
    }

    private FavoriteRepository favoriteRepository() {
        return proxy(FavoriteRepository.class, (method, args) -> switch (method.getName()) {
            case "findByUserIdOrderByCreatedAtDesc" -> {
                if (args != null && args.length == 2) {
                    int size = ((PageRequest) args[1]).getPageSize();
                    yield new PageImpl<>(List.copyOf(favorites).subList(0, Math.min(size, favorites.size())), (PageRequest) args[1], favorites.size());
                }
                yield List.copyOf(favorites);
            }
            case "insertIfAbsent" -> {
                boolean exists = favorites.stream().anyMatch(f -> f.getUserId().equals(args[0]) && f.getSongId().equals(args[1]));
                if (!exists) favorites.add(favorite((Long) args[1]));
                yield exists ? 0 : 1;
            }
            case "save" -> { favorites.add((Favorite) args[0]); yield args[0]; }
            case "deleteByUserIdAndSongId" -> {
                int before = favorites.size();
                favorites.removeIf(f -> f.getUserId().equals(args[0]) && f.getSongId().equals(args[1]));
                yield (long) (before - favorites.size());
            }
            default -> defaultValue(method.getReturnType());
        });
    }

    private SongRepository songRepository() {
        return proxy(SongRepository.class, (method, args) -> switch (method.getName()) {
            case "existsById" -> songs.containsKey(args[0]);
            case "findById" -> {
                findByIdCalls.incrementAndGet();
                yield Optional.ofNullable(songs.get(args[0]));
            }
            case "findAllById" -> {
                findAllByIdCalls.incrementAndGet();
                Iterable<?> ids = (Iterable<?>) args[0];
                List<Song> found = new ArrayList<>();
                for (Object id : ids) {
                    Song song = songs.get(id);
                    if (song != null) found.add(song);
                }
                yield found;
            }
            default -> defaultValue(method.getReturnType());
        });
    }

    @SuppressWarnings("unchecked")
    private <T> T proxy(Class<T> type, Handler handler) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, (proxy, method, args) -> handler.call(method, args));
    }

    private Object defaultValue(Class<?> type) {
        if (type == boolean.class) return false;
        if (type == long.class) return 0L;
        if (type == int.class) return 0;
        return null;
    }

    private Favorite favorite(Long songId) {
        Favorite favorite = new Favorite();
        favorite.setUserId(7L);
        favorite.setSongId(songId);
        return favorite;
    }

    private Song song(Long id, String title) {
        Song song = new Song();
        song.setId(id);
        song.setTitle(title);
        song.setArtist("测试歌手");
        song.setMediaType("KTV_VIDEO");
        return song;
    }

    @FunctionalInterface
    private interface Handler {
        Object call(java.lang.reflect.Method method, Object[] args);
    }
}
