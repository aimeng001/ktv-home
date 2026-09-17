<template>
  <div class="page">
    <!-- 顶部迷你曲目条 / Mini track bar at top -->
    <div class="topbar">
      <span class="down" @click="$router.back()">⌄</span>
      <div class="grow center">
        <div class="t">{{ song?.title || '暂无播放' }}
          <span class="a" v-if="song">· {{ song.artist }}</span></div>
        <div class="bar"><i :style="{ width: progressPct + '%' }"></i></div>
      </div>
      <div class="cover"></div>
    </div>

    <!-- 歌词滚动区 / Lyric scroll area -->
    <div class="lyric grow">
      <div v-if="lyricState === 'error'" class="nolyric error-state" role="alert">
        <span>歌词加载失败：{{ lyricError }}</span>
        <button class="retry" @click="retryLyric">重试</button>
      </div>
      <div v-else-if="lyricState === 'empty'" class="nolyric">这首歌暂无歌词</div>
      <div v-else-if="lyricState === 'idle'" class="nolyric">还没有歌曲播放</div>
      <div v-else class="lines" :style="{ transform: `translateY(${offset}px)` }">
        <div v-for="(l, i) in lines" :key="i" class="ln" :class="{ cur: i === curLine }">
          <template v-if="i === curLine && l.words?.length">
            <span v-for="(word, wi) in l.words" :key="wi" :class="{ sung: word.time <= player.positionMs }">{{ word.text }}</span>
          </template>
          <template v-else>{{ l.text }}</template>
        </div>
      </div>
    </div>

    <!-- 底部迷你控制 / Mini controls at bottom -->
    <div class="mini">
      <button class="c" @click="restart">🔁</button>
      <button class="c big" @click="togglePlay">{{ player.isPlaying ? '⏸' : '▶' }}</button>
      <button class="c" @click="next">⏭</button>
    </div>
  </div>
</template>

<script setup>
/**
 * 歌词视图 — 展示当前播放歌曲的滚动歌词，支持逐字高亮和迷你播放控制。
 *
 * Lyric view — displays scrolling lyrics for the currently playing song,
 * with word-by-word highlighting and mini playback controls.
 */
import { ref, computed, watch, onMounted, onBeforeUnmount } from 'vue'
import { usePlayerStore } from '../stores/player'
import { useUserStore } from '../stores/user'
import { makeControls } from '../api/client'
import { parseLrc } from '../composables/lrc'
import { useToast } from '../composables/useToast'
import { confirmDialog } from '../composables/useDialog'
import { currentLyricIndex, lyricViewState } from './lyricState'
import { createLyricLoader } from './lyricLoader'

const player = usePlayerStore()
const user = useUserStore()
const controls = makeControls(user.clientToken, { onResponse: response => player.applyControlResponse(response) })
const { toast } = useToast()

/** 当前播放歌曲的计算属性 / Computed: currently playing song */
const song = computed(() => player.nowPlaying?.song)
/** 解析后的歌词行数组，每项含 time(ms) 和 text / Parsed lyric lines, each with time(ms) and text */
const lines = ref([])       // [{time, text}]
const lyricError = ref('')
const lyricState = computed(() => lyricViewState(song.value, lines.value, lyricError.value))
const lyricLoader = createLyricLoader({
  fetchImpl: fetch,
  parse: parseLrc,
  getCurrentSong: () => song.value,
  onReset: () => { lines.value = []; lyricError.value = '' },
  onSuccess: parsed => { lines.value = parsed },
  onError: error => { lyricError.value = error?.message || '网络错误，请稍后重试' }
})
/** 每行歌词高度(px)，用于滚动偏移计算 / Line height (px) for scroll offset calculation */
const LINE_H = 44

/**
 * 播放进度百分比 (0–100)。
 *
 * Playback progress percentage (0–100).
 */
const progressPct = computed(() => {
  const dur = song.value?.durationMs || 0
  return dur ? Math.min(100, Math.round((player.positionMs / dur) * 100)) : 0
})

/**
 * 当前高亮行索引：最后一个 time <= 当前播放位置的歌词行。
 *
 * Current highlighted line index: the last line whose time <= current playback position.
 */
const curLine = computed(() => {
  return currentLyricIndex(lines.value, player.positionMs)
})

