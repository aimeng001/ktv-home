import { describe, expect, it } from 'vitest'
import { canDeleteSongs } from './libraryMode'

describe('admin library deletion gate', () => {
  it('allows deletion only after MANAGED mode is confirmed', () => {
    expect(canDeleteSongs('MANAGED')).toBe(true)
  })

  it('denies deletion for external or unknown modes', () => {
    expect(canDeleteSongs('EXTERNAL_READ_ONLY')).toBe(false)
    expect(canDeleteSongs(null)).toBe(false)
    expect(canDeleteSongs(undefined)).toBe(false)
    expect(canDeleteSongs('unexpected')).toBe(false)
  })
})
