/**
 * Format dynamic user-facing toast message after ordering a song.
 * Uses QueueSnapshot.list length to compute wait position.
 *
 * @param {object|null} res - Snapshot returned by /api/control order action
 * @returns {string} User-facing toast message
 */
export function formatOrderToast(res) {
  const count = res?.list?.length
  return count ? `已加入待播（第 ${count} 位）` : '已加入队列'
}
