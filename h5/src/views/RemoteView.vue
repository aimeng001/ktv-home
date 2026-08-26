<template>
  <div class="page">
    <header class="topbar"><h1>遥控器</h1><span class="online"><i></i>电视在线</span></header>
    <!-- 当前曲目卡 / Now Playing Card -->
    <section class="sec">
      <div class="now">
        <div class="cover" :style="coverStyle"><Music2 v-if="!coverUrl" :size="22" /></div>
        <div class="grow">
          <div class="t">{{ song?.title || '暂无播放' }}</div>
          <div class="s" v-if="song">{{ song.artist }} · {{ player.nowPlaying?.orderedByNick || '' }} 点</div>
          <div class="bar"><i :style="{ width: progressPct + '%' }"></i></div>
        </div>
      </div>
    </section>

    <!-- 主控制排 / Main Controls -->
    <section class="sec ctlrow">
      <button class="ctl" @click="restart"><span><RotateCcw :size="20" /></span><em>重唱</em></button>
      <button class="bigctl" :aria-label="player.isPlaying ? '暂停' : '播放'" @click="togglePlay">
        <Pause v-if="player.isPlaying" :size="28" fill="currentColor" />
        <Play v-else :size="28" fill="currentColor" />
      </button>
      <button class="ctl" @click="confirmNext"><span><SkipForward :size="20" fill="currentColor" /></span><em>切歌</em></button>
    </section>

    <!-- 音量 / Volume -->
    <section class="sec">
      <div class="section-label"><b>电视音量</b><span>{{ vol }}</span></div>
      <div class="row vol">
        <button aria-label="降低音量" @click="adjustVolume(-5)"><Minus :size="18" /></button>
        <input type="range" min="0" max="100" v-model.number="vol" @change="onVol" class="grow" />
        <button aria-label="提高音量" @click="adjustVolume(5)"><Plus :size="18" /></button>
      </div>
    </section>

    <!-- 原/伴唱 / Original/Accompaniment -->
    <section class="sec">
      <div class="section-label"><b>演唱模式</b><span>{{ canVocal ? '支持原唱/伴唱' : '当前版本不可切换' }}</span></div>
      <div class="seg" :class="{ disabled: !canVocal }">
        <div :class="{ on: player.vocalMode === 'original' }" @click="setVocal('original')">原唱</div>
        <div :class="{ on: player.vocalMode === 'accompaniment' }" @click="setVocal('accompaniment')">伴唱</div>
      </div>
      <div class="note">{{ canVocal ? '当前曲目支持原唱/伴唱切换' : '当前曲目无伴唱语义，此处禁用' }}</div>
      <button v-if="canVocal" class="track-fix" @click="swapVocalTracks">原唱和伴唱弄反了？纠正并记住</button>
    </section>

    <!-- 氛围音效（P3.1 完整；此处已可发送）/ Ambience Effects (P3.1 complete; available here) -->
    <section class="sec">
      <div class="section-label"><b>现场气氛</b><span>发送到电视</span></div>
      <div class="grid4">
        <button v-for="e in effects" :key="e.id" class="fx" @click="effect(e)">
          <component :is="e.icon" :size="20" />{{ e.label }}
        </button>
      </div>
    </section>

    <div class="tip"><Lightbulb :size="13" />人声音量请在麦克风上调节</div>

    <TabBar active="remote" />
  </div>
</template>

<script setup>
/**
 * 遥控器页面 —— 提供播放控制、音量调节、原伴唱切换、氛围音效等远程操作。
 *
 * Remote control page — provides playback control, volume adjustment,
 * original/accompaniment switching, ambience effects, and other remote operations.
 */
