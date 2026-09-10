import { reactive, readonly } from 'vue'

/**
 * 逐页浮层引导(带我逛一圈)· 全局单例状态。
 *
 * 步骤靠 CSS 选择器锚定目标元素(优先锚侧栏导航项——任何页面都在,最稳),
 * TourOverlay.vue 读这里的状态画遮罩+高亮+气泡。首次进系统自动触发一次(localStorage 记住)。
 */
export interface TourStep {
  /** 目标元素选择器;为空=居中欢迎/收尾卡,不高亮任何元素 */
  target?: string
  title: string
  desc: string
}

const SEEN_KEY = 'vend_tour_seen'

const STEPS: TourStep[] = [
  {
    title: '👋 欢迎用「园区小卖 · 账房」',
    desc: '花 30 秒带你逛一圈,认识每天在哪儿干活。随时可点「跳过」,以后在「新手指引」或右下角「?」还能再看。',
  },
  {
    target: '[data-tour="dashboard"]',
    title: '🏠 经营驾驶舱',
    desc: '每天第一眼看这里:卖得怎么样、钱赚了多少、有没有红灯要处理,一屏看清。',
  },
  {
    target: '[data-tour="purchase"]',
    title: '🚛 采购入库(唯一手工单)',
    desc: '进货到货了,在这里录一张采购入库单。日常只有这一种单要手工录,销售全靠导入。',
  },
  {
    target: '[data-tour="import"]',
    title: '📥 导入中心(每天必做)',
    desc: '把售卖机后台(fanmaiji.top)导出的出货明细 Excel 拖进来,系统自动记销售账。不用手敲数字。',
  },
  {
    target: '[data-tour="inventory"]',
    title: '📦 库存管理',
    desc: '每个商品还剩多少、值多少钱:库存 = 期初 + 入库 − 销售 − 损耗;负库存、库存不足一眼看到。',
  },
  {
    target: '[data-tour="stocktake"]',
    title: '📋 盘点',
    desc: '月末盘一次:账面数自动带出,只填对不上的,确认后自动生成盘盈亏单。',
  },
  {
    target: '[data-tour="reports"]',
    title: '📈 报表',
    desc: '毛利报表(按商品 / 按机器)和进销存汇总;月份选「累计」看期初至今全量,就是旧版台账那张表。',
  },
  {
    target: '[data-tour="guide"]',
    title: '🧭 新手指引 = 你的说明书',
    desc: '这里有「开业向导」(带你把系统跑起来)和「帮助手册」。不懂就回这来,或点任意页右下角的「?」。',
  },
]

const state = reactive({
  active: false,
  stepIndex: 0,
  steps: STEPS,
})

export function useTour() {
  const start = (fromBeginning = true) => {
    if (fromBeginning) state.stepIndex = 0
    state.active = true
  }
  const next = () => {
    if (state.stepIndex < state.steps.length - 1) state.stepIndex++
    else finish()
  }
  const prev = () => {
    if (state.stepIndex > 0) state.stepIndex--
  }
  const finish = () => {
    state.active = false
    try {
      localStorage.setItem(SEEN_KEY, '1')
    } catch {
      /* 隐私模式忽略 */
    }
  }
  /** 首次进系统:没看过就自动开一次 */
  const autoStartOnce = () => {
    let seen = '1'
    try {
      seen = localStorage.getItem(SEEN_KEY) || ''
    } catch {
      seen = '1'
    }
    if (!seen) start(true)
  }
  const isLast = () => state.stepIndex >= state.steps.length - 1

  return { state: readonly(state), start, next, prev, finish, autoStartOnce, isLast }
}
