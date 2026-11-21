/**
 * Global typing capture composable for KTV kiosk blind-typing.
 * Captures alphanumeric keys when the user is not actively typing in an input element or modal dialog.
 */
export function useGlobalTypingCapture({ onChar, onBackspace, isBlocked } = {}) {
  function handleKeyDown(event) {
    if (!event || event.defaultPrevented) return
    if (event.ctrlKey || event.altKey || event.metaKey) return
    if (typeof isBlocked === 'function' && isBlocked()) return
    if (typeof window !== 'undefined' && window.location && /^\/(m\/)?admin/.test(window.location.pathname || '')) {
      return
    }

    const active = typeof document !== 'undefined' ? document.activeElement : null
    if (active) {
      const tag = (active.tagName || '').toUpperCase()
      if (tag === 'INPUT' || tag === 'TEXTAREA' || tag === 'SELECT' || active.isContentEditable) {
        return
      }
      if (typeof active.closest === 'function') {
        if (active.closest('[role="dialog"]') || active.closest('.modal-backdrop') || active.closest('.ktv-modal') || active.closest('.modal-mask')) {
          return
        }
      }
    }

    if (event.key === 'Backspace') {
      event.preventDefault()
      if (typeof onBackspace === 'function') onBackspace()
      return
    }

    if (event.key && event.key.length === 1 && /^[a-zA-Z0-9]$/.test(event.key)) {
      if (typeof onChar === 'function') {
        onChar(event.key.toUpperCase())
      }
    }
  }

  if (typeof window !== 'undefined') {
    window.addEventListener('keydown', handleKeyDown)
  }

  function dispose() {
    if (typeof window !== 'undefined') {
      window.removeEventListener('keydown', handleKeyDown)
    }
  }

  return { dispose }
}
