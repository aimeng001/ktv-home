<template>
  <aside class="soft-keyboard" aria-label="点歌拼音软键盘">
    <div class="kb-input-row">
      <input
        ref="inputEl"
        type="text"
        :value="modelValue"
        maxlength="40"
        :placeholder="kbMode === 'number' ? '输入 6 位歌曲编号（如 800101）直接秒定歌曲' : '输入歌名 / 歌手 / 拼音首字母'"
        @input="onManualInput"
        @compositionstart="isComposing = true"
        @compositionend="onCompositionEnd"
        @keydown.enter="onEnter"
      />
      <button v-if="modelValue" type="button" class="kb-clear-mini" aria-label="清空输入" @click="clearAll">
        <X :size="15" />
      </button>
    </div>

    <!-- 字数筛选胶囊（对标 Android TV / IKTV 50.0.4 键盘上方常驻字数栏） -->
    <div class="word-count-bar" aria-label="歌名字数过滤">
      <button
        v-for="opt in wordCountOptions"
        :key="opt.value"
        type="button"
        class="word-count-chip"
        :class="{ on: (wordCount ?? 0) === opt.value }"
        @click="selectWordCount(opt.value)"
      >
        {{ opt.label }}
      </button>
    </div>

    <div class="kb-mode-tabs">
      <button
        type="button"
        :class="{ on: kbMode === 'qwerty' }"
        @click="switchMode('qwerty')"
      >
        全拼 26 键
      </button>
      <button
        type="button"
        :class="{ on: kbMode === 't9' }"
        @click="switchMode('t9')"
      >
        T9 九宫格
      </button>
      <button
        type="button"
        :class="{ on: kbMode === 'number' }"
        @click="switchMode('number')"
      >
        数字小键盘
      </button>
    </div>

    <!-- 全拼 26 键 + 数字 -->
    <div v-if="kbMode === 'qwerty'" class="qwerty-wrap">
      <div v-for="(row, rIdx) in qwertyRows" :key="rIdx" class="qwerty-row">
        <button
          v-for="keyChar in row"
          :key="keyChar"
          type="button"
          class="kb-key"
          @click="appendChar(keyChar)"
        >
          {{ keyChar }}
        </button>
      </div>
      <div class="qwerty-actions">
        <button type="button" class="kb-action" @click="backspace">⌫ 退格</button>
        <button type="button" class="kb-action" @click="clearAll">清空</button>
        <button type="button" class="kb-action gold" @click="$emit('submit')">搜索</button>
      </div>
    </div>

    <!-- T9 九宫格（对标 Android TV 800ms Multi-Tap 连按循环字母） -->
    <div v-else-if="kbMode === 't9'" class="t9-wrap">
      <div class="t9-grid">
        <button
          v-for="item in t9Keys"
          :key="item.digit"
          type="button"
          class="t9-key"
          :class="{ action: item.action }"
          @click="onT9Press(item)"
        >
          <strong>{{ item.label }}</strong>
          <small v-if="item.letters !== item.label">{{ item.letters }}</small>
        </button>
      </div>
      <p class="t9-hint">连按数字键可在 800ms 内循环切换字母，停顿后输入下一位</p>
    </div>

    <!-- 数字小键盘（对标 Android TV / IKTV 50.0.4 6 位歌曲编号秒点） -->
    <div v-else-if="kbMode === 'number'" class="number-wrap">
      <div class="number-grid">
        <button
          v-for="item in numberKeys"
          :key="item.digit"
          type="button"
          class="number-key"
          :class="{ action: item.action }"
          @click="onNumberPress(item)"
        >
          <strong>{{ item.label }}</strong>
        </button>
      </div>
      <p class="number-hint">输入 6 位歌曲编号（如 800101）直接秒定歌曲</p>
    </div>
  </aside>
</template>

<script setup>
import { ref } from 'vue'
import { X } from 'lucide-vue-next'
import { NUMBER_KEYS, QWERTY_ROWS, T9_DIGIT_MAP, T9_KEYS, WORD_COUNT_OPTIONS } from './ktvCatalogPresets'

