package com.homektv.library;

import com.homektv.domain.Song;
import com.homektv.repo.SongSearchRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Locale;

/**
 * 歌曲搜索服务（P1.6）。归一化关键词后调用综合查询。
 */
@Service
public class SongSearchService {

    private static final int PAGE_SIZE = 50;

    public static final int MAX_KEYWORD_LENGTH = 50;

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
     * the requested page is cut.
     */
    public List<Song> search(String keyword, String mediaType, int page) {
        if (keyword == null || keyword.isBlank()) return List.of();
        // 拼音字段均为小写存储，中文 ILIKE 不受影响，这里统一小写以命中 init/py 条件
        String kw = keyword.trim().toLowerCase();
        if (kw.length() > MAX_KEYWORD_LENGTH) {
            kw = kw.substring(0, MAX_KEYWORD_LENGTH);
        }
        String normalizedType = mediaType == null ? "" : mediaType.trim().toUpperCase(Locale.ROOT);
        return searchRepo.search(kw, normalizedType,
                PageRequest.of(Math.max(0, page), PAGE_SIZE));
    }
}
