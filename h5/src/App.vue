<template>
  <router-view v-slot="{ Component }">
    <component :is="Component" />
  </router-view>
  <ToastHost />
  <DialogHost />
</template>

<script setup>
/**
 * 应用根组件，负责挂载路由视图以及全局 Toast/对话框宿主。
 *
 * Root application component that mounts the router view and global toast/dialog hosts.
 */
import { onMounted, onUnmounted } from 'vue'
import { useRouter } from 'vue-router'
import { usePlayerStore } from './stores/player'
import { useUserStore } from './stores/user'
import { useFavoritesStore } from './stores/favorites'
import api from './api/client'
import ToastHost from './components/ToastHost.vue'
import DialogHost from './components/DialogHost.vue'

// 已注册用户进入即连 WebSocket；未注册的在进入页提交后由路由跳转触发
// Registered users connect immediately; unregistered users connect after entry-page navigation.
const player = usePlayerStore()
const user = useUserStore()
const favorites = useFavoritesStore()
const router = useRouter()

onMounted(async () => {
  if (!user.isRegistered || !user.isServerRegistered) return
  // The numeric id belongs to the current server database. Re-register on
  // every H5 boot so a stale localStorage id cannot authorize/label a new NAS.
  try {
    const profile = await api.registerUser(user.clientToken, user.nickname)
    if (!user.markRegistrationSuccess(profile)) throw new Error('服务端未返回有效用户身份')
    player.connect()
    favorites.load(user.clientToken).catch(() => {})
  } catch (error) {
    user.markRegistrationFailure(error)
    player.disconnect()
    await router.replace({ name: 'entry' })
  }
})
onUnmounted(() => player.disconnect())
</script>
