package com.homektv.web;
import com.homektv.common.ApiException;

import com.homektv.domain.AppUser;
import com.homektv.queue.UserService;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 点歌人注册/昵称（H5-01，详设§7）。局域网免登录，仅轻量标识。
 *
 * Song requester registration / nickname (H5-01, detailed design §7).
 * LAN-based, login-free, lightweight identity only.
 */
@RestController
@RequestMapping("/api")
public class UserController {

    private final UserService userService;

    public UserController(UserService userService) {
        this.userService = userService;
    }

    /**
     * 提交昵称，返回去重后的最终昵称（P2.13 昵称冲突显序号）。
     *
     * Submits a nickname and returns the final deduplicated nickname
     * (P2.13 nickname conflict appends a sequence number).
     *
     * @param body 请求体，含 client_token 和 nickname
     * @return 包含用户 id 和最终 nickname 的 Map
     */
    @PostMapping("/user")
    public Map<String, Object> register(@RequestBody Map<String, String> body) {
        String token = body.get("client_token");
        String nickname = body.get("nickname");
        if (token == null || token.isBlank()) {
            throw new ApiException("INVALID_ARGUMENT", "缺少 client_token");
        }
        AppUser u = userService.upsert(token, nickname);
        return Map.of("id", u.getId(), "nickname", u.getNickname());
    }
}
