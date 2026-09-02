<template>
  <div class="page">
    <!-- 搜索框 / Search bar -->
    <div class="sec sbar">
      <button class="back" aria-label="返回" @click="$router.back()"><ChevronLeft :size="24" /></button>
      <div class="search grow">
        <Search :size="17" />
        <input ref="inp" v-model="kw" placeholder="歌名 / 歌手 / 拼音首字母" maxlength="50" @input="onInput" />
        <button v-if="kw" class="clear" aria-label="清空" @click="clear"><X :size="16" /></button>
      </div>
    </div>

    <div class="sec filters" role="group" aria-label="歌曲版本筛选">
      <button v-for="filter in filters" :key="filter.value" class="filter"
              :class="{ on: activeFilter === filter.value }"
              :aria-pressed="activeFilter === filter.value"
              @click="selectFilter(filter.value)">{{ filter.label }}</button>
    </div>

    <!-- 搜索结果 / Search results -->
    <div class="sec grow results">
      <div v-if="searchState === 'loading'" class="tip">搜索中…</div>
      <template v-else-if="searchState === 'results'">
        <div class="cnt"><b>搜索结果</b><span>{{ results.length }} 首歌曲</span></div>
        <SongRow v-for="s in results" :key="s.id" :song="s" :keyword="kw"
                 :extra="fmtDur(s.durationMs)" :ordered="orderedIds.has(s.id)" @order="order" />
      </template>
      <div v-else-if="searchState === 'error'" class="error-state" role="alert">
        <div class="e-title">搜索失败：{{ searchError }}</div>
        <button class="btn ghost" @click="retrySearch">重试</button>
      </div>
      <div v-else-if="searchState === 'empty'" class="empty">
        <div class="e-title">曲库还没有这首歌</div>
        <button class="btn ghost" @click="addWish">告诉我们想唱《{{ kw }}》</button>
      </div>
      <div v-else class="tip">输入关键词开始搜索</div>
    </div>

    <TabBar active="home" />
  </div>
</template>

<script setup>
/**
 * SearchView - 歌曲搜索页面
 * 支持按歌名、歌手或拼音首字母搜索歌曲。包含 300ms 输入防抖、
 * 搜索结果展示、点歌加入队列以及心愿歌曲提交功能。
 *
 * SearchView - Song search page.
 * Supports searching songs by title, artist or pinyin initials.
 * Features 300ms input debounce, search result display, song queuing,
 * and wish-song submission.
 */
import { computed, ref, onMounted, onBeforeUnmount, reactive } from 'vue'
import api, { makeControls } from '../api/client'
import { useUserStore } from '../stores/user'
import { useToast } from '../composables/useToast'
import { useOrderLock } from '../composables/useOrderLock'
import TabBar from '../components/TabBar.vue'
import SongRow from '../components/SongRow.vue'
import { searchViewState } from './searchState'
import { createSearchController } from './searchController'
import { formatOrderToast } from './orderFeedbackState'
import { ChevronLeft, Search, X } from 'lucide-vue-next'

const user = useUserStore()
const { toast } = useToast()
const controls = makeControls(user.clientToken)
const { executeOrder } = useOrderLock()

/** @type {import('vue').Ref<string>} 当前搜索关键词 / Current search keyword */
const kw = ref('')
/** @type {import('vue').Ref<Array>} 搜索结果列表 / Search result list */
const results = ref([])
/** @type {import('vue').Ref<boolean>} 搜索加载状态 / Search loading flag */
const loading = ref(false)
const searchError = ref('')
const activeFilter = ref('')
const filters = [
  { label: '全部', value: '' },
  { label: 'KTV 双音轨', value: 'KTV_VIDEO' },
  { label: 'MV', value: 'MV' },
  { label: '纯音频', value: 'AUDIO' }
]
/** 已点歌 ID 集合，用于高亮标记 / Ordered song ID set, for highlight marking */
const orderedIds = reactive(new Set())
const inp = ref(null)
const searchState = computed(() => searchViewState(kw.value, loading.value, searchError.value, results.value))
const searchController = createSearchController(api, {
  onState: next => {
    results.value = next.results
    loading.value = next.loading
    searchError.value = next.error
  }
})

