package com.homektv.web;

import com.homektv.domain.Wish;
import com.homektv.queue.UserService;
import com.homektv.repo.WishRepository;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * 心愿单（P3.3，详设§11.1）：H5 无结果提交，后台查看/导出/删除。
 *
 * Wishlist (P3.3, DS §11.1): H5 submission with no results; backend view/export/delete.
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

    /**
     * 删除心愿记录。
     *
     * Delete a wish item by ID.
     */
    @DeleteMapping("/admin/wishes/{id}")
    public Map<String, Object> delete(@PathVariable Long id) {
        if (!wishRepo.existsById(id)) {
            throw new ApiException("NOT_FOUND", "心愿记录不存在");
        }
        wishRepo.deleteById(id);
        return Map.of("status", "ok");
    }

    /**
     * 导出心愿单 CSV 文件。
     *
     * Export all wishes as a CSV file.
     */
    @GetMapping(value = "/admin/wishes/export", produces = "text/csv; charset=UTF-8")
    public ResponseEntity<byte[]> exportCsv() {
        List<Wish> list = wishRepo.findAllByOrderByCreatedAtDesc();
        StringBuilder csv = new StringBuilder();
        csv.append('\ufeff'); // UTF-8 BOM
        csv.append("ID,关键词,创建者ID,创建时间\n");
        for (Wish w : list) {
            String kw = w.getKeyword() == null ? "" : w.getKeyword().replace("\"", "\"\"");
            csv.append(w.getId()).append(",")
               .append("\"").append(kw).append("\",")
               .append(w.getCreatedBy() == null ? "" : w.getCreatedBy()).append(",")
               .append(w.getCreatedAt() == null ? "" : w.getCreatedAt().toString()).append("\n");
        }
        byte[] bytes = csv.toString().getBytes(StandardCharsets.UTF_8);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"ktv-wishes.csv\"")
                .contentType(MediaType.parseMediaType("text/csv; charset=UTF-8"))
                .body(bytes);
    }
}
