<template>
  <div v-if="activeModal" class="modal-backdrop" @click.self="$emit('close')">
    <div class="modal-card" role="dialog" :aria-label="modalTitle">
      <header class="modal-hd">
        <h3>{{ modalTitle }}</h3>
        <button type="button" class="close-btn" aria-label="关闭弹窗" @click="$emit('close')">
          <X :size="18" />
        </button>
      </header>

      <!-- 系统信息 -->
      <div v-if="activeModal === 'system'" class="modal-body">
        <div class="kv-grid">
          <div class="kv"><small>服务状态</small><strong :title="healthError">{{ systemInfoLoading ? '读取中…' : healthInfo?.status || (healthError ? '读取失败' : '未知') }}</strong></div>
          <div class="kv"><small>服务端版本</small><strong :title="releaseError">{{ systemInfoLoading ? '读取中…' : releaseInfo?.version || (releaseError ? '读取失败' : '未知') }}</strong></div>
          <div class="kv"><small>物理 TV 状态</small><strong>{{ player.tvOnline ? '在线' : player.tvOnline === false ? '离线' : '同步中' }}</strong></div>
          <div class="kv"><small>当前网页模式</small><strong>{{ modeText }}</strong></div>
          <div class="kv"><small>在线网页/遥控器</small><strong>{{ player.connectedPhones }}</strong></div>
          <div class="kv"><small>当前曲库音频布局</small><strong>{{ player.audioLayout?.layout || 'NORMAL_STEREO' }}</strong></div>
        </div>
        <div class="modal-actions">
          <button v-if="healthError || releaseError" type="button" class="btn ghost system-info-retry" :disabled="systemInfoLoading" @click="loadSystemInfo">
            {{ systemInfoLoading ? '读取中…' : '重试读取' }}
          </button>
          <router-link to="/admin" class="btn" @click="$emit('close')">打开管理后台</router-link>
        </div>
      </div>

      <!-- 手机扫码点歌 -->
      <div v-else-if="activeModal === 'qr'" class="modal-body qr-body">
        <img :src="qrUrl" alt="微信扫码点歌二维码" class="qr-img" />
        <p class="qr-tip">用微信或手机浏览器扫码，即可接入当前房间共同点歌与遥控</p>
        <div v-if="shareUrlFallback" class="share-url-fallback" role="status">
          <span>无法访问剪贴板，请长按下方地址手动复制</span>
          <input
            ref="shareUrlInput"
            :value="shareUrlFallback"
            aria-label="点歌地址"
            readonly
            @focus="$event.target.select()"
            @click="$event.target.select()"
          />
        </div>
        <div class="modal-actions">
          <button type="button" class="btn ghost" @click="copyShareUrl">复制点歌地址</button>
        </div>
      </div>

      <!-- 系统设置 & 身份与模式切换 -->
      <div v-else-if="activeModal === 'settings'" class="modal-body">
        <label class="field-label">
          我的点歌昵称
          <div class="nick-row">
            <input v-model="nickDraft" maxlength="12" placeholder="输入昵称" />
            <button type="button" class="btn" :disabled="savingNick" @click="saveNickname">
              {{ savingNick ? '保存中…' : '保存' }}
            </button>
          </div>
        </label>

        <div class="setting-block">
          <small>网页端工作模式</small>
          <div class="mode-choices">
            <button type="button" :class="{ on: player.modeOverride === 'auto' }" @click="player.setModeOverride('auto')">
              自动检测
            </button>
            <button
              type="button"
              :disabled="player.tvOnline === true"
              :class="{ on: player.modeOverride === 'host' || player.isWebHost }"
              @click="$emit('claim-web-host')"
            >
              接管本机 KTV 主屏
            </button>
            <button type="button" :class="{ on: player.modeOverride === 'console' }" @click="player.setModeOverride('console')">
              仅作遥控控制台
            </button>
          </div>
          <p class="hint">
            {{ player.tvOnline ? '检测到 TV 在线，本机已自动锁定为 TV 控制台；若 TV 下线将自动恢复完整 KTV 模式。' : '当前无 TV 在线，本机可作为完整 Web KTV 播放端，也可退居为控制台。' }}
          </p>
        </div>

        <div class="setting-block">
          <small>自动全屏演唱</small>
          <div class="toggle-row">
            <span>空闲 60 秒无操作时自动进入全屏演唱</span>
            <button
              type="button"
              class="toggle-pill"
              :class="{ on: autoFullscreen }"
              :aria-pressed="String(autoFullscreen)"
              @click="$emit('toggle-auto-fullscreen')"
            >
              {{ autoFullscreen ? '已开启' : '已关闭' }}
            </button>
          </div>
        </div>

        <label class="field-label">
          媒体反馈 / 心愿点歌
          <div class="nick-row">
            <input v-model="wishDraft" maxlength="40" placeholder="输入曲库缺少的歌名或歌手" />
            <button type="button" class="btn ghost" :disabled="submittingWish" @click="submitWishFeedback">
              {{ submittingWish ? '提交中…' : '提交心愿' }}
            </button>
          </div>
        </label>

        <div class="modal-actions">
          <router-link to="/admin" class="btn ghost" @click="$emit('close')">进入系统管理后台</router-link>
        </div>
      </div>

      <!-- 更新歌库确认 -->
      <div v-else-if="activeModal === 'scan'" class="modal-body">
        <p class="scan-desc">
          更新歌库会向服务端发起增量扫描请求；若为外部只读 NAS 曲库（EXTERNAL_READ_ONLY），服务端不会改动源文件。
        </p>
        <div class="modal-actions">
          <button type="button" class="btn ghost" @click="$emit('close')">取消</button>
          <button type="button" class="btn" :disabled="scanning" @click="triggerScan">
            {{ scanning ? '已触发…' : '立即触发扫描' }}
          </button>
        </div>
      </div>

      <!-- 调音、音轨纠正与现场音效 -->
      <div v-else-if="activeModal === 'mixer'" class="modal-body">
        <div class="mixer-row">
          <span>房间音量 ({{ player.muted ? '已静音' : vol }})</span>
          <input type="range" min="0" max="100" aria-label="房间音量" v-model.number="vol" @change="$emit('set-volume', vol)" />
          <button type="button" class="mini-mute-btn" :class="{ on: player.muted }" @click="$emit('toggle-mute')">
            {{ player.muted ? '取消静音' : '静音' }}
          </button>
        </div>

        <div class="mixer-row">
          <span>演唱音轨</span>
          <div class="seg-btns">
            <button type="button" :class="{ on: player.vocalMode === 'original' }" :disabled="!canVocal" @click="$emit('set-vocal', 'original')">原唱</button>
            <button type="button" :class="{ on: player.vocalMode === 'accompaniment' }" :disabled="!canVocal" @click="$emit('set-vocal', 'accompaniment')">伴唱</button>
          </div>
        </div>



        <div class="mixer-row">
          <span>伴奏升降调</span>
          <div class="seg-btns">
            <button type="button" @click="$emit('pitch-delta', -1)">降调</button>
            <button type="button" @click="$emit('pitch-reset')">原调</button>
            <button type="button" @click="$emit('pitch-delta', 1)">升调</button>
          </div>
        </div>
        <p class="hint">
          {{ player.isTvConsole ? 'TV 端变调请在电视调音界面调节。' : '提示：电脑浏览器端支持伴奏实时无变速升降调；原调输出时自动直通。' }}
        </p>

        <div class="effects-grid">
          <button v-for="fx in effects" :key="fx.id" type="button" class="fx-btn" @click="$emit('effect', fx)">
            <component :is="fx.icon" :size="18" />
            <span>{{ fx.label }}</span>
          </button>
        </div>

        <p class="hint">提示：人声混响请在硬件麦克风调节；本系统曲库按 0 磁盘缓存实时流播放，不提供离线缓存下载。</p>
      </div>
    </div>
  </div>
