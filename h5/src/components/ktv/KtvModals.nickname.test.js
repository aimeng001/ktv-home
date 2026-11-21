// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createApp, nextTick, h } from 'vue'
import { createPinia, setActivePinia } from 'pinia'

const { apiMock, toastSpy } = vi.hoisted(() => ({
  apiMock: { registerUser: vi.fn() },
  toastSpy: vi.fn()
}))

vi.mock('../../api/client', () => ({ default: apiMock }))
vi.mock('../../composables/useToast', async () => {
  const { ref } = await import('vue')
  return { useToast: () => ({ message: ref(''), toast: toastSpy }) }
})
vi.mock('vue-router', () => ({ useRouter: () => ({ push: vi.fn(), replace: vi.fn() }) }))

import KtvModals from './KtvModals.vue'
import { useUserStore } from '../../stores/user'

describe('KtvModals 昵称保存失败回滚', () => {
  let app
  let root
  let user
  let pinia

  beforeEach(async () => {
    localStorage.clear()
    pinia = createPinia()
    setActivePinia(pinia)
    user = useUserStore()
    user.ensureToken()
    user.markRegistrationSuccess({ id: 17, nickname: '旧昵称' })
    apiMock.registerUser.mockRejectedValue(new Error('网络异常'))

    root = document.createElement('div')
    document.body.appendChild(root)
    app = createApp(KtvModals, { activeModal: 'settings' })
    app.component('RouterLink', { render: () => h('a') })
    app.use(pinia)
    app.mount(root)
    await nextTick()
  })

  afterEach(() => {
    if (app) app.unmount()
    if (root) root.remove()
    app = null
    root = null
    vi.clearAllMocks()
    localStorage.clear()
  })

  async function submitNickname(value) {
    const input = root.querySelector('.nick-row input')
    expect(input).not.toBeNull()
    input.value = value
    input.dispatchEvent(new Event('input'))
    await nextTick()
    root.querySelector('.nick-row button').click()
    await nextTick()
    await nextTick()
    await nextTick()
  }

  it('昵称保存失败后可写身份不被清空', async () => {
    await submitNickname('新昵称')
    expect(user.serverUserId).toBe(17)
    expect(user.isReady).toBe(true)
    expect(toastSpy).toHaveBeenCalled()
  })

  it('昵称更新失败时回滚本地昵称', async () => {
    await submitNickname('新昵称')
    expect(user.nickname).toBe('旧昵称')
    expect(localStorage.getItem('ktv_nickname')).toBe('旧昵称')
  })
})
