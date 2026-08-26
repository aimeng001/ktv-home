<template>
  <button class="audio-layout-trigger" :disabled="!song?.fileId" title="配置原唱、伴唱和音频布局" @click="openEditor">音频设置</button>
  <div v-if="open" class="audio-layout-mask" @click.self="closeEditor">
    <div class="audio-layout-modal" role="dialog" aria-modal="true" aria-labelledby="audio-layout-title">
      <div class="audio-layout-head">
        <div><h2 id="audio-layout-title">音频布局设置</h2><p>《{{ song.title }}》 · {{ song.artist || '未知歌手' }}</p></div>
        <button class="audio-layout-close" type="button" title="关闭" @click="closeEditor">×</button>
      </div>
      <div v-if="error" class="audio-layout-error" role="alert">{{ error }}</div>
      <template v-if="draft">
        <label class="audio-layout-field">布局
          <select v-model="draft.layout" @change="ensureLayoutDefaults">
            <option v-for="item in AUDIO_LAYOUT_OPTIONS" :key="item.value" :value="item.value">{{ item.label }}</option>
          </select>
        </label>
        <template v-if="draft.layout === 'DUAL_TRACK'">
          <p class="audio-layout-hint">媒体检测到 {{ trackOptions.length }} 条音轨；请分别指定原唱和伴唱。</p>
          <label class="audio-layout-field">Original（原唱）
            <select v-model.number="draft.originalTrackIndex">
              <option v-for="index in trackOptions" :key="`original-${index}`" :value="index">Track {{ index }}</option>
            </select>
          </label>
          <label class="audio-layout-field">Accompaniment（伴唱）
            <select v-model.number="draft.accompanimentTrackIndex">
              <option v-for="index in trackOptions" :key="`accompaniment-${index}`" :value="index">Track {{ index }}</option>
            </select>
          </label>
        </template>
        <template v-else-if="draft.layout === 'DUAL_CHANNEL'">
          <p class="audio-layout-hint">默认 Original = LEFT、Accompaniment = RIGHT；只保存语义，不修改源文件。</p>
          <label class="audio-layout-field">Original（原唱）
            <select v-model="draft.originalChannel">
              <option v-for="item in AUDIO_CHANNEL_OPTIONS" :key="`original-${item.value}`" :value="item.value">{{ item.label }}</option>
            </select>
          </label>
          <label class="audio-layout-field">Accompaniment（伴唱）
            <select v-model="draft.accompanimentChannel">
              <option v-for="item in AUDIO_CHANNEL_OPTIONS" :key="`accompaniment-${item.value}`" :value="item.value">{{ item.label }}</option>
            </select>
          </label>
        </template>
        <p v-else class="audio-layout-hint">普通立体声不区分原唱和伴唱。</p>
      </template>
      <div class="audio-layout-actions">
        <button class="audio-layout-secondary" type="button" @click="closeEditor">取消</button>
        <button class="audio-layout-secondary" type="button" :disabled="!canSwap || saving" @click="swap">一键交换原唱/伴唱</button>
        <button class="audio-layout-primary" type="button" :disabled="saving" @click="save">{{ saving ? '保存中…' : '保存设置' }}</button>
      </div>
    </div>
  </div>
</template>

<script setup>
import { computed, ref } from 'vue'
import api from '../../api/client'
import {
  AUDIO_CHANNEL_OPTIONS,
  AUDIO_LAYOUT_OPTIONS,
  createAudioLayoutDraft
} from './audioLayout'

const props = defineProps({ song: { type: Object, required: true } })
const emit = defineEmits(['updated'])
const open = ref(false)
const saving = ref(false)
const error = ref('')
const draft = ref(null)
const trackOptions = computed(() => Array.from({ length: Math.max(0, Number(props.song.audioTracks) || 0) }, (_, index) => index))
const canSwap = computed(() => draft.value?.layout === 'DUAL_CHANNEL' || draft.value?.layout === 'DUAL_TRACK')

