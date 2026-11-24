package com.homektv.common;

/**
 * Published after queue mutations commit so cached read snapshots invalidate immediately.
 */
public record QueueSnapshotInvalidatedEvent() {
}
