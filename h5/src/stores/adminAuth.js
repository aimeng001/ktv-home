import { defineStore } from 'pinia'

const TOKEN_KEY = 'home-ktv.admin.token'

export function getAdminToken() {
  return typeof localStorage === 'undefined' ? '' : localStorage.getItem(TOKEN_KEY) || ''
}

export function clearAdminToken() {
  if (typeof localStorage !== 'undefined') localStorage.removeItem(TOKEN_KEY)
}

/** 管理后台会话，仅保存服务端发放的短期令牌，不保存管理员密码。 */
export const useAdminAuthStore = defineStore('adminAuth', {
  state: () => ({ token: getAdminToken() }),
  getters: {
    isAuthenticated: (state) => Boolean(state.token)
  },
  actions: {
    setToken(token) {
      this.token = typeof token === 'string' ? token : ''
      if (this.token) localStorage.setItem(TOKEN_KEY, this.token)
      else clearAdminToken()
    },
    clearToken() {
      this.token = ''
      clearAdminToken()
    }
  }
})