import { ref, computed, watch } from 'vue'
import { usePlayerStore } from '../stores/player'
import { useUserStore } from '../stores/user'
import { makeControls } from '../api/client'
import { useToast } from '../composables/useToast'
import { confirmDialog } from '../composables/useDialog'
import TabBar from '../components/TabBar.vue'
import { GlassWater, Hand, Lightbulb, Megaphone, Minus, Music2, PartyPopper, Pause, Play, Plus, RotateCcw, SkipForward } from 'lucide-vue-next'

const player = usePlayerStore()
const user = useUserStore()
const { toast } = useToast()
const controls = makeControls(user.clientToken)

const song = computed(() => player.nowPlaying?.song)
const coverUrl = computed(() => song.value?.coverUrl || '')
const coverStyle = computed(() => coverUrl.value ? { backgroundImage: `url(${coverUrl.value})` } : {})
/**
 * 是否支持原/伴唱音轨切换（KTV 版本）。
 *
 * Whether vocal track switching is supported (KTV version).
 */
const canVocal = computed(() => song.value?.hasVocalTrack === true ||
  ['DUAL_TRACK', 'DUAL_CHANNEL'].includes(player.audioLayout?.layout))
/**
 * 播放进度百分比（0–100）。
 *
 * Playback progress percentage (0–100).
 */
const progressPct = computed(() => {
  const dur = song.value?.durationMs || 0
  return dur ? Math.min(100, Math.round((player.positionMs / dur) * 100)) : 0
})

const vol = ref(player.volume)
watch(() => player.volume, v => { vol.value = v })

const effects = [
  { id: 'clap', icon: Hand, label: '鼓掌' }, { id: 'cheer', icon: PartyPopper, label: '欢呼' },
  { id: 'boo', icon: Megaphone, label: '倒彩' }, { id: 'toast', icon: GlassWater, label: '干杯' }
]

/**
 * 切换播放/暂停。
 *
 * Toggle play/pause.
 */
async function togglePlay() {
  try { player.isPlaying ? await controls.pause() : await controls.play() }
  catch (e) { toast(e.message || '操作失败') }
}
/**
 * 重唱（从头播放当前歌曲）。
 *
 * Restart current song from the beginning.
 */
async function restart() {
  try { await controls.restart(); toast('重唱：从头播放') }
  catch (e) { toast(e.message || '操作失败') }
}
/**
 * 弹出确认后切到下一首。
 *
 * Confirm dialog then skip to the next song.
 */
async function confirmNext() {
  if (!await confirmDialog(`将切掉《${song.value?.title || ''}》。`, { title: '确认切歌', tone: 'warning' })) return
  try { await controls.next(); toast('已切歌') }
  catch (e) { toast(e.message || '切歌失败') }
}
/**
 * 音量滑块变更时同步到电视端。
 *
 * Sync volume slider changes to the TV.
 */
async function onVol() {
  try { await controls.setVolume(vol.value) }
  catch (e) { toast(e.message || '调音量失败') }
}

async function adjustVolume(delta) {
  vol.value = Math.min(100, Math.max(0, vol.value + delta))
  await onVol()
}
/**
 * 切换原唱/伴唱模式。
 *
 * Switch between original and accompaniment vocal modes.
 *
 * @param {'original'|'accompaniment'} mode 音轨模式 / vocal mode
 */
async function setVocal(mode) {
  if (!canVocal.value) { toast('该版本无伴唱音轨'); return }
  try { await controls.setVocal(mode); toast(mode === 'original' ? '已切原唱' : '已切伴唱') }
  catch (e) { toast(e.message || '切换失败') }
}
/**
 * 纠正原唱/伴唱音轨标记（将两个音轨互换并记住）。
 *
 * Swap the original/accompaniment track labels (persist the correction).
 */
async function swapVocalTracks() {
  if (!canVocal.value) { toast('该歌曲没有原唱/伴唱语义'); return }
  if (!await confirmDialog(`将永久更正《${song.value?.title || ''}》的原唱和伴唱标记。`, { title: '纠正音轨标记' })) return
  try { await controls.swapVocalTracks(); toast('已纠正，本歌曲下次播放继续生效') }
  catch (e) { toast(e.message || '纠正失败') }
}
/**
 * 发送氛围音效（鼓掌/欢呼/倒彩/干杯）。
 *
 * Send an ambience effect (clap, cheer, boo, toast).
 *
 * @param {{ id: string, ic: string, label: string }} e 音效对象 / effect object
 */
