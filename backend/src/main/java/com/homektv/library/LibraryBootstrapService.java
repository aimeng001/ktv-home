package com.homektv.library;

import com.homektv.config.AppProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/** Submits the first external-library scan after Flyway and application startup, or incremental scan if onStartup enabled. */
@Component
public class LibraryBootstrapService {
    private static final Logger log = LoggerFactory.getLogger(LibraryBootstrapService.class);

    private final LibraryScanCoordinator coordinator;
    private final AppProperties props;
    private final LibraryScanSeenPathStore seenPathStore;

    public LibraryBootstrapService(LibraryScanCoordinator coordinator, AppProperties props) {
        this(coordinator, props, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public LibraryBootstrapService(LibraryScanCoordinator coordinator, AppProperties props,
                                   @org.springframework.beans.factory.annotation.Autowired(required = false) LibraryScanSeenPathStore seenPathStore) {
        this.coordinator = coordinator;
        this.props = props;
        this.seenPathStore = seenPathStore;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
        if (props.isExternalReadOnly() && seenPathStore != null) {
            Thread.ofVirtual().name("orphan-seen-path-cleaner").start(() -> {
                try {
                    int cleaned = seenPathStore.cleanOrphanSeenPaths();
                    if (cleaned > 0) {
                        log.info("启动时异步清理历史孤儿临时扫描路径记录：{} 行", cleaned);
                    }
                } catch (Exception ex) {
                    log.warn("启动时清理孤儿临时扫描路径记录异常：{}", ex.getMessage());
                }
            });
        }
        boolean bootstrapped = coordinator.requestBootstrap();
        if (!bootstrapped && props.isExternalReadOnly() && props.getScan().isOnStartup()) {
            log.info("曲库已完成过初次导入，根据 scan.on-startup 配置启动后台增量检测扫描");
            coordinator.requestScan();
        }
    }
}
