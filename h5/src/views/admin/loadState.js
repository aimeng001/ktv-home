/**
 * Return the next metadata poll delay. Failed polls back off exponentially,
 * but never schedule an unbounded delay.
 */
export function pollingDelay(status, failureCount, baseMs = 1500, maxMs = 30000) {
  if (status !== 'stale') return baseMs
  const failures = Math.max(1, Number(failureCount) || 1)
  return Math.min(maxMs, baseMs * (2 ** failures))
}
