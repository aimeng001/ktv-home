export const MAX_SCAN_POLL_FAILURES = 3

/** 扫描进度轮询：基准 1s，每次 ×1.2，5s 封顶（20 万首扫描要跑十几分钟）。 */
export const SCAN_POLL_BASE_MS = 1000
export const SCAN_POLL_MAX_MS = 5000
export const SCAN_POLL_BACKOFF = 1.2

/**
 * 第 attempt 次轮询之后的等待时长（毫秒）。
 *
 * 固定 1 秒轮询在长扫描里等于每 6 小时 2 万次请求；退避到 5 秒封顶后请求数降到 1/5，
 * 而「看起来还在动」的体感没有明显变化。
 *
 * @param {number} attempt 已完成的轮询次数（0 表示首次）/ completed polls, 0-based
 * @returns {number} 下次轮询的等待毫秒 / delay before the next poll
 */
export function nextScanPollDelay(attempt) {
  const steps = Math.max(0, Number(attempt) || 0)
  return Math.min(Math.round(SCAN_POLL_BASE_MS * Math.pow(SCAN_POLL_BACKOFF, steps)), SCAN_POLL_MAX_MS)
}

export function recordScanPollFailure(state, error) {
  const failures = (state.failures || 0) + 1
  return {
    ...state,
    failures,
    error,
    stop: failures >= MAX_SCAN_POLL_FAILURES,
    running: failures >= MAX_SCAN_POLL_FAILURES ? false : state.running
  }
}

export function recordScanPollSuccess(state, progress) {
  return { failures: 0, error: null, stop: false, running: !!progress?.running }
}
