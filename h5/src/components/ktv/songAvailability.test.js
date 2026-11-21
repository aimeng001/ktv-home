import { describe, expect, it } from 'vitest'
import { UNAVAILABLE_REASON_LABELS, unavailableReasonLabel } from './songAvailability'

describe('song availability labels', () => {
  it('translates every reason code the backend can send', () => {
    expect(Object.keys(UNAVAILABLE_REASON_LABELS).sort()).toEqual(['PENDING_PROBE', 'SONG_NOT_READY'])
    expect(unavailableReasonLabel('SONG_NOT_READY')).toBe('正在扫描或媒体探测未完成')
    expect(unavailableReasonLabel('PENDING_PROBE')).toBe('媒体信息探测中')
  })

  it('never leaks a raw error code to the user', () => {
    expect(unavailableReasonLabel('SOME_FUTURE_CODE')).toBe('当前不可点')
    expect(unavailableReasonLabel('SOME_FUTURE_CODE')).not.toContain('_')
    expect(unavailableReasonLabel()).toBe('当前不可点')
    expect(unavailableReasonLabel('')).toBe('当前不可点')
  })
})
