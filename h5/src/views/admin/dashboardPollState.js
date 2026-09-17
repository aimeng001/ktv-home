export const MAX_SCAN_POLL_FAILURES = 3

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
