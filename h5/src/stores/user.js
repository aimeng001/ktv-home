/**
 * 用户身份管理 store — 负责客户端 token 与昵称的生成、持久化和读写。
 *
 * User identity store — manages generation, persistence and read/write of client token and nickname.
 */
import { defineStore } from 'pinia'

// localStorage 存储键名 / localStorage storage keys
const TOKEN_KEY = 'ktv_client_token'
const NICK_KEY = 'ktv_nickname'
const USER_ID_KEY = 'ktv_server_user_id'

/**
 * 生成客户端唯一标识 token：优先使用原生 crypto.randomUUID()，不可用时降级为随机字符串。
 *
 * Generate a unique client token: prefers native crypto.randomUUID(), falls back to a random string.
 * @returns {string} 客户端 token / client token
 */
function genToken() {
  // 优先用原生 UUID，降级到随机串
  // Prefer native UUID; fall back to random string
  if (globalThis.crypto?.randomUUID) return crypto.randomUUID()
  return 'tok-' + Math.random().toString(36).slice(2) + Date.now().toString(36)
}

/**
 * 生成默认随机昵称，格式为「家人」+ 四位数字。
 *
 * Generate a default random nickname: "家人" + 4-digit number.
 * @returns {string} 默认昵称 / default nickname
 */
function genNickname() {
  return '家人' + Math.floor(1000 + Math.random() * 9000)
}

/**
 * 点歌人身份 store（详设 H5-01）。
 * 管理 client_token 与 nickname，写入 localStorage，非首次进入免填。
 *
 * Song requester identity store (detailed design H5-01).
 * Manages client_token and nickname, persisted to localStorage; skips form on subsequent visits.
 */
export const useUserStore = defineStore('user', {
  state: () => ({
    // 客户端唯一标识 / client unique token
    clientToken: localStorage.getItem(TOKEN_KEY) || '',
    // 用户昵称 / user nickname
    nickname: localStorage.getItem(NICK_KEY) || '',
    // 服务端 POST /user 返回的稳定身份；仅有本地昵称不代表可写。
    serverUserId: Number(localStorage.getItem(USER_ID_KEY)) || null,
    registrationError: ''
  }),
  getters: {
    // 是否已完成初次进入（有 token 且有昵称）
    // Whether the user has completed initial registration (has both token and nickname)
    isRegistered: (s) => !!s.clientToken && !!s.nickname,
    isServerRegistered: (s) => Number.isInteger(s.serverUserId) && s.serverUserId > 0,
    isReady: (s) => !!s.clientToken && !!s.nickname && Number.isInteger(s.serverUserId) && s.serverUserId > 0 && !s.registrationError
  },
  actions: {
    /**
     * 确保存在 clientToken：首次调用时生成并持久化到 localStorage。
     *
     * Ensure a clientToken exists: generates and persists to localStorage on first call.
     * @returns {string} 当前有效的 clientToken / current valid clientToken
     */
    ensureToken() {
      if (!this.clientToken) {
        this.clientToken = genToken()
        localStorage.setItem(TOKEN_KEY, this.clientToken)
      }
      return this.clientToken
    },

    /**
     * 返回建议昵称：已设置则返回已有昵称，否则返回随机默认值。
     *
     * Suggest a nickname: returns the existing one if set, otherwise a random default.
     * @returns {string} 昵称 / nickname
     */
    suggestNickname() {
      return this.nickname || genNickname()
    },

    /**
     * 注册/保存昵称（H5-01 提交时调用）。
     * 先确保 token 存在，再保存昵称；空值或纯空格时使用随机默认值。
     *
     * Register/save nickname (called on H5-01 form submit).
     * Ensures token exists first, then saves the nickname; falls back to random default for blank input.
     * @param {string} nickname - 用户输入的昵称 / user-supplied nickname
     */
    register(nickname) {
      this.ensureToken()
      this.nickname = (nickname || '').trim() || genNickname()
      localStorage.setItem(NICK_KEY, this.nickname)
      this.serverUserId = null
      this.registrationError = ''
      localStorage.removeItem(USER_ID_KEY)
    },

    /** 保存服务端最终昵称和用户 id；缺失 id 时保持不可写。 */
    markRegistrationSuccess(profile) {
      const id = Number(profile?.id)
      if (!Number.isInteger(id) || id <= 0 || !profile?.nickname) {
        this.markRegistrationFailure(new Error('服务端未返回有效用户身份'))
        return false
      }
      this.serverUserId = id
      localStorage.setItem(USER_ID_KEY, String(id))
      this.setNickname(profile.nickname)
      this.registrationError = ''
      return true
    },

    /** 注册失败时清除可写身份，入口页必须停留并允许重试。 */
    markRegistrationFailure(error) {
      this.serverUserId = null
      localStorage.removeItem(USER_ID_KEY)
      this.registrationError = error?.message || '无法连接点歌服务，请重试'
    },

    /**
     * 用服务端去重后的最终昵称覆盖本地记录（P2.13）。
     *
     * Overwrite local nickname with the server-deduplicated final value (P2.13).
     * @param {string} nickname - 服务端返回的昵称 / server-returned nickname
     */
    setNickname(nickname) {
      if (!nickname) return
      this.nickname = nickname
      localStorage.setItem(NICK_KEY, nickname)
    }
  }
})
