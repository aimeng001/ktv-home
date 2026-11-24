/**
 * LRC 歌词解析模块（详设 H5-07）。
 * 支持标准逐行时间标签 [mm:ss.xx] 与逐字增强 LRC 格式 <mm:ss.xx>。
 *
 * LRC lyrics parsing module (H5-07).
 * Supports standard line-level timestamps [mm:ss.xx] and word-level enhanced LRC <mm:ss.xx>.
 */

const LINE_TAG = /\[(\d{1,3}):(\d{2})(?:[.:](\d{1,3}))?]/g
const WORD_TAG = /<(\d{1,3}):(\d{2})(?:[.:](\d{1,3}))?>/g

/**
 * 解析 LRC 歌词文本，返回按时间排序的歌词行数组。
 * 每行包含 time（毫秒时间戳）、text（纯文本），逐字 LRC 额外包含 words（逐字时间数组）。
 *
 * Parse LRC lyrics text into a time-sorted array of lyric lines.
 * Each line contains time (ms timestamp), text (plain text); word-level LRC also includes words (word-level timing array).
 *
 * @param {string} text - LRC 歌词原始文本 / Raw LRC lyrics text
 * @returns {Array<{time: number, text: string, words?: Array<{time: number, text: string}>}>} 解析后的歌词行数组 / Parsed lyric lines array
 */
export function parseLrc(text) {
  if (!text) return []
  const out = []
  for (const raw of text.split(/\r?\n/)) {
    const times = []
    let m
    LINE_TAG.lastIndex = 0
    while ((m = LINE_TAG.exec(raw)) !== null) {
      const min = parseInt(m[1], 10)
      const sec = parseInt(m[2], 10)
      const frac = m[3] ? parseInt(m[3].padEnd(3, '0'), 10) : 0
      times.push(min * 60000 + sec * 1000 + frac)
    }
    if (!times.length) continue
    const content = raw.replace(LINE_TAG, '').trim()
    if (!content) continue
    const wordMatches = [...content.matchAll(/<(?:(\d{1,3}):(\d{2})(?:[.:](\d{1,3}))?)>/g)]
    const words = wordMatches.map((match, index) => {
      const start = Number(match[1]) * 60000 + Number(match[2]) * 1000 + Number((match[3] || '').padEnd(3, '0') || 0)
      const from = match.index + match[0].length
      const to = index + 1 < wordMatches.length ? wordMatches[index + 1].index : content.length
      return { time: start, text: content.slice(from, to) }
    }).filter(word => word.text)
    const plainText = content.replace(WORD_TAG, '')
    for (const t of times) {
      const line = { time: t, text: plainText }
      if (words.length) line.words = words
      out.push(line)
    }
  }
  out.sort((a, b) => a.time - b.time)
  return out
}
