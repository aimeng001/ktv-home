import { describe, expect, it } from 'vitest'
import { searchViewState } from './searchState'

describe('search view state', () => {
  it('keeps a request error distinct from a successful empty result', () => {
    expect(searchViewState('周杰伦', false, '', [])).toBe('empty')
    expect(searchViewState('周杰伦', false, '网络错误', [])).toBe('error')
  })

  it('prioritizes loading and supports results and idle states', () => {
    expect(searchViewState('周杰伦', true, '', [])).toBe('loading')
    expect(searchViewState('周杰伦', false, '', [{ id: 1 }])).toBe('results')
    expect(searchViewState('', false, '', [])).toBe('idle')
  })
})
