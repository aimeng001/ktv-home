/**
 * 生成跳转至元数据刮削页面的路由对象。
 *
 * Builds route target object for metadata scrape navigation.
 *
 * @param {Array<number|string>} ids 待刮削的歌曲 ID 列表 / Song IDs to scrape
 * @param {boolean} review 是否直接打开单曲人工审核弹窗 / Whether to open single-song review directly
 * @returns {Object} Vue router location object
 */
export function buildScrapeRoute(ids = [], review = false) {
  const songIds = Array.isArray(ids) ? ids.filter(Boolean) : []
  return {
    name: 'admin-metadata-scrape',
    query: songIds.length
      ? { songIds: songIds.join(','), ...(review ? { review: '1' } : {}) }
      : undefined
  }
}