const MULTI_TAP_TIMEOUT_MS = 800

const props = defineProps({
  modelValue: { type: String, default: '' },
  wordCount: { type: Number, default: 0 },
})
const emit = defineEmits(['update:modelValue', 'update:wordCount', 'submit'])

const wordCountOptions = WORD_COUNT_OPTIONS

function selectWordCount(val) {
  emit('update:wordCount', val)
}

const kbMode = ref('qwerty')
const inputEl = ref(null)
const isComposing = ref(false)
const qwertyRows = QWERTY_ROWS
const t9Keys = T9_KEYS
const numberKeys = NUMBER_KEYS

let lastT9Digit = null
let lastT9TapAt = 0
let lastT9TapCount = 0

function resetMultiTap() {
  lastT9Digit = null
  lastT9TapAt = 0
  lastT9TapCount = 0
}

function switchMode(mode) {
  kbMode.value = mode
  resetMultiTap()
  if (mode === 'number') {
    emit('update:wordCount', 0)
  }
}

function onNumberPress(item) {
  resetMultiTap()
  if (item.action === 'clear') {
    clearAll()
  } else if (item.action === 'backspace') {
    backspace()
  } else if (item.digit) {
    appendChar(item.digit)
  }
}

function onManualInput(e) {
  resetMultiTap()
  emit('update:modelValue', e.target.value)
}

function onCompositionEnd(e) {
  isComposing.value = false
  onManualInput(e)
}

function onEnter(e) {
  if (isComposing.value || e.isComposing) return
  e.preventDefault()
  emit('submit')
}

function appendChar(ch) {
  resetMultiTap()
  emit('update:modelValue', (props.modelValue || '') + ch)
}

function backspace() {
  resetMultiTap()
  const v = props.modelValue || ''
  if (!v) return
  emit('update:modelValue', v.slice(0, -1))
}

function clearAll() {
  resetMultiTap()
  emit('update:modelValue', '')
}

function onT9Press(item) {
  if (item.action === 'clear') {
    clearAll()
    return
  }
  if (item.action === 'backspace') {
    backspace()
    return
  }
  const letters = T9_DIGIT_MAP[item.digit]
  if (!letters) {
    appendChar(item.digit)
    return
  }
  const now = Date.now()
  const current = props.modelValue || ''
  const prevChar = current.slice(-1)
  if (
    lastT9Digit === item.digit
    && (now - lastT9TapAt) < MULTI_TAP_TIMEOUT_MS
    && current.length > 0
    && letters.includes(prevChar)
  ) {
    lastT9TapCount += 1
    lastT9TapAt = now
    const nextLetter = letters[(lastT9TapCount - 1) % letters.length]
    emit('update:modelValue', current.slice(0, -1) + nextLetter)
  } else {
    lastT9Digit = item.digit
    lastT9TapCount = 1
    lastT9TapAt = now
    emit('update:modelValue', current + letters[0])
  }
}

function focusInput() {
  inputEl.value?.focus()
}

defineExpose({ focusInput })
</script>

