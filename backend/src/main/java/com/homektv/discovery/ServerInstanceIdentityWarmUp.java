package com.homektv.discovery;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * 启动时解析一次安装标识，使 {@code /api/health} 无需访问数据库即可返回。
 *
 * Resolves the installation identity once during startup so {@code /api/health}
 * can answer without a database round trip.
 *
 * <p>刻意使用 {@link ApplicationRunner} 而非 {@code @PostConstruct}：该调用必须经过
 * Spring 代理，{@code getOrCreate()} 才能保留其事务咨询锁。
 */
@Component
public class ServerInstanceIdentityWarmUp implements ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(ServerInstanceIdentityWarmUp.class);

    private final ServerInstanceIdentityService identityService;

    public ServerInstanceIdentityWarmUp(ServerInstanceIdentityService identityService) {
        this.identityService = identityService;
    }

    @Override
    public void run(ApplicationArguments args) {
        try {
            identityService.cache(identityService.getOrCreate());
        } catch (RuntimeException failure) {
            // 缓存为空时 /api/health 降级为 "unknown"；数据库可用性由 /api/ready 报告。
            // An empty cache degrades /api/health to "unknown"; /api/ready reports the database.
            log.warn("server instance identity warm-up failed: {}", failure.getMessage());
        }
    }
}
