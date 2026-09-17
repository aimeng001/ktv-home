package com.homektv.web;

import com.homektv.discovery.ServerInstanceIdentityService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 健康检查端点（P0.2 验收）。
 *
 * Health check endpoint (P0.2 acceptance).
 */
@RestController
@RequestMapping("/api")
public class HealthController {
    private final ServerInstanceIdentityService identityService;

    public HealthController(ServerInstanceIdentityService identityService) {
        this.identityService = identityService;
    }

    /**
     * 返回服务健康状态，供 TV 端局域网扫描确认这是点歌服务端而非其它 HTTP 服务。
     *
     * <p>轻量服务发现端点：只读取启动时缓存的安装标识，不访问数据库；数据库就绪状态由
     * {@code /api/ready} 报告。
     *
     * Returns service health status for TV-side LAN scanning to confirm this is a karaoke server, not another HTTP service.
     * This probe never touches the database; {@code /api/ready} reports database availability.
     *
     * @return 包含 {@code status} 和 {@code service} 标识的 Map
     */
    @GetMapping("/health")
    public Map<String, String> health() {
        // service 标识供 TV 端局域网扫描确认「这是点歌服务端」而非其它 http 服务
        return Map.of("status", "UP", "service", "home-ktv",
                "instanceId", identityService.cachedOrDefault());
    }
}
