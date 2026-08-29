package com.homektv.diagnostics;

/** Secret-free memory and scan snapshot for the authenticated diagnostics API. */
public record MemoryDiagnostics(
        long heapUsedBytes,
        long heapCommittedBytes,
        long heapMaxBytes,
        long nonHeapUsedBytes,
        boolean scanRunning,
        String scanPhase,
        int discoveredFiles,
        int probeProcessed) {
}
