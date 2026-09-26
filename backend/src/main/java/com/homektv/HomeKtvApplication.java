package com.homektv;

import com.homektv.config.AppProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableAsync;

/**
 * 家庭 KTV 点歌系统 · 服务端入口。
 */
@SpringBootApplication
@EnableConfigurationProperties(AppProperties.class)
@EnableAsync
public class HomeKtvApplication {

    public static void main(String[] args) {
        SpringApplication.run(HomeKtvApplication.class, args);
    }
}
