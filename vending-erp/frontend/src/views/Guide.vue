<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { guideApi, type SetupStatus } from '@/api/guide'
import { useTour } from '@/composables/useTour'

/**
 * 新手指引中心(帮助中心)——同事上手的总入口:
 * ① 开业向导:上手三步做成带真实进度的清单(读 setup-status,不写死);
 * ② 每日流程速查卡:今天该干嘛;
 * ③ 逐页浮层引导:带我逛一圈(useTour);
 * ④ 帮助手册:分模块图文,搜索过滤,不懂就翻这里。
 */
const router = useRouter()
const { start: startTour } = useTour()

const status = ref<SetupStatus | null>(null)
const loading = ref(true)

onMounted(async () => {
  try {
    status.value = await guideApi.setupStatus()
  } catch {
    status.value = null
  } finally {
    loading.value = false
  }
})

/** 第1步 · 建档案 5 小项(真实进度) */
const step1Items = computed(() => {
  const s = status.value
  return [
    { key: 'machine', label: '机器与货道', tip: '后台设备ID 要和 fanmaiji.top 对齐', ok: !!s && s.machineCount > 0, count: s?.machineCount ?? 0, to: '/settings' },
    { key: 'product', label: '商品档案', tip: 'SKU / 条码 / 别名', ok: !!s && s.productCount > 0, count: s?.productCount ?? 0, to: '/settings' },
    { key: 'supplier', label: '供应商', tip: '名称 / 联系人', ok: !!s && s.supplierCount > 0, count: s?.supplierCount ?? 0, to: '/settings' },
  ]
})
const step1Done = computed(() => step1Items.value.every((i) => i.ok))
const step1Left = computed(() => step1Items.value.filter((i) => !i.ok).length)
const step2Done = computed(() => !!status.value?.prekitDone)
const step3Done = computed(() => !!status.value?.reconciled)
const doneCount = computed(() => [step1Done.value, step2Done.value, step3Done.value].filter(Boolean).length)
const allDone = computed(() => doneCount.value === 3)

/** 手风琴:默认展开第一个未完成的大步 */
const activeStep = ref('1')
onMounted(() => {
  setTimeout(() => {
    if (step1Done.value && !step2Done.value) activeStep.value = '2'
    else if (step1Done.value && step2Done.value && !step3Done.value) activeStep.value = '3'
  }, 300)
})

const go = (to: string) => router.push(to)

/** 每日流程速查卡 */
const dailyFlow = [
  { n: '① 到货', t: '录采购入库', d: '进货到货 → 采购入库 → 直接录入库', to: '/purchase' },
  { n: '② 早上', t: '导出货明细', d: 'fanmaiji.top 导「截至昨日」出货明细,拖进导入中心', to: '/import' },
  { n: '③', t: '处理红灯', d: '负库存 / 库存不足 / 待绑定 / 改价', to: '/dashboard' },
  { n: '④ 月末', t: '盘点', d: '账面带出只填差异,确认生成盘盈亏单', to: '/stocktake' },
  { n: '⑤', t: '看报表', d: '毛利、进销存,月份选「累计」看全量', to: '/reports' },
]