<style scoped>
.soft-keyboard {
  background: rgba(13, 18, 30, 0.96);
  backdrop-filter: blur(12px);
  border: 1px solid rgba(255, 255, 255, 0.14);
  border-radius: 12px;
  padding: 12px;
  display: flex;
  flex-direction: column;
  gap: 9px;
}
.kb-input-row {
  display: flex;
  align-items: center;
  gap: 6px;
  background: rgba(255, 255, 255, 0.08);
  border: 1px solid rgba(49, 130, 251, 0.45);
  border-radius: 8px;
  padding: 0 10px;
  height: 40px;
}
.kb-input-row input {
  flex: 1;
  min-width: 0;
  background: transparent;
  border: none;
  outline: none;
  color: var(--text);
  font-size: 14px;
}
.kb-clear-mini {
  color: var(--dim);
  display: grid;
  place-items: center;
}
.word-count-bar {
  display: grid;
  grid-template-columns: repeat(7, 1fr);
  gap: 4px;
}
.word-count-chip {
  height: 30px;
  border-radius: 6px;
  font-size: 12px;
  color: var(--dim, #a8b7d4);
  background: rgba(255, 255, 255, 0.05);
  border: 1px solid rgba(255, 255, 255, 0.1);
  display: grid;
  place-items: center;
  transition: all 0.15s ease;
  cursor: pointer;
}
.word-count-chip:hover {
  color: #fff;
  border-color: rgba(49, 130, 251, 0.55);
}
.word-count-chip.on {
  color: #ffffff;
  background: var(--iktv-blue, #3182fb);
  border-color: var(--iktv-blue, #3182fb);
  font-weight: 700;
  box-shadow: 0 0 8px var(--iktv-blue-glow, rgba(49, 130, 251, 0.42));
}
.kb-mode-tabs {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 6px;
}
.kb-mode-tabs button {
  height: 32px;
  border-radius: 6px;
  font-size: 12px;
  color: var(--dim);
  background: rgba(255, 255, 255, 0.06);
  border: 1px solid rgba(255, 255, 255, 0.1);
}
.kb-mode-tabs button.on {
  color: #ffffff;
  background: var(--iktv-indigo, #4936ff);
  border-color: var(--iktv-indigo, #4936ff);
  font-weight: 700;
}
.qwerty-wrap {
  display: flex;
  flex-direction: column;
  gap: 6px;
}
.qwerty-row {
  display: flex;
  justify-content: center;
  gap: 4px;
}
.kb-key {
  flex: 1;
  min-width: 22px;
  height: 36px;
  border-radius: 6px;
  background: rgba(255, 255, 255, 0.1);
  border: 1px solid rgba(255, 255, 255, 0.14);
  font-size: 13px;
  font-weight: 700;
  color: var(--text);
}
.kb-key:hover, .kb-key:active {
  background: rgba(49, 130, 251, 0.25);
  border-color: var(--iktv-blue, #3182fb);
}
.qwerty-actions {
  display: grid;
  grid-template-columns: 1fr 1fr 1fr;
  gap: 6px;
  margin-top: 2px;
}
.kb-action {
  height: 36px;
  border-radius: 6px;
  background: rgba(255, 255, 255, 0.09);
  border: 1px solid rgba(255, 255, 255, 0.14);
  font-size: 13px;
  font-weight: 600;
}
.kb-action.gold {
  background: var(--iktv-blue, #3182fb);
  color: #ffffff;
  border-color: var(--iktv-blue, #3182fb);
  font-weight: 700;
}
.t9-grid {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 6px;
}
.t9-key {
  height: 46px;
  border-radius: 8px;
  background: rgba(255, 255, 255, 0.1);
  border: 1px solid rgba(255, 255, 255, 0.14);
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  line-height: 1.1;
}
.t9-key strong { font-size: 15px; }
.t9-key small { font-size: 11px; color: var(--dim); margin-top: 2px; }
.t9-key.action { background: rgba(49, 130, 251, 0.15); border-color: rgba(49, 130, 251, 0.36); }
.t9-wrap { display: flex; flex-direction: column; gap: 6px; }
.t9-hint { font-size: 10px; color: var(--dim); text-align: center; }

.number-grid {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 6px;
}
.number-key {
  height: 46px;
  border-radius: 8px;
  background: rgba(255, 255, 255, 0.1);
  border: 1px solid rgba(255, 255, 255, 0.14);
  display: flex;
  align-items: center;
  justify-content: center;
}
.number-key strong { font-size: 18px; font-weight: 800; color: #fff; }
.number-key.action { background: rgba(49, 130, 251, 0.15); border-color: rgba(49, 130, 251, 0.36); }
.number-key.action strong { font-size: 14px; font-weight: 700; color: #93c5fd; }
.number-key:hover, .number-key:active {
  background: rgba(49, 130, 251, 0.25);
  border-color: var(--iktv-blue, #3182fb);
}
.number-wrap { display: flex; flex-direction: column; gap: 6px; }
.number-hint { font-size: 11px; color: var(--dim); text-align: center; }
</style>
