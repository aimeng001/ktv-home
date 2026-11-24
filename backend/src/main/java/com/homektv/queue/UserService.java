package com.homektv.queue;

import com.homektv.common.QueueSnapshotInvalidatedEvent;
import com.homektv.domain.AppUser;
import com.homektv.repo.AppUserRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 轻量点歌人服务：按 client_token 查找或创建（非账号体系，详设§10）。
 */
@Service
public class UserService {

    public static final int MAX_CLIENT_TOKEN_LENGTH = 256;
    public static final int MAX_NICKNAME_LENGTH = 40;

    private final AppUserRepository userRepo;
    @Autowired(required = false)
    private ApplicationEventPublisher eventPublisher;

    public UserService(AppUserRepository userRepo) {
        this.userRepo = userRepo;
    }

    /** 按 token 解析用户 id；不存在则以默认昵称创建。token 为空返回 null（匿名）。 */
    @Transactional
    public Long resolveUserId(String clientToken) {
        if (clientToken == null || clientToken.isBlank()) return null;
        validateClientToken(clientToken);
        userRepo.insertIfAbsent(clientToken, defaultNickname());
        return userRepo.findByClientToken(clientToken)
                .orElseThrow(() -> new IllegalStateException("用户创建后无法读取"))
                .getId();
    }

    /**
     * 按 token 解析**已存在**用户的 id，只读、绝不创建。
     *
     * 供查询类接口使用：GET 不应产生写库副作用，否则任何未注册 token 都能把 app_user 表撑大。
     * token 为空返回 null；token 未注册也返回 null。
     *
     * Resolves an **existing** user id by token without ever creating one.
     *
     * Used by read-only endpoints so that GET requests cannot grow app_user.
     *
     * @param clientToken 客户端令牌 / client token
     * @return 已存在用户 id，查不到返回 null / existing user id, or null when absent
     */
    @Transactional(readOnly = true)
    public Long resolveExistingUserId(String clientToken) {
        if (clientToken == null || clientToken.isBlank()) return null;
        validateClientToken(clientToken);
        return userRepo.findByClientToken(clientToken).map(AppUser::getId).orElse(null);
    }

    @Transactional
    public AppUser upsert(String clientToken, String nickname) {
        validateClientToken(clientToken);
        if (nickname != null && !nickname.isBlank()) validateNickname(nickname);
        userRepo.insertIfAbsent(clientToken, defaultNickname());
        AppUser u = userRepo.findByClientToken(clientToken)
                .orElseThrow(() -> new IllegalStateException("用户创建后无法读取"));
        if (nickname != null && !nickname.isBlank()) {
            u.setNickname(dedupeNickname(nickname.trim(), clientToken));
        } else if (u.getNickname() == null) {
            u.setNickname(defaultNickname());
        }
        AppUser saved = userRepo.save(u);
        if (eventPublisher != null) {
            eventPublisher.publishEvent(new QueueSnapshotInvalidatedEvent());
        }
        return saved;
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

    private void validateClientToken(String clientToken) {
        if (clientToken == null || clientToken.isBlank()) {
            throw new IllegalArgumentException("client_token 不能为空");
        }
        if (clientToken.length() > MAX_CLIENT_TOKEN_LENGTH || containsControlCharacter(clientToken)) {
            throw new IllegalArgumentException("client_token 长度或字符无效");
        }
    }

    private void validateNickname(String nickname) {
        if (nickname.trim().length() > MAX_NICKNAME_LENGTH || containsControlCharacter(nickname)) {
            throw new IllegalArgumentException("nickname 长度或字符无效");
        }
    }

    private boolean containsControlCharacter(String value) {
        return value.chars().anyMatch(Character::isISOControl);
    }
}
