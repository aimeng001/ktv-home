import { describe, expect, it } from 'vitest'
import { normalizeScanProgress } from './scanProgress'

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
