package com.homektv.library;

import com.homektv.config.AppProperties;
import com.homektv.ws.WsBroadcaster;
import com.homektv.ws.WsEvent;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.*;
import java.util.Map;
import java.util.concurrent.*;

import static java.nio.file.StandardWatchEventKinds.*;

/**
 * 目录自动监听入库（P2.7，详设§9.3）。
 * WatchService 监听曲库根目录变更；带防抖（合并短时间内的多次变更），
 * 触发增量扫描并广播「曲库已更新」。WatchService 不可用时静默降级（可靠手动扫描兜底）。
 */
@Service
public class LibraryWatchService {

    private static final Logger log = LoggerFactory.getLogger(LibraryWatchService.class);
    private static final long DEBOUNCE_MS = 3000;

    private final AppProperties props;
    private final SettingService settingService;
    private final LibraryScanService scanService;
    private final MediaImportService importService;
    private final WsBroadcaster broadcaster;

    private WatchService watchService;
    private ExecutorService watchExecutor;
    private ScheduledExecutorService debounceExecutor;
    private ScheduledFuture<?> pendingScan;
    private volatile boolean running;

    public LibraryWatchService(AppProperties props, SettingService settingService,
                               LibraryScanService scanService, MediaImportService importService,
                               WsBroadcaster broadcaster) {
        this.props = props;
        this.settingService = settingService;
        this.scanService = scanService;
        this.importService = importService;
        this.broadcaster = broadcaster;
    }

    @PostConstruct
    public void start() {
        reloadFromSettings();
    }

    /** 应用系统设置中的自动扫描开关，保存设置后立即生效。 */
    public synchronized void reloadFromSettings() {
        if (!settingService.isLibraryWatchEnabled()) {
            log.info("源目录自动监听已关闭，仅支持手动扫描");
            stopWatching();
            return;
        }
        startWatching();
    }

    private synchronized void startWatching() {
        if (running) return;
        Path root = Path.of(props.getSourceLibraryPath());
        if (!Files.isDirectory(root)) {
            log.warn("曲库目录不存在，跳过自动监听：{}", root);
            return;
        }
        try {
            watchService = FileSystems.getDefault().newWatchService();
            registerRecursive(root);
            running = true;
            debounceExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "lib-scan-debounce"); t.setDaemon(true); return t;
            });
            watchExecutor = Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "lib-watch"); t.setDaemon(true); return t;
            });
            watchExecutor.submit(this::watchLoop);
            log.info("曲库目录自动监听已启动：{}", root);
        } catch (IOException e) {
            log.warn("无法启动目录监听（降级为手动扫描）：{}", e.getMessage());
        }
    }

    private void registerRecursive(Path root) throws IOException {
        Files.walk(root)
                .filter(Files::isDirectory)
                .forEach(dir -> {
                    try {
                        dir.register(watchService, ENTRY_CREATE, ENTRY_DELETE, ENTRY_MODIFY);
                    } catch (IOException e) {
                        log.debug("注册监听失败：{}", dir);
                    }
                });
    }

    private void watchLoop() {
        while (running) {
            WatchKey key;
            try {
                key = watchService.take();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (ClosedWatchServiceException e) {
                return;
            }
            boolean relevant = false;
            for (WatchEvent<?> event : key.pollEvents()) {
                if (event.kind() != OVERFLOW) relevant = true;
            }
            key.reset();
            if (relevant) scheduleScan();
        }
    }

    /** 防抖：3s 内的多次变更合并为一次扫描 */
    private synchronized void scheduleScan() {
        if (pendingScan != null && !pendingScan.isDone()) {
            pendingScan.cancel(false);
        }
        pendingScan = debounceExecutor.schedule(this::runScan, DEBOUNCE_MS, TimeUnit.MILLISECONDS);
    }

    private void runScan() {
        try {
            if (LibraryModePolicy.isExternalReadOnly(props)) {
                LibraryScanService.ScanResult result = scanService.scanAll();
                if (result.added() > 0 || result.updated() > 0) {
                    broadcaster.broadcast(WsEvent.of("library_updated",
                            Map.of("added", result.added(), "updated", result.updated())));
                }
                log.info("外部只读曲库自动扫描：扫描 {}，新增 {}，更新 {}，跳过 {}",
                        result.scanned(), result.added(), result.updated(), result.skipped());
                return;
            }
            MediaImportService.SourceScanResult result = importService.scanSourceLibrary();
            if (result.copied() > 0) {
                broadcaster.broadcast(WsEvent.of("library_updated",
                        Map.of("copied", result.copied(), "pendingTranscode", result.pendingTranscode())));
                log.info("源目录自动扫描：直拷 {}，待转码 {}，重复 {}",
                        result.copied(), result.pendingTranscode(),
                        result.skippedSourceDuplicate() + result.skippedOutputDuplicate());
            }
        } catch (Exception e) {
            log.warn("自动扫描失败：{}", e.getMessage());
        }
    }

    @PreDestroy
    public void stop() {
        stopWatching();
    }

    private synchronized void stopWatching() {
        running = false;
        if (pendingScan != null) pendingScan.cancel(false);
        pendingScan = null;
        try { if (watchService != null) watchService.close(); } catch (IOException ignored) {}
        if (watchExecutor != null) watchExecutor.shutdownNow();
        if (debounceExecutor != null) debounceExecutor.shutdownNow();
        watchService = null;
        watchExecutor = null;
        debounceExecutor = null;
    }
}
