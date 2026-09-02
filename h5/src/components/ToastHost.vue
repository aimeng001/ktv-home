<template>
  <!-- Toast 消息容器 / Toast message container -->
  <transition name="toast">
    <div v-if="message" class="proto-toast">{{ message }}</div>
  </transition>
</template>

<script setup>
/**
 * Toast 全局宿主组件。挂载在应用根节点，通过 useToast composable 接收并展示全局提示消息。
 *
 * Global toast host component. Mounted at the app root, it receives and displays
 * global toast messages via the useToast composable.
 */
import { useToast } from '../composables/useToast'

// 从 composable 中解构出消息响应式引用 / Destructure reactive message ref from composable
const { message } = useToast()
</script>

<style scoped>
.proto-toast {
  position: fixed; left: 50%; bottom: 90px; transform: translateX(-50%);
  background: rgba(15,17,24,.92); backdrop-filter: blur(16px);
  border: 1px solid rgba(255,255,255,.08); color: #f0f2f7;
  padding: 12px 20px; border-radius: 12px; font-size: 13px; z-index: 4000; white-space: nowrap;
  box-shadow: 0 12px 40px rgba(0,0,0,.5);
}
.toast-enter-active, .toast-leave-active { transition: opacity .25s; }
.toast-enter-from, .toast-leave-to { opacity: 0; }
</style>
