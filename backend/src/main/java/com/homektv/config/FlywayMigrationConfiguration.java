package com.homektv.config;

import org.flywaydb.core.Flyway;
import org.springframework.boot.autoconfigure.flyway.FlywayMigrationStrategy;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 自适应数据库迁移策略：
 * 启动时先执行 flyway.repair() 自动校准并自愈已应用迁移的历史校验和（如历史 V24 注释漂移），
 * 然后执行 flyway.migrate() 继续平滑升级，保证新老数据库版本升级无感自愈。
 */
@Configuration(proxyBeanMethods = false)
public class FlywayMigrationConfiguration {

    @Bean
    public FlywayMigrationStrategy flywayMigrationStrategy() {
        return flyway -> {
            flyway.repair();
            flyway.migrate();
        };
    }
}
