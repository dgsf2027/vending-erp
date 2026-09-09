import { execSync } from 'node:child_process'
import { fileURLToPath, URL } from 'node:url'
import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import UnoCSS from 'unocss/vite'

/**
 * 构建时把 git 版本写进页面(侧栏底部「版本 xxxxxxx」,点开对应 GitHub 提交)。
 * 本地/CI 有 .git 时直接读;Docker 构建上下文排除了 .git,由 build-arg 传 VITE_GIT_SHA / VITE_GIT_DATE / VITE_GIT_BRANCH。
 */
function git(cmd: string, fallback: string): string {
  try {
    return execSync(cmd, { stdio: ['ignore', 'pipe', 'ignore'] }).toString().trim() || fallback
  } catch {
    return fallback
  }
}
const GIT_SHA = process.env.VITE_GIT_SHA || git('git rev-parse --short HEAD', 'unknown')
const GIT_DATE = process.env.VITE_GIT_DATE || git('git log -1 --format=%cI', '')
const GIT_BRANCH = process.env.VITE_GIT_BRANCH || git('git rev-parse --abbrev-ref HEAD', '')

// https://vitejs.dev/config/
export default defineConfig({
  plugins: [vue(), UnoCSS()],
  define: {
    __GIT_SHA__: JSON.stringify(GIT_SHA),
    __GIT_DATE__: JSON.stringify(GIT_DATE),
    __GIT_BRANCH__: JSON.stringify(GIT_BRANCH),
    __BUILD_TIME__: JSON.stringify(new Date().toISOString()),
  },
  resolve: {
    alias: {
      '@': fileURLToPath(new URL('./src', import.meta.url)),
    },
  },
  server: {
    port: 5174,
    proxy: {
      // /api → 后端 8081(后端 context-path 就是 /api,前缀原样转发)
      // 多人并行起服时可用 VITE_PROXY_TARGET 覆盖,如 VITE_PROXY_TARGET=http://127.0.0.1:8084 pnpm dev
      '/api': {
        target: process.env.VITE_PROXY_TARGET || 'http://127.0.0.1:8081',
        changeOrigin: true,
      },
    },
  },
})
