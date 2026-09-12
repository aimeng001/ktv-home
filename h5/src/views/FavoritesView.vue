<template>
  <div class="page">
    <header class="top"><button @click="$router.back()">‹</button><strong>我的收藏</strong><span>{{ songs.length }} 首</span></header>
    <main>
      <!-- 收藏页头部 / Favorites header -->
      <div class="intro"><span>❤️</span><div><h1>喜欢的歌</h1><p>收藏保存在当前手机身份下</p></div></div>
      <div v-if="favoritesStatus === 'loading'" class="tip">加载中…</div>
      <div v-else-if="favoritesStatus === 'error'" class="tip error-tip">收藏读取失败：{{ favoritesError?.message || '网络错误，请稍后重试' }} <button class="retry" @click="load">重试</button></div>
      <div v-else-if="!songs.length" class="empty"><span>♡</span><strong>还没有收藏歌曲</strong><p>在歌曲右侧点爱心即可收藏</p></div>
      <!-- 歌曲列表（含点歌按钮） / Song list with queuing -->
      <div v-else class="list">
        <SongRow v-for="song in songs" :key="song.id" :song="song" :ordered="orderedIds.has(song.id)" @order="order" />
      </div>
    </main>
    <TabBar active="home" />
  </div>
</template>

<script setup>
/**
 * 收藏页面 — 展示用户收藏的歌曲列表，支持点歌操作。
 * 收藏数据与当前手机身份绑定，切换身份后数据不同。
 *
 * Favorites page — displays the user's favorited songs and supports queuing.
 * Favorites are tied to the current device identity and differ across identities.
 */

import { onMounted, ref, watch } from 'vue'
import api, { makeControls } from '../api/client'
import SongRow from '../components/SongRow.vue'
import TabBar from '../components/TabBar.vue'
import { useFavoritesStore } from '../stores/favorites'
import { useUserStore } from '../stores/user'
import { useToast } from '../composables/useToast'
import { useAsyncResource } from '../composables/useAsyncResource'
import { useOrderLock } from '../composables/useOrderLock'
import { formatOrderToast } from './orderFeedbackState'
import { useQueuedSongIds } from '../composables/useQueuedSongIds'

const user = useUserStore()
const favorites = useFavoritesStore()
const { toast } = useToast()
const controls = makeControls(user.clientToken)
const { executeOrder, inflightIds } = useOrderLock()
const { orderedIds, player } = useQueuedSongIds(inflightIds)
const favoritesResource = useAsyncResource(async () => {
  const result = await api.favorites(user.clientToken)
  await favorites.load(user.clientToken, true)
  return result
}, [])
const songs = favoritesResource.data
const favoritesStatus = favoritesResource.status
const favoritesError = favoritesResource.error

onMounted(load)
watch(() => favorites.ids.slice(), ids => {
  songs.value = songs.value.filter(song => ids.includes(song.id))
})

/**
 * 加载收藏歌曲列表，同时同步收藏 ID 到 store。
 * 失败时清空列表，避免展示过期数据。
 *
 * Loads the favorites song list and syncs favorite IDs to the store.
 * On failure, clears the list to avoid showing stale data.
 */
async function load() {
  await favoritesResource.load()
}

/**
 * 将歌曲加入播放队列（点歌，带防抖并发锁）。
 * @param {Object} song - 歌曲对象，需包含 id 属性。
 */
async function order(song) {
  await executeOrder(song.id, async () => {
    try {
      const res = await controls.order(song.id)
      player.applyControlResponse(res)
      toast(formatOrderToast(res))
    } catch (error) {
      toast(error.code === 'SONG_IN_QUEUE' ? (error.message || '已在队列中') : (error.message || '点歌失败'))
    }
  })
}
</script>

<style scoped>
.page { min-height: 100vh; padding-bottom: 74px; display: flex; flex-direction: column; }
.top { height: 48px; display: flex; align-items: center; justify-content: space-between; padding: 0 16px; border-bottom: 1px solid var(--line); font-size: 14px; }
.top button { background: none; border: none; color: var(--gold); font-size: 24px; padding: 0 8px; cursor: pointer; }
.intro { display: flex; align-items: center; gap: 14px; padding: 20px 16px; background: linear-gradient(180deg, rgba(255,107,97,.12) 0%, transparent 100%); }
.intro span { font-size: 32px; }
.intro h1 { font-size: 20px; font-weight: 700; margin: 0; }
.intro p { margin: 4px 0 0; color: var(--dim); font-size: 12px; }
.list { padding: 0 8px; }
.tip { color: var(--dim2); font-size: 13px; padding: 40px 0; text-align: center; }
.error-tip { color: var(--coral); display: flex; align-items: center; justify-content: center; gap: 8px; }
.retry { border: 1px solid var(--line); background: var(--panel2); color: var(--text); border-radius: 4px; padding: 2px 8px; font-size: 12px; }
.empty { text-align: center; padding: 60px 0; color: var(--dim); }
.empty span { font-size: 40px; color: var(--dim2); display: block; margin-bottom: 12px; }
.empty strong { display: block; font-size: 15px; color: var(--text); margin-bottom: 6px; }
.empty p { font-size: 12px; color: var(--dim2); margin: 0; }
</style>
