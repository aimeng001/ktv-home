package com.homektv.diagnostics;

import com.homektv.library.LibraryScanService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.MemoryUsage;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * Takes a bounded, secret-free snapshot of JVM memory and scan progress.
 * The injectable constructor keeps the behavior deterministic in unit tests.
 */
@Service
public class MemoryDiagnosticsService {
    private final Supplier<LibraryScanService.ScanProgress> scanProgress;
    private final MemoryMXBean memory;

    @Autowired
    public MemoryDiagnosticsService(LibraryScanService scanner) {
        this(scanner::getScanProgress, ManagementFactory.getMemoryMXBean());
    }

    MemoryDiagnosticsService(Supplier<LibraryScanService.ScanProgress> scanProgress,
                             MemoryMXBean memory) {
        this.scanProgress = Objects.requireNonNull(scanProgress, "scanProgress");
        this.memory = Objects.requireNonNull(memory, "memory");
    }

    public MemoryDiagnostics snapshot() {
        MemoryUsage heap = memory.getHeapMemoryUsage();
        MemoryUsage nonHeap = memory.getNonHeapMemoryUsage();
        LibraryScanService.ScanProgress progress = scanProgress.get();
        return new MemoryDiagnostics(
                known(heap.getUsed()),
                known(heap.getCommitted()),
                known(heap.getMax()),
                known(nonHeap.getUsed()),
                progress != null && progress.running(),
                progress == null || progress.phase() == null ? "IDLE" : progress.phase(),
                progress == null ? 0 : Math.max(0, progress.discovered()),
                progress == null ? 0 : Math.max(0, progress.probeCompleted()));
    }

    private static long known(long value) {
        return Math.max(0L, value);
    }
}