function openEditor() {
  draft.value = createAudioLayoutDraft(props.song.audioLayout)
  error.value = ''
  open.value = true
  ensureLayoutDefaults()
}

function closeEditor() {
  if (!saving.value) open.value = false
}

function ensureLayoutDefaults() {
  if (draft.value?.layout !== 'DUAL_TRACK' || trackOptions.value.length < 2) return
  if (draft.value.originalTrackIndex == null) draft.value.originalTrackIndex = 0
  if (draft.value.accompanimentTrackIndex == null) draft.value.accompanimentTrackIndex = 1
}

async function save() {
  if (!draft.value || !props.song.fileId) return
  saving.value = true; error.value = ''
  try {
    const result = await api.adminUpdateAudioLayout(props.song.fileId, draft.value)
    draft.value = createAudioLayoutDraft(result)
    emit('updated', result)
    open.value = false
  } catch (e) { error.value = e.message || '音频布局保存失败' } finally { saving.value = false }
}

async function swap() {
  if (!canSwap.value || !props.song.fileId) return
  saving.value = true; error.value = ''
  try {
    const result = await api.adminSwapAudioLayout(props.song.fileId)
    draft.value = createAudioLayoutDraft(result)
    emit('updated', result)
  } catch (e) { error.value = e.message || '原唱/伴唱交换失败' } finally { saving.value = false }
}
</script>

<style scoped>
.audio-layout-trigger,.audio-layout-secondary,.audio-layout-primary{display:inline-flex;align-items:center;justify-content:center;min-height:30px;padding:0 9px;border:1px solid #dbe3ee;border-radius:6px;background:#fff;color:#2563eb;font-size:11px;font-weight:600;white-space:nowrap}.audio-layout-trigger:hover:not(:disabled),.audio-layout-secondary:hover:not(:disabled){border-color:#bfdbfe;background:#eff6ff}.audio-layout-trigger:disabled,.audio-layout-secondary:disabled,.audio-layout-primary:disabled{cursor:not-allowed;opacity:.45}.audio-layout-mask{position:fixed;inset:0;z-index:110;display:grid;place-items:center;background:rgba(15,23,42,.45)}.audio-layout-modal{width:min(480px,calc(100vw - 32px));padding:22px;background:#fff;border-radius:8px;box-shadow:0 18px 50px rgba(15,23,42,.18)}.audio-layout-head{display:flex;align-items:flex-start;justify-content:space-between;gap:16px;padding-bottom:14px;border-bottom:1px solid #e2e8f0}.audio-layout-head h2{font-size:17px}.audio-layout-head p{margin-top:5px;color:#64748b;font-size:11px}.audio-layout-close{display:grid;place-items:center;width:30px;height:30px;border:1px solid #cbd5e1;border-radius:6px;color:#475569;font-size:20px;line-height:1}.audio-layout-error{margin-top:13px;padding:9px 10px;border:1px solid #fecaca;border-radius:6px;background:#fef2f2;color:#b91c1c;font-size:11px}.audio-layout-field{display:flex;flex-direction:column;gap:6px;margin-top:14px;color:#475569;font-size:12px}.audio-layout-field select{height:36px;padding:0 10px;border:1px solid #cbd5e1;border-radius:6px;background:#fff;color:#172033;font:inherit}.audio-layout-field select:focus{border-color:#60a5fa;box-shadow:0 0 0 3px rgba(37,99,235,.1);outline:0}.audio-layout-hint{margin-top:13px;color:#64748b;font-size:11px;line-height:1.5}.audio-layout-actions{display:flex;justify-content:flex-end;gap:8px;margin-top:19px}.audio-layout-primary{border-color:#2563eb;background:#2563eb;color:#fff}.audio-layout-primary:hover:not(:disabled){background:#1d4ed8}
</style>
