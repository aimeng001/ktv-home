import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

const coreCoverageFiles = [
  'src/App.vue',
  'src/api/client.js',
  'src/api/sharedProtocolFixture.js',
  'src/api/ws.js',
  'src/composables/useQueuedSongIds.js',
  'src/stores/adminAuth.js',
  'src/stores/player.js',
  'src/stores/user.js',
  'src/views/QueueView.vue',
  'src/views/RemoteView.vue',
  'src/views/latestRequest.js',
  'src/views/admin/AdminLoginView.vue',
  'src/views/admin/AudioLayoutEditor.vue',
  'src/views/admin/audioLayout.js',
  'src/views/admin/libraryMode.js',
  'src/views/admin/librarySource.js',
  'src/views/admin/progressPollGate.js',
  'src/views/admin/scanProgress.js',
  'src/views/admin/settingsState.js'
]
const coreProfile = process.env.KTV_TEST_PROFILE === 'core'

// H5 部署在服务端 /m 路径下；开发时代理 /api 与 /ws 到 Spring Boot
export default defineConfig({
  base: '/m/',
  plugins: [vue()],
  server: {
    port: 5173,
    proxy: {
      '/api': { target: 'http://localhost:8080', changeOrigin: true },
      '/ws': { target: 'ws://localhost:8080', ws: true }
    }
  },
  resolve: {
    // Vitest's Windows worker resolver can be denied realpath access inside node_modules.
    preserveSymlinks: process.env.VITEST === 'true'
  },
  build: {
    // 构建产物输出到服务端 static/m，供 Spring Boot 直接托管（base=/m/）
    outDir: '../backend/src/main/resources/static/m',
    emptyOutDir: true
  },
  test: {
    environment: 'node',
    globals: true,
    coverage: {
      provider: 'v8',
      reporter: ['text-summary', 'cobertura'],
      reportsDirectory: process.env.KTV_COVERAGE_DIR || './coverage',
      include: coreProfile ? coreCoverageFiles : ['src/**/*.{js,vue}'],
      exclude: ['src/**/*.test.js', 'src/__tests__/**', 'src/test/**'],
      ...(coreProfile ? { thresholds: { branches: 100 } } : {})
    }
  }
})