async function effect(e) {
  try { await controls.effect(e.id); toast('已发送：' + e.label) }
  catch (err) { toast(err.message || '发送失败') }
}
</script>

<style scoped>
.page { min-height: 100vh; padding-bottom: 74px; display: flex; flex-direction: column; }
.topbar { height:56px;padding:0 16px;display:flex;align-items:center;justify-content:space-between; }.topbar h1 { font-size:18px; }.online { display:flex;align-items:center;gap:6px;color:var(--mint);font-size:11px; }.online i { width:6px;height:6px;border-radius:50%;background:var(--mint); }
.sec { padding:0 16px;margin-top:18px; }.topbar + .sec { margin-top:4px; }
.now {
  padding:10px 0 15px;border-bottom:1px solid var(--line);display:flex;gap:12px;align-items:center;
}
.cover {
  width:58px;height:58px;border-radius:6px;flex:none;display:grid;place-items:center;
  background:#202630 center / cover no-repeat;color:var(--dim2);border:1px solid var(--glass-border);
}
.now .t { font-size: 16px; font-weight: 800; }
.now .s { font-size: 12px; color: var(--dim); margin-top: 3px; }
.bar { height: 4px; background: rgba(255,255,255,.06); border-radius: 3px; margin-top: 8px; overflow: hidden; }
.bar i { display:block;height:100%;background:var(--coral); }
.ctlrow { display:flex;align-items:center;justify-content:center;gap:36px;padding-top:4px; }
.ctl { display: flex; flex-direction: column; align-items: center; gap: 7px; color: var(--dim); }
.ctl span { width: 56px; height: 56px; border-radius: 50%; background: var(--panel2);
  border: 1px solid var(--glass-border); display: flex; align-items: center; justify-content: center; font-size: 20px; }
.ctl em { font-size: 11px; font-style: normal; }
.bigctl {
  width:78px;height:78px;border-radius:50%;display:grid;place-items:center;background:var(--gold);
  color:#1a1400;box-shadow:0 8px 32px rgba(240,199,66,.35);
}
.section-label { display:flex;justify-content:space-between;margin-bottom:11px;font-size:12px; }.section-label span { color:var(--gold);font-size:11px; }.vol { gap:12px; }
.vol button { width:24px;height:24px;display:grid;place-items:center;color:var(--dim); }
.vol input { accent-color: var(--gold); }
.note { font-size: 11px; color: var(--dim2); margin-top: 7px; }
.track-fix { width: 100%; margin-top: 10px; padding: 9px 12px; border-radius: 10px; border: 1px dashed rgba(240,199,66,.35);
  background: rgba(240,199,66,.06); color: var(--gold); font-size: 12px; }
.seg { display:flex;padding:3px;background:var(--panel);border:1px solid var(--line);border-radius:6px;overflow:hidden; }
.seg.disabled { opacity: .5; }
.seg div { flex: 1; text-align: center; padding: 10px; font-size: 14px; color: var(--dim); }
.seg div.on { border-radius:4px;background:var(--gold);color:#211d14;font-weight:700; }
.grid4 { display: grid; grid-template-columns: repeat(4, 1fr); gap: 10px; }
.fx { height:58px;display:grid;place-items:center;align-content:center;gap:5px;background:var(--panel);border:1px solid var(--line);border-radius:6px;padding:6px 4px;color:var(--dim);font-size:10px; }
.fx svg { color:var(--cyan); }
.tip { display:flex;align-items:center;justify-content:center;gap:5px;font-size:11px;color:var(--dim2);margin-top:20px; }
</style>