onMounted(() => inp.value?.focus())
onBeforeUnmount(() => searchController.dispose())

/**
 * 输入事件处理，300ms 防抖触发搜索（详设 H5-03）
 *
 * Input event handler with 300ms debounce before search (spec H5-03).
 */
function onInput() {
  searchController.setQuery(kw.value, activeFilter.value)
}

/** 清空搜索关键词和结果 / Clear keyword and results */
function clear() { kw.value = ''; searchController.clear(); inp.value?.focus() }

function selectFilter(value) {
  if (activeFilter.value === value) return
  activeFilter.value = value
  if (!kw.value.trim()) return
  searchController.setFilter(value, kw.value)
}

function retrySearch() {
  if (!kw.value.trim() || loading.value) return
  searchController.retry(kw.value, activeFilter.value)
}

/**
 * 将歌曲加入点歌队列，并标记为已点（带防抖并发锁）。
 * @param {Object} song - 歌曲对象，需包含 id 属性
 */
async function order(song) {
  await executeOrder(song.id, async () => {
    try {
      const res = await controls.order(song.id)
      orderedIds.add(song.id)
      toast(formatOrderToast(res))
    } catch (e) {
      toast(e.code === 'SONG_IN_QUEUE' ? (e.message || '已在队列中') : (e.message || '点歌失败'))
    }
  })
}

/**
 * 提交心愿歌曲，通知管理端补充曲库。
 */
async function addWish() {
  try {
    await api.addWish(kw.value.trim(), user.clientToken)
    toast('已记下《' + kw.value.trim() + '》，稍后补充到曲库')
  } catch (e) {
    toast(e.message || '提交失败')
  }
}

/**
 * 将毫秒时长格式化为 m:ss 字符串。
 *
 * Format duration in milliseconds to m:ss string.
 *
 * @param {number} ms - 毫秒时长 / Duration in milliseconds
 * @returns {string} 格式化后的时长，如 "3:45" / Formatted duration, e.g. "3:45"
 */
function fmtDur(ms) {
  if (!ms) return ''
  const s = Math.round(ms / 1000)
  return `${Math.floor(s / 60)}:${String(s % 60).padStart(2, '0')}`
}
</script>

<style scoped>
.page { min-height: 100vh; padding-bottom: 74px; display: flex; flex-direction: column; }
.sec { padding: 0 16px; }
.sbar { display:flex;align-items:center;gap:8px;padding-top:10px; }
.back { width:30px;height:30px;display:grid;place-items:center;color:var(--dim);padding:0; }
.search {
  height:46px;background:var(--panel);border:1px solid rgba(255,198,75,.28);border-radius:8px;
  padding:0 12px;display:flex;align-items:center;gap:8px;color:var(--gold);
}
.search input { flex: 1; background: none; border: none; outline: none; color: var(--text); font-size: 14px; }
.clear { display:grid;place-items:center;color:var(--dim2);padding:4px; }
.filters { display:flex;gap:7px;padding-top:10px;overflow-x:auto;scrollbar-width:none; }.filters::-webkit-scrollbar { display:none; }.filter { flex:none;padding:6px 10px;border:1px solid var(--line);border-radius:999px;color:var(--dim);font-size:10px;line-height:1.2; }.filter.on { border-color:var(--coral);color:var(--coral);background:rgba(255,107,97,.08); }
.results { margin-top:6px;overflow-y:auto; }
.cnt { display:flex;justify-content:space-between;padding:5px 0 8px;color:var(--dim2);font-size:10px; }.cnt b { color:var(--text);font-size:12px; }
.tip { color: var(--dim2); font-size: 13px; padding: 30px 0; text-align: center; }
.empty { text-align: center; padding: 40px 0; color: var(--dim); }
.error-state { text-align: center; padding: 36px 0; }
.e-title { color: var(--text); font-size: 14px; margin-bottom: 12px; }
</style>
