package com.homektv.diagnostics;

import com.homektv.resource.ResourceBudget;

/** Secret-free memory and scan snapshot for the authenticated diagnostics API. */
public record MemoryDiagnostics(
        long heapUsedBytes,
        long heapCommittedBytes,
        long heapMaxBytes,
        long nonHeapUsedBytes,
        boolean scanRunning,
        String scanPhase,
        int discoveredFiles,
        int probeProcessed,
        ResourceBudget.Snapshot resourceBudget) {
}