/** 帮助手册 */
const faqs = [
  {
    em: '📥', q: '导入中心怎么用?三个通道分别是什么?',
    a: [
      ['这是什么', '全系统的数据入口。每天把后台导出的 Excel 拖进来,系统自动记账,不用手敲数字。'],
      ['怎么做', '日常只用通道①:出货明细 → 销售记录。拖文件 → 看预览 → 确认入账。通道②(补货记录)和③(商品列表)是给要按机器分账的人用的,可以不管。'],
      ['常见问题', '名称对不上会进「待绑定队列」,到差异处理里手动绑一次,以后系统自动认。整批可回滚,导错了不怕。'],
    ],
  },
  {
    em: '📦', q: '库存是怎么算的?为什么和以前的台账一样?',
    a: [
      ['这是什么', '一本账:库存 = 期初 + 入库 − 销售 − 盘亏/报损,和旧版台账一个算法。'],
      ['怎么做', '「库存管理」看每个商品剩多少、值多少;加权单价 = (期初金额 + 入库金额) ÷ (期初数量 + 入库数量),没进货史就按商品档案的「参考成本」。'],
      ['常见问题', '负库存 = 卖出去的比进的多,多半是漏录采购,补录就好;库存不足 = 结存 ≤ 3 件,该进货了。'],
    ],
  },
  {
    em: '🚛', q: '采购入库怎么录?为什么只有它要手工?',
    a: [
      ['这是什么', '进货到货时录的单据。这是日常唯一需要手工录的单,其余全靠导入自动生成。'],
      ['怎么做', '「采购入库」→ 直接录入库 → 选供应商、商品、数量、单价 → 确认入库,库存 +、加权成本更新。'],
      ['常见问题', '录错数量用「红冲」整单反向;录错单价用「成本调整」;确认过的单不能直接改。'],
    ],
  },
  {
    em: '📋', q: '月末盘点怎么做?',
    a: [
      ['这是什么', '旧版《月末盘点表》的升级版:账面结存自动带出,只填对不上的。'],
      ['怎么做', '「盘点」→ 新建(仓库)→ 录实盘(只填差异,选原因)→ 提交 → 查账归因 → 确认,系统自动生成盘盈亏单并修正库存。'],
      ['常见问题', '差异金额按加权单价算;单笔差异超 ¥50 需要老板确认。'],
    ],
  },
  {
    em: '🔴', q: '驾驶舱的「红灯」是什么意思?怎么清零?',
    a: [
      ['这是什么', '系统帮你盯的风险点:负库存、库存不足、新商品待绑别名、改价待确认。'],
      ['怎么做', '点红灯 → 跳到对应的待办去处理 → 处理完自动熄灯。目标是「红灯清零」= 账健康。'],
      ['常见问题', '红灯不是错误,是提醒;每天花几分钟清一遍,月底对账就轻松。'],
    ],
  },
  {
    em: '📈', q: '报表怎么看?「累计」是什么?',
    a: [
      ['这是什么', '毛利报表(按商品 / 按机器)和进销存汇总(期初 / 入库 / 出库 / 期末)。'],
      ['怎么做', '「报表」→ 选月份;选「累计」= 期初至今全量,就是旧版进销存台账默认那张表。'],
      ['常见问题', '毛利 = 实收 − 加权成本;没有成本的商品显「—」,到商品档案填「参考成本」就会计入。'],
    ],
  },
  {
    em: '🧩', q: '侧栏底部的「更多功能」是什么?',
    a: [
      ['这是什么', '暂未启用的模块:AI 补货、出库上架、任务日历、资金与对账、供应商往来、资产家底、BI、月度报表、PDCA。'],
      ['怎么做', '点「更多功能」展开就能进,页面和数据都在;日常用不到可以一直收着。'],
      ['常见问题', '这些模块需要额外的数据(补货记录导入、资金账户、结算模式)才有意义,先把进销存跑顺再说。'],
    ],
  },
]
const kw = ref('')
const filteredFaqs = computed(() => {
  const k = kw.value.trim()
  if (!k) return faqs
  return faqs.filter((f) => f.q.includes(k) || f.a.some((row) => row[1].includes(k)))
})
</script>

