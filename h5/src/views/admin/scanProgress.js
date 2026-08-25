/**
 * Normalizes the two backend scan-progress contracts for the admin UI.
 * External read-only scans report added/updated/skipped; managed scans report
 * copied/pending-transcode/duplicate counters.
 */
export function normalizeScanProgress(value = {}) {
  const external = ['added', 'updated']
    .some(key => Object.prototype.hasOwnProperty.call(value, key))

  return external
    ? {
        mode: 'EXTERNAL_READ_ONLY',
        primaryLabel: '新增/更新',
        primaryCount: (value.added || 0) + (value.updated || 0),
        secondaryLabel: '跳过',
        secondaryCount: value.skipped || 0,
        duplicateCount: 0,
        unrecognizedCount: value.unrecognized || 0,
        failedCount: 0
      }
    : {
        mode: 'MANAGED',
        primaryLabel: '直接移动',
        primaryCount: value.copied || 0,
        secondaryLabel: '待转码',
        secondaryCount: value.pendingTranscode || 0,
        duplicateCount: (value.skippedSourceDuplicate || 0) + (value.skippedOutputDuplicate || 0),
        unrecognizedCount: value.unrecognized || 0,
        failedCount: value.failed || 0
      }
}
