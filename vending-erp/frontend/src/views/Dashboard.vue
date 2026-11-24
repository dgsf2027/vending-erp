<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import {
  reportApi,
  type DashboardResp,
  type DashboardScope,
  type DashboardTrendRow,
  type MoneyLightsResp,
  type StockResp,
} from '@/api/report'
import { importsApi, type ImportBatch } from '@/api/imports'
import { pageAliasPending } from '@/api/basedata'
import { taskApi, type TodayViewResp } from '@/api/task'
import { clearanceAlerts, machineSuggestions, purchaseSuggestions } from '@/api/replenish'
import { listTickets } from '@/api/prekit'
import { pageFlows } from '@/api/money'
import { settlementOverview } from '@/api/settlement'
import AnomalyRadar from '@/components/ai/AnomalyRadar.vue'
import { supplierOverview } from '@/api/settle'
import { pdcaApi, type ItemRow } from '@/api/pdca'
import { finreportApi, type AssetSnapshotResp } from '@/api/finreport'
import { aiApi } from '@/api/ai'
import LlmTransparencyBadge from '@/components/ai/LlmTransparencyBadge.vue'
import MockBadge from '@/components/ai/MockBadge.vue'
import { useAppStore } from '@/stores/app'

/**
 * 经营驾驶舱(三维度升级版):
 * 支持「月度总览(默认) / 年度总览 / 经营总览」三大视角切换，
 * 全面呈现销售、毛利、经营净利润、采购投入、订单客单、走势图、机器/热销TOP10/品类/支付渠道，
 * 同时保留今日工作台、红灯待办、实时家底、AI 洞察与异常雷达。
 */
const router = useRouter()
const appStore = useAppStore()

// ---------- 三维度经营总览状态(默认月度总览) ----------
const scope = ref<DashboardScope>('month')
const selectedMonth = ref<string | undefined>(undefined)
const selectedYear = ref<string | undefined>(undefined)
const dash = ref<DashboardResp | null>(null)
const scopeLoading = ref(false)

type TrendMetric = 'salesAmt' | 'grossProfit' | 'purchaseAmt' | 'orderCount'
const trendMetric = ref<TrendMetric>('salesAmt')
const trendView = ref<'chart' | 'table'>('chart')
const hoveredTrend = ref<DashboardTrendRow | null>(null)

const scopeTabs: { key: DashboardScope; label: string; desc: string }[] = [
  { key: 'month', label: '月度总览', desc: '按月看每日走势与环比' },
  { key: 'year', label: '年度总览', desc: '按年看1~12月走势与同比' },
  { key: 'overall', label: '经营总览', desc: '开账至今全期累计' },
]

async function loadDashboard(targetScope: DashboardScope = scope.value, period?: string) {
  scopeLoading.value = true
  hoveredTrend.value = null
  try {
    const res = await reportApi.dashboard(targetScope, period)
    dash.value = res
    scope.value = res.scope
    if (res.scope === 'month') {
      selectedMonth.value = res.period
    } else if (res.scope === 'year') {
      selectedYear.value = res.period
    }
    if (res.dataAsOf) {
      appStore.setDataAsOf(res.dataAsOf.slice(0, 10))
    }
  } finally {
    scopeLoading.value = false
  }
}

function switchScope(next: DashboardScope) {
  if (scope.value === next && dash.value) return
  scope.value = next
  if (next === 'month') {
    loadDashboard('month', selectedMonth.value)
  } else if (next === 'year') {
    loadDashboard('year', selectedYear.value)
  } else {
    loadDashboard('overall')
  }
}

function onPeriodSelect(val: string) {
  if (scope.value === 'month') {
    selectedMonth.value = val
    loadDashboard('month', val)
  } else if (scope.value === 'year') {
    selectedYear.value = val
    loadDashboard('year', val)
  }
}

const canStepPrev = computed(() => {
  if (!dash.value) return false
  if (scope.value === 'month') {
    const idx = dash.value.months.indexOf(dash.value.period)
    return idx >= 0 && idx < dash.value.months.length - 1
  }
  if (scope.value === 'year') {
    const idx = dash.value.years.indexOf(dash.value.period)
    return idx >= 0 && idx < dash.value.years.length - 1
  }
  return false
})

const canStepNext = computed(() => {
  if (!dash.value) return false
  if (scope.value === 'month') {
    return dash.value.months.indexOf(dash.value.period) > 0
  }
  if (scope.value === 'year') {
    return dash.value.years.indexOf(dash.value.period) > 0
  }
  return false
})

function stepPeriod(delta: -1 | 1) {
  if (!dash.value) return
  const list = scope.value === 'month' ? dash.value.months : dash.value.years
  const idx = list.indexOf(dash.value.period)
  if (idx < 0) return
  // list 为倒序(最新在前): 上一期 = idx + 1, 下一期 = idx - 1
  const nextIdx = delta < 0 ? idx + 1 : idx - 1
  if (nextIdx >= 0 && nextIdx < list.length) {
    onPeriodSelect(list[nextIdx])
  }
}

// ---------- 格式化工具 ----------
const money = (v: number | string | null | undefined, digits = 0) =>
  v == null
    ? '—'
    : `${Number(v) < 0 ? '-' : ''}¥${Math.abs(Number(v)).toLocaleString('zh-CN', {
        minimumFractionDigits: digits,
        maximumFractionDigits: digits,
      })}`

const money2 = (v: number | string | null | undefined) => money(v, 2)

const signedMoney2 = (v: number | string | null | undefined) => {
  if (v == null) return '—'
  const n = Number(v)
  return `${n >= 0 ? '+' : '-'}${money2(Math.abs(n))}`
}

const numFmt = (v: number | string | null | undefined) =>
  v == null ? '—' : Number(v).toLocaleString('zh-CN', { maximumFractionDigits: 2 })

const pctFmt = (v: number | string | null | undefined) =>
  v == null ? '—' : `${Number(v).toFixed(1)}%`

const growthText = (v: number | string | null | undefined) => {
  if (v == null) return null
  const n = Number(v)
  return `${n >= 0 ? '▲' : '▼'} ${Math.abs(n).toFixed(1)}%`
}

// ---------- 走势图计算 ----------
const trendMetricMeta: Record<TrendMetric, { label: string; isMoney: boolean; color: string }> = {
  salesAmt: { label: '销售额', isMoney: true, color: '#2b6a4e' },
  grossProfit: { label: '销售毛利', isMoney: true, color: '#3b8b65' },
  purchaseAmt: { label: '采购投入', isMoney: true, color: '#c07a24' },
  orderCount: { label: '订单数', isMoney: false, color: '#36688d' },
}

const trendRows = computed(() => dash.value?.trend ?? [])

function trendVal(row: DashboardTrendRow, metric: TrendMetric = trendMetric.value): number {
  const raw = row[metric]
  return raw == null ? 0 : Number(raw)
}

const trendMaxAbs = computed(() => {
  let max = 0
  for (const r of trendRows.value) {
    const v = Math.abs(trendVal(r))
    if (v > max) max = v
  }
  return max
})

function barHeightPct(row: DashboardTrendRow): number {
  if (trendMaxAbs.value <= 0) return 0
  const v = Math.abs(trendVal(row))
  if (v <= 0) return 0
  return Math.max(4, Math.round((v / trendMaxAbs.value) * 100))
}

const trendTitle = computed(() => {
  if (scope.value === 'month') return '每日经营走势'
  if (scope.value === 'year') return '1~12 月经营走势'
  return '开账至今各月走势'
})

const purchaseSalesRatio = computed(() => {
  const d = dash.value
  if (!d || !d.salesAmt || Number(d.salesAmt) <= 0) return null
  return ((Number(d.purchaseAmt ?? 0) / Number(d.salesAmt)) * 100).toFixed(1)
})

