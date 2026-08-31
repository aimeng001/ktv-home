<template>
  <div class="page">
    <!-- 顶部导航栏 / Header navigation -->
    <header class="head"><button @click="$router.back()">‹</button><div><h1>{{ pageTitle }}</h1><p>{{ songTotal }} 首歌曲</p></div></header>
    <!-- 排序标签页 / Sort tabs -->
    <div class="sorts"><button :class="{ on: sort === 'hot' }" @click="setSort('hot')">按热度</button><button :class="{ on: sort === 'title' }" @click="setSort('title')">按歌名</button><button :class="{ on: sort === 'new' }" @click="setSort('new')">最新</button></div>
    <div v-if="mode === 'all'" class="gender-filter"><button v-for="item in genderOptions" :key="item.value" :class="{ on: artistGender === item.value }" @click="setGender(item.value)">{{ item.label }}</button></div>
    <!-- 歌曲列表区域 / Song list area -->
    <main>
      <div v-if="songsStatus === 'loading' && !songs.length" class="tip">加载中…</div>
      <div v-else-if="songsStatus === 'error' && !songs.length" class="tip error-tip">歌曲读取失败：{{ songsError?.message || '网络错误，请稍后重试' }} <button class="retry" @click="load">重试</button></div>
      <div v-else-if="!songs.length" class="tip">没有找到相关歌曲</div>
      <SongRow v-for="song in songs" :key="song.id" :song="song" :extra="fmtDur(song.durationMs)" :ordered="orderedIds.has(song.id)" @order="order" />
      <div v-if="songs.length && hasMoreSongs" class="load-more"><button :disabled="songsStatus === 'loading'" @click="loadMoreSongs">{{ songsStatus === 'loading' ? '加载中…' : '加载更多' }}</button></div>
      <div v-if="songs.length && songsStatus === 'error'" class="tip error-tip">后续歌曲读取失败：{{ songsError?.message || '网络错误，请稍后重试' }} <button class="retry" @click="load(false)">重试</button></div>
    </main>
    <TabBar active="home" />
  </div>
</template>

<script setup>
/**
 * 艺人/分类歌曲浏览页面。
 * 支持按热度、歌名、最新排序，可按艺人、语种、标签、演唱形式等模式筛选。
 *
 * Artist/category song browse page.
 * Supports sorting by hotness, title, or newest first, and filtering
 * by artist, language, tag, vocal form, and other modes.
 */
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import api, { makeControls } from '../api/client'
import { useUserStore } from '../stores/user'
import { useToast } from '../composables/useToast'
import { useAsyncResource } from '../composables/useAsyncResource'
import { useOrderLock } from '../composables/useOrderLock'
import TabBar from '../components/TabBar.vue'
import SongRow from '../components/SongRow.vue'

const route = useRoute(), user = useUserStore(), { toast } = useToast(), controls = makeControls(user.clientToken)
const { executeOrder } = useOrderLock()
const sort = ref(route.query.sort || 'hot'), artistGender = ref(route.query.artistGender || '')
const genderOptions = [{ value:'', label:'全部歌手' }, { value:'男歌手', label:'男歌手' }, { value:'女歌手', label:'女歌手' }, { value:'组合', label:'组合' }]
// 已点歌曲 ID 集合，防止重复点歌 / Set of ordered song IDs to prevent duplicate ordering
const orderedIds = reactive(new Set())

/**
 * 当前浏览模式。
 * 可选值：all（全部）、artist（艺人）、language（语种）、tag（标签）、vocalForm（演唱形式）。
 *
 * Current browse mode.
 * Possible values: all, artist, language, tag, vocalForm.
 */
const mode = computed(() => route.query.mode || (route.params.name === 'all' ? 'all' : 'artist'))

/**
 * 当前筛选值，如艺人名、语种名等。
 *
 * Current filter value, e.g. artist name, language name, etc.
 */
const value = computed(() => route.query.value || route.params.name || '')
const artistKey = computed(() => route.query.artistKey || '')

/**
 * 页面标题，根据当前模式动态生成。
 *
 * Page title, dynamically generated based on the current mode.
 */
