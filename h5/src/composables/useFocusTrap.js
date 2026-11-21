import { onBeforeUnmount, watch } from 'vue'

/** 可 Tab 到达的元素。故意不用 offsetParent/getClientRects 过滤：jsdom 没有布局，会把列表判空。 */
const FOCUSABLE_SELECTOR = [
  'a[href]',
  'button:not([disabled])',
  'input:not([disabled]):not([type="hidden"])',
  'select:not([disabled])',
  'textarea:not([disabled])',
  '[tabindex]:not([tabindex="-1"])'
].join(', ')

/**
 * 判断全局对话框是否压在当前弹窗之上。
 *
 * `DialogHost` 打开时会把 body 的其他子节点全部置 `inert` 并设 `aria-hidden`，
 * 此时焦点陷阱里的 `focus()` 会静默失败（按 Tab 像没反应）。所以一旦有全局
 * 对话框在最上层，这里必须完全让位。
 */
function globalDialogOnTop() {
  return document.querySelector('.dialog-mask .dialog-card') !== null
}

/**
 * 把键盘 Tab 焦点限制在容器内，并在关闭时把焦点还给打开它的元素。
 *
 * @param {import('vue').Ref<HTMLElement|null>} containerRef 弹窗容器 / the dialog container
 * @param {() => boolean} active 是否打开（getter 或 ref）/ whether the dialog is open
 * @returns {{ stop: () => void }} 手动解除陷阱 / manual teardown
 */
export function useFocusTrap(containerRef, active) {
  let opener = null
  let listening = false

  function focusable() {
    const container = containerRef.value
    if (!container) return []
    return Array.from(container.querySelectorAll(FOCUSABLE_SELECTOR))
  }

  function onKeydown(event) {
    if (event.key !== 'Tab') return
    if (globalDialogOnTop()) return
    const container = containerRef.value
    if (!container) return
    const items = focusable()
    if (!items.length) {
      event.preventDefault()
      container.focus?.()
      return
    }
    const first = items[0]
    const last = items[items.length - 1]
    const current = document.activeElement
    if (!container.contains(current)) {
      event.preventDefault()
      first.focus()
      return
    }
    if (event.shiftKey && current === first) {
      event.preventDefault()
      last.focus()
    } else if (!event.shiftKey && current === last) {
      event.preventDefault()
      first.focus()
    }
  }

  function start() {
    if (listening) return
    opener = document.activeElement
    document.addEventListener('keydown', onKeydown, true)
    listening = true
    const items = focusable()
    if (items.length) items[0].focus()
    else containerRef.value?.focus?.()
  }

  function stop() {
    if (listening) {
      document.removeEventListener('keydown', onKeydown, true)
      listening = false
    }
    if (opener && typeof opener.focus === 'function' && document.contains(opener)) {
      opener.focus()
    }
    opener = null
  }

  // flush: 'post' 保证弹窗 DOM 已经挂上再找焦点元素。
  watch(active, value => { value ? start() : stop() }, { immediate: true, flush: 'post' })
  onBeforeUnmount(stop)

  return { stop }
}
