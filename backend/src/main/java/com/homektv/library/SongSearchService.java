package com.homektv.library;

import com.homektv.domain.Song;
import com.homektv.repo.SongSearchRepository;
import com.homektv.web.ApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;

/**
 * 歌曲搜索服务（P1.6）。归一化关键词后调用综合查询，带慢查询告警。
 */
@Service
public class SongSearchService {

    private static final Logger log = LoggerFactory.getLogger(SongSearchService.class);
    private static final int PAGE_SIZE = 50;
    public static final int MAX_KEYWORD_LENGTH = SearchKeyword.DEFAULT_MAX_LENGTH;
    public static final int MAX_PAGE = 4000;
    private static final long SLOW_QUERY_THRESHOLD_MS = 500L;

    private final SongSearchRepository searchRepo;

    public SongSearchService(SongSearchRepository searchRepo) {
        this.searchRepo = searchRepo;
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

        long started = System.nanoTime();
        try {
            PageRequest request = PageRequest.of(page, PAGE_SIZE);
            if (searchKeyword.lower().codePointCount(0, searchKeyword.lower().length()) <= 2) {
                return searchRepo.searchShort(searchKeyword.raw(), searchKeyword.lower(),
                        searchKeyword.likePattern(), normalizedType, request);
            }
            return searchRepo.search(searchKeyword.raw(), searchKeyword.lower(), searchKeyword.likePattern(),
                    normalizedType, request);
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
