/**
 * Prevent overlapping progress requests and reject responses from an older task.
 * A single follow-up request is queued when a timer fires during an in-flight poll.
 */
export function createProgressPollGate() {
  let generation = 0
  let inFlight = null
  let queued = false

  return {
    begin() {
      if (inFlight !== null) {
        queued = true
        return null
      }
      inFlight = ++generation
      return inFlight
    },

    isCurrent(request) {
      return request !== null && request === inFlight && request === generation
    },

    invalidate({ queueLatest = false } = {}) {
      generation += 1
      queued = inFlight !== null && Boolean(queueLatest)
    },

    finish(request) {
      if (request !== inFlight) return false
      inFlight = null
      const shouldPollAgain = queued
      queued = false
      return shouldPollAgain
    }
  }
}
