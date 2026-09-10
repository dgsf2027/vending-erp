<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { reportApi, type GrossMarginResp, type StockResp } from '@/api/report'
import { importsApi, type ImportBatch } from '@/api/imports'
import { pageAliasPending } from '@/api/basedata'
import { useAppStore } from '@/stores/app'

/**
 * 经营驾驶舱(2026-09 精简版,对齐旧版看板):
 * 一眼看清:红灯要处理什么 → 本月/累计卖了多少赚了多少 → 货上压了多少钱 → 谁最能卖、什么最好卖。
 * 只读汇总,每个数字都能点进出处(库存页 / 报表页 / 单品 / 机器详情)。
 */
const router = useRouter()
const appStore = useAppStore()

const loading = ref(false)
const stock = ref<StockResp | null>(null)
const gmSku = ref<GrossMarginResp | null>(null)
const gmMachine = ref<GrossMarginResp | null>(null)
const gmAll = ref<GrossMarginResp | null>(null)
const latestBatch = ref<ImportBatch | null>(null)
const pendingAlias = ref(0)
const priceChangeCount = ref(0)

async function load() {
  loading.value = true
  try {
    const [s, skuFirst, all, batches, pending] = await Promise.all([
      reportApi.stock(),
      reportApi.grossMargin(undefined, 'sku'),
      reportApi.grossMargin('累计', 'sku'),
      importsApi.batches(1, 1),
      pageAliasPending({ pendingStatus: '待处理', size: 1 }),
    ])
    stock.value = s
    // 当月销售过小(0 或仅测试噪声)→ 回退到最近一个有真实销售的月份,避免首屏出现失真数字
    let sku = skuFirst
    const trivial = (r: GrossMarginResp) => Number(r.totalSalesAmt) < 100
    const realMonths = sku.months.filter((m) => m !== '累计')
    if (trivial(sku) && realMonths.length > 1) {
      for (let i = realMonths.length - 2; i >= 0; i--) {
        const prev = await reportApi.grossMargin(realMonths[i], 'sku')
        if (!trivial(prev)) {
          sku = prev
          break
        }
      }
    }
    gmSku.value = sku
    gmAll.value = all
    gmMachine.value = await reportApi.grossMargin(sku.month, 'machine')
    latestBatch.value = batches.records[0] ?? null
    pendingAlias.value = pending.total
    if (s.dataAsOf) appStore.setDataAsOf(s.dataAsOf.slice(0, 10))
    // 改价待确认:取最近一个出货明细批次的改价清单条数
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
onMounted(load)

const money = (v: number | string | null | undefined) =>
  v == null ? '—' : `¥${Number(v).toLocaleString(undefined, { maximumFractionDigits: 0 })}`
const pct = (v: number | string | null | undefined) => (v == null ? '—' : `${Math.round(Number(v))}`)

const redCount = computed(
  () =>
    (stock.value?.negativeCount ?? 0) +
    (stock.value?.lowStockCount ?? 0) +
    pendingAlias.value +
    priceChangeCount.value,
)

/** 在库 SKU 数(合计 > 0)/ 全部 SKU 数 */
const activeSkuCount = computed(() => stock.value?.rows.filter((r) => Number(r.totalQty) > 0).length ?? 0)
const skuCount = computed(() => stock.value?.rows.length ?? 0)

/** 机器卡:当月机器维毛利行(key=machineId) */
const machineRows = computed(() => (gmMachine.value?.rows ?? []).filter((r) => r.key != null))

const today = new Date()
const weekday = ['日', '一', '二', '三', '四', '五', '六'][today.getDay()]
const todayStr = `${today.getFullYear()}-${String(today.getMonth() + 1).padStart(2, '0')}-${String(today.getDate()).padStart(2, '0')}`
</script>

<template>
  <div class="ledger-page" v-loading="loading">
    <div class="ledger-crumb">园区小卖 ERP / 首页</div>
    <div class="ledger-title">
      <h2>经营驾驶舱</h2>
      <span class="sub">{{ todayStr }} 星期{{ weekday }}</span>
    </div>
    <p class="ledger-note">
      一眼看清:<b>有什么要处理 → 卖得怎么样 → 货上压了多少钱</b>
      <span v-if="latestBatch" class="mini" style="margin-left: 6px">
        最近导入:{{ latestBatch.fileType }} · {{ (latestBatch.createTime ?? '').replace('T', ' ').slice(5, 16) }} ✓
      </span>
    </p>

    <!-- 红灯待办(点击跳对应页) -->
    <div class="alerts">
      <div v-if="stock?.negativeCount" class="alert a-red" @click="router.push('/inventory')">
        🚨 <b>{{ stock.negativeCount }} 个商品负库存</b> —— 卖出去的比进的多,多半是漏录采购单,先补录再看毛利
        <span class="go">去库存页处理 →</span>
      </div>
      <div v-if="stock?.lowStockCount" class="alert a-amber" @click="router.push('/inventory')">
        ⚠️ <b>{{ stock.lowStockCount }} 个商品库存不足</b>(结存 ≤ {{ stock.lowStockThreshold }} 件)—— 请及时补货
        <span class="go">去库存页查看 →</span>
      </div>
      <div v-if="pendingAlias" class="alert a-amber" @click="router.push('/import')">
        🔗 <b>{{ pendingAlias }} 个新商品待绑别名</b> —— 不绑,这些销售算不进毛利
        <span class="go">去绑定 →</span>
      </div>
      <div v-if="priceChangeCount" class="alert a-amber" @click="router.push('/import')">
        💲 <b>最近批次 {{ priceChangeCount }} 条改价待确认</b>(成交价 ≠ 档案参考价)
        <span class="go">去确认 →</span>
      </div>
      <div v-if="!redCount" class="alert a-blue">
        ✅ 红灯清零:无负库存 · 无库存不足 · 无待绑别名 · 无改价待确认 —— 台账健康
      </div>
    </div>

    <!-- 数字卡:本月 / 累计 / 库存金额 / 预警 -->
    <div class="stat-grid">
      <div class="ledger-card stat" style="cursor: pointer" @click="router.push('/reports')">
        <div class="lb">本月销售 / 毛利 <span class="chip c-gray">{{ gmSku?.month ?? '—' }}</span></div>
        <div class="vv num">
          {{ money(gmSku?.totalSalesAmt) }}
          <span style="font-size: 16px; color: var(--green)">/ {{ money(gmSku?.totalGrossProfit) }}</span>
        </div>
        <span class="mini">毛利率 {{ gmSku?.totalMarginPct != null ? gmSku.totalMarginPct + '%' : '—' }}</span>
        <div class="human">人话:这个月每卖 100 块赚 {{ pct(gmSku?.totalMarginPct) }} 块;明细去报表页。</div>
      </div>
      <div class="ledger-card stat" style="cursor: pointer" @click="router.push('/reports')">
        <div class="lb">累计销售 / 毛利 <span class="chip c-gray">期初至今</span></div>
        <div class="vv num">
          {{ money(gmAll?.totalSalesAmt) }}
          <span style="font-size: 16px; color: var(--green)">/ {{ money(gmAll?.totalGrossProfit) }}</span>
        </div>
        <span class="mini">
          综合毛利率 {{ gmAll?.totalMarginPct != null ? gmAll.totalMarginPct + '%' : '—' }}
          <template v-if="gmAll?.noCostCount"> · {{ gmAll.noCostCount }} 个商品成本待补未计</template>
        </span>
        <div class="human">人话:开账以来一共卖了多少、赚了多少;报表页月份选「累计」看逐品。</div>
      </div>
      <div class="ledger-card stat" style="cursor: pointer" @click="router.push('/inventory')">
        <div class="lb">压在货上的钱 <span class="chip c-gray">按加权成本</span></div>
        <div class="vv num">{{ money(stock?.totalAmount) }}</div>
        <span class="mini">在库 {{ activeSkuCount }} / {{ skuCount }} 个商品有货</span>
        <div class="human">人话:这是家底,与库存页同一个数;库存 = 期初 + 入库 − 销售 − 损耗。</div>
      </div>
      <div class="ledger-card stat" style="cursor: pointer" @click="router.push('/inventory')">
        <div class="lb">库存预警 <span class="chip c-gray">≤ {{ stock?.lowStockThreshold ?? 3 }} 件</span></div>
        <div class="vv num">
          <span :style="{ color: stock?.negativeCount ? 'var(--red)' : 'inherit' }">{{ stock?.negativeCount ?? '—' }}</span>
          <span style="font-size: 13px; color: var(--ink2)">负库存</span>
          <span style="margin-left: 10px" :style="{ color: stock?.lowStockCount ? 'var(--amber)' : 'inherit' }">{{ stock?.lowStockCount ?? '—' }}</span>
          <span style="font-size: 13px; color: var(--ink2)">库存不足</span>
        </div>
        <span class="mini">数据截至 {{ stock?.dataAsOf ? stock.dataAsOf.slice(0, 10) : '—' }}</span>
        <div class="human">人话:负库存先补录采购;库存不足的该进货了。</div>
      </div>
    </div>

    <div class="two-col">
      <!-- 机器卡(当月机器维,点进机器详情) -->
      <div class="ledger-card">
        <h3>机器 · 谁最能卖 <span class="hint">{{ gmMachine?.month ?? '' }} 销售额 · 点卡看机器详情</span></h3>
        <div class="mgrid">
          <div
            v-for="m in machineRows"
            :key="m.key!"
            class="mcard"
            @click="router.push(`/machines/${m.key}`)"
          >
            <h4>{{ m.name }} ↗</h4>
            <div class="id num">{{ m.code }}</div>
            <div class="mrow"><span>本月销售</span><b class="num">{{ money(m.salesAmt) }}</b></div>
            <div class="mrow">
              <span>毛利</span>
              <b class="num">{{ m.grossProfit != null ? money(m.grossProfit) : '—' }}</b>
            </div>
            <div class="mrow mini" v-if="m.noCostSkuCount">
              <span style="color: var(--amber)">⚠️ {{ m.noCostSkuCount }} 笔销售成本待补</span>
            </div>
          </div>
        </div>
        <el-empty v-if="!machineRows.length" description="尚无机器销售数据(先在导入中心导后台出货明细)" :image-size="60" />
      </div>

      <!-- 热销 TOP(当月 SKU 维) -->
      <div class="ledger-card">
        <h3>本月热销 TOP 5 <span class="hint">按销售额 · 点商品名进单品页</span></h3>
        <table class="ltab">
          <thead>
            <tr><th>#</th><th>商品</th><th class="num">销售额</th><th class="num">毛利率</th></tr>
          </thead>
          <tbody>
            <tr v-for="(r, i) in (gmSku?.rows ?? []).filter((x) => x.key != null).slice(0, 5)" :key="r.key!">
              <td class="num">{{ i + 1 }}</td>
              <td><a class="plink" @click="router.push(`/products/${r.key}`)">{{ r.name }} ↗</a></td>
              <td class="num">{{ money(r.salesAmt) }}</td>
              <td class="num">{{ r.marginPct != null ? r.marginPct + '%' : '—' }}</td>
            </tr>
          </tbody>
        </table>
        <el-empty v-if="!gmSku?.rows?.length" description="尚无销售数据" :image-size="60" />
        <p class="mini" style="margin-top: 8px">
          📈 <a class="plink" @click="router.push('/reports')">报表(毛利 · 进销存 · 累计)→</a>
        </p>
      </div>
    </div>

    <p class="ledger-foot-note">
      — 驾驶舱只读汇总,每个数字都能点进出处;红灯清零是每天的第一目标 —
    </p>
  </div>
</template>

<style scoped>
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
  grid-template-columns: repeat(4, 1fr);
  gap: 14px;
  margin-bottom: 16px;
}
.stat {
  margin-bottom: 0;
}
.stat .lb {
  font-size: 12.5px;
  color: var(--ink2);
  display: flex;
  justify-content: space-between;
  align-items: center;
}
.stat .vv {
  font-size: 28px;
  font-weight: 700;
  margin: 6px 0 2px;
}
.stat .human {
  font-size: 12px;
  color: var(--ink2);
  border-top: 1px dashed var(--line2);
  margin-top: 10px;
  padding-top: 8px;
  line-height: 1.6;
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
  padding: 14px 16px;
  background: var(--card);
  position: relative;
  overflow: hidden;
  cursor: pointer;
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
.mcard h4 {
  font-size: 15px;
  font-weight: 700;
  margin: 0 0 2px;
  color: var(--green);
}
.mcard .id {
  font-size: 10.5px;
  color: #a89f8a;
  letter-spacing: 0.5px;
}
.mrow {
  display: flex;
  justify-content: space-between;
  font-size: 12.5px;
  color: var(--ink2);
  margin-top: 9px;
  align-items: center;
}
.mrow b {
  font-size: 15px;
  color: var(--ink);
}
.ltab {
  width: 100%;
  border-collapse: collapse;
  font-size: 13px;
  margin-top: 10px;
}
.ltab th {
  font-size: 12px;
  color: var(--ink2);
  font-weight: 600;
  text-align: left;
  padding: 8px 10px;
  border-bottom: 2px solid var(--line2);
  background: #faf7ef;
}
.ltab th.num,
.ltab td.num {
  text-align: right;
  font-family: var(--num);
}
.ltab td {
  padding: 9px 10px;
  border-bottom: 1px solid var(--line);
}
.plink {
  color: var(--green);
  cursor: pointer;
  font-weight: 600;
}

/* ============ 响应式(桌面 4 列 / 平板 2 列 / 手机 1 列) ============ */
@media (max-width: 900px) {
  .stat-grid {
    grid-template-columns: repeat(2, 1fr);
  }
  .two-col {
    grid-template-columns: 1fr;
  }
}
@media (max-width: 560px) {
  .stat-grid {
    grid-template-columns: 1fr;
  }
  .mgrid {
    grid-template-columns: 1fr;
  }
  .stat .vv {
    font-size: 24px;
  }
}
</style>
