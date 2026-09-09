/// <reference types="vite/client" />

declare module '*.vue' {
  import type { DefineComponent } from 'vue'
  const component: DefineComponent<{}, {}, any>
  export default component
}

interface ImportMetaEnv {
  readonly VITE_API_BASE_URL?: string
}

interface ImportMeta {
  readonly env: ImportMetaEnv
}

// 构建时由 vite.config.ts define 注入的版本信息(侧栏底部「版本」)
declare const __GIT_SHA__: string
declare const __GIT_DATE__: string
declare const __GIT_BRANCH__: string
declare const __BUILD_TIME__: string