</template>

<script setup>
import { computed, nextTick, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { GlassWater, Hand, Megaphone, PartyPopper, X } from 'lucide-vue-next'
import api from '../../api/client'
import { getAdminToken } from '../../stores/adminAuth'
import { usePlayerStore } from '../../stores/player'
import { useUserStore } from '../../stores/user'
import { useToast } from '../../composables/useToast'
import { confirmDialog } from '../../composables/useDialog'

const props = defineProps({
  activeModal: { type: String, default: '' },
  autoFullscreen: { type: Boolean, default: false }
})
const emit = defineEmits([
  'close',
  'claim-web-host',
  'toggle-auto-fullscreen',
  'set-volume',
  'toggle-mute',
  'set-vocal',
  'swap-vocal',
  'pitch-delta',
  'pitch-reset',
  'effect'
])

const router = useRouter()
const player = usePlayerStore()
const user = useUserStore()
const { toast } = useToast()

const healthInfo = ref(null)
const releaseInfo = ref(null)
const healthError = ref('')
const releaseError = ref('')
const systemInfoLoading = ref(false)
const shareUrlFallback = ref('')
const shareUrlInput = ref(null)
const nickDraft = ref(user.nickname || '')
const wishDraft = ref('')
const savingNick = ref(false)
const submittingWish = ref(false)
const scanning = ref(false)
const vol = ref(player.volume)

watch(() => player.volume, v => { vol.value = v })
watch(() => props.activeModal, async (modal) => {
  if (modal === 'settings') {
    nickDraft.value = user.nickname || ''
    wishDraft.value = ''
  }
  if (modal === 'system') {
    await loadSystemInfo()
  }
})

async function loadSystemInfo() {
  systemInfoLoading.value = true
  healthInfo.value = null
  releaseInfo.value = null
  healthError.value = ''
  releaseError.value = ''
  const [healthResult, releaseResult] = await Promise.allSettled([
    api.health(),
    api.releaseInfo()
  ])
  if (healthResult.status === 'fulfilled') healthInfo.value = healthResult.value
  else healthError.value = healthResult.reason?.message || '读取失败'
  if (releaseResult.status === 'fulfilled') releaseInfo.value = releaseResult.value
  else releaseError.value = releaseResult.reason?.message || '读取失败'
  systemInfoLoading.value = false
}

const modalTitle = computed(() => ({
  system: '系统信息',
  qr: '手机扫码点歌',
  settings: '系统设置与模式',
  scan: '更新歌库',
  mixer: '调音与现场音效'
}[props.activeModal] || ''))

const modeText = computed(() => (
  player.effectiveMode === 'TV_CONSOLE'
    ? 'TV 控制台（静音镜像）'
    : player.effectiveMode === 'WEB_HOST'
      ? '完整 Web KTV 主播放端'
      : '多端协同控制台'
))

const qrUrl = computed(() => '/api/qr?room=default&size=420')
const canVocal = computed(() => (
  player.nowPlaying?.song?.hasVocalTrack === true
  || ['DUAL_TRACK', 'DUAL_CHANNEL'].includes(player.audioLayout?.layout)
))

const effects = [
  { id: 'clap', icon: Hand, label: '鼓掌' },
  { id: 'cheer', icon: PartyPopper, label: '欢呼' },
  { id: 'boo', icon: Megaphone, label: '倒彩' },
  { id: 'toast', icon: GlassWater, label: '干杯' },
]

async function saveNickname() {
  const next = nickDraft.value.trim()
  if (!next) {
    toast('昵称不能为空')
    return
  }
  savingNick.value = true
  const prev = { nickname: user.nickname, serverUserId: user.serverUserId }
  try {
    user.ensureToken()
    const res = await api.registerUser(user.clientToken, next)
    if (!user.markRegistrationSuccess(res)) throw new Error('昵称更新失败')
    toast(`已更新昵称：${user.nickname}`)
  } catch (e) {
    // 昵称更新失败不得清空可写身份：register() 会置空 serverUserId，这里直接回滚。
    if (prev.serverUserId) {
      user.markRegistrationSuccess({ id: prev.serverUserId, nickname: prev.nickname || user.nickname })
    } else {
      user.setNickname(prev.nickname)
    }
    toast(e.message || '保存昵称失败')
  } finally {
    savingNick.value = false
  }
}

async function submitWishFeedback() {
  const kw = wishDraft.value.trim()
  if (!kw) {
    toast('请输入想补充的歌曲或歌手名称')
    return
  }
  submittingWish.value = true
  try {
    await api.addWish(kw, user.clientToken)
    wishDraft.value = ''
    toast(`已记录心愿反馈：《${kw}》`)
  } catch (e) {
    toast(e.message || '提交反馈失败')
  } finally {
    submittingWish.value = false
  }
}

async function copyShareUrl() {
  const shareUrl = `${location.origin}/m/?room=default`
  try {
    const clipboard = navigator.clipboard
    if (typeof clipboard?.writeText !== 'function') {
      await showShareUrlFallback(shareUrl)
      return
    }
    await clipboard.writeText(shareUrl)
    shareUrlFallback.value = ''
    toast('已复制点歌地址')
  } catch {
    await showShareUrlFallback(shareUrl)
  }
}

async function showShareUrlFallback(shareUrl) {
  shareUrlFallback.value = shareUrl
  await nextTick()
  shareUrlInput.value?.focus()
  shareUrlInput.value?.select()
}

async function triggerScan() {
  if (!getAdminToken()) {
    const goLogin = await confirmDialog('触发曲库增量扫描需要管理员权限，是否前往管理后台登录？', {
      title: '需要管理员权限'
    })
    if (goLogin) {
      emit('close')
      router.push({ name: 'admin-login', query: { redirect: '/admin' } })
    }
    return
  }
  scanning.value = true
  try {
    await api.adminStartScan()
    toast('已在后台触发曲库扫描')
    emit('close')
  } catch (e) {
    toast(e.message || '触发扫描失败')
  } finally {
    scanning.value = false
  }
}
</script>

<style scoped>
.modal-backdrop {
  position: fixed;
  inset: 0;
  background: rgba(5, 8, 15, 0.72);
  z-index: 70;
  display: grid;
  place-items: center;
  padding: 16px;
}
.modal-card {
  width: min(480px, 100%);
  border-radius: 16px;
  background: linear-gradient(180deg, #1b2234, #111622);
  border: 1px solid rgba(255, 198, 75, 0.28);
  padding: 16px;
  box-shadow: var(--shadow);
}
.modal-hd {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 14px;
}
.modal-hd h3 { font-size: 16px; color: var(--gold2); }
.close-btn {
  width: 32px; height: 32px; border-radius: 50%;
  background: rgba(255, 255, 255, 0.08); display: grid; place-items: center;
}
.modal-body { display: flex; flex-direction: column; gap: 12px; }
.kv-grid { display: grid; grid-template-columns: repeat(2, 1fr); gap: 8px; }
.kv { padding: 10px; border-radius: 10px; background: rgba(255, 255, 255, 0.05); }
.kv small { display: block; font-size: 11px; color: var(--dim); }
.kv strong { font-size: 13px; margin-top: 4px; display: block; }
.qr-body { align-items: center; text-align: center; }
.qr-img { width: 220px; height: 220px; border-radius: 12px; background: #fff; padding: 8px; }
.share-url-fallback { width: 100%; display: flex; flex-direction: column; gap: 6px; text-align: left; font-size: 12px; color: var(--dim); }
.share-url-fallback input { width: 100%; height: 38px; border-radius: 8px; padding: 0 10px; user-select: all; background: rgba(255, 255, 255, 0.08); border: 1px solid rgba(255, 255, 255, 0.14); color: var(--text); }
.qr-tip, .scan-desc, .hint { font-size: 12px; color: var(--dim); line-height: 1.5; }
.field-label { display: flex; flex-direction: column; gap: 6px; font-size: 12px; color: var(--dim); }
.nick-row { display: flex; gap: 8px; }
.nick-row input {
  flex: 1; height: 38px; border-radius: 8px; padding: 0 10px;
  background: rgba(255, 255, 255, 0.08); border: 1px solid rgba(255, 255, 255, 0.14); color: var(--text);
}
.setting-block small { display: block; font-size: 12px; color: var(--dim); margin-bottom: 6px; }
.toggle-row {
  display: flex; align-items: center; justify-content: space-between; gap: 10px;
  padding: 8px 10px; border-radius: 8px; background: rgba(255, 255, 255, 0.05); font-size: 12px;
}
.toggle-pill {
  padding: 5px 12px; border-radius: 999px; font-size: 12px; font-weight: 700; flex: none;
  background: rgba(255, 255, 255, 0.1); color: var(--dim); border: 1px solid rgba(255, 255, 255, 0.14);
}
.toggle-pill.on { background: var(--gold); color: #191304; border-color: var(--gold); }
.mode-choices { display: grid; grid-template-columns: repeat(3, 1fr); gap: 6px; }
.mode-choices button {
  padding: 8px 6px; border-radius: 8px; font-size: 12px;
  background: rgba(255, 255, 255, 0.08); color: var(--dim); border: 1px solid rgba(255, 255, 255, 0.1);
}
.mode-choices button.on { background: var(--gold); color: #191304; font-weight: 800; border-color: var(--gold); }
.mode-choices button:disabled { opacity: 0.4; cursor: not-allowed; }
.mixer-row { display: flex; align-items: center; justify-content: space-between; gap: 12px; font-size: 13px; }
.mixer-row input[type="range"] { flex: 1; accent-color: var(--gold); }
.mini-mute-btn {
  padding: 5px 10px; border-radius: 8px; font-size: 12px; flex: none;
  background: rgba(255, 255, 255, 0.09); border: 1px solid rgba(255, 255, 255, 0.14);
}
.mini-mute-btn.on { background: rgba(239, 68, 68, 0.2); border-color: rgba(239, 68, 68, 0.5); color: #fca5a5; }
.seg-btns { display: flex; gap: 6px; }
.seg-btns button {
  padding: 6px 12px; border-radius: 8px; font-size: 12px;
  background: rgba(255, 255, 255, 0.09); border: 1px solid rgba(255, 255, 255, 0.14);
}
.seg-btns button.on { background: var(--gold); color: #191304; font-weight: 800; }
.seg-btns button:disabled { opacity: 0.45; }
.swap-row { display: flex; justify-content: flex-end; }
.effects-grid { display: grid; grid-template-columns: repeat(4, 1fr); gap: 8px; }
.fx-btn {
  padding: 10px 4px; border-radius: 10px; background: rgba(255, 255, 255, 0.08);
  border: 1px solid rgba(255, 255, 255, 0.12); display: flex; flex-direction: column;
  align-items: center; gap: 4px; font-size: 12px;
}
.fx-btn:hover { border-color: var(--gold); color: var(--gold2); }
.modal-actions { display: flex; justify-content: flex-end; gap: 10px; margin-top: 4px; }
.btn.small { padding: 6px 12px; font-size: 12px; }
</style>