/**
 * 滚动偏移量(px)，使当前歌词行保持在可视区域居中位置。
 *
 * Scroll offset (px) to keep the current lyric line centered in the viewport.
 */
const offset = computed(() => -(Math.max(0, curLine.value) * LINE_H))

/**
 * 根据歌曲 ID 加载并解析歌词文件。
 *
 * Fetches and parses the lyric file for the given song ID.
 *
 * @param {string|number} id - 歌曲唯一标识 / Song ID
 */
async function loadLyric(id) {
  return lyricLoader.load(id)
}

function retryLyric() { loadLyric(song.value?.id) }

/** 监听播放队列变化，自动加载对应歌词 / Watch queue changes to auto-load lyrics */
watch(() => player.nowPlaying?.queueId, () => loadLyric(song.value?.id))
/** 组件挂载时加载当前歌词 / Load lyrics for current song on mount */
onMounted(() => loadLyric(song.value?.id))
onBeforeUnmount(() => lyricLoader.dispose())

/**
 * 切换播放/暂停状态。
 *
 * Toggles between play and pause.
 */
async function togglePlay() {
  try { player.isPlaying ? await controls.pause() : await controls.play() }
  catch (error) { toast(error.message || '播放控制失败') }
}
/**
 * 重新播放当前歌曲。
 *
 * Restarts the current song from the beginning.
 */
async function restart() {
  try { await controls.restart() }
  catch (error) { toast(error.message || '重唱失败') }
}
/**
 * 切到下一首歌曲，操作前弹出确认对话框。
 *
 * Skips to the next song after a confirmation dialog.
 */
async function next() {
  if (!await confirmDialog(`将切掉《${song.value?.title || ''}》。`, { title: '确认切歌', tone: 'warning' })) return
  try { await controls.next() }
  catch (error) { toast(error.message || '切歌失败') }
}
</script>

<style scoped>
.page {
  min-height: 100vh; display: flex; flex-direction: column;
  background: radial-gradient(ellipse 350px 350px at 50% 45%, rgba(240,199,66,.04), transparent),
              linear-gradient(175deg, rgba(20,26,42,.9), var(--bg));
}
.topbar { display: flex; align-items: center; gap: 10px; padding: 16px; }
.down { font-size: 20px; color: var(--dim); }
.center { text-align: center; }
.center .t { font-size: 14px; font-weight: 700; }
.center .a { color: var(--dim); font-weight: 400; font-size: 11px; }
.bar { height: 3px; background: rgba(255,255,255,.06); border-radius: 3px; margin-top: 7px; overflow: hidden; }
.bar i { display: block; height: 100%; background: linear-gradient(90deg, var(--gold), var(--gold2)); transition: width .5s linear; }
.cover {
  width: 34px; height: 34px; border-radius: 8px; flex: none;
  background: linear-gradient(145deg, rgba(40,46,66,.85), rgba(20,24,36,.92)); border: 1px solid var(--glass-border);
}
.lyric { overflow: hidden; display: flex; flex-direction: column; justify-content: center; padding: 0 30px; text-align: center; }
.nolyric { color: var(--dim2); text-align: center; }
.error-state { display:flex;align-items:center;justify-content:center;gap:10px;flex-wrap:wrap; }
.retry { padding:5px 10px;border:1px solid var(--line);border-radius:6px;color:var(--gold);font-size:11px; }
.lines { transition: transform .4s cubic-bezier(.4,0,.2,1); }
.ln { font-size: 15px; color: var(--dim2); padding: 10px 0; transition: var(--transition); height: 44px; }
.ln.cur { font-size: 20px; color: var(--gold); font-weight: 700; text-shadow: 0 0 20px rgba(240,199,66,.2); }
.ln.cur span { transition: color .12s linear; }
.ln.cur span.sung { color: var(--gold2); text-shadow: 0 0 16px rgba(240,199,66,.3); }
.mini { display: flex; justify-content: center; gap: 28px; padding: 20px 0 40px; }
.c { width: 44px; height: 44px; border-radius: 50%; background: var(--panel2);
  border: 1px solid var(--glass-border); font-size: 16px; }
.c.big { width: 52px; height: 52px; background: var(--gold-glow); border-color: rgba(240,199,66,.2); }
</style>
