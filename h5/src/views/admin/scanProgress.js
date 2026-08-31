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
        failedCount: value.failedPaths || 0
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

/**
 * Calculates progress for the two-phase scanner without treating Fast Index
 * completion as the end of the media-probe phase.
 */
export function scanPercent(value = {}) {
  if (value.state === 'FAILED' || value.phase === 'FAILED') return 0
  const phase = value.phase
  if (phase === 'COMPLETED' || (!value.running && value.finishedAt && value.state !== 'FAILED')) return 100
  if (phase === 'DISCOVERING') return 0
  if (phase === 'FAST_INDEX') {
    const discovered = Math.max(Number(value.discovered) || 0, Number(value.total) || 0)
    if (!discovered) return 0
    return Math.min(50, Math.max(0,
      Math.round((Number(value.fastIndexed) || 0) * 50 / discovered)))
  }
  if (phase === 'MEDIA_PROBE') {
    const queued = Math.max(Number(value.probeQueued) || 0, Number(value.probeCompleted) || 0)
    if (!queued) return 100
    return Math.min(100, 50 + Math.max(0,
      Math.round((Number(value.probeCompleted) || 0) * 50 / queued)))
  }
  // Keep the legacy contract usable for older servers.
  return value.total
    ? Math.min(100, Math.max(0, Math.round((Number(value.completed) || 0) * 100 / value.total)))
    : (value.running ? 0 : 100)
}

export function scanPhaseLabel(phase, state) {
  if (state === 'FAILED' || phase === 'FAILED') return '扫描失败'
  if (state === 'PARTIAL') return '部分扫描完成'
  return {
    DISCOVERING: '正在读取文件列表',
    FAST_INDEX: '正在建立快速索引',
    MEDIA_PROBE: '正在读取媒体信息',
    COMPLETED: '扫描完成'
  }[phase] || '正在扫描源路径'
}

export function formatEta(seconds) {
  if (!seconds || seconds <= 0) return ''
  const h = Math.floor(seconds / 3600)
  const m = Math.floor((seconds % 3600) / 60)
  const s = seconds % 60
  if (h > 0) return `${h}小时${m}分`
  if (m > 0) return `${m}分${s}秒`
  return `${s}秒`
}
