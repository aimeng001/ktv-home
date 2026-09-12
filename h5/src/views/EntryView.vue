<template>
  <div class="entry">
    <!-- 品牌区：Logo + 标题 / Branding: logo + title -->
    <div class="logo">🎤</div>
    <div class="title">{{ welcomeText }}</div>
    <div class="room">{{ roomLabel }}</div>

    <!-- 昵称输入区 / Nickname input -->
    <div class="field">
      <label>你的昵称（点歌时显示）</label>
      <div class="input-wrap">
        <input v-model="nickname" maxlength="12" placeholder="输入昵称" />
      </div>
      <div class="hint">已为你随机生成，可修改；本机记忆，下次免填</div>
    </div>

    <button class="btn enter-btn" :disabled="registering" @click="enter">
      {{ registering ? '正在连接…' : '进入点歌' }}
    </button>
    <div v-if="registrationError" class="registration-error" role="alert">
      {{ registrationError }}
      <button class="retry" :disabled="registering" @click="enter">重试</button>
    </div>
    <!-- 页脚提示 / Footer note -->
    <div class="foot">仅限家庭局域网使用 · 无需注册</div>
  </div>
</template>

<script setup>
/**
 * 入口页面 — 用户输入昵称后进入点歌系统。
 * 支持随机昵称生成、本地记忆和昵称冲突自动去重。
 *
 * Entry page — user enters a nickname and proceeds to the song-request system.
 * Supports random nickname generation, local memory, and automatic dedup on nickname conflict.
 */
import { onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { useUserStore } from '../stores/user'
import { usePlayerStore } from '../stores/player'
import api from '../api/client'

const router = useRouter()
const user = useUserStore()
const player = usePlayerStore()

const welcomeText = ref('家庭KTV')
const roomLabel = ref('房间：客厅')

// 默认回填已存昵称或随机建议值（详设 H5-01）
// Default: fallback to saved nickname or a random suggestion (spec H5-01)
const nickname = ref(user.suggestNickname())
const registering = ref(false)
const registrationError = ref('')

onMounted(async () => {
  try {
    const data = await api.standbyContent()
    if (data?.welcomeText) welcomeText.value = data.welcomeText
    if (data?.subtitle) {
      const lines = data.subtitle.split('\n')
      if (lines[1]) roomLabel.value = lines[1]
      else if (lines[0]) roomLabel.value = lines[0]
    }
  } catch {
    // keep default fallback
  }
})

/**
 * 点击"进入点歌"按钮：注册昵称并跳转到首页。
 * 先将昵称注册到本地 store，再同步到服务端以处理昵称冲突（P2.13）。
 * 注册完成后建立 WebSocket 连接并路由到 home。
 *
 * Handles the "Enter" button: registers the nickname and navigates to home.
 * Registers locally first, then syncs to server to resolve nickname conflicts (P2.13).
 * After registration, establishes the WebSocket connection and routes to home.
 */
async function enter() {
  if (registering.value) return
  user.register(nickname.value)
  registering.value = true
  registrationError.value = ''
  // 同步到服务端并取回去重后的最终昵称（P2.13 昵称冲突显序号）
  // Sync to server and fetch the deduped final nickname (P2.13 nickname conflict → suffix)
  try {
    const res = await api.registerUser(user.clientToken, user.nickname)
    if (!user.markRegistrationSuccess(res)) throw new Error('服务端未返回有效用户身份')
    player.connect()
    router.replace({ name: 'home' })
  } catch (error) {
    user.markRegistrationFailure(error)
    registrationError.value = user.registrationError || '无法连接点歌服务，请重试'
  } finally {
    registering.value = false
  }
}
</script>

<style scoped>
.entry {
  min-height: 100vh;
  display: flex; flex-direction: column; align-items: center;
  padding: 0 32px calc(28px + var(--safe-bottom));
  background: radial-gradient(ellipse 400px 300px at 50% 30%, rgba(240,199,66,.06), transparent),
              linear-gradient(175deg, rgba(20,26,42,.9), var(--bg));
}
.logo {
  width: 88px; height: 88px; border-radius: 24px; margin-top: 22vh;
  background: linear-gradient(135deg, var(--gold), #dba70e);
  display: flex; align-items: center; justify-content: center; font-size: 40px;
  box-shadow: 0 12px 40px rgba(240,199,66,.25);
}
.title { font-size: 28px; font-weight: 800; letter-spacing: -.5px; margin-top: 22px; }
.room {
  font-size: 13px; color: var(--dim); margin-top: 12px;
  background: var(--panel2); border: 1px solid var(--glass-border);
  border-radius: 999px; padding: 5px 13px;
}
.field { width: 100%; margin-top: 42px; }
.field label { font-size: 13px; color: var(--dim); display: block; margin-bottom: 10px; }
.input-wrap {
  background: var(--panel2); border: 1px solid rgba(240,199,66,.2);
  border-radius: 12px; padding: 12px 14px;
}
.input-wrap input {
  width: 100%; background: none; border: none; outline: none;
  color: var(--text); font-size: 15px;
}
.hint { font-size: 11px; color: var(--dim2); margin-top: 10px; }
.enter-btn { width: 100%; padding: 16px; font-size: 17px; border-radius: 14px; margin-top: 28px; }
.enter-btn:disabled { opacity: .6; cursor: wait; }
.registration-error { width: 100%; margin-top: 14px; color: #ffb4ab; font-size: 13px; text-align: center; }
.registration-error .retry { margin-left: 8px; color: var(--gold); background: none; border: 0; text-decoration: underline; }
.foot { margin-top: auto; font-size: 11px; color: var(--dim2); }
</style>
