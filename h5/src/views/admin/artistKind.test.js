import { describe, expect, it } from 'vitest'
import { ARTIST_KIND_LABELS, artistKindLabel } from './artistKind'

describe('artist kind labels', () => {
  it('covers every kind the backend can report', () => {
    expect(Object.keys(ARTIST_KIND_LABELS).sort()).toEqual(
      ['GROUP', 'PERSON', 'UNATTRIBUTED', 'UNKNOWN', 'VARIOUS'].sort()
    )
  })

  it('renders a group as a group instead of an unassigned note', () => {
    expect(artistKindLabel('GROUP')).toBe('组合')
    expect(artistKindLabel('GROUP')).not.toBe(artistKindLabel('UNATTRIBUTED'))
    expect(artistKindLabel('VARIOUS')).toBe('群星')
    expect(artistKindLabel('PERSON')).toBe('个人')
    expect(artistKindLabel('UNATTRIBUTED')).toBe('未归属')
    expect(artistKindLabel('UNKNOWN')).toBe('未分类')
  })

  it('falls back safely for absent or future kinds', () => {
    expect(artistKindLabel()).toBe('')
    expect(artistKindLabel(null)).toBe('')
    expect(artistKindLabel('')).toBe('')
    expect(artistKindLabel('FUTURE_KIND')).toBe('未分类')
  })
})
