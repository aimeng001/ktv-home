import { describe, expect, it } from 'vitest'
import { formatEta, normalizeScanProgress, scanPercent, scanPhaseLabel } from './scanProgress'

describe('normalizeScanProgress', () => {
  it('maps external-library counters to read-only scan labels', () => {
    expect(normalizeScanProgress({
      running: false,
      total: 4,
      completed: 4,
      added: 2,
      updated: 1,
      skipped: 1,
      unrecognized: 1
    })).toEqual({
      mode: 'EXTERNAL_READ_ONLY',
      primaryLabel: '新增/更新',
      primaryCount: 3,
      secondaryLabel: '跳过',
      secondaryCount: 1,
      duplicateCount: 0,
      unrecognizedCount: 1,
      failedCount: 0
    })
  })

  it('recognizes either external counter and defaults missing counters to zero', () => {
    expect(normalizeScanProgress({ updated: 2, failedPaths: 1 })).toEqual({
      mode: 'EXTERNAL_READ_ONLY',
      primaryLabel: '新增/更新',
      primaryCount: 2,
      secondaryLabel: '跳过',
      secondaryCount: 0,
      duplicateCount: 0,
      unrecognizedCount: 0,
      failedCount: 1
    })
  })

  it('uses zero for an omitted updated counter in an external scan', () => {
    expect(normalizeScanProgress({ added: 2 })).toMatchObject({
      mode: 'EXTERNAL_READ_ONLY',
      primaryCount: 2
    })
  })

  it('preserves managed-library scan counters and labels', () => {
    expect(normalizeScanProgress({
      copied: 2,
      pendingTranscode: 3,
      skippedSourceDuplicate: 4,
      skippedOutputDuplicate: 1,
      unrecognized: 2,
      failed: 1
    })).toEqual({
      mode: 'MANAGED',
      primaryLabel: '直接移动',
      primaryCount: 2,
      secondaryLabel: '待转码',
      secondaryCount: 3,
      duplicateCount: 5,
      unrecognizedCount: 2,
      failedCount: 1
    })
  })

  it('defaults missing managed counters to zero', () => {
    expect(normalizeScanProgress({})).toEqual({
      mode: 'MANAGED',
      primaryLabel: '直接移动',
      primaryCount: 0,
      secondaryLabel: '待转码',
      secondaryCount: 0,
      duplicateCount: 0,
      unrecognizedCount: 0,
      failedCount: 0
    })
  })
})

describe('scanPercent', () => {
  it('does not report fast-index completion as full scan completion', () => {
    expect(scanPercent({ running: true, phase: 'FAST_INDEX', discovered: 500, fastIndexed: 500 }))
      .toBe(50)
  })

  it('uses the probe queue for the second half of progress', () => {
    expect(scanPercent({ running: true, phase: 'MEDIA_PROBE', probeQueued: 500, probeCompleted: 250 }))
      .toBe(75)
  })

  it('reports completed scans as 100 percent and discovery as zero', () => {
    expect(scanPercent({ running: false, phase: 'COMPLETED' })).toBe(100)
    expect(scanPercent({ running: true, phase: 'DISCOVERING' })).toBe(0)
  })

  it('reports failed scans as 0 percent even if finishedAt is set', () => {
    expect(scanPercent({ running: false, state: 'FAILED', finishedAt: '2026-09-01T00:00:00Z' })).toBe(0)
    expect(scanPercent({ running: false, phase: 'FAILED', finishedAt: '2026-09-01T00:00:00Z' })).toBe(0)
  })

  it('handles zero totals, unfinished legacy scans, and finished timestamps', () => {
    expect(scanPercent({ running: true, phase: 'FAST_INDEX', discovered: 0, total: 0 })).toBe(0)
    expect(scanPercent({ running: true, phase: 'MEDIA_PROBE', probeQueued: 0 })).toBe(100)
    expect(scanPercent({ running: true, total: 0 })).toBe(0)
    expect(scanPercent({ running: false, total: 0 })).toBe(100)
    expect(scanPercent({ running: false, finishedAt: '2026-09-01T00:00:00Z' })).toBe(100)
  })

  it('clamps fast-index, media-probe, and legacy progress to their bounds', () => {
    expect(scanPercent({ phase: 'FAST_INDEX', discovered: 4, total: 8, fastIndexed: 12 })).toBe(50)
    expect(scanPercent({ phase: 'FAST_INDEX', discovered: 4, fastIndexed: -1 })).toBe(0)
    expect(scanPercent({ phase: 'FAST_INDEX', discovered: 4, fastIndexed: 0 })).toBe(0)
    expect(scanPercent({ phase: 'MEDIA_PROBE', probeQueued: 4, probeCompleted: 8 })).toBe(100)
    expect(scanPercent({ phase: 'MEDIA_PROBE', probeQueued: 4, probeCompleted: -1 })).toBe(50)
    expect(scanPercent({ phase: 'MEDIA_PROBE', probeQueued: 4, probeCompleted: 0 })).toBe(50)
    expect(scanPercent({ total: 4, completed: 8 })).toBe(100)
    expect(scanPercent({ total: 4, completed: -1 })).toBe(0)
    expect(scanPercent({ total: 4, completed: 0 })).toBe(0)
  })

  it('uses the larger discovered or queued count as the progress denominator', () => {
    expect(scanPercent({ phase: 'FAST_INDEX', discovered: 2, total: 4, fastIndexed: 2 })).toBe(25)
    expect(scanPercent({ phase: 'MEDIA_PROBE', probeQueued: 2, probeCompleted: 4 })).toBe(100)
  })
})

describe('scanPhaseLabel', () => {
  it('explains the current bounded scan phase', () => {
    expect(scanPhaseLabel('FAST_INDEX')).toBe('正在建立快速索引')
    expect(scanPhaseLabel('MEDIA_PROBE')).toBe('正在读取媒体信息')
    expect(scanPhaseLabel('COMPLETED', 'FAILED')).toBe('扫描失败')
    expect(scanPhaseLabel('COMPLETED', 'PARTIAL')).toBe('部分扫描完成')
    expect(scanPhaseLabel('FAILED')).toBe('扫描失败')
    expect(scanPhaseLabel('DISCOVERING')).toBe('正在读取文件列表')
    expect(scanPhaseLabel('COMPLETED')).toBe('扫描完成')
    expect(scanPhaseLabel('UNKNOWN')).toBe('正在扫描源路径')
  })
})

describe('formatEta', () => {
  it('formats seconds, minutes, and hours while omitting non-positive values', () => {
    expect(formatEta(0)).toBe('')
    expect(formatEta(-1)).toBe('')
    expect(formatEta(9)).toBe('9秒')
    expect(formatEta(65)).toBe('1分5秒')
    expect(formatEta(3661)).toBe('1小时1分')
  })
})
