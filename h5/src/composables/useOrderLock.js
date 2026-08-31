import { reactive } from 'vue'

/**
 * useOrderLock - 点歌并发锁与防抖 Composable
 * 维护正在进行点歌网络请求的歌曲 ID 集合，防止短时间内连点发起重复请求导致 SONG_IN_QUEUE 报错。
 *
 * useOrderLock - In-flight song ordering lock & debounce composable.
 * Tracks song IDs currently being ordered to prevent duplicate network requests and spurious errors.
 */
export function useOrderLock() {
  const inflightIds = reactive(new Set())

  /**
   * 执行带锁的点歌操作。
   * @param {number|string} songId - 歌曲 ID
   * @param {() => Promise<any>} orderFn - 实际发起点歌的异步函数
   * @returns {Promise<boolean>} 是否成功发起并执行（若并发重复则返回 false）
   */
  async function executeOrder(songId, orderFn) {
    if (!songId || inflightIds.has(songId)) {
      return false
    }
    inflightIds.add(songId)
    try {
      await orderFn()
      return true
    } finally {
      inflightIds.delete(songId)
    }
  }

  /**
   * 检查指定歌曲是否正在点歌中。
   * @param {number|string} songId - 歌曲 ID
   * @returns {boolean}
   */
  function isOrdering(songId) {
    return inflightIds.has(songId)
  }

  return {
    executeOrder,
    isOrdering,
    inflightIds
  }
}