/** 盈利结构条(占已算成本销售额比例) */
const profitStructure = computed(() => {
  const d = dash.value
  if (!d || d.grossProfit == null || !d.costedSalesAmt || Number(d.costedSalesAmt) <= 0) {
    return null
  }
  const base = Number(d.costedSalesAmt)
  const costPct = Math.max(0, Math.min(100, (Number(d.costAmt ?? 0) / base) * 100))
  const expenseTotal = Math.max(
    0,
    Number(d.platformFeeAmt ?? 0) + Number(d.expenseAmt ?? 0) + Number(d.lossAmt ?? 0),
  )
  const expPct = Math.max(0, Math.min(100 - costPct, (expenseTotal / base) * 100))
  const netPct = Math.max(0, 100 - costPct - expPct)
  return {
    costPct: Number(costPct.toFixed(1)),
    expPct: Number(expPct.toFixed(1)),
    netPct: Number(netPct.toFixed(1)),
    expenseTotal,
  }
})

// ---------- 实时家底、红灯与工作台 ----------
const loading = ref(false)
const stock = ref<StockResp | null>(null)
const latestBatch = ref<ImportBatch | null>(null)
const pendingAlias = ref(0)
const priceChangeCount = ref(0)

async function loadSnapshot() {
  loading.value = true
  try {
    const [s, batches, pending] = await Promise.all([
      reportApi.stock(),
      importsApi.batches(1, 1),
      pageAliasPending({ pendingStatus: '待处理', size: 1 }),
    ])
    stock.value = s
    latestBatch.value = batches.records[0] ?? null
    pendingAlias.value = pending.total
    if (s.dataAsOf) appStore.setDataAsOf(s.dataAsOf.slice(0, 10))
    const saleBatches = await importsApi.batches(1, 1, '出货明细')
    const sb = saleBatches.records[0]
    if (sb && sb.batchStatus === '已导入') {
      try {
        priceChangeCount.value = (await importsApi.priceChanges(sb.id)).length
      } catch {
        priceChangeCount.value = 0
      }
    }
  } finally {
    loading.value = false
  }
}

onMounted(() => {
  loadDashboard('month')
  loadSnapshot()
})

const activeSkuCount = computed(() => stock.value?.rows.filter((r) => Number(r.totalQty) > 0).length ?? 0)
const skuCount = computed(() => stock.value?.rows.length ?? 0)

const replenishPending = ref<number | null>(null)
const replenishUrgent = ref(0)
const prekitOverdue = ref(0)
const clearanceCount = ref(0)
onMounted(async () => {
  try {
    const [m, p, tickets, alerts] = await Promise.all([
      machineSuggestions(),
      purchaseSuggestions(),
      listTickets(),
      clearanceAlerts(),
    ])
    const rows = [...m.rows, ...p.rows].filter((r) => r.planStatus === '建议')
    replenishPending.value = rows.length
    replenishUrgent.value = rows.filter((r) => {
      try {
        const u = String(JSON.parse(r.formulaJson ?? '{}')['紧急度'] ?? '')
        return u === '缺货' || u === '急'
      } catch {
        return false
      }
    }).length
    prekitOverdue.value = tickets.filter((t) => t.overdue).length
    clearanceCount.value = alerts.length
  } catch {
    replenishPending.value = null
  }
})

const todayTasks = ref<TodayViewResp | null>(null)
onMounted(async () => {
  try {
    todayTasks.value = await taskApi.today()
  } catch {
    todayTasks.value = null
  }
})
const taskChip = (s: string, dt?: string | null) =>
  s === '已完成' ? (dt === '系统校验' ? '✅' : '🟡') : s === '逾期' ? '🔴' : '⬜'

const pdcaDue = ref<ItemRow[]>([])
onMounted(async () => {
  try {
    pdcaDue.value = await pdcaApi.dueWeek()
  } catch {
    pdcaDue.value = []
  }
})

const today = new Date()
const weekday = ['日', '一', '二', '三', '四', '五', '六'][today.getDay()]
const todayStr = `${today.getFullYear()}-${String(today.getMonth() + 1).padStart(2, '0')}-${String(today.getDate()).padStart(2, '0')}`

const moneyMode = ref<string | null>(null)
const moneyPending = ref<number | null>(null)
const todayFlowCount = ref<number | null>(null)
const overduePayable = ref(0)
const moneyLights = ref<MoneyLightsResp | null>(null)
onMounted(async () => {
  try {
    const period = todayStr.slice(0, 7)
    const [flows, ov, sup, lights] = await Promise.all([
      pageFlows({ current: 1, size: 200, fromPeriod: period, toPeriod: period }),
      settlementOverview(),
      supplierOverview(),
      reportApi.moneyLights(),
    ])
    todayFlowCount.value = flows.records.filter((f) => (f.bizTime ?? '').slice(0, 10) === todayStr).length
    moneyMode.value = ov.mode
    moneyPending.value = ov.mode === 'PLATFORM' ? Number(ov.pendingBalance ?? 0) : null
    overduePayable.value = sup.filter((s) => s.overdue).length
    moneyLights.value = lights
  } catch {
    todayFlowCount.value = null
  }
})

function gotoDiffPending() {
  const l = moneyLights.value
  if (l && Number(l.diffBillCount) + Number(l.diffPaymentCount) > 0) router.push('/suppliers')
  else router.push('/money')
}

const redCount = computed(
  () =>
    (stock.value?.negativeCount ?? 0) +
    (stock.value?.lowStockCount ?? 0) +
    pendingAlias.value +
    priceChangeCount.value +
    prekitOverdue.value +
    clearanceCount.value +
    (moneyLights.value?.redTotal ?? 0),
)

const assets = ref<AssetSnapshotResp | null>(null)
onMounted(async () => {
  try {
    assets.value = await finreportApi.assets()
  } catch {
    assets.value = null
  }
})

const netAssetMom = computed(() => {
  const a = assets.value
  if (!a || a.prevNetAsset == null) return null
  return Number((Number(a.netAsset) - Number(a.prevNetAsset)).toFixed(2))
})

const insight = ref<{ title: string; text: string; llmCallId: number; model: string } | null>(null)
const insightLoading = ref(false)
onMounted(async () => {
  insightLoading.value = true
  try {
    const r = await aiApi.insight('asset-mom')
    insight.value = {
      title: String(r.title),
      text: String(r.text),
      llmCallId: Number(r.llmCallId),
      model: r.model != null ? String(r.model) : '',
    }
  } catch {
    insight.value = null
  } finally {
    insightLoading.value = false
  }
})
</script>

