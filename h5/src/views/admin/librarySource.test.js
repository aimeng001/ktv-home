import { describe, expect, it } from 'vitest'
import { sourceLabel } from './librarySource'

describe('admin library source labels', () => {
  it('distinguishes external read-only files from legacy files', () => {
    expect(sourceLabel('EXTERNAL_READ_ONLY')).toBe('外部只读')
    expect(sourceLabel('UNKNOWN')).toBe('历史曲库')
  })
})
