package com.homektv.queue;

import com.homektv.domain.AppUser;
import com.homektv.library.SettingService;
import com.homektv.repo.AppUserRepository;
import com.homektv.web.ApiException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.Map;

@Service
public class RoomHostService {
    private static final String HOST_USER_ID = "room_host_user_id";

    private final SettingService settingService;
    private final AppUserRepository userRepository;

    public RoomHostService(SettingService settingService, AppUserRepository userRepository) {
        this.settingService = settingService;
        this.userRepository = userRepository;
    }

    public boolean isHost(String clientToken) {
        Long currentUserId = resolveExistingUserId(clientToken);
        Long hostUserId = hostUserId();
        return hostUserId != null && hostUserId.equals(currentUserId);
    }

    public Map<String, Object> status(String clientToken) {
        Long currentUserId = resolveExistingUserId(clientToken);
        Long hostUserId = hostUserId();
        AppUser host = hostUserId == null ? null : userRepository.findById(hostUserId).orElse(null);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("claimed", host != null);
        result.put("hostUserId", host == null ? null : host.getId());
        result.put("hostNickname", host == null ? null : host.getNickname());
        result.put("isHost", host != null && host.getId().equals(currentUserId));
        return result;
    }

    @Transactional
    public Map<String, Object> claim(String clientToken) {
        AppUser user = requireUser(clientToken);
        userRepository.lockRoomHost();
        Long existing = hostUserId();
        if (existing != null && !existing.equals(user.getId()) && userRepository.existsById(existing)) {
            throw new ApiException("HOST_ALREADY_CLAIMED", "房主已由其他用户认领");
        }
        settingService.putInternal(HOST_USER_ID, user.getId());
        return status(clientToken);
    }

    @Transactional
    public Map<String, Object> release(String clientToken) {
        userRepository.lockRoomHost();
        requireHost(clientToken);
        settingService.putInternal(HOST_USER_ID, 0L);
        return status(clientToken);
    }

    public void requireHost(String clientToken) {
        AppUser user = requireUser(clientToken);
        Long host = hostUserId();
        if (host == null || !host.equals(user.getId())) {
            throw new ApiException("HOST_REQUIRED", "只有房主可以执行该操作");
        }
    }

    private AppUser requireUser(String clientToken) {
        if (clientToken == null || clientToken.isBlank()) throw new ApiException("USER_REQUIRED", "请先设置昵称");
        return userRepository.findByClientToken(clientToken)
                .orElseThrow(() -> new ApiException("USER_REQUIRED", "请先设置昵称"));
    }

    private Long resolveExistingUserId(String clientToken) {
        if (clientToken == null || clientToken.isBlank()) return null;
        return userRepository.findByClientToken(clientToken).map(AppUser::getId).orElse(null);
    }

    private Long hostUserId() {
        Object value = settingService.getAll().get(HOST_USER_ID);
        if (value instanceof Number number && number.longValue() > 0) return number.longValue();
        if (value != null) {
            try { long id = Long.parseLong(value.toString()); return id > 0 ? id : null; }
            catch (NumberFormatException ignored) { return null; }
        }
        return null;
    }
}
