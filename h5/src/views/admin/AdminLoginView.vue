<template>
  <main class="login-page">
    <section class="login-card" aria-labelledby="admin-login-title">
      <p class="eyebrow">HOME KTV</p>
      <h1 id="admin-login-title">管理后台登录</h1>
      <p class="hint">管理功能需要管理员密码；普通点歌和播放不受影响。</p>
      <p v-if="!configured" class="warning" role="alert">服务端尚未设置 KTV_ADMIN_PASSWORD，请先完成部署配置。</p>
      <p v-if="error" class="error" role="alert">{{ error }}</p>
      <form @submit.prevent="login">
        <label for="admin-password">管理员密码</label>
        <input id="admin-password" v-model="password" type="password" autocomplete="current-password" :disabled="loading || !configured" required />
        <button type="submit" :disabled="loading || !configured || !password">
          {{ loading ? '登录中…' : '登录' }}
        </button>
      </form>
    </section>
  </main>
</template>

<script setup>
import { onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { api } from '../../api/client'
import { useAdminAuthStore } from '../../stores/adminAuth'

const route = useRoute()
const router = useRouter()
const auth = useAdminAuthStore()
const password = ref('')
const configured = ref(true)
const loading = ref(false)
const error = ref('')

function redirectPath() {
  const redirect = route.query.redirect
  if (typeof redirect === 'string' && (redirect === '/admin' || redirect.startsWith('/admin/')) && !redirect.startsWith('//')) {
    return redirect
  }
  return '/admin'
}

function goToAdmin() {
  router.replace(redirectPath())
}

onMounted(async () => {
  try {
    const status = await api.adminAuthStatus()
    configured.value = Boolean(status?.configured)
    if (status?.authenticated) goToAdmin()
  } catch (requestError) {
    error.value = requestError?.message || '无法读取管理员配置'
  }
})

async function login() {
  if (!configured.value || !password.value || loading.value) return
  loading.value = true
  error.value = ''
  try {
    const result = await api.adminLogin(password.value)
    auth.setToken(result?.token)
    password.value = ''
    goToAdmin()
  } catch (requestError) {
    error.value = requestError?.message || '登录失败，请检查密码'
  } finally {
    loading.value = false
  }
}
</script>

<style scoped>
.login-page { min-height:100vh; display:grid; place-items:center; padding:24px; background:#f5f7fa; color:#172033; }
.login-card { width:min(390px,100%); padding:30px; border:1px solid #e2e8f0; border-radius:12px; background:#fff; box-shadow:0 16px 45px rgba(15,23,42,.09); }
.eyebrow { margin:0; color:#2563eb; font-size:11px; font-weight:800; letter-spacing:.12em; }
h1 { margin:8px 0 0; font-size:25px; }
.hint { margin:12px 0 22px; color:#64748b; font-size:13px; line-height:1.7; }
label { display:block; margin-bottom:7px; font-size:13px; font-weight:600; }
input { box-sizing:border-box; width:100%; height:42px; padding:0 12px; border:1px solid #cbd5e1; border-radius:7px; font:inherit; }
input:focus { outline:2px solid #bfdbfe; border-color:#2563eb; }
button { width:100%; height:42px; margin-top:16px; border:0; border-radius:7px; background:#2563eb; color:#fff; font:inherit; font-weight:700; cursor:pointer; }
button:disabled { background:#94a3b8; cursor:not-allowed; }
.warning,.error { margin:0 0 16px; padding:10px 12px; border-radius:7px; font-size:12px; line-height:1.6; }
.warning { background:#fff7ed; color:#9a3412; }.error { background:#fef2f2; color:#b91c1c; }
</style>
