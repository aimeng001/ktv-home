package com.homektv.library;

import com.homektv.config.AppProperties;
import com.homektv.web.ApiException;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/** Single entry point for bootstrap, admin, and watch-triggered external scans. */
@Service
public class LibraryScanCoordinator {
    private static final Logger log = LoggerFactory.getLogger(LibraryScanCoordinator.class);

    private final AppProperties props;
    private final LibraryScanService scanService;
    private final LibraryScanStateStore stateStore;
    private final Executor executor;
    private final ExecutorService ownedExecutor;
    private final ScheduledExecutorService heartbeatExecutor;
    private final AtomicBoolean scheduled = new AtomicBoolean();

    @Autowired
    public LibraryScanCoordinator(AppProperties props, LibraryScanService scanService,
                                  LibraryScanStateStore stateStore) {
        this(props, scanService, stateStore, newExecutor(), newHeartbeatExecutor());
    }

    LibraryScanCoordinator(AppProperties props, LibraryScanService scanService,
                           LibraryScanStateStore stateStore, Executor executor) {
        this(props, scanService, stateStore, executor, newHeartbeatExecutor());
    }

    LibraryScanCoordinator(AppProperties props, LibraryScanService scanService,
                           LibraryScanStateStore stateStore, Executor executor,
                           ScheduledExecutorService heartbeatExecutor) {
        this.props = props;
        this.scanService = scanService;
        this.stateStore = stateStore;
        this.executor = executor;
        this.ownedExecutor = executor instanceof ExecutorService service ? service : null;
        this.heartbeatExecutor = heartbeatExecutor;
    }

    public boolean requestBootstrap() {
        if (!props.isExternalReadOnly() || !stateStore.needsBootstrap(props)) return false;
        return request("BOOTSTRAP", null);
    }

    public boolean requestScan() {
        return requestScan(null);
    }

    public boolean requestScan(Consumer<LibraryScanService.ScanResult> onSuccess) {
        if (!props.isExternalReadOnly()) return false;
        return request("MANUAL_OR_WATCH", onSuccess);
    }

    public LibraryScanService.ScanProgress startScan() {
        requestScan();
        return scanService.getScanProgress();
    }

    /** Explicit administrator confirmation for a changed library root. */
    public boolean rebindCurrentRoot() {
        return props.isExternalReadOnly() && stateStore.rebind(props);
    }

    private boolean request(String trigger, Consumer<LibraryScanService.ScanResult> onSuccess) {
        if (!scheduled.compareAndSet(false, true)) return false;
        LibraryScanStateStore.Claim claim;
        try {
            claim = stateStore.tryClaim(props, trigger).orElse(null);
        } catch (RuntimeException failure) {
            scheduled.set(false);
            log.warn("曲库扫描状态无法申领：{}", failure.getMessage());
            return false;
        }
        if (claim == null) {
            scheduled.set(false);
            return false;
        }
        try {
            executor.execute(() -> run(claim, onSuccess));
            return true;
        } catch (RejectedExecutionException failure) {
            scheduled.set(false);
            stateStore.markFailed(claim, "SCAN_EXECUTOR_REJECTED", failure.getMessage());
            log.warn("曲库扫描任务未能排队：{}", failure.getMessage());
            return false;
        }
    }

    private void run(LibraryScanStateStore.Claim claim,
                     Consumer<LibraryScanService.ScanResult> onSuccess) {
        try (LibraryScanLease lease = new LibraryScanLease(
                claim,
                stateStore,
                this::currentProgressSnapshot,
                heartbeatExecutor,
                Duration.ofSeconds(30))) {
            LibraryScanService.ScanResult result = scanService.scanAllWithLease(lease::assertOwned);
            if (stateStore.markCompleted(claim, result, scanService.getScanProgress())
                    && onSuccess != null) {
                onSuccess.accept(result);
            }
        } catch (LeaseLostException lost) {
            log.warn("曲库扫描租约已失效，旧任务终止：{}", lost.getMessage());
        } catch (ApiException failure) {
            stateStore.markFailed(claim, failure.getCode(), failure.getMessage());
            log.warn("曲库扫描未完成：{} - {}", failure.getCode(), failure.getMessage());
        } catch (RuntimeException failure) {
            stateStore.markFailed(claim, "SCAN_RUNTIME_ERROR", failure.getMessage());
            log.error("曲库扫描发生未捕获异常", failure);
        } finally {
            scheduled.set(false);
        }
    }

    private LibraryScanStateStore.ScanSnapshot currentProgressSnapshot() {
        LibraryScanService.ScanProgress progress = scanService.getScanProgress();
        return new LibraryScanStateStore.ScanSnapshot(
                progress.phase(),
                progress.discovered(),
                progress.fastIndexed(),
                progress.probeCompleted(),
                progress.probeQueued());
    }

    @PreDestroy
    void shutdown() {
        if (ownedExecutor != null) ownedExecutor.shutdownNow();
        if (heartbeatExecutor != null) heartbeatExecutor.shutdownNow();
    }

    private static ExecutorService newExecutor() {
        return Executors.newSingleThreadExecutor(r -> {
            Thread thread = new Thread(r, "library-scan-coordinator");
            thread.setDaemon(true);
            return thread;
        });
    }

    private static ScheduledExecutorService newHeartbeatExecutor() {
        return Executors.newSingleThreadScheduledExecutor(r -> {
            Thread thread = new Thread(r, "library-scan-heartbeat");
            thread.setDaemon(true);
            return thread;
        });
    }
}
