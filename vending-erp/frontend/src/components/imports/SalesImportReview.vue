<script setup lang="ts">
import { computed, ref } from 'vue'
import type { PreviewResp } from '@/api/imports'

const props = defineProps<{ preview: PreviewResp }>()
const tab = ref('rows')
const sales = computed(() => props.preview.sales!)
const money = (value: number) => Number(value || 0).toLocaleString('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 2 })
const integer = (value: number) => Number(value || 0).toLocaleString('zh-CN', { maximumFractionDigits: 3 })
const newCount = computed(() => sales.value.products.filter(p => p.status === '新增商品').length)
const pendingCount = computed(() => sales.value.products.filter(p => !['已匹配', '新增商品'].includes(p.status)).length)
const headers = computed(() => props.preview.sourceHeaders?.length ? props.preview.sourceHeaders : props.preview.headers)
const rows = computed(() => props.preview.sourcePreviewRows?.length ? props.preview.sourcePreviewRows : props.preview.previewRows)
</script>

<template>
  <div class="sales-review">
    <div class="sales-format"><el-tag type="success">已识别：{{ sales.format }}</el-tag><span>{{ sales.dateFrom }} 至 {{ sales.dateTo }}</span></div>
    <div class="sales-totals">
      <div><span>销售明细 / 订单</span><b>{{ integer(preview.rowTotal) }} <small>行 / {{ integer(sales.orderCount) }} 单</small></b></div>
      <div><span>表格销售数量</span><b>{{ integer(sales.quantity) }} <small>件</small></b></div>
      <div><span>表格实收合计</span><b>¥{{ money(sales.amount) }}</b></div>
      <div><span>商品 / 机器</span><b>{{ sales.productCount }} <small>种 / {{ sales.machineCount }} 台</small></b></div>
    </div>
    <p class="sales-rule">以上是整张表的合计，包含已经导入过的记录；确认后会显示实际新增和跳过数量。同一订单的不同商品分别保留。</p>
    <p v-if="sales.format === '元气森林销售表'" class="sales-rule">按“订单时间”记销售，单价由支付金额÷销售数量计算。优惠券抵扣共 ¥{{ money(sales.discount) }}，仅供核对，不加进实收金额。</p>
    <el-alert v-if="sales.missingMachines.length" type="error" :closable="false" title="这些柜子编码还没有机器档案，请先到设置中心补充，再重新核对。" class="mb-8px">
      <div class="missing-machines">{{ sales.missingMachines.join('、') }}</div>
    </el-alert>
    <el-alert v-if="sales.invalidRows" type="error" :closable="false" :title="`有 ${sales.invalidRows} 行需要修改，请按原表行号修正后重新上传。`" class="mb-8px">
      <ul><li v-for="issue in sales.issues" :key="issue">{{ issue }}</li></ul>
      <div>合计不包含无法读取的数值；最多展示前20条问题。</div>
    </el-alert>
    <el-tabs v-model="tab">
      <el-tab-pane label="销售明细（前20行）" name="rows">
        <el-table :data="rows" border size="small" max-height="360">
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
.sales-rule { color:#66766c; line-height:1.7; font-size:12px; margin:10px 0; }
.missing-machines { overflow-wrap:anywhere; line-height:1.8; }
@media(max-width:680px) { .sales-totals { grid-template-columns:repeat(2,minmax(0,1fr)); } .sales-totals b { font-size:19px; } }
</style>
