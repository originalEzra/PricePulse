import { defineConfig, loadEnv } from 'vite'
import vue from '@vitejs/plugin-vue'

export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, process.cwd(), 'PRICEPULSE_')

  return {
    plugins: [vue()],
    server: {
      host: '127.0.0.1',
      port: 5173,
      strictPort: true,
      proxy: {
        '/api': {
          target: env.PRICEPULSE_BACKEND_URL || 'http://127.0.0.1:18080',
          changeOrigin: true,
        },
      },
    },
  }
})
