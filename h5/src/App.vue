<template>
  <router-view v-slot="{ Component }">
    <component :is="Component" />
  </router-view>
  <ToastHost />
  <DialogHost />
</template>

<script setup>
/**
 * 应用根组件，负责免阻断用户会话注册、WebSocket 连接以及全局 Toast/对话框宿主。
 */
import { onMounted, onUnmounted, watch } from 'vue'
import { usePlayerStore } from './stores/player'
import { useUserStore } from './stores/user'
import { useFavoritesStore } from './stores/favorites'
import { useToast } from './composables/useToast'
import api from './api/client'
import ToastHost from './components/ToastHost.vue'
import DialogHost from './components/DialogHost.vue'

const player = usePlayerStore()
const user = useUserStore()
const favorites = useFavoritesStore()
const { toast } = useToast()

if (!user.isRegistered) {
  user.register(user.suggestNickname())
}

watch(() => player.lastToast, (next) => {
  if (next?.text) toast(next.text)
})

onMounted(async () => {
  try {
    const profile = await api.registerUser(user.clientToken, user.nickname)
    if (!user.markRegistrationSuccess(profile)) throw new Error('服务端未返回有效用户身份')
    player.connect()
    favorites.load(user.clientToken).catch(() => {})
  } catch (error) {
    user.markRegistrationFailure(error)
    // Still connect WebSocket for live room/TV monitoring even if registration needs retry
    player.connect()
  }
})

onUnmounted(() => player.disconnect())
</script>