<template>
  <div class="ledger-page">
    <div class="ledger-crumb">园区小卖 ERP / 帮助 / 新手指引</div>
    <div class="ledger-title">
      <h2>新手指引</h2>
      <span class="sub">从这里开始 · 不懂就翻这里</span>
    </div>
    <p class="ledger-note">
      <b>三步让系统跑起来</b>,每天照着「速查卡」做,遇到不会的翻「帮助手册」,或点任意页右下角的
      <b>「?」</b>。
    </p>

    <!-- 全绿横幅 -->
    <el-alert v-if="allDone" type="success" :closable="false" class="mb-14px"
      title="🎉 系统已就绪 · 三步全部完成,可以正常使用啦" />
    <el-alert v-else type="warning" :closable="false" class="mb-14px"
      :title="`系统还没完全开张 —— 还差 ${3 - doneCount} 步,跟着下面做完就能用`" />

    <!-- A. 开业向导 -->
    <el-card shadow="never" class="mb-16px">
      <div class="flex items-center justify-between flex-wrap gap-8px mb-8px">
        <div class="text-16px font-bold"><span class="mr-6px">🚩</span>开业向导 · 让系统跑起来</div>
        <div class="flex items-center gap-10px text-13px text-gray-500">
          <span>已完成 <b style="color: var(--green)">{{ doneCount }} / 3</b></span>
          <span class="prog-bar"><i :style="{ width: (doneCount / 3 * 100) + '%' }" /></span>
        </div>
      </div>

      <el-collapse v-model="activeStep" accordion>
        <!-- 第1步 -->
        <el-collapse-item name="1">
          <template #title>
            <span class="step-badge" :class="step1Done ? 'ok' : 'no'">{{ step1Done ? '✓' : '1' }}</span>
            <span class="step-title">建档案(设置中心:机器 / 商品 / 供应商)</span>
            <el-tag :type="step1Done ? 'success' : 'warning'" size="small" effect="light" class="ml-10px">
              {{ step1Done ? '已完成' : `还差 ${step1Left} 项` }}
            </el-tag>
          </template>
          <div class="sub-item" v-for="it in step1Items" :key="it.key">
            <span class="tick" :class="it.ok ? 'ok' : 'no'">{{ it.ok ? '✓' : '' }}</span>
            <div class="si-txt">
              {{ it.label }}
              <small>· {{ it.tip }}{{ it.ok && it.count ? ` · 已录 ${it.count}` : '' }}</small>
            </div>
            <el-button size="small" :type="it.ok ? 'default' : 'primary'" :plain="it.ok" @click="go(it.to)">
              {{ it.ok ? '去查看' : '去录入' }} →
            </el-button>
          </div>
        </el-collapse-item>

        <!-- 第2步 -->
        <el-collapse-item name="2">
          <template #title>
            <span class="step-badge" :class="step2Done ? 'ok' : 'no'">{{ step2Done ? '✓' : '2' }}</span>
            <span class="step-title">导期初数据(上线一次性)</span>
            <el-tag :type="step2Done ? 'success' : 'info'" size="small" effect="light" class="ml-10px">
              {{ step2Done ? '已完成' : '未开始' }}
            </el-tag>
          </template>
          <p class="step-desc">
            老 Excel 进销存套表搬家:① 商品档案 + 别名(一码多品清洗)→ ② 历史采购(建加权成本)→
            ③ 历史销售 → 与老账对平才算「期初完成」。
          </p>
          <el-button type="primary" @click="go('/import')">进入向导 →</el-button>
        </el-collapse-item>

        <!-- 第3步 -->
        <el-collapse-item name="3">
          <template #title>
            <span class="step-badge" :class="step3Done ? 'ok' : 'no'">{{ step3Done ? '✓' : '3' }}</span>
            <span class="step-title">对平验收</span>
            <el-tag :type="step3Done ? 'success' : 'info'" size="small" effect="light" class="ml-10px">
              {{ step3Done ? '已完成' : '未开始' }}
            </el-tag>
          </template>
          <p class="step-desc">
            库存 / 毛利与老账核对,驾驶舱「红灯清零」= 正式可用。
          </p>
          <el-button :type="step3Done ? 'default' : 'primary'" :plain="step3Done" @click="go('/dashboard')">
            去驾驶舱 →
          </el-button>
        </el-collapse-item>
      </el-collapse>
    </el-card>

    <!-- B. 每日速查卡 -->
    <el-card shadow="never" class="mb-16px">
      <div class="flex items-center justify-between flex-wrap gap-8px mb-12px">
        <div class="text-16px font-bold"><span class="mr-6px">📆</span>每天该干嘛 · 速查卡</div>
        <el-button plain @click="startTour(true)">▶ 带我逛一圈</el-button>
      </div>
      <div class="flow">
        <template v-for="(f, i) in dailyFlow" :key="f.t">
          <div class="fstep" @click="go(f.to)">
            <div class="fn">{{ f.n }}</div>
            <div class="ft">{{ f.t }}</div>
            <div class="fd">{{ f.d }}</div>
          </div>
          <div v-if="i < dailyFlow.length - 1" class="farrow">→</div>
        </template>
      </div>
      <div class="flow-note">手工只需录一种单:采购到货 → 「采购入库」。销售靠导入自动记账,库存和毛利自动算。</div>
    </el-card>

    <!-- C. 帮助手册 -->
    <el-card shadow="never">
      <div class="text-16px font-bold mb-10px"><span class="mr-6px">📖</span>帮助手册 · 不懂就翻这里</div>
      <el-input v-model="kw" placeholder="🔍 搜一下,例如:怎么导入出货明细 / 负库存 / 加权成本 / 盘点" clearable class="mb-12px" />
      <el-collapse>
        <el-collapse-item v-for="(f, i) in filteredFaqs" :key="i" :name="String(i)">
          <template #title><span class="mr-8px text-16px">{{ f.em }}</span>{{ f.q }}</template>
          <p v-for="row in f.a" :key="row[0]" class="faq-a"><b>{{ row[0] }}:</b>{{ row[1] }}</p>
        </el-collapse-item>
      </el-collapse>
      <el-empty v-if="!filteredFaqs.length" description="没搜到,换个词试试" :image-size="70" />
    </el-card>
  </div>
