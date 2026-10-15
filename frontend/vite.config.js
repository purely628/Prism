import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

export default defineConfig({
  plugins: [vue()],
  server: {
    port: 5173,
    proxy: {
      '/api': { target: 'http://localhost:8080', changeOrigin: true },
      '/v1': { target: 'http://localhost:8080', changeOrigin: true }
    }
  },
  build: {
    chunkSizeWarningLimit: 1600
  }
})