<template>
  <div class="ledger-page" v-loading="loading">
    <div class="ledger-crumb">园区小卖 ERP / 首页</div>
    <div class="ledger-title">
      <h2>经营驾驶舱</h2>
      <span class="sub">{{ todayStr }} 星期{{ weekday }}</span>
    </div>
    <p class="ledger-note">
      一眼看清:<b>卖得怎么样 → 钱赚了多少 → 成本费用去哪了 → 现在该干什么</b>
      <span v-if="latestBatch" class="mini" style="margin-left: 6px">
        最近导入:{{ latestBatch.fileType }} · {{ (latestBatch.createTime ?? '').replace('T', ' ').slice(5, 16) }} ✓
      </span>
    </p>

    <!-- 三维度切换栏(默认月度总览) -->
    <div class="ledger-card scope-bar">
      <div class="scope-tabs" role="tablist">
        <button
          v-for="tab in scopeTabs"
          :key="tab.key"
          type="button"
          class="scope-tab"
          :class="{ active: scope === tab.key }"
          @click="switchScope(tab.key)"
        >
          <span class="tab-name">{{ tab.label }}</span>
        </button>
      </div>

      <div class="scope-controls">
        <template v-if="scope === 'month'">
          <el-button size="small" :disabled="!canStepPrev || scopeLoading" @click="stepPeriod(-1)">‹</el-button>
          <el-select
            :model-value="dash?.period"
            size="small"
            style="width: 132px"
            :disabled="scopeLoading || !(dash?.months?.length)"
            placeholder="选择月份"
            @change="onPeriodSelect"
          >
            <el-option v-for="m in dash?.months ?? []" :key="m" :label="m" :value="m" />
          </el-select>
          <el-button size="small" :disabled="!canStepNext || scopeLoading" @click="stepPeriod(1)">›</el-button>
        </template>

        <template v-else-if="scope === 'year'">
          <el-button size="small" :disabled="!canStepPrev || scopeLoading" @click="stepPeriod(-1)">‹</el-button>
          <el-select
            :model-value="dash?.period"
            size="small"
            style="width: 120px"
            :disabled="scopeLoading || !(dash?.years?.length)"
            placeholder="选择年份"
            @change="onPeriodSelect"
          >
            <el-option v-for="y in dash?.years ?? []" :key="y" :label="`${y} 年`" :value="y" />
          </el-select>
          <el-button size="small" :disabled="!canStepNext || scopeLoading" @click="stepPeriod(1)">›</el-button>
        </template>

        <template v-else>
          <span class="chip c-green">开账至今全期累计</span>
          <span v-if="dash?.firstBizDate && dash?.lastBizDate" class="mini num">
            {{ dash.firstBizDate }} ~ {{ dash.lastBizDate }}
          </span>
        </template>

        <span v-if="dash" class="scope-meta mini">
          有售 {{ dash.activeDays }} 天 · 活跃机器 {{ dash.activeMachineCount }}/{{ dash.totalMachineCount }} 台 · 动销 {{ dash.soldSkuCount }} 品
        </span>
      </div>
    </div>

    <!-- 红灯待办(仅在有待办时前置提醒;清零则在实时运营区展示绿灯) -->
    <div v-if="redCount" class="alerts">
      <div v-if="stock?.negativeCount" class="alert a-red" @click="router.push('/inventory')">
        🚨 <b>{{ stock.negativeCount }} 个 SKU 负库存</b> —— 可能漏录采购单,先补录再谈毛利
        <span class="go">去库存页处理 →</span>
      </div>
      <div v-if="stock?.lowStockCount" class="alert a-amber" @click="router.push('/inventory')">
        ⚠️ <b>{{ stock.lowStockCount }} 个商品库存不足</b>(结存 ≤ {{ stock.lowStockThreshold }} 件)—— 请及时补货
        <span class="go">去库存页查看 →</span>
      </div>
      <div v-if="pendingAlias" class="alert a-amber" @click="router.push('/import')">
        ⚠️ <b>{{ pendingAlias }} 个新商品待绑别名</b> —— 不绑毛利算不准
        <span class="go">去绑定 →</span>
      </div>
      <div v-if="priceChangeCount" class="alert a-amber" @click="router.push('/import')">
        💲 <b>最近批次 {{ priceChangeCount }} 条改价待确认</b>(成交价 ≠ 档案参考价)
        <span class="go">去确认 →</span>
      </div>
      <div v-if="prekitOverdue" class="alert a-amber" @click="router.push('/outbound')">
        🧺 <b>{{ prekitOverdue }} 张配货单超窗未核销</b> —— 过了 ±48h 窗口还没等到后台补货记录
        <span class="go">去核销 →</span>
      </div>
      <div v-if="clearanceCount" class="alert a-amber" @click="router.push('/replenish')">
        🏷 <b>{{ clearanceCount }} 个清仓商品滞留超 30 天</b> —— 仓库还压着货,请三选一处理
        <span class="go">去处理 →</span>
      </div>
      <div
        v-if="moneyLights?.overduePayableCount"
        class="alert a-red"
        data-block="light-overdue-payable"
        @click="router.push('/suppliers')"
      >
        ⏰ <b>{{ moneyLights.overduePayableCount }} 家供应商货款逾期</b>(最长 {{ moneyLights.maxOverdueDays }} 天)—— 该结的钱没结,先去付款
        <span class="go">去供应商页处理 →</span>
      </div>
      <div
        v-if="moneyLights?.diffTotal"
        class="alert a-red"
        data-block="light-diff-pending"
        @click="gotoDiffPending()"
      >
        ⚖️ <b>{{ moneyLights.diffTotal }} 张单据差异挂起</b>(结算单 {{ moneyLights.diffBillCount }} · 付款 {{ moneyLights.diffPaymentCount }} · 平台结算 {{ moneyLights.diffSettlementCount }})
        <span class="go">去处理 →</span>
      </div>
      <div
        v-if="moneyLights?.claimOverdueCount"
        class="alert a-red"
        data-block="light-claim-overdue"
        @click="router.push('/money')"
      >
        🧾 <b>{{ moneyLights.claimOverdueCount }} 笔索赔超 {{ moneyLights.claimThresholdDays }} 天未到账</b>(最久 {{ moneyLights.claimOldestDays }} 天)
        <span class="go">去钱账页跟进 →</span>
      </div>
      <div
        v-if="moneyLights?.settleOverdue"
        class="alert a-red"
        data-block="light-settle-overdue"
        @click="router.push('/money')"
      >
        🏦 <b>在途货款超期未结算</b>:最早一笔已挂 {{ moneyLights.settleOldestDays }} 天,在途 ¥{{ Number(moneyLights.settlePendingBalance ?? 0).toLocaleString('zh-CN', { minimumFractionDigits: 2 }) }}
        <span class="go">去钱账页核对 →</span>
      </div>
    </div>

    <!-- =============== 维度经营数据核心区(随 月度/年度/经营总览 动态切换) =============== -->
    <div v-loading="scopeLoading" class="scope-section">
      <!-- 6 大经营核心指标 -->
      <div class="stat-grid">
        <!-- 1. 销售额 -->
        <div class="ledger-card stat stat-clickable" @click="router.push('/reports')">
          <div class="lb">
            <span>💰 销售额 <span class="chip c-gray">{{ dash?.periodLabel ?? '—' }}</span></span>
            <span
              v-if="dash?.compareLabel && dash?.salesGrowthPct != null"
              class="growth-pill"
              :class="Number(dash.salesGrowthPct) >= 0 ? 'g-up' : 'g-down'"
            >
              {{ dash.compareLabel }} {{ growthText(dash.salesGrowthPct) }}
            </span>
          </div>
          <div class="vv num">
            {{ money2(dash?.salesAmt) }}
          </div>
          <div class="sub-metrics">
            <span class="mini">日均 <b>{{ money(dash?.dailyAvgSales) }}</b></span>
            <span class="mini" v-if="dash?.refundCount"> · 退单 {{ dash.refundCount }} 笔({{ money2(dash.refundAmt) }})</span>
            <span class="mini" v-else> · 无退款</span>
          </div>
          <div class="human">
            人话:正常实收减退款(测试单不计);{{ dash?.compareLabel && dash?.prevSalesAmt != null ? `${dash.compareLabel.replace('较', '')}同期 ${money(dash.prevSalesAmt)}` : '点卡进报表页看逐品明细' }}。
          </div>
        </div>

        <!-- 2. 销售毛利 & 毛利率 -->
        <div class="ledger-card stat stat-clickable" @click="router.push('/reports')">
          <div class="lb">
            <span>📈 销售毛利 <span class="chip c-green" v-if="dash?.marginPct != null">毛利率 {{ pctFmt(dash.marginPct) }}</span></span>
            <span
              v-if="dash?.compareLabel && dash?.profitGrowthPct != null"
              class="growth-pill"
              :class="Number(dash.profitGrowthPct) >= 0 ? 'g-up' : 'g-down'"
            >
              {{ dash.compareLabel }} {{ growthText(dash.profitGrowthPct) }}
            </span>
          </div>
          <div class="vv num" :style="{ color: dash?.grossProfit != null && Number(dash.grossProfit) < 0 ? 'var(--red)' : 'var(--green)' }">
            {{ money2(dash?.grossProfit) }}
          </div>
          <div class="sub-metrics">
            <span class="mini">销售成本 <b>{{ money2(dash?.costAmt) }}</b></span>
            <span v-if="dash?.noCostSkuCount" class="mini" style="color: var(--amber)">
              · ⚠️ {{ dash.noCostSkuCount }} 品成本待补
            </span>
          </div>
          <div class="human">
            人话:每卖 100 块毛利赚 {{ dash?.marginPct != null ? Math.round(Number(dash.marginPct)) : '—' }} 块({{ scope === 'overall' ? '累计加权成本' : '按月加权成本' }}口径)。
          </div>
        </div>

        <!-- 3. 经营净利润 -->
        <div class="ledger-card stat stat-clickable" @click="router.push('/monthly-report')">
          <div class="lb">
            <span>🏆 经营净利润</span>
            <span class="chip c-gray">净利率 {{ pctFmt(dash?.operatingMarginPct) }}</span>
          </div>
          <div
            class="vv num"
            :style="{ color: dash?.operatingProfit == null ? 'inherit' : Number(dash.operatingProfit) >= 0 ? 'var(--green-deep, #1c3d2f)' : 'var(--red)' }"
          >
            {{ money2(dash?.operatingProfit) }}
          </div>
          <div class="sub-metrics">
            <span class="mini">手续费 {{ money(dash?.platformFeeAmt) }} · 杂费 {{ money(dash?.expenseAmt) }} · 损耗 {{ money(dash?.lossAmt) }}</span>
          </div>
          <div class="human">
            人话:毛利扣完手续费、杂费、损耗再加赔付/补贴({{ money(dash?.otherIncomeAmt) }})后真正落袋的利润。
          </div>
        </div>

        <!-- 4. 采购入库投入 -->
        <div class="ledger-card stat stat-clickable" @click="router.push('/purchase')">
          <div class="lb">
            <span>🚛 采购入库投入</span>
            <span
              v-if="dash?.compareLabel && dash?.purchaseGrowthPct != null"
              class="growth-pill"
              :class="Number(dash.purchaseGrowthPct) <= 0 ? 'g-up' : 'g-neutral'"
            >
              {{ dash.compareLabel }} {{ growthText(dash.purchaseGrowthPct) }}
            </span>
          </div>
          <div class="vv num">{{ money2(dash?.purchaseAmt) }}</div>
          <div class="sub-metrics">
            <span class="mini">
              入库 <b>{{ dash?.purchaseDocCount ?? 0 }}</b> 单 · <b>{{ numFmt(dash?.purchaseQty) }}</b> 件
              <template v-if="purchaseSalesRatio"> · 采销比 {{ purchaseSalesRatio }}%</template>
            </span>
          </div>
          <div class="human">
            人话:期内已确认采购入库与期初建账金额;采销比高于 100% 说明在备货扩库存。
          </div>
        </div>

        <!-- 5. 销量与订单表现 -->
        <div class="ledger-card stat stat-clickable" @click="router.push('/bi')">
          <div class="lb">
            <span>🧾 销量与订单</span>
            <span
              v-if="dash?.compareLabel && dash?.orderGrowthPct != null"
              class="growth-pill"
              :class="Number(dash.orderGrowthPct) >= 0 ? 'g-up' : 'g-down'"
            >
              订单 {{ growthText(dash.orderGrowthPct) }}
            </span>
          </div>
          <div class="vv num">
            {{ numFmt(dash?.salesQty) }}
            <span class="unit">件</span>
            <span class="sub-val">/ {{ numFmt(dash?.orderCount) }} 单</span>
          </div>
          <div class="sub-metrics">
            <span class="mini">客单价 <b>{{ money2(dash?.avgOrderAmt) }}</b> · 动销 {{ dash?.soldSkuCount ?? 0 }} 品</span>
            <span v-if="dash?.exchangeCount" class="mini"> · 兑换 {{ dash.exchangeCount }} 笔</span>
          </div>
          <div class="human">
            人话:同订单多商品自动合并计数;活跃机器 {{ dash?.activeMachineCount ?? 0 }}/{{ dash?.totalMachineCount ?? 0 }} 台。
          </div>
        </div>

        <!-- 6. 盈利结构与损耗净额 -->
        <div class="ledger-card stat">
          <div class="lb">
            <span>📊 收入去向拆解</span>
            <span class="chip c-gray">占已计成本销售</span>
          </div>
          <div v-if="profitStructure" class="struct-box">
            <div class="struct-bar">
              <div class="sb-cost" :style="{ width: profitStructure.costPct + '%' }" :title="`货品成本 ${profitStructure.costPct}%`" />
              <div class="sb-exp" :style="{ width: profitStructure.expPct + '%' }" :title="`费用与损耗 ${profitStructure.expPct}%`" />
              <div class="sb-net" :style="{ width: profitStructure.netPct + '%' }" :title="`利润留存 ${profitStructure.netPct}%`" />
            </div>
            <div class="struct-legend">
              <span><i class="dot d-cost" />货本 {{ profitStructure.costPct }}%</span>
              <span><i class="dot d-exp" />费损 {{ profitStructure.expPct }}%</span>
              <span><i class="dot d-net" />留存 {{ profitStructure.netPct }}%</span>
            </div>
          </div>
          <div v-else class="vv num" style="font-size: 20px; color: var(--ink2)">暂无成本拆解</div>
          <div class="sub-metrics">
            <span class="mini">净损耗 {{ money2(dash?.netLossAmt) }} (已获赔 {{ money2(dash?.compensatedAmt) }})</span>
          </div>
          <div class="human">
            人话:一眼看出钱是花在进货成本、平台费用损耗，还是真正留在自己兜里。
          </div>
        </div>
      </div>

      <!-- 走势图 + 明细表 -->
      <div class="ledger-card trend-card">
        <div class="trend-head">
          <div>
            <h3 style="margin: 0">
              📈 {{ trendTitle }}
              <span class="hint">{{ dash?.periodLabel ?? '' }}</span>
            </h3>
          </div>
          <div class="trend-actions">
            <div class="pill-group">
              <button
                v-for="(meta, mKey) in trendMetricMeta"
                :key="mKey"
                type="button"
                class="pill-btn"
                :class="{ active: trendMetric === mKey }"
                @click="trendMetric = mKey as TrendMetric"
              >
                {{ meta.label }}
              </button>
            </div>
            <div class="pill-group">
              <button
                type="button"
                class="pill-btn"
                :class="{ active: trendView === 'chart' }"
                @click="trendView = 'chart'"
              >
                图表
              </button>
              <button
                type="button"
                class="pill-btn"
                :class="{ active: trendView === 'table' }"
                @click="trendView = 'table'"
              >
                明细表
              </button>
            </div>
          </div>
        </div>

        <!-- 悬停节点详情条 -->
        <div v-if="trendView === 'chart' && trendRows.length" class="trend-hover-bar">
          <template v-if="hoveredTrend">
            <b>{{ hoveredTrend.key }}</b>
            <span>销售额 <b class="num">{{ money2(hoveredTrend.salesAmt) }}</b></span>
            <span>毛利 <b class="num">{{ money2(hoveredTrend.grossProfit) }}</b> ({{ pctFmt(hoveredTrend.marginPct) }})</span>
            <span>采购 <b class="num">{{ money2(hoveredTrend.purchaseAmt) }}</b></span>
            <span>销量 <b class="num">{{ numFmt(hoveredTrend.salesQty) }}</b> 件 · {{ hoveredTrend.orderCount }} 单</span>
          </template>
          <span v-else class="mini">鼠标移到任意柱子上查看该{{ scope === 'month' ? '日' : '月' }}销售、毛利、采购与订单明细</span>
        </div>

        <!-- 柱状图视图 -->
        <div v-if="trendView === 'chart'">
          <div v-if="trendRows.length" class="bar-chart" :class="{ dense: trendRows.length > 16 }">
            <div
              v-for="row in trendRows"
              :key="row.key"
              class="bar-col"
              :class="{ active: hoveredTrend?.key === row.key }"
              @mouseenter="hoveredTrend = row"
              @click="hoveredTrend = row"
            >
              <div class="bar-val num" v-if="trendRows.length <= 14 && trendVal(row) !== 0">
                {{ trendMetricMeta[trendMetric].isMoney ? Math.round(trendVal(row)) : trendVal(row) }}
              </div>
              <div class="bar-track">
                <div
                  class="bar-fill"
                  :style="{
                    height: barHeightPct(row) + '%',
                    background: trendVal(row) < 0 ? 'var(--red)' : trendMetricMeta[trendMetric].color,
                  }"
                />
              </div>
              <div class="bar-lbl num">{{ scope === 'month' ? row.label.slice(3) : row.label }}</div>
            </div>
          </div>
          <el-empty v-else description="该区间暂无经营走势数据" :image-size="60" />
        </div>

        <!-- 走势明细表视图 -->
        <div v-else class="table-scroll">
          <table v-if="trendRows.length" class="ltab">
            <thead>
              <tr>
                <th>{{ scope === 'month' ? '日期' : '月份' }}</th>
                <th class="num">销售额</th>
                <th class="num">销量</th>
                <th class="num">订单数</th>
                <th class="num">毛利</th>
                <th class="num">毛利率</th>
                <th class="num">采购投入</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="row in trendRows" :key="row.key">
                <td class="num">{{ row.key }}</td>
                <td class="num"><b>{{ money2(row.salesAmt) }}</b></td>
                <td class="num">{{ numFmt(row.salesQty) }}</td>
                <td class="num">{{ row.orderCount }}</td>
                <td class="num" :style="{ color: row.grossProfit != null && Number(row.grossProfit) < 0 ? 'var(--red)' : 'var(--green)' }">
                  {{ money2(row.grossProfit) }}
                </td>
                <td class="num">{{ pctFmt(row.marginPct) }}</td>
                <td class="num">{{ money2(row.purchaseAmt) }}</td>
              </tr>
            </tbody>
          </table>
          <el-empty v-else description="该区间暂无经营明细" :image-size="60" />
        </div>
      </div>

      <!-- 机器排行 + 热销 TOP 10 -->
      <div class="two-col" style="margin-bottom: 16px">
        <div class="ledger-card" style="margin-bottom: 0">
          <h3>
            机器 · 谁最能卖
            <span class="hint">{{ dash?.periodLabel ?? '' }} · 点卡进机器详情</span>
          </h3>
          <div v-if="dash?.machines?.length" class="mgrid">
            <div
              v-for="m in dash.machines"
              :key="m.machineId"
              class="mcard"
              @click="router.push(`/machines/${m.machineId}`)"
            >
              <div class="mcard-top">
                <h4>{{ m.name }} ↗</h4>
                <span v-if="m.sharePct != null" class="chip c-green">占 {{ pctFmt(m.sharePct) }}</span>
              </div>
              <div class="id num">{{ m.code }} · {{ m.orderCount }} 单 · {{ numFmt(m.salesQty) }} 件</div>
              <div class="share-track" v-if="m.sharePct != null">
                <div class="share-fill" :style="{ width: Math.min(100, Math.max(0, Number(m.sharePct))) + '%' }" />
              </div>
              <div class="mrow"><span>销售额</span><b class="num">{{ money2(m.salesAmt) }}</b></div>
              <div class="mrow">
                <span>毛利 / 毛利率</span>
                <b class="num" :style="{ color: m.grossProfit != null && Number(m.grossProfit) < 0 ? 'var(--red)' : 'var(--green)' }">
                  {{ money2(m.grossProfit) }} <small class="mini">({{ pctFmt(m.marginPct) }})</small>
                </b>
              </div>
              <div class="mrow mini">
                <span>日均销售</span>
                <span class="num">{{ money(m.dailyAvgAmt) }}/天</span>
              </div>
              <div class="mrow mini" v-if="m.noCostSkuCount">
                <span style="color: var(--amber)">⚠️ {{ m.noCostSkuCount }} 个 SKU 成本待补</span>
              </div>
            </div>
          </div>
          <el-empty v-else description="该区间尚无机器销售数据" :image-size="60" />
        </div>

        <div class="ledger-card" style="margin-bottom: 0">
          <h3>
            热销商品 TOP 10
            <span class="hint">{{ dash?.periodLabel ?? '' }} · 按销售额</span>
          </h3>
          <div v-if="dash?.topProducts?.length" class="table-scroll">
            <table class="ltab">
              <thead>
                <tr>
                  <th>#</th>
                  <th>商品</th>
                  <th class="num">销量</th>
                  <th class="num">销售额</th>
                  <th class="num">毛利</th>
                  <th class="num">毛利率</th>
                </tr>
              </thead>
              <tbody>
                <tr v-for="(r, i) in dash.topProducts" :key="r.productId ?? `unbound-${i}`">
                  <td class="num">{{ i + 1 }}</td>
                  <td>
                    <a
                      v-if="r.productId != null"
                      class="plink"
                      @click="router.push(`/products/${r.productId}`)"
                    >
                      {{ r.name }} ↗
                    </a>
                    <span v-else>{{ r.name }}</span>
                    <div class="mini">{{ r.category }} · 占 {{ pctFmt(r.sharePct) }}</div>
                  </td>
                  <td class="num">{{ numFmt(r.salesQty) }}</td>
                  <td class="num"><b>{{ money2(r.salesAmt) }}</b></td>
                  <td class="num">{{ r.hasCost ? money2(r.grossProfit) : '—' }}</td>
                  <td class="num">{{ r.hasCost ? pctFmt(r.marginPct) : '待补' }}</td>
                </tr>
              </tbody>
            </table>
          </div>
          <el-empty v-else description="该区间尚无商品销售数据" :image-size="60" />
          <p class="mini" style="margin-top: 10px">
            📈 <a class="plink" @click="router.push('/reports')">查看全部商品毛利表 →</a>
            <span style="margin: 0 8px; color: var(--line2)">|</span>
            <a class="plink" @click="router.push('/bi')">BI 经营分析 →</a>
          </p>
        </div>
      </div>

      <!-- 品类结构 + 支付渠道与利润构成 -->
      <div class="two-col" style="margin-bottom: 16px">
        <div class="ledger-card" style="margin-bottom: 0">
          <h3>
            品类销售与毛利结构
            <span class="hint">{{ dash?.periodLabel ?? '' }} · 哪类货最赚钱</span>
          </h3>
          <div v-if="dash?.categories?.length" class="cat-list">
            <div v-for="c in dash.categories" :key="c.category" class="cat-item">
              <div class="cat-row">
                <div>
                  <b>{{ c.category }}</b>
                  <span class="mini" style="margin-left: 6px">{{ c.skuCount }} 品 · {{ numFmt(c.salesQty) }} 件</span>
                </div>
                <div class="num">
                  <b>{{ money2(c.salesAmt) }}</b>
                  <span class="mini" style="margin-left: 6px">({{ pctFmt(c.sharePct) }})</span>
                </div>
              </div>
              <div class="share-track">
                <div class="share-fill" :style="{ width: Math.min(100, Math.max(0, Number(c.sharePct ?? 0))) + '%' }" />
              </div>
              <div class="cat-sub mini">
                <span>毛利 <b class="num">{{ money2(c.grossProfit) }}</b></span>
                <span>毛利率 <b class="num">{{ pctFmt(c.marginPct) }}</b></span>
              </div>
            </div>
          </div>
          <el-empty v-else description="该区间暂无品类数据" :image-size="60" />
        </div>

        <div class="ledger-card" style="margin-bottom: 0">
          <h3>
            支付渠道与利润构成
            <span class="hint">{{ dash?.periodLabel ?? '' }}</span>
          </h3>

          <div class="sub-block-title">支付渠道占比</div>
          <div v-if="dash?.payMethods?.length" class="pay-list">
            <div v-for="p in dash.payMethods" :key="p.method" class="pay-item">
              <div class="cat-row">
                <span><b>{{ p.method }}</b> <span class="mini">({{ p.orderCount }} 单)</span></span>
                <span class="num"><b>{{ money2(p.salesAmt) }}</b> · {{ pctFmt(p.sharePct) }}</span>
              </div>
              <div class="share-track">
                <div class="share-fill pay-fill" :style="{ width: Math.min(100, Math.max(0, Number(p.sharePct ?? 0))) + '%' }" />
              </div>
            </div>
          </div>
          <p v-else class="mini" style="margin: 6px 0 14px">该区间暂无支付渠道数据</p>

          <div class="sub-block-title" style="margin-top: 16px">经营利润构成明细</div>
          <table class="ltab pl-mini">
            <tbody>
              <tr>
                <td>销售收入(实收净额)</td>
                <td class="num"><b>{{ money2(dash?.salesAmt) }}</b></td>
              </tr>
              <tr>
                <td>减:销售成本(加权结转)</td>
                <td class="num">{{ dash?.costAmt != null ? signedMoney2(-Number(dash.costAmt)) : '—' }}</td>
              </tr>
              <tr class="pl-sub">
                <td><b>= 销售毛利</b></td>
                <td class="num"><b>{{ money2(dash?.grossProfit) }}</b></td>
              </tr>
              <tr>
                <td>减:平台手续费 / 杂费支出</td>
                <td class="num">{{ signedMoney2(-(Number(dash?.platformFeeAmt ?? 0) + Number(dash?.expenseAmt ?? 0))) }}</td>
              </tr>
              <tr>
                <td>减:盘亏与报损成本(净损耗 {{ money2(dash?.netLossAmt) }})</td>
                <td class="num">{{ signedMoney2(-Number(dash?.lossAmt ?? 0)) }}</td>
              </tr>
              <tr>
                <td>加:赔付/补贴/平台外收入 ± 成本调整</td>
                <td class="num">{{ signedMoney2(Number(dash?.otherIncomeAmt ?? 0) + Number(dash?.costAdjustAmt ?? 0)) }}</td>
              </tr>
              <tr class="pl-total">
                <td><b>= 经营净利润</b></td>
                <td class="num" :style="{ color: dash?.operatingProfit != null && Number(dash.operatingProfit) < 0 ? 'var(--red)' : 'var(--green)' }">
                  <b>{{ money2(dash?.operatingProfit) }}</b>
                </td>
              </tr>
            </tbody>
          </table>
        </div>
      </div>
    </div>

    <!-- =============== 实时家底与运营状态(当前实时截面) =============== -->
    <div class="section-divider">
      <span>当前实时家底与运营工作台</span>
      <span class="mini">以下指标为截至此刻的实时库存、资金与待办，不随上方时间维度切换</span>
    </div>

    <!-- 今日工作台 -->
    <div class="ledger-card work-strip" style="cursor: pointer" @click="router.push('/tasks')">
      <h3 style="color: #fff; margin: 0">📋 今日工作台</h3>
      <template v-if="todayTasks">
        <span
          v-for="t in todayTasks.instances.slice(0, 3)"
          :key="t.id"
          class="chip"
          style="background: rgba(255, 255, 255, 0.15); color: #e8efe9"
        >
          {{ taskChip(t.instanceStatus, t.doneType) }} {{ t.taskName }}
          <b v-if="t.assigneeUserName" style="color: #ffd27a">· {{ t.assigneeUserName }}</b>
        </span>
        <span v-if="!todayTasks.instances.length" class="chip" style="background: rgba(255, 255, 255, 0.15); color: #c3d4c8">
          今日无到期任务
        </span>
        <span v-if="todayTasks.overdue.length" class="chip" style="background: rgba(192, 57, 43, 0.35); color: #ffd0c7">
          🔴 {{ todayTasks.overdue.length }} 个逾期
        </span>
        <span class="mini" style="color: #9db8a8; margin-left: auto">
          待办 {{ todayTasks.todoCount }} · 已完成 {{ todayTasks.doneCount }} · 任务日历 →
        </span>
      </template>
      <span v-else class="mini" style="color: #9db8a8; margin-left: auto">
        任务绑角色、角色绑人;完成有系统校验 · 任务日历 →
      </span>
    </div>

    <!-- 本周到期待验证改进任务 PDCA -->
    <div
      v-if="pdcaDue.length"
      class="ledger-card"
      style="cursor: pointer; border-left: 3px solid var(--amber)"
      @click="router.push('/pdca')"
    >
      <h3 style="margin: 0">
        🔄 本周到期待验证改进任务
        <span class="hint">改进有没有见效,到期系统自动回查</span>
        <span class="chip c-amber" style="margin-left: 8px">{{ pdcaDue.length }} 条</span>
      </h3>
      <div style="display: flex; gap: 8px; flex-wrap: wrap; margin-top: 8px">
        <span v-for="d in pdcaDue.slice(0, 5)" :key="d.id" class="chip" :class="d.due ? 'c-red' : 'c-amber'">
          {{ d.due ? '⏰' : '📅' }} {{ d.sourceScene }} · {{ d.verifyMetric }} · {{ d.verifyDate }}
        </span>
        <span class="mini" style="margin-left: auto; color: var(--ink2)">去改进循环页回查 →</span>
      </div>
    </div>

    <div v-if="!redCount" class="alerts">
      <div class="alert a-blue">
        ✅ 红灯清零:无负库存 · 无库存不足 · 无待绑别名 · 无改价待确认 · 无超窗配货单 · 无清仓残余 · 无逾期应付 · 无差异挂起 · 无索赔超期 · 无在途超期 —— 台账健康,钱账干净
      </div>
    </div>

    <!-- 实时家底 4 卡 -->
    <div class="stat-grid live-grid">
      <div class="ledger-card stat stat-clickable" @click="router.push('/inventory')">
        <div class="lb">📦 压在货上的钱 <span class="chip c-gray">按加权成本</span></div>
        <div class="vv num">{{ money(stock?.totalAmount) }}</div>
        <span class="mini">仓库 {{ money(stock?.warehouseAmount) }} + 机器里 {{ money(stock?.machineAmount) }} · 在库 {{ activeSkuCount }}/{{ skuCount }} 品</span>
        <div class="human">人话:实时库存家底,与库存页/资产家底同一个数。</div>
      </div>

      <div class="ledger-card stat stat-clickable" @click="router.push('/inventory')">
        <div class="lb">🚨 库存预警 <span class="chip c-gray">≤ {{ stock?.lowStockThreshold ?? 3 }} 件</span></div>
        <div class="vv num">
          <span :style="{ color: stock?.negativeCount ? 'var(--red)' : 'inherit' }">{{ stock?.negativeCount ?? '—' }}</span>
          <span class="unit">负库存</span>
          <span style="margin-left: 10px" :style="{ color: stock?.lowStockCount ? 'var(--amber)' : 'inherit' }">{{ stock?.lowStockCount ?? '—' }}</span>
          <span class="unit">库存不足</span>
        </div>
        <span class="mini">数据截至 {{ stock?.dataAsOf ? stock.dataAsOf.slice(0, 10) : '—' }}</span>
        <div class="human">人话:负库存先补录采购;库存不足的该进货了。</div>
      </div>

      <div class="ledger-card stat stat-clickable" @click="router.push('/replenish')">
        <div class="lb">🤖 AI 补货建议 <span class="chip c-gray">今日待处理</span></div>
        <div class="vv num">
          {{ replenishPending == null ? '—' : replenishPending }}
          <span class="unit">条</span>
          <span v-if="replenishUrgent" style="font-size: 14px; color: var(--red)">· 急/缺货 {{ replenishUrgent }}</span>
        </div>
        <span class="mini">(R,S) 公式算数字 · AI 讲人话 · 配货单 pre-kit</span>
        <div class="human">人话:{{ replenishPending == null ? '建议数暂取不到,去补货页看' : replenishPending === 0 ? '货道和仓库都够撑,今天不用补' : `有 ${replenishPending} 条建议等你勾选,点卡去补货页处理` }}。</div>
      </div>

      <div class="ledger-card stat stat-clickable" data-block="money-card" @click="router.push('/money')">
        <div class="lb">
          💰 钱账 · 净家底
          <span v-if="overduePayable" class="chip c-amber">🟡 逾期 {{ overduePayable }} 家</span>
        </div>
        <div class="vv num">
          {{ assets?.netAsset != null ? money(assets.netAsset) : '—' }}
          <span
            v-if="netAssetMom != null"
            style="font-size: 13px; margin-left: 4px"
            :style="{ color: netAssetMom >= 0 ? 'var(--green)' : 'var(--red)' }"
          >
            {{ netAssetMom >= 0 ? '▲' : '▼' }}{{ money(Math.abs(netAssetMom)) }}
          </span>
        </div>
        <span class="mini">今日流水 {{ todayFlowCount == null ? '—' : todayFlowCount }} 笔 · 现金 {{ assets?.cashTotal != null ? money(assets.cashTotal) : '—' }}</span>
        <br />
        <span class="mini" v-if="moneyMode === 'UNSET'" style="color: var(--amber)">⚠️ 结算模式待核实</span>
        <span class="mini" v-else-if="moneyMode === 'PLATFORM'">在途货款 ¥{{ moneyPending == null ? '—' : moneyPending.toLocaleString('zh-CN', { minimumFractionDigits: 2 }) }}</span>
        <span class="mini" v-else-if="moneyMode === 'DIRECT'">微信/支付宝直连 · 到账核对走月度钱盘</span>
        <span class="mini" v-else>资金与待结算数据暂取不到</span>
        <div class="human">人话:{{ overduePayable ? `有 ${overduePayable} 家供应商货款逾期未付,先去处理` : '每笔钱都有流水可追,点卡进资金与对账页' }}。</div>
      </div>
    </div>

    <!-- AI 经营洞察 -->
    <div class="ledger-card ai-insight" data-block="ai-insight" style="margin-top: 16px">
      <h3 style="margin: 0 0 6px">
        🧠 AI 经营洞察
        <span class="hint">{{ insight?.title ?? '家底环比解读' }} · 规则算数字 · AI 讲人话</span>
        <MockBadge v-if="insight" :model="insight.model" :text="insight.text" />
      </h3>
      <div v-loading="insightLoading">
        <p v-if="insight" class="insight-text">
          {{ insight.text }}
          <LlmTransparencyBadge :call-id="insight.llmCallId" size="mini" />
        </p>
        <p v-else class="mini" style="color: var(--ink2)">洞察暂取不到,去 BI 经营分析页看完整解读。</p>
      </div>
    </div>

    <!-- 异常雷达 -->
    <AnomalyRadar :limit="5" style="margin-top: 16px" />

    <p class="ledger-foot-note">
      — 驾驶舱支持月度 / 年度 / 全期三维度只读汇总，每个数字都能点进出处 —
    </p>
  </div>
