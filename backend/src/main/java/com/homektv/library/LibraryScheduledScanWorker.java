package com.homektv.library;

import com.homektv.config.AppProperties;
import com.homektv.resource.PlaybackPressureMonitor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.OffsetDateTime;

/**
 * 后台自动定时增量巡检（无人值守，0 人工干预）。
 * 在满足调度周期时，自动比对 NAS 曲库文件变更并触发增量快扫描；
 * 具备播放压力避让、防并发重入与 NAS 根目录离线熔断保护。
 */
@Component
public class LibraryScheduledScanWorker {
    private static final Logger log = LoggerFactory.getLogger(LibraryScheduledScanWorker.class);

    private final AppProperties props;
    private final LibraryScanCoordinator coordinator;
    private final LibraryScanStateStore stateStore;
    private final PlaybackPressureMonitor pressureMonitor;

    @Autowired
    public LibraryScheduledScanWorker(AppProperties props,
                                      LibraryScanCoordinator coordinator,
                                      LibraryScanStateStore stateStore,
                                      PlaybackPressureMonitor pressureMonitor) {
        this.props = props;
        this.coordinator = coordinator;
        this.stateStore = stateStore;
        this.pressureMonitor = pressureMonitor;
    }

    @Scheduled(fixedDelayString = "${home-ktv.library.scheduled-scan-tick-ms:60000}",
               initialDelayString = "${home-ktv.library.scheduled-scan-initial-delay-ms:60000}")
    public void scheduledTick() {
        if (!props.isExternalReadOnly()) {
            return;
        }

        int intervalMinutes = props.getScan().getScheduleMinutes();
        if (intervalMinutes <= 0) {
            return;
        }

        if (coordinator.hasActiveScan()) {
            return;
        }

        if (pressureMonitor.isPlaybackBusy()) {
            log.debug("当前大屏或播放端正忙，本次定时增量巡检避让推迟");
            return;
        }

        LibraryScanStateStore.Snapshot snapshot = stateStore.find().orElse(null);
        if (snapshot == null) {
            return;
        }

        // 排除当前正在运行的活跃状态（RUNNING），只要处于终态（COMPLETED, IDLE, FAILED, PARTIAL, INTERRUPTED），
        // 并且满足配置的退避周期，就允许自动触发下一次增量巡检以实现故障自愈与无人值守
        if (snapshot.state() == LibraryScanStateStore.State.RUNNING) {
            return;
        }

        OffsetDateTime lastUpdated = snapshot.updatedAt();
        if (lastUpdated != null && OffsetDateTime.now().isBefore(lastUpdated.plusMinutes(intervalMinutes))) {
            return;
        }

        Path root = LibraryModePolicy.activeLibraryRoot(props).toAbsolutePath().normalize();
        if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS) || !Files.isReadable(root)) {
            log.warn("NAS 外部曲库根目录不可读或可能脱机，自动巡检熔断保护：{}", root);
            return;
        }

        log.info("达到定时巡检周期（{}分钟），自动发起 NAS 曲库后台增量快比对", intervalMinutes);
        coordinator.requestScan();
    }
}
