// @vitest-environment jsdom

import { describe, it, expect, beforeEach } from 'vitest'
import { setActivePinia, createPinia } from 'pinia'
import { useUserStore } from './user'

describe('useUserStore', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    localStorage.clear()
  })

  it('未注册时 isRegistered 为 false', () => {
    const user = useUserStore()
    expect(user.isRegistered).toBe(false)
  })

  it('ensureToken 生成并持久化 token', () => {
    const user = useUserStore()
    const t = user.ensureToken()
    expect(t).toBeTruthy()
    expect(localStorage.getItem('ktv_client_token')).toBe(t)
    // 再次调用返回同一 token
    expect(user.ensureToken()).toBe(t)
  })

  it('register 保存昵称并持久化', () => {
    const user = useUserStore()
    user.register('小明')
    expect(user.nickname).toBe('小明')
    expect(user.isRegistered).toBe(true)
    expect(localStorage.getItem('ktv_nickname')).toBe('小明')
  })

  it('register 空昵称时回退到随机默认值', () => {
    const user = useUserStore()
    user.register('   ')
    expect(user.nickname).toMatch(/^家人\d{4}$/)
  })

  it('suggestNickname 未设置时给出 家人XXXX 格式', () => {
    const user = useUserStore()
    expect(user.suggestNickname()).toMatch(/^家人\d{4}$/)
  })

  it('只有服务端返回稳定 id 后才允许进入可写点歌状态', () => {
    const user = useUserStore()
    user.register('小明')
    expect(user.isReady).toBe(false)

    expect(user.markRegistrationSuccess({ id: 37, nickname: '小明#2' })).toBe(true)

    expect(user.isReady).toBe(true)
    expect(user.serverUserId).toBe(37)
    expect(user.nickname).toBe('小明#2')
    expect(localStorage.getItem('ktv_server_user_id')).toBe('37')
  })

  it('服务端注册失败会清除 id，不能继续静默进入点歌页', () => {
    const user = useUserStore()
    user.register('小明')
    user.markRegistrationSuccess({ id: 37, nickname: '小明' })
    user.markRegistrationFailure(new Error('服务不可用'))

    expect(user.isReady).toBe(false)
    expect(user.serverUserId).toBeNull()
    expect(user.registrationError).toBe('服务不可用')
    expect(localStorage.getItem('ktv_server_user_id')).toBeNull()
  })

  it('非法服务端身份响应保持 fail-closed', () => {
    const user = useUserStore()
    user.register('小明')

    expect(user.markRegistrationSuccess({ id: 0, nickname: '' })).toBe(false)
    expect(user.isReady).toBe(false)
    expect(user.registrationError).toContain('有效用户身份')
  })
})
