<template>
  <teleport to="body">
    <transition name="dialog-fade">
      <div v-if="state.open" class="dialog-mask" @click.self="close(false)">
        <section ref="card" class="dialog-card" role="dialog" aria-modal="true" :aria-label="state.title" tabindex="-1">
          <!-- 图标：根据色调切换 / Icon: switches by tone -->
          <div class="dialog-icon" :class="state.tone"><TriangleAlert v-if="state.tone === 'warning'" :size="22" /><CircleCheck v-else-if="state.tone === 'success'" :size="22" /><CircleAlert v-else :size="22" /></div>
          <div class="dialog-copy"><h2>{{ state.title }}</h2><p>{{ state.message }}</p></div>
          <button class="dialog-close" aria-label="关闭" @click="close(false)"><X :size="18" /></button>
          <footer class="dialog-actions">
            <button v-if="state.mode === 'confirm'" class="dialog-btn secondary" @click="close(false)">取消</button>
            <button ref="confirmButton" data-dialog-confirm class="dialog-btn" :class="state.tone === 'warning' ? 'danger' : 'primary'" @click="close(true)">{{ state.mode === 'confirm' ? '确认' : '知道了' }}</button>
          </footer>
        </section>
      </div>
    </transition>
  </teleport>
</template>

<script setup>
/**
 * 全局对话框组件，通过 Teleport 渲染到 body，
 * 支持确认（双按钮）/提示（单按钮）两种模式和 warning / success / error 三种色调。
 *
 * Global dialog component, rendered to body via Teleport.
 * Supports confirm (dual-button) / alert (single-button) modes and
 * warning / success / error tones.
 */
import { nextTick, onMounted, onUnmounted, ref, watch } from 'vue'
import { CircleAlert, CircleCheck, TriangleAlert, X } from 'lucide-vue-next'
import { useDialog } from '../composables/useDialog'
import { dialogKeyAction } from './dialogHostState'

const { state, close } = useDialog()
const card = ref(null)
const confirmButton = ref(null)
let opener = null

watch(() => state.open, async open => {
  if (open) {
    opener = document.activeElement instanceof HTMLElement ? document.activeElement : null
    await nextTick()
    confirmButton.value?.focus({ preventScroll: true })
    if (!confirmButton.value) card.value?.focus({ preventScroll: true })
  } else if (opener instanceof HTMLElement && opener.isConnected) {
    await nextTick()
    opener.focus({ preventScroll: true })
    opener = null
  }
})

/**
 * 按下 Escape 键时关闭对话框。
 *
 * Closes the dialog when the Escape key is pressed.
 */
const onKeydown = event => {
  const action = dialogKeyAction({
    open: state.open,
    mode: state.mode,
    key: event.key,
    targetTag: event.target?.tagName || ''
  })
  if (action === 'ignore') return
  event.preventDefault()
  close(action === 'confirm')
}
onMounted(() => window.addEventListener('keydown', onKeydown))
onUnmounted(() => window.removeEventListener('keydown', onKeydown))
</script>

<style scoped>
.dialog-mask{position:fixed;inset:0;z-index:3000;display:grid;place-items:center;padding:20px;background:rgba(3,5,10,.68);backdrop-filter:blur(7px)}
.dialog-card{position:relative;width:min(420px,100%);padding:22px;background:linear-gradient(155deg,#202634,#12161f);border:1px solid rgba(255,255,255,.13);border-radius:14px;box-shadow:0 28px 90px rgba(0,0,0,.55);display:grid;grid-template-columns:42px 1fr auto;gap:12px;color:#f6f8fc;outline:0}
.dialog-icon{width:38px;height:38px;border-radius:10px;display:grid;place-items:center;background:rgba(96,165,250,.14);color:#93c5fd}.dialog-icon.warning{background:rgba(251,191,36,.14);color:#fbbf24}.dialog-icon.error{background:rgba(248,113,113,.14);color:#fca5a5}.dialog-icon.success{background:rgba(52,211,153,.14);color:#6ee7b7}
.dialog-copy h2{font-size:16px;line-height:1.35;margin:1px 0 7px}.dialog-copy p{white-space:pre-line;color:#b9c4d4;font-size:13px;line-height:1.65;word-break:break-word}.dialog-close{width:30px;height:30px;display:grid;place-items:center;border-radius:7px;color:#aeb9c8}.dialog-close:hover{background:rgba(255,255,255,.08);color:#fff}
.dialog-actions{grid-column:1/-1;display:flex;justify-content:flex-end;gap:9px;margin-top:8px}.dialog-btn{min-width:78px;height:36px;padding:0 14px;border-radius:7px;font-size:13px;font-weight:700}.dialog-btn.primary{background:#3b82f6;color:#fff}.dialog-btn.danger{background:#d45757;color:#fff}.dialog-btn.secondary{background:rgba(255,255,255,.07);border:1px solid rgba(255,255,255,.12);color:#d7deea}.dialog-btn:active{transform:scale(.98)}
.dialog-fade-enter-active,.dialog-fade-leave-active{transition:opacity .18s ease}.dialog-fade-enter-active .dialog-card,.dialog-fade-leave-active .dialog-card{transition:transform .18s ease,opacity .18s ease}.dialog-fade-enter-from,.dialog-fade-leave-to{opacity:0}.dialog-fade-enter-from .dialog-card,.dialog-fade-leave-to .dialog-card{opacity:0;transform:translateY(10px) scale(.98)}
@media(max-width:480px){.dialog-mask{align-items:end;padding:12px}.dialog-card{width:100%;border-radius:14px;padding:20px}.dialog-actions{margin-top:10px}.dialog-btn{flex:1;height:42px}}
</style>
