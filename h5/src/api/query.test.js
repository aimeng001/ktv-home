import { describe, expect, it } from 'vitest'
import { toQuery } from './query'

describe('toQuery', () => {
  it('跳过空串与空值，保留 0 和 false', () => {
    expect(toQuery({ keyword: '晴天', gender: '', region: null, page: 0, all: false }))
      .toBe('keyword=%E6%99%B4%E5%A4%A9&page=0&all=false')
  })

  it('没有可用参数时返回空串，不留下悬空的 ?', () => {
    expect(toQuery()).toBe('')
    expect(toQuery({ keyword: '', page: null })).toBe('')
  })

  it('编码分隔符与等号', () => {
    expect(toQuery({ q: 'a&b=c' })).toBe('q=a%26b%3Dc')
  })
})
