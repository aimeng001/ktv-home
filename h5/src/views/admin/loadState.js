/**
 * Convert a settled request into a UI state without discarding the last good
 * value when a refresh fails.
 */
export function requestState(result, previousValue = null) {
  if (result?.status === 'fulfilled') {
    return { status: 'ready', value: result.value, error: null }
  }
  return { status: 'error', value: previousValue, error: result?.reason || new Error('读取失败') }
}

/**
 * Return the next metadata poll delay. Failed polls back off exponentially,
 * but never schedule an unbounded delay.
 */
export function pollingDelay(status, failureCount, baseMs = 1500, maxMs = 30000) {
  if (status !== 'stale') return baseMs
  const failures = Math.max(1, Number(failureCount) || 1)
  return Math.min(maxMs, baseMs * (2 ** failures))
}