const pageTitle = computed(() => mode.value === 'all' ? '全部歌曲' : mode.value === 'language' ? `${value.value}歌曲` : mode.value === 'tag' ? value.value : mode.value === 'vocalForm' ? value.value : value.value)

const songsResource = useAsyncResource(async () => {
  const params = { sort: sort.value, page: songPage.value, size: 50 }
  if (mode.value !== 'all') params[mode.value] = value.value
  if (mode.value === 'artist' && artistKey.value) params.artistKey = artistKey.value
  if (artistGender.value) params.artistGender = artistGender.value
  return api.browseSongPage(params)
}, { items: [], total: 0, page: 0, size: 50 })
const songs = computed(() => Array.isArray(songsResource.data.value?.items) ? songsResource.data.value.items : [])
const songsStatus = songsResource.status
const songsError = songsResource.error
const songPage = ref(0), songTotal = ref(0)
const hasMoreSongs = computed(() => songs.value.length < songTotal.value)

/**
 * 加载歌曲列表。
 *
 * Load song list.
 */
async function load(reset = true) {
  if (reset) {
    songPage.value = 0
    songTotal.value = 0
  }
  const previousItems = reset ? [] : [...songs.value]
  const requestedPage = songPage.value
  const result = await songsResource.load()
  if (!result || requestedPage !== songPage.value) return result
  const nextItems = Array.isArray(result.items) ? result.items : []
  songsResource.data.value = {
    ...result,
    items: reset ? nextItems : [...previousItems, ...nextItems]
  }
  songTotal.value = Number.isFinite(result.total) ? result.total : 0
  return result
}
async function loadMoreSongs() {
  if (songsStatus.value === 'loading' || !hasMoreSongs.value) return
  songPage.value += 1
  await load(false)
}
onMounted(() => load(true)); watch(() => route.fullPath, () => load(true))

/**
 * 切换排序方式并重新加载。
 *
 * @param {'hot' | 'title' | 'new'} value - 排序方式 / sort method
 */
function setSort(value) { sort.value = value; load(true) }
function setGender(value) { artistGender.value = value; load(true) }

/**
 * 将歌曲加入播放队列。失败时弹出错误提示。
 *
 * @param {{ id: string }} song - 歌曲对象 / song object
 */
async function order(song) {
  await executeOrder(song.id, async () => {
    try {
      await controls.order(song.id)
      orderedIds.add(song.id)
      toast('已加入队列')
    } catch (error) {
      toast(error.code === 'SONG_IN_QUEUE' ? (error.message || '已在队列中') : (error.message || '点歌失败'))
    }
  })
}

/**
 * 将毫秒时长格式化为 mm:ss 字符串。
 *
 * @param {number} ms - 毫秒 / milliseconds
 * @returns {string} 格式化后的时长 / formatted duration string
 */
function fmtDur(ms) { if (!ms) return ''; const value = Math.round(ms / 1000); return `${Math.floor(value / 60)}:${String(value % 60).padStart(2, '0')}` }
</script>

<style scoped>
.page{min-height:100vh;padding-bottom:74px}.head{display:flex;align-items:center;gap:12px;padding:13px 16px 8px}.head button{border:0;background:none;color:var(--text);font-size:30px;width:30px}.head h1{font-size:19px;margin:0}.head p{font-size:11px;color:var(--dim);margin:3px 0 0}.sorts,.gender-filter{display:flex;gap:7px;padding:7px 16px 5px;overflow-x:auto}.gender-filter{padding-top:2px;padding-bottom:8px}.sorts button,.gender-filter button{border:1px solid var(--glass-border);background:var(--panel2);color:var(--dim);border-radius:999px;padding:6px 14px;white-space:nowrap}.sorts button.on,.gender-filter button.on{background:var(--gold-glow);border-color:rgba(240,199,66,.28);color:var(--gold)}main{padding:0 16px}.tip{text-align:center;color:var(--dim2);padding:45px 10px}.error-tip{color:var(--coral)}.retry{margin-left:7px;border:1px solid var(--glass-border);border-radius:7px;padding:4px 9px;color:var(--gold);background:var(--panel2)}.load-more{text-align:center;padding:16px 0 8px}.load-more button{border:1px solid var(--glass-border);border-radius:8px;padding:8px 20px;color:var(--gold);background:var(--panel2)}
</style>
