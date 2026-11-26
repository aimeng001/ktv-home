package com.homektv.library;
import com.homektv.common.CatalogCacheEpoch;
import com.homektv.common.SearchKeyword;

import com.homektv.domain.Song;
import com.homektv.repo.SongSearchRepository;
import com.homektv.common.ApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 歌曲搜索服务（P1.6）。归一化关键词后调用动态路由查询与版本感知缓存，带慢查询告警。
 */
@Service
public class SongSearchService {

    private static final Logger log = LoggerFactory.getLogger(SongSearchService.class);
    private static final int PAGE_SIZE = 50;
    public static final int MAX_KEYWORD_LENGTH = SearchKeyword.DEFAULT_MAX_LENGTH;
    public static final int MAX_PAGE = 20000;
    private static final long SLOW_QUERY_THRESHOLD_MS = 500L;
    private static final int SEARCH_CACHE_CAPACITY = 512;
    private static final long SEARCH_CACHE_TTL_NANOS = 60_000_000_000L;

    private record SearchCacheEntry(List<Song> songs, long expiresAtNanos) {}

    private final SongSearchRepository searchRepo;
    private final SongSearchQueryRepository dynamicQueryRepo;
    private final CatalogRevisionService catalogRevisionService;
    private final Map<String, SearchCacheEntry> searchCache = Collections.synchronizedMap(
            new LinkedHashMap<>(128, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, SearchCacheEntry> eldest) {
                    return size() > SEARCH_CACHE_CAPACITY;
                }
            });

    public SongSearchService(SongSearchRepository searchRepo) {
        this(searchRepo, null, null);
    }

    @Autowired
    public SongSearchService(SongSearchRepository searchRepo,
                             @Autowired(required = false) SongSearchQueryRepository dynamicQueryRepo,
                             @Autowired(required = false) CatalogRevisionService catalogRevisionService) {
        this.searchRepo = searchRepo;
        this.dynamicQueryRepo = dynamicQueryRepo;
        this.catalogRevisionService = catalogRevisionService;
    }

    /**
     * 综合搜索。关键词裁剪空白并截断至最大50字符；拼音匹配走小写。
     * 空关键词返回空列表（前端应展示热榜而非全量）。
     */
    public List<Song> search(String keyword, int page) {
        return search(keyword, "", page);
    }

    /**
     * Search with the media type applied inside the repository query, before
     * the requested page is cut. Logs queries taking >= 500ms.
     */
    public List<Song> search(String keyword, String mediaType, int page) {
        if (page < 0 || page > MAX_PAGE) {
            throw new ApiException("INVALID_PAGE", "搜索页码超出允许范围");
        }
        if (keyword == null || keyword.isBlank()) return List.of();
        SearchKeyword searchKeyword = SearchKeyword.of(keyword, MAX_KEYWORD_LENGTH);
        String normalizedType = mediaType == null
                ? ""
                : mediaType.trim().toUpperCase(java.util.Locale.ROOT);
        String lower = searchKeyword.lower();
        Long numericId = lower.matches("^[0-9]{1,10}$") ? Long.parseLong(lower) : 0L;

        String cacheKey = null;
        long now = System.nanoTime();
        if (dynamicQueryRepo != null) {
            long revision = currentCatalogRevision();
            long epoch = CatalogCacheEpoch.current();
            cacheKey = revision + ":" + epoch + "|" + normalizedType + "|" + page + "|" + searchKeyword.raw();
            SearchCacheEntry cached = searchCache.get(cacheKey);
            if (cached != null && now < cached.expiresAtNanos()) {
                return cached.songs();
            }
        }

        long started = System.nanoTime();
        try {
            if (dynamicQueryRepo != null) {
                List<Song> result = dynamicQueryRepo.search(searchKeyword, numericId, normalizedType, page, PAGE_SIZE);
                List<Song> immutable = result == null ? List.of() : List.copyOf(result);
                if (cacheKey != null) {
                    searchCache.put(cacheKey, new SearchCacheEntry(immutable, System.nanoTime() + SEARCH_CACHE_TTL_NANOS));
                }
                return immutable;
            }
            PageRequest request = PageRequest.of(page, PAGE_SIZE);
            if (lower.length() == 1 && lower.charAt(0) >= 'a' && lower.charAt(0) <= 'z') {
                return searchRepo.searchSingleInitial(searchKeyword.raw(), lower,
                        searchKeyword.likePattern(), normalizedType, request);
            }
            if (lower.codePointCount(0, lower.length()) <= 2) {
                return searchRepo.searchShort(searchKeyword.raw(), lower,
                        searchKeyword.likePattern(), numericId, normalizedType, request);
            }
            return searchRepo.search(searchKeyword.raw(), lower, searchKeyword.likePattern(),
                    numericId, normalizedType, request);
        } finally {
            long duration = (System.nanoTime() - started) / 1_000_000L;
            if (duration >= SLOW_QUERY_THRESHOLD_MS) {
                log.warn("Slow song search: keywordHash={}, keywordLength={}, typeHash={}, "
                                + "typeLength={}, page={}, durationMs={}",
                        hashForLog(searchKeyword.raw()), searchKeyword.raw().length(),
                        hashForLog(normalizedType), normalizedType.length(), page, duration);
            }
        }
    }

    private long currentCatalogRevision() {
        if (catalogRevisionService == null) return 0L;
        try {
            return catalogRevisionService.current();
        } catch (RuntimeException ignored) {
            return 0L;
        }
    }

    private static String hashForLog(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest, 0, 8);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
