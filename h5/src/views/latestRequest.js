/**
 * Tracks the latest request and aborts the previous one.
 * A caller must still check isCurrent before publishing its result.
 */
export function createLatestRequest() {
  let serial = 0
  let controller = null

  return {
    begin() {
      controller?.abort()
      controller = new AbortController()
      const id = ++serial
      return { id, signal: controller.signal }
    },
    isCurrent(id) {
      return id === serial
    },
    cancel() {
      serial += 1
      controller?.abort()
      controller = null
    }
  }
}
