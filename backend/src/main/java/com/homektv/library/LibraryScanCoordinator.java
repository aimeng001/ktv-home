package com.homektv.library;

import com.homektv.config.AppProperties;
import com.homektv.web.ApiException;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
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

    /** Submits one external scan and returns a truthful acceptance state. */
    public ScanSubmission submitScan() {
        if (!props.isExternalReadOnly()) {
            return new ScanSubmission(false, "NOT_APPLICABLE", "IDLE",
                    "EXTERNAL_READ_ONLY_REQUIRED", "当前服务模式不支持外部曲库扫描");
        }
        try {
            Path root = LibraryModePolicy.activeLibraryRoot(props).toAbsolutePath().normalize();
            if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS) || !Files.isReadable(root)) {
                return new ScanSubmission(false, "ROOT_UNAVAILABLE", "IDLE",
                        "LIBRARY_ROOT_UNAVAILABLE", "曲库根目录当前不可读取");
            }
            LibraryScanStateStore.Snapshot snapshot = stateStore.find().orElse(null);
            if (rootMismatch(root, snapshot)) {
                return new ScanSubmission(false, "NEEDS_REBIND", snapshot.state().name(),
                        "LIBRARY_ROOT_IDENTITY_CHANGED", "曲库根目录身份已变化，需要管理员重新确认");
            }
            if (hasActiveScan()) {
                return new ScanSubmission(true, "ALREADY_RUNNING", "RUNNING", null,
                        "曲库扫描任务已在运行");
            }
            if (requestScan()) {
                return new ScanSubmission(true, "ACCEPTED", "RUNNING", null,
                        "服务端已接受曲库扫描请求");
            }
            if (hasActiveScan()) {
                return new ScanSubmission(true, "ALREADY_RUNNING", "RUNNING", null,
                        "曲库扫描任务已在运行");
            }
            LibraryScanStateStore.Snapshot latest = stateStore.find().orElse(null);
            if (rootMismatch(root, latest)) {
                return new ScanSubmission(false, "NEEDS_REBIND", latest.state().name(),
                        "LIBRARY_ROOT_IDENTITY_CHANGED", "曲库扫描未受理，需要管理员核对曲库状态");
            }
            String error = latest == null ? null : latest.errorCode();
            return new ScanSubmission(false, "REJECTED", latest == null ? "UNKNOWN" : latest.state().name(),
                    error == null || error.isBlank() ? "SCAN_NOT_ACCEPTED" : error,
                    "服务端未能受理曲库扫描请求");
        } catch (RuntimeException failure) {
            log.warn("曲库扫描请求状态无法确认：{}", failure.getClass().getSimpleName());
            return new ScanSubmission(false, "UNAVAILABLE", "UNKNOWN",
                    "SCAN_STATE_UNAVAILABLE", "服务端无法确认曲库扫描状态");
        }
    }

    private boolean hasActiveScan() {
        LibraryScanService.ScanProgress progress = scanService.getScanProgress();
        return scheduled.get() || (progress != null && progress.running()) || stateStore.hasActiveLease();
    }

    private boolean rootMismatch(Path root, LibraryScanStateStore.Snapshot snapshot) {
        if (snapshot == null) return false;
        return !root.toString().equals(snapshot.configuredRoot())
                || LibraryIdentity.compare(snapshot.rootIdentity(), LibraryIdentity.resolve(root))
                == LibraryIdentity.IdentityState.MISMATCH;
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

    public record ScanSubmission(boolean accepted, String requestStatus, String scanState,
                                 String errorCode, String message) {}
}
