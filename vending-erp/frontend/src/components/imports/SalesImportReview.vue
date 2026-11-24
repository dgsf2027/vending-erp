<script setup lang="ts">
import { computed, ref } from 'vue'
import type { PreviewResp } from '@/api/imports'

const props = defineProps<{ preview: PreviewResp }>()
const tab = ref('rows')
const sales = computed(() => props.preview.sales!)
const money = (value: number) => Number(value || 0).toLocaleString('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 2 })
const integer = (value: number) => Number(value || 0).toLocaleString('zh-CN', { maximumFractionDigits: 3 })
const duplicateRows = computed(() => sales.value.duplicateRowCount ?? 0)
const duplicateOrders = computed(() => sales.value.duplicateOrderCount ?? 0)
const newRowCount = computed(() => sales.value.newRowCount ?? props.preview.rowTotal)
const duplicatePreviewRows = computed(() => sales.value.duplicatePreviewRows ?? [])
const sampleDuplicateOrders = computed(() => sales.value.sampleDuplicateOrders ?? [])
const newCount = computed(() => sales.value.products.filter(p => p.status === '新增商品').length)
const pendingCount = computed(() => sales.value.products.filter(p => !['已匹配', '新增商品'].includes(p.status)).length)
const headers = computed(() => props.preview.sourceHeaders?.length ? props.preview.sourceHeaders : props.preview.headers)
const rows = computed(() => props.preview.sourcePreviewRows ?? props.preview.previewRows)
</script>

<template>
  <div class="sales-review">
    <div class="sales-format">
      <el-tag type="success">已识别：{{ sales.format }}</el-tag>
      <el-tag v-if="duplicateRows > 0" type="warning">已按订单编号自动清除重复 {{ integer(duplicateRows) }} 行</el-tag>
      <el-tag v-else type="info">已核对历史订单编号：无重复</el-tag>
      <span v-if="sales.dateFrom || sales.dateTo">{{ sales.dateFrom }} 至 {{ sales.dateTo }}</span>
    </div>
    <el-alert
      v-if="duplicateRows > 0 && newRowCount === 0"
      type="warning"
      :closable="false"
      show-icon
      class="mb-10px"
      :title="`表中全部 ${integer(preview.rowTotal)} 行（${integer(duplicateOrders)} 个订单编号，¥${money(sales.duplicateAmount || 0)}）均已导入过，已全部清除，无需重复导入。`"
    />
    <el-alert
      v-else-if="duplicateRows > 0"
      type="success"
      :closable="false"
      show-icon
      class="mb-10px"
      :title="`已与历史销售记录核对订单编号：自动清除重复 ${integer(duplicateRows)} 行（${integer(duplicateOrders)} 单 · ¥${money(sales.duplicateAmount || 0)}），以下仅保留 ${integer(newRowCount)} 行新增记录待导入。`"
    />
    <div class="sales-totals">
      <div>
        <span>{{ duplicateRows > 0 ? '待导入明细 / 订单' : '销售明细 / 订单' }}</span>
        <b>{{ integer(newRowCount) }} <small>行 / {{ integer(sales.orderCount) }} 单</small></b>
        <em v-if="duplicateRows > 0">原表 {{ integer(preview.rowTotal) }} 行 · 已清重复 {{ integer(duplicateRows) }} 行</em>
      </div>
      <div>
        <span>{{ duplicateRows > 0 ? '待导入销售数量' : '表格销售数量' }}</span>
        <b>{{ integer(sales.quantity) }} <small>件</small></b>
        <em v-if="duplicateRows > 0">原表 {{ integer(sales.rawQuantity || 0) }} 件 · 已清 {{ integer(sales.duplicateQuantity || 0) }} 件</em>
      </div>
      <div>
        <span>{{ duplicateRows > 0 ? '待导入实收合计' : '表格实收合计' }}</span>
        <b>¥{{ money(sales.amount) }}</b>
        <em v-if="duplicateRows > 0">原表 ¥{{ money(sales.rawAmount || 0) }} · 已清 ¥{{ money(sales.duplicateAmount || 0) }}</em>
      </div>
      <div>
        <span>商品 / 机器</span>
        <b>{{ sales.productCount }} <small>种 / {{ sales.machineCount }} 台</small></b>
      </div>
    </div>
    <p class="sales-rule">系统已按订单编号与上次及历史导入的销售记录自动对比，重复订单已清除、不会重复入账；同一新订单的不同商品明细分别保留。</p>
    <p v-if="sales.format === '元气森林销售表'" class="sales-rule">按“订单时间”记销售，单价由支付金额÷销售数量计算。优惠券抵扣共 ¥{{ money(sales.discount) }}，仅供核对，不加进实收金额。</p>
    <el-alert v-if="sales.missingMachines.length" type="error" :closable="false" title="这些柜子编码还没有机器档案，请先到设置中心补充，再重新核对。" class="mb-8px">
      <div class="missing-machines">{{ sales.missingMachines.join('、') }}</div>
    </el-alert>
    <el-alert v-if="sales.invalidRows" type="error" :closable="false" :title="`有 ${sales.invalidRows} 行需要修改，请按原表行号修正后重新上传。`" class="mb-8px">
      <ul><li v-for="issue in sales.issues" :key="issue">{{ issue }}</li></ul>
      <div>合计不包含无法读取的数值；最多展示前20条问题。</div>
    </el-alert>
    <el-tabs v-model="tab">
      <el-tab-pane :label="duplicateRows > 0 ? `待导入明细（${integer(newRowCount)}行，前20行）` : '销售明细（前20行）'" name="rows">
        <el-table :data="rows" border size="small" max-height="360">
          <el-table-column v-for="header in headers" :key="header" :prop="header" :label="header" :min-width="header.includes('名称') ? 200 : 145" show-overflow-tooltip />
          <template #empty><div class="sales-empty">已清除全部重复记录，本次没有新的销售明细可导入</div></template>
        </el-table>
      </el-tab-pane>
      <el-tab-pane v-if="duplicateRows > 0" :label="`已清除重复（${integer(duplicateRows)}行）`" name="duplicates">
        <p v-if="sampleDuplicateOrders.length" class="sales-rule">
          重复订单编号示例（前{{ sampleDuplicateOrders.length }}个）：{{ sampleDuplicateOrders.join('、') }}
        </p>
        <el-table :data="duplicatePreviewRows" border size="small" max-height="360">
          <el-table-column label="清除原因" width="110">
            <template #default="{ row }"><el-tag type="danger" size="small">{{ row.__dupReason || '历史已导入' }}</el-tag></template>
          </el-table-column>
          <el-table-column v-for="header in headers" :key="header" :prop="header" :label="header" :min-width="header.includes('名称') ? 200 : 145" show-overflow-tooltip />
        </el-table>
      </el-tab-pane>
      <el-tab-pane :label="`商品匹配（${sales.productCount}）`" name="products">
        <p class="sales-rule">已匹配的商品沿用原编码；{{ newCount }}种新品确认后分配YQ编码。{{ pendingCount ? `${pendingCount}种身份有冲突，导入后进入待绑定队列。` : '' }}预览不会创建商品。</p>
        <el-table :data="sales.products" border size="small" max-height="360">
          <el-table-column prop="sourceName" label="表格商品名称" min-width="230" show-overflow-tooltip />
          <el-table-column label="商品编码 / 处理方式" min-width="170">
            <template #default="{ row }"><b v-if="row.skuCode">{{ row.skuCode }}</b><el-tag v-else :type="row.status === '新增商品' ? 'success' : 'warning'" size="small">{{ row.status === '新增商品' ? '确认后自动分配YQ编码' : '待核对绑定' }}</el-tag></template>
          </el-table-column>
          <el-table-column prop="productName" label="系统商品名称" min-width="210" show-overflow-tooltip />
          <el-table-column prop="rowCount" label="明细行数" width="90" align="right" />
          <el-table-column prop="quantity" label="销售数量" width="90" align="right" />
          <el-table-column label="实收金额" width="110" align="right"><template #default="{ row }">{{ money(row.amount) }}</template></el-table-column>
          <el-table-column prop="reason" label="匹配说明" min-width="210" show-overflow-tooltip />
        </el-table>
      </el-tab-pane>
    </el-tabs>
  </div>
</template>

<style scoped>
.sales-format { display:flex; align-items:center; flex-wrap:wrap; gap:12px; margin:8px 0 14px; color:#66766c; font-size:13px; }
.sales-totals { display:grid; grid-template-columns:repeat(4,minmax(0,1fr)); gap:12px; }
.sales-totals > div { padding:14px; background:#f1f6f3; border:1px solid #dce8e0; border-radius:6px; }
.sales-totals span { display:block; color:#64766a; font-size:12px; margin-bottom:8px; }
.sales-totals b { color:#24563e; font-size:23px; font-variant-numeric:tabular-nums; }
.sales-totals small { font-size:12px; font-weight:normal; }
.sales-totals em { display:block; margin-top:6px; font-style:normal; font-size:11px; color:#6d7d74; }
.sales-rule { color:#66766c; line-height:1.7; font-size:12px; margin:10px 0; }
.sales-empty { padding:18px 0; color:#75857c; }
.missing-machines { overflow-wrap:anywhere; line-height:1.8; }
@media(max-width:680px) { .sales-totals { grid-template-columns:repeat(2,minmax(0,1fr)); } .sales-totals b { font-size:19px; } }
</style>
