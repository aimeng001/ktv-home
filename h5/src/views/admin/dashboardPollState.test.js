import { describe, expect, it } from 'vitest'
import {
  MAX_SCAN_POLL_FAILURES,
  nextScanPollDelay,
  recordScanPollFailure,
  recordScanPollSuccess
} from './dashboardPollState'

describe('dashboard scan polling state', () => {
  it('starts the failure count from zero when the state has no counter', () => {
    const error = new Error('temporary failure')
    const next = recordScanPollFailure({ running: false }, error)

    expect(next).toMatchObject({ failures: 1, error, stop: false, running: false })
  })

  it('keeps polling through transient failures and stops at the configured limit', () => {
    const error = new Error('temporary failure')
    const first = recordScanPollFailure({ failures: 1, running: true }, error)
    const second = recordScanPollFailure(first, error)

    expect(MAX_SCAN_POLL_FAILURES).toBe(3)
    expect(first).toMatchObject({ failures: 2, error, stop: false, running: true })
    expect(second).toMatchObject({ failures: 3, error, stop: true, running: false })
  })

  it('resets failures and stop state on success, including missing progress', () => {
    expect(recordScanPollSuccess({ failures: 2, error: new Error('503'), stop: true }, null))
      .toEqual({ failures: 0, error: null, stop: false, running: false })
    expect(recordScanPollSuccess({}, { running: true })).toEqual({
      failures: 0,
      error: null,
      stop: false,
      running: true
    })
  })

  it('递增扫描轮询间隔，并在 5 秒封顶', () => {
    expect([0, 1, 2, 3, 4].map(nextScanPollDelay)).toEqual([1000, 1200, 1440, 1728, 2074])
    // 20 万首扫描要跑很久，不能无限退避到「看起来卡住」
    expect(nextScanPollDelay(10)).toBe(5000)
    expect(nextScanPollDelay(50)).toBe(5000)
  })
})