</template>

<style scoped>
.scope-bar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 14px;
  flex-wrap: wrap;
  padding: 12px 16px;
  margin-bottom: 16px;
  background: linear-gradient(180deg, #fffdf8, #faf5ea);
}
.scope-tabs {
  display: inline-flex;
  background: #ede6d6;
  border-radius: 10px;
  padding: 4px;
  gap: 4px;
}
.scope-tab {
  border: none;
  background: transparent;
  padding: 7px 18px;
  border-radius: 8px;
  font-size: 13.5px;
  font-weight: 600;
  color: var(--ink2);
  cursor: pointer;
  transition: all 0.16s ease;
}
.scope-tab:hover {
  color: var(--ink);
}
.scope-tab.active {
  background: var(--green);
  color: #fff;
  box-shadow: 0 2px 6px rgba(28, 61, 47, 0.22);
}
.scope-controls {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}
.scope-meta {
  padding-left: 6px;
  border-left: 1px solid var(--line2);
  color: var(--ink2);
}

.work-strip {
  background: linear-gradient(135deg, #1c3d2f, #2a5741);
  color: #fff;
  display: flex;
  align-items: center;
  gap: 14px;
  flex-wrap: wrap;
  padding: 14px 20px;
}
.work-strip h3 {
  font-family: var(--serif);
}
.alerts {
  display: flex;
  flex-direction: column;
  gap: 8px;
  margin-bottom: 16px;
}
.alert {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 11px 16px;
  border-radius: 10px;
  font-size: 13px;
  border: 1px solid;
  cursor: pointer;
}
.a-red {
  background: var(--red-soft);
  border-color: #eac6bf;
  color: #8c2b20;
}
.a-amber {
  background: var(--amber-soft);
  border-color: #ecd8b8;
  color: #8a5a1d;
}
.a-blue {
  background: var(--blue-soft);
  border-color: #c8dae8;
  color: #2b5375;
  cursor: default;
}
.alert .go {
  margin-left: auto;
  font-size: 12px;
  font-weight: 600;
  text-decoration: underline;
  white-space: nowrap;
}

.stat-grid {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 14px;
  margin-bottom: 16px;
}
.live-grid {
  grid-template-columns: repeat(4, 1fr);
}
.stat {
  margin-bottom: 0;
}
.stat-clickable {
  cursor: pointer;
  transition: transform 0.14s ease, border-color 0.14s ease;
}
.stat-clickable:hover {
  transform: translateY(-1px);
  border-color: var(--green);
}
.stat .lb {
  font-size: 12.5px;
  color: var(--ink2);
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 6px;
  flex-wrap: wrap;
}
.stat .vv {
  font-size: 26px;
  font-weight: 700;
  margin: 8px 0 4px;
  line-height: 1.2;
}
.stat .vv .unit {
  font-size: 13px;
  font-weight: 500;
  color: var(--ink2);
  margin-left: 2px;
}
.stat .vv .sub-val {
  font-size: 16px;
  font-weight: 600;
  color: var(--ink2);
  margin-left: 6px;
}
.sub-metrics {
  min-height: 19px;
}
.stat .human {
  font-size: 12px;
  color: var(--ink2);
  border-top: 1px dashed var(--line2);
  margin-top: 10px;
  padding-top: 8px;
  line-height: 1.55;
}

.growth-pill {
  font-size: 11.5px;
  font-weight: 600;
  padding: 2px 7px;
  border-radius: 999px;
  font-family: var(--num);
  white-space: nowrap;
}
.g-up {
  background: #e5f4ec;
  color: #1f6b47;
}
.g-down {
  background: #fbeae7;
  color: #b43425;
}
.g-neutral {
  background: #f5ebd8;
  color: #8a5a1d;
}

/* 盈利结构条 */
.struct-box {
  margin: 12px 0 8px;
}
.struct-bar {
  display: flex;
  height: 12px;
  border-radius: 999px;
  overflow: hidden;
  background: #ece6d8;
}
.sb-cost {
  background: #d39c52;
}
.sb-exp {
  background: #c85c4b;
}
.sb-net {
  background: #2b6a4e;
}
.struct-legend {
  display: flex;
  justify-content: space-between;
  font-size: 11.5px;
  color: var(--ink2);
  margin-top: 6px;
  font-family: var(--num);
}
.dot {
  display: inline-block;
  width: 8px;
  height: 8px;
  border-radius: 50%;
  margin-right: 4px;
}
.d-cost {
  background: #d39c52;
}
.d-exp {
  background: #c85c4b;
}
.d-net {
  background: #2b6a4e;
}

/* 走势图 */
.trend-card {
  margin-bottom: 16px;
}
.trend-head {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 12px;
  flex-wrap: wrap;
  margin-bottom: 10px;
}
.trend-actions {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}
.pill-group {
  display: inline-flex;
  background: #f1ebdd;
  border-radius: 8px;
  padding: 2px;
  gap: 2px;
}
.pill-btn {
  border: none;
  background: transparent;
  padding: 4px 10px;
  border-radius: 6px;
  font-size: 12px;
  color: var(--ink2);
  cursor: pointer;
}
.pill-btn.active {
  background: #fff;
  color: var(--green);
  font-weight: 600;
  box-shadow: 0 1px 3px rgba(0, 0, 0, 0.08);
}
.trend-hover-bar {
  display: flex;
  align-items: center;
  gap: 14px;
  flex-wrap: wrap;
  padding: 8px 12px;
  border-radius: 8px;
  background: #faf7ef;
  border: 1px solid var(--line);
  font-size: 12.5px;
  margin-bottom: 12px;
  min-height: 36px;
}
.bar-chart {
  display: flex;
  align-items: flex-end;
  gap: 8px;
  height: 196px;
  padding: 18px 6px 4px;
  overflow-x: auto;
}
.bar-chart.dense {
  gap: 4px;
}
.bar-col {
  flex: 1 1 0;
  min-width: 20px;
  height: 100%;
  display: flex;
  flex-direction: column;
  align-items: center;
  cursor: pointer;
  border-radius: 6px;
  padding: 2px 1px;
}
.bar-col:hover,
.bar-col.active {
  background: rgba(43, 106, 78, 0.07);
}
.bar-val {
  font-size: 10.5px;
  color: var(--ink2);
  height: 16px;
  line-height: 16px;
  white-space: nowrap;
}
.bar-track {
  flex: 1;
  width: 100%;
  max-width: 34px;
  display: flex;
  align-items: flex-end;
  justify-content: center;
  padding: 2px 0;
}
.bar-fill {
  width: 78%;
  border-radius: 4px 4px 0 0;
  transition: height 0.25s ease;
  min-height: 2px;
}
.bar-lbl {
  font-size: 11px;
  color: var(--ink2);
  margin-top: 5px;
  white-space: nowrap;
}

.two-col {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 16px;
}
.mgrid {
  display: grid;
  grid-template-columns: repeat(2, 1fr);
  gap: 12px;
  margin-top: 10px;
}
.mcard {
  border: 1px solid var(--line);
  border-radius: 12px;
  padding: 13px 15px;
  background: var(--card);
  position: relative;
  overflow: hidden;
  cursor: pointer;
  transition: border-color 0.15s ease;
}
.mcard:hover {
  border-color: var(--green);
}
.mcard::after {
  content: '';
  position: absolute;
  top: 0;
  left: 0;
  right: 0;
  height: 4px;
  background: var(--green);
}
.mcard-top {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 6px;
}
.mcard h4 {
  font-size: 15px;
  font-weight: 700;
  margin: 0;
  color: var(--green);
}
.mcard .id {
  font-size: 11px;
  color: #8f8673;
  margin-top: 3px;
}
.mrow {
  display: flex;
  justify-content: space-between;
  font-size: 12.5px;
  color: var(--ink2);
  margin-top: 7px;
  align-items: center;
}
.mrow b {
  font-size: 14px;
  color: var(--ink);
}

.share-track {
  height: 6px;
  background: #efe9dc;
  border-radius: 999px;
  overflow: hidden;
  margin: 6px 0;
}
.share-fill {
  height: 100%;
  background: linear-gradient(90deg, #2b6a4e, #4a9572);
  border-radius: 999px;
}
.pay-fill {
  background: linear-gradient(90deg, #36688d, #5691bc);
}

.cat-list,
.pay-list {
  display: flex;
  flex-direction: column;
  gap: 11px;
  margin-top: 10px;
}
.cat-row {
  display: flex;
  justify-content: space-between;
  align-items: baseline;
  font-size: 13px;
  gap: 8px;
}
.cat-sub {
  display: flex;
  justify-content: space-between;
  color: var(--ink2);
}
.sub-block-title {
  font-size: 12.5px;
  font-weight: 700;
  color: var(--ink2);
  margin-top: 8px;
}

.table-scroll {
  overflow-x: auto;
}
.ltab {
  width: 100%;
  border-collapse: collapse;
  font-size: 13px;
  margin-top: 8px;
}
.ltab th {
  font-size: 12px;
  color: var(--ink2);
  font-weight: 600;
  text-align: left;
  padding: 8px 9px;
  border-bottom: 2px solid var(--line2);
  background: #faf7ef;
  white-space: nowrap;
}
.ltab th.num,
.ltab td.num {
  text-align: right;
  font-family: var(--num);
  white-space: nowrap;
}
.ltab td {
  padding: 8px 9px;
  border-bottom: 1px solid var(--line);
}
.pl-mini td {
  padding: 7px 9px;
  font-size: 12.5px;
}
.pl-sub td {
  background: #faf7ef;
}
.pl-total td {
  background: #eef6f1;
  border-top: 1.5px solid var(--line2);
}

.section-divider {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  flex-wrap: wrap;
  gap: 8px;
  margin: 22px 0 12px;
  padding-bottom: 6px;
  border-bottom: 1px dashed var(--line2);
  font-size: 14px;
  font-weight: 700;
  color: var(--ink);
}
.plink {
  color: var(--green);
  cursor: pointer;
  font-weight: 600;
}
.ai-insight .insight-text {
  font-size: 13px;
  line-height: 1.7;
  color: var(--ink);
  margin: 4px 0 0;
}

@media (max-width: 1120px) {
  .live-grid {
    grid-template-columns: repeat(2, 1fr);
  }
}
@media (max-width: 900px) {
  .stat-grid {
    grid-template-columns: repeat(2, 1fr);
  }
  .two-col {
    grid-template-columns: 1fr;
  }
}
@media (max-width: 560px) {
  .stat-grid,
  .live-grid,
  .mgrid {
    grid-template-columns: 1fr;
  }
  .scope-bar {
    padding: 10px 12px;
  }
  .scope-tab {
    padding: 6px 12px;
    font-size: 12.5px;
  }
  .scope-meta {
    border-left: none;
    padding-left: 0;
  }
  .work-strip {
    padding: 12px 14px;
  }
  .stat .vv {
    font-size: 22px;
  }
}
</style>
