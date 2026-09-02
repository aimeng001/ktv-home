package com.homektv.web;

import com.homektv.domain.Wish;
import com.homektv.queue.UserService;
import com.homektv.repo.WishRepository;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 心愿单（P3.3，详设§11.1）：H5 无结果提交，后台查看/导出。
 *
 * Wishlist (P3.3, DS §11.1): H5 submission with no results; backend view/export.
 */
@RestController
@RequestMapping("/api")
public class WishController {

    private final WishRepository wishRepo;
    private final UserService userService;

    public WishController(WishRepository wishRepo, UserService userService) {
        this.wishRepo = wishRepo;
        this.userService = userService;
    }

    /**
     * 提交心愿（缺歌反馈）。
     *
     * Submit a wish (missing song feedback).
     * @param body 请求体，包含 keyword 和 client_token
     * @return 包含 status 的 Map
     */
    @PostMapping("/wishes")
    public Map<String, Object> add(@RequestBody Map<String, String> body) {
        String keyword = body.get("keyword");
        if (keyword == null || keyword.isBlank()) {
            throw new ApiException("INVALID_ARGUMENT", "缺少关键词");
        }
        String trimmed = keyword.trim();
        if (trimmed.length() > 100) {
            throw new ApiException("INVALID_ARGUMENT", "心愿关键词不能超过 100 个字符");
        }
        Wish w = new Wish();
        w.setKeyword(trimmed);
        w.setCreatedBy(userService.resolveUserId(body.get("client_token")));
        wishRepo.save(w);
        return Map.of("status", "ok");
    }

    /**
     * 心愿单列表（后台查看/导出）。
     *
     * Wishlist entries (backend view/export).
     * @return 心愿单列表，按创建时间降序排列
     */
    @GetMapping("/admin/wishes")
    public List<Wish> list() {
        return wishRepo.findAllByOrderByCreatedAtDesc();
    }
}
