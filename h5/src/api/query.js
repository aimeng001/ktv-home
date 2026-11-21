/**
 * 查询串拼接工具。
 *
 * 「不传参数」和「传空串」对后端是两件事：`status=` 会命中空状态过滤，
 * 而不是「不过滤」。所以空串和 null/undefined 一律不发。
 *
 * @param {Record<string, unknown>} [params] 参数对象 / query parameters
 * @returns {string} 已编码的查询串（不含 `?`）/ encoded query string without the leading `?`
 */
export function toQuery(params = {}) {
  return new URLSearchParams(
    Object.entries(params).filter(([, value]) => value !== '' && value != null)
  ).toString()
}
