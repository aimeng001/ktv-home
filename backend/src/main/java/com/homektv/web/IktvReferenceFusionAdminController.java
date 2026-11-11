package com.homektv.web;

import com.homektv.library.IktvFusionException;
import com.homektv.library.IktvSongMatchBatchService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Admin-authenticated status, rollback, and explicit resume for reference fusion. */
@RestController
@RequestMapping("/api/admin/iktv-reference/fusion")
public final class IktvReferenceFusionAdminController {
    private final IktvSongMatchBatchService batches;

    public IktvReferenceFusionAdminController(IktvSongMatchBatchService batches) {
        this.batches = batches;
    }

    @GetMapping("/status")
    public IktvSongMatchBatchService.FusionStatus status() {
        return batches.status();
    }

    @GetMapping("/batches/{batchId}")
    public IktvSongMatchBatchService.BatchStatus batch(@PathVariable long batchId) {
        try {
            return batches.batchStatus(batchId);
        } catch (IktvFusionException failure) {
            throw new ApiException(failure.getCode(), failure.getMessage());
        }
    }

    @PostMapping("/batches/{batchId}/rollback")
    public IktvSongMatchBatchService.RollbackResult rollback(@PathVariable long batchId) {
        try {
            return batches.rollback(batchId);
        } catch (IktvFusionException failure) {
            throw new ApiException(failure.getCode(), failure.getMessage());
        }
    }

    @PostMapping("/resume")
    public IktvSongMatchBatchService.RollbackResult resume() {
        return batches.resume();
    }
}
