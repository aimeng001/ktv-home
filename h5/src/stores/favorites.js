/**
 * 收藏（喜欢）状态管理模块。
 * 管理用户收藏歌曲的 ID 列表，支持加载和切换收藏状态。
 *
 * Favorites (liked songs) state management module.
 * Manages the user's favorite song ID list, supporting load and toggle operations.
 */

import { defineStore } from 'pinia'
import api from '../api/client'
import { loadAllFavoritePages } from '../views/favoritesState'

export const useFavoritesStore = defineStore('favorites', {
  /**
   * @property {string[]} ids - 已收藏歌曲的 ID 列表 / Favorite song IDs
   * @property {string} loadedFor - 上次加载对应的 clientToken，用于缓存去重 / Last loaded clientToken for cache dedup
   * @property {boolean} loading - 加载中标记 / Loading flag
   */
  state: () => ({ ids: [], loadedFor: '', loading: false }),
  getters: {
    /**
     * 判断指定歌曲是否已收藏。
     *
     * Check whether the given song is in favorites.
     * @param {string} songId - 歌曲 ID / Song ID
     * @returns {boolean} 已收藏返回 true / true if favorited
     */
    has: (state) => (songId) => state.ids.includes(songId)
  },
  actions: {
    /**
     * 加载当前用户的收藏歌曲 ID 列表。
     * 内置防重机制：同一 clientToken 不重复加载，除非 force=true。
     *
     * Load the current user's favorite song IDs.
     * Built-in dedup: skips reload for the same clientToken unless force=true.
     * @param {string} clientToken - 客户端标识 / Client token
     * @param {boolean} [force=false] - 是否强制重新加载 / Whether to force reload
     * @returns {Promise<void>}
     */
    async load(clientToken, force = false) {
      if (!clientToken || this.loading || (!force && this.loadedFor === clientToken)) return
      this.loading = true
      try {
        this.ids = await loadAllFavoritePages((page, size) => api.favoriteIds(clientToken, page, size))
        this.loadedFor = clientToken
      } finally {
        this.loading = false
      }
    },
    /**
     * 切换指定歌曲的收藏状态：已收藏则取消，未收藏则添加。
     *
     * Toggle the favorite status of a song: remove if favorited, add if not.
     * @param {string} songId - 歌曲 ID / Song ID
     * @param {string} clientToken - 客户端标识 / Client token
     * @returns {Promise<boolean>} 操作后是否已收藏 / Whether favorited after the operation
     */
    async toggle(songId, clientToken) {
      if (this.has(songId)) {
        await api.removeFavorite(songId, clientToken)
        this.ids = this.ids.filter(id => id !== songId)
        return false
      }
      await api.addFavorite(songId, clientToken)
      if (!this.has(songId)) this.ids = [songId, ...this.ids]
      return true
    }
  }
})
