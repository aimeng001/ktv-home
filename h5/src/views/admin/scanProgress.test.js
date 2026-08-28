import { describe, expect, it } from 'vitest'
import { normalizeScanProgress, scanPercent, scanPhaseLabel } from './scanProgress'

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
})

describe('scanPhaseLabel', () => {
  it('explains the current bounded scan phase', () => {
    expect(scanPhaseLabel('FAST_INDEX')).toBe('正在建立快速索引')
    expect(scanPhaseLabel('MEDIA_PROBE')).toBe('正在读取媒体信息')
  })
})