</template>

<style scoped>
.prog-bar {
  width: 140px;
  height: 8px;
  background: var(--green-soft);
  border-radius: 99px;
  overflow: hidden;
  display: inline-block;
}
.prog-bar > i {
  display: block;
  height: 100%;
  background: var(--green);
  border-radius: 99px;
  transition: width 0.4s ease;
}
.step-badge {
  width: 24px;
  height: 24px;
  border-radius: 99px;
  display: inline-grid;
  place-items: center;
  font-size: 13px;
  font-weight: 700;
  margin-right: 10px;
  flex-shrink: 0;
}
.step-badge.ok {
  background: var(--green);
  color: #fff;
}
.step-badge.no {
  background: #eee9dd;
  color: #a39a83;
  border: 1.5px solid var(--line2);
}
.step-title {
  font-weight: 700;
  font-size: 15px;
}
.step-desc {
  font-size: 13px;
  color: var(--ink2);
  margin: 4px 0 12px;
  line-height: 1.7;
}
.sub-item {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 10px 0;
  border-bottom: 1px dashed var(--line);
}
.sub-item:last-child {
  border-bottom: 0;
}
.tick {
  width: 20px;
  height: 20px;
  border-radius: 99px;
  display: grid;
  place-items: center;
  font-size: 12px;
  flex-shrink: 0;
  color: #fff;
}
.tick.ok {
  background: var(--green);
}
.tick.no {
  background: #fff;
  border: 1.5px solid var(--line2);
}
.si-txt {
  font-size: 14px;
}
.si-txt small {
  color: var(--ink2);
  font-size: 12px;
}
.sub-item .el-button {
  margin-left: auto;
}
/* 速查卡 */
.flow {
  display: flex;
  align-items: stretch;
  flex-wrap: wrap;
  gap: 0;
}
.fstep {
  flex: 1;
  min-width: 120px;
  background: #fbfaf4;
  border: 1px solid var(--line);
  border-radius: 10px;
  padding: 12px;
  cursor: pointer;
  transition: all 0.18s;
}
.fstep:hover {
  border-color: var(--green);
  box-shadow: 0 2px 8px rgba(47, 110, 82, 0.12);
}
.fn {
  font-family: var(--serif);
  font-size: 13px;
  color: var(--green);
  font-weight: 800;
}
.ft {
  font-weight: 700;
  font-size: 13.5px;
  margin: 4px 0 3px;
}
.fd {
  font-size: 11.5px;
  color: var(--ink2);
  line-height: 1.5;
}
.farrow {
  align-self: center;
  color: var(--line2);
  font-size: 20px;
  padding: 0 6px;
}
.flow-note {
  font-size: 12px;
  color: var(--ink2);
  text-align: center;
  margin-top: 10px;
}
.faq-a {
  font-size: 13px;
  color: var(--ink2);
  line-height: 1.7;
  margin: 6px 0;
}
.faq-a b {
  color: var(--ink);
}
@media (max-width: 768px) {
  .flow {
    flex-direction: column;
  }
  .farrow {
    transform: rotate(90deg);
    padding: 2px 0;
  }
  .prog-bar {
    width: 90px;
  }
}
</style>
