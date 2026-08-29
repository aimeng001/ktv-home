package com.homektv.queue;

import com.homektv.domain.AppUser;
import com.homektv.repo.AppUserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 轻量点歌人服务：按 client_token 查找或创建（非账号体系，详设§10）。
 */
@Service
public class UserService {

    private final AppUserRepository userRepo;

    public UserService(AppUserRepository userRepo) {
        this.userRepo = userRepo;
    }

    /** 按 token 解析用户 id；不存在则以默认昵称创建。token 为空返回 null（匿名）。 */
    @Transactional
    public Long resolveUserId(String clientToken) {
        if (clientToken == null || clientToken.isBlank()) return null;
        userRepo.insertIfAbsent(clientToken, defaultNickname());
        return userRepo.findByClientToken(clientToken)
                .orElseThrow(() -> new IllegalStateException("用户创建后无法读取"))
                .getId();
    }

    @Transactional
    public AppUser upsert(String clientToken, String nickname) {
        userRepo.insertIfAbsent(clientToken, defaultNickname());
        AppUser u = userRepo.findByClientToken(clientToken)
                .orElseThrow(() -> new IllegalStateException("用户创建后无法读取"));
        if (nickname != null && !nickname.isBlank()) {
            u.setNickname(dedupeNickname(nickname.trim(), clientToken));
        } else if (u.getNickname() == null) {
            u.setNickname(defaultNickname());
        }
        return userRepo.save(u);
    }

    /**
     * 昵称冲突显序号（P2.13，详设§4.5）：不做唯一性强校验，
     * 若已有其他用户用了同昵称，展示为「昵称#N」。
     */
    private String dedupeNickname(String nickname, String clientToken) {
        long sameName = userRepo.countByClientTokenNotAndNickname(clientToken, nickname)
                + userRepo.countByClientTokenNotAndNicknameStartingWith(clientToken, nickname + "#");
        return sameName == 0 ? nickname : nickname + "#" + (sameName + 1);
    }

    private String defaultNickname() {
        return "家人" + (int) (Math.random() * 9000 + 1000);
    }
}
