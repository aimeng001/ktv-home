import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

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
  build: {
    // 构建产物输出到服务端 static/m，供 Spring Boot 直接托管（base=/m/）
    outDir: '../backend/src/main/resources/static/m',
    emptyOutDir: true
  },
  test: {
    environment: 'node',
    globals: true
  }
})
