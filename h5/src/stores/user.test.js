// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { useUserStore } from './user'

const TOKEN_KEY = 'ktv_client_token'
const NICK_KEY = 'ktv_nickname'
const USER_ID_KEY = 'ktv_server_user_id'

describe('user identity store', () => {
  beforeEach(() => {
    localStorage.clear()
    setActivePinia(createPinia())
  })

  afterEach(() => {
    vi.restoreAllMocks()
    vi.unstubAllGlobals()
    localStorage.clear()
  })

  it('restores persisted identity and distinguishes local registration from server readiness', () => {
    localStorage.setItem(TOKEN_KEY, 'saved-token')
    localStorage.setItem(NICK_KEY, '家人')
    localStorage.setItem(USER_ID_KEY, '42')
    const user = useUserStore()

    expect(user.clientToken).toBe('saved-token')
    expect(user.nickname).toBe('家人')
    expect(user.serverUserId).toBe(42)
    expect(user.isRegistered).toBe(true)
    expect(user.isServerRegistered).toBe(true)
    expect(user.isReady).toBe(true)

    user.registrationError = 'registration failed'
    expect(user.isReady).toBe(false)
  })

  it('generates and persists a UUID token once when crypto UUID is available', () => {
    const randomUUID = vi.fn(() => 'uuid-token')
    vi.stubGlobal('crypto', { randomUUID })
    const user = useUserStore()

    expect(user.ensureToken()).toBe('uuid-token')
    expect(user.ensureToken()).toBe('uuid-token')
    expect(randomUUID).toHaveBeenCalledTimes(1)
    expect(localStorage.getItem(TOKEN_KEY)).toBe('uuid-token')
  })

  it('uses the random token fallback when crypto UUID is unavailable', () => {
    vi.stubGlobal('crypto', {})
    vi.spyOn(Math, 'random').mockReturnValue(0.5)
    vi.spyOn(Date, 'now').mockReturnValue(1_700_000_000_000)
    const user = useUserStore()

    const token = user.ensureToken()

    expect(token).toBe('tok-iloyw3v28')
    expect(localStorage.getItem(TOKEN_KEY)).toBe(token)
  })

  it('suggests the saved nickname or a four-digit default', () => {
    localStorage.setItem(NICK_KEY, '已保存昵称')
    const savedUser = useUserStore()
    const random = vi.spyOn(Math, 'random').mockReturnValue(0)

    expect(savedUser.suggestNickname()).toBe('已保存昵称')
    expect(random).not.toHaveBeenCalled()

    localStorage.removeItem(NICK_KEY)
    setActivePinia(createPinia())
    expect(useUserStore().suggestNickname()).toBe('家人1000')
    expect(random).toHaveBeenCalledTimes(1)
  })

  it('trims a supplied nickname and clears stale server registration', () => {
    localStorage.setItem(TOKEN_KEY, 'existing-token')
    localStorage.setItem(NICK_KEY, '旧昵称')
    localStorage.setItem(USER_ID_KEY, '42')
    const user = useUserStore()
    user.registrationError = 'retry required'

    user.register('  新昵称  ')

    expect(user.clientToken).toBe('existing-token')
    expect(user.nickname).toBe('新昵称')
    expect(user.serverUserId).toBeNull()
    expect(user.registrationError).toBe('')
    expect(localStorage.getItem(NICK_KEY)).toBe('新昵称')
    expect(localStorage.getItem(USER_ID_KEY)).toBeNull()
  })

  it('uses a generated nickname when registration input is absent or blank', () => {
    localStorage.setItem(TOKEN_KEY, 'existing-token')
    vi.spyOn(Math, 'random').mockReturnValue(0.999999)
    const user = useUserStore()

    user.register()
    expect(user.nickname).toBe('家人9999')

    user.register(' \t ')

    expect(user.nickname).toBe('家人9999')
    expect(localStorage.getItem(NICK_KEY)).toBe('家人9999')
  })

  it('persists a valid server identity and replaces the suggested nickname', () => {
    localStorage.setItem(TOKEN_KEY, 'client-token')
    const user = useUserStore()
    user.register('本地昵称')

    expect(user.markRegistrationSuccess({ id: '17', nickname: '服务端最终昵称' })).toBe(true)
    expect(user.serverUserId).toBe(17)
    expect(user.nickname).toBe('服务端最终昵称')
    expect(user.isReady).toBe(true)
    expect(localStorage.getItem(USER_ID_KEY)).toBe('17')
    expect(localStorage.getItem(NICK_KEY)).toBe('服务端最终昵称')
  })

  it.each([
    [{ nickname: '昵称' }, 'missing id'],
    [{ id: 0, nickname: '昵称' }, 'nonpositive id'],
    [{ id: 9, nickname: '' }, 'missing nickname']
  ])('rejects an invalid server profile (%s)', (profile) => {
    localStorage.setItem(USER_ID_KEY, '31')
    const user = useUserStore()

    expect(user.markRegistrationSuccess(profile)).toBe(false)
    expect(user.serverUserId).toBeNull()
    expect(user.isServerRegistered).toBe(false)
    expect(user.registrationError).toBe('服务端未返回有效用户身份')
    expect(localStorage.getItem(USER_ID_KEY)).toBeNull()
  })

  it('uses the server error message or a safe retry message and clears write identity', () => {
    localStorage.setItem(USER_ID_KEY, '23')
    const user = useUserStore()

    user.markRegistrationFailure(new Error('server offline'))
    expect(user.registrationError).toBe('server offline')
    expect(user.isReady).toBe(false)
    expect(localStorage.getItem(USER_ID_KEY)).toBeNull()

    localStorage.setItem(USER_ID_KEY, '24')
    user.markRegistrationFailure({})
    expect(user.registrationError).toBe('无法连接点歌服务，请重试')
    expect(user.serverUserId).toBeNull()
    expect(localStorage.getItem(USER_ID_KEY)).toBeNull()
  })

  it('ignores an empty server nickname and persists a nonempty one', () => {
    localStorage.setItem(NICK_KEY, '当前昵称')
    const user = useUserStore()

    user.setNickname('')
    expect(user.nickname).toBe('当前昵称')
    expect(localStorage.getItem(NICK_KEY)).toBe('当前昵称')

    user.setNickname('最终昵称')
    expect(user.nickname).toBe('最终昵称')
    expect(localStorage.getItem(NICK_KEY)).toBe('最终昵称')
  })
})
