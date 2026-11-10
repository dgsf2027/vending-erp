<script setup lang="ts">
import { computed, ref } from 'vue'
import { ElMessage } from 'element-plus'
import ProductSelect from '@/components/basedata/ProductSelect.vue'
import { previewPurchaseWorkbook, reviewPurchaseWorkbook, purchaseWorkbookTemplate,
  type PurchaseWorkbookPreview, type PurchaseWorkbookRow, type PurchaseWorkbookDraft } from '@/api/purchase'
const props = defineProps<{ modelValue: boolean; savedKeys: string[] }>()
const emit = defineEmits<{ (e: 'update:modelValue', value: boolean): void; (e: 'import', draft: PurchaseWorkbookDraft): void }>()
const input = ref<HTMLInputElement>()
const busy = ref(false), error = ref(''), fileName = ref(''), selectedDate = ref(''), defaultDate = ref('')
const data = ref<PurchaseWorkbookPreview | null>(null)
const parseErrors = ref<string[]>([])
const sourceKey = ref(''), showAll = ref(false), dirty = ref(false)
const manualRows = ref<number[]>([])
const activeDay = computed(() => data.value?.days.find(d => d.bizDate === selectedDate.value))
const activeRows = computed(() => (data.value?.rows || []).filter(r => r.bizDate === selectedDate.value))
const visibleRows = computed(() => activeRows.value.filter(r => showAll.value || r.errors.length || r.needsConfirmation && !r.confirmed))
const dayKey = computed(() => sourceKey.value + ':' + selectedDate.value)
const alreadySaved = computed(() => props.savedKeys.includes(dayKey.value))
function close() { if (!busy.value) emit('update:modelValue', false) }
function changed(r: PurchaseWorkbookRow) { r.confirmed = false; dirty.value = true }
function confirmRow(r: PurchaseWorkbookRow) { r.confirmed = true; dirty.value = true }
function choose(r: PurchaseWorkbookRow, productId: number) {
  r.allocations = [{ productId, qty: r.qty }]; r.confirmed = true; dirty.value = true
}
function split(r: PurchaseWorkbookRow) {
  r.allocations.push({ productId: null, qty: null }); changed(r)
}
function setQuantity(r: PurchaseWorkbookRow, index: number, value: number | null | undefined) {
  r.allocations[index].qty = value == null ? null : Number((value * (r.boxSpec || 1)).toFixed(3)); changed(r)
}
function candidateName(r: PurchaseWorkbookRow, id: number | null) {
  const c = r.candidates.find(c => c.productId === id)
  return c ? `${c.skuCode} · ${c.productName}` : '请选择对应商品'
}
async function upload(event: Event) {
  const el = event.target as HTMLInputElement, file = el.files?.[0]; el.value = ''
  if (!file || busy.value) return
  busy.value = true; error.value = ''; data.value = null; parseErrors.value = []; dirty.value = false; manualRows.value = []; defaultDate.value = ''
  try {
    if (!/\.xlsx?$/i.test(file.name) || file.size > 5 * 1024 * 1024) throw new Error('请选择不超过 5MB 的 .xls 或 .xlsx 文件')
    data.value = await previewPurchaseWorkbook(file); fileName.value = file.name
    sourceKey.value = file.name + ':' + Date.now(); parseErrors.value = [...data.value.errors]
    selectedDate.value = data.value.days[0]?.bizDate || ''; showAll.value = false
  } catch (e) { error.value = e instanceof Error ? e.message : '读取失败，请重新上传' }
  finally { busy.value = false }
}
async function review(apply = false) {
  if (!data.value || busy.value || parseErrors.value.length) return
  busy.value = true; error.value = ''
  try {
    const checked = await reviewPurchaseWorkbook(data.value.rows, defaultDate.value || undefined)
    const previous = selectedDate.value
    data.value = { ...checked, warnings: data.value.warnings, skippedSubtotals: data.value.skippedSubtotals }
    if (!checked.days.some(d => d.bizDate === previous)) selectedDate.value = checked.days[0]?.bizDate || ''
    dirty.value = false
    if (apply) {
      const day = activeDay.value
      if (!day || day.errors.length || !day.lines.length) { error.value = '这次采购还有待核对的口味、数量或日期，请先处理下方提示。'; return }
      if (alreadySaved.value) return
      emit('import', { day, fileName: fileName.value, sourceKey: dayKey.value })
      emit('update:modelValue', false)
    } else ElMessage.success('已重新核对商品、数量和金额')
  } catch (e) { error.value = e instanceof Error ? e.message : '核对失败，请重试' }
  finally { busy.value = false }
}
async function download() {
  busy.value = true
  try {
    const blob = await purchaseWorkbookTemplate(); const url = URL.createObjectURL(blob)
    const a = document.createElement('a'); a.href = url; a.download = '采购拿货表导入模板.xlsx'; a.click()
    setTimeout(() => URL.revokeObjectURL(url), 1000)
  } catch (e) { error.value = e instanceof Error ? e.message : '模板下载失败' }
  finally { busy.value = false }
}
</script>

<template>
  <el-dialog :model-value="modelValue" title="采购拿货表 · 汇总与销售关联" width="min(1260px, 96vw)" top="3vh"
    class="purchase-workbook-dialog" :close-on-click-modal="false" :close-on-press-escape="!busy" :show-close="!busy" :before-close="close">
    <div class="purchase-workbook" v-loading="busy">
      <div class="intro">上传原拿货表 → 按采购日期整理 → 核对销售商品与口味 → 带入入库单确认。同一天同编码自动汇总，保留原始金额。</div>
      <div class="tools">
        <el-button type="primary" :disabled="busy" @click="input?.click()">{{ data ? '重新选择拿货表' : '选择拿货表' }}</el-button>
        <input ref="input" type="file" accept=".xls,.xlsx" hidden @change="upload">
        <el-button :disabled="busy" @click="download">下载同格式模板</el-button>
        <span class="muted">{{ fileName || '支持采购日期、产品名称、商品编码、整件规格、总件数、采购数量、整件价格、总价格' }}</span>
      </div>
      <el-alert v-if="error" type="error" :closable="false" :title="error" show-icon />
      <el-alert v-if="parseErrors.length" type="error" :closable="false" title="原表有数据问题，请修正后重新上传。">
        <div v-for="item in parseErrors" :key="item">{{ item }}</div>
      </el-alert>
      <template v-if="data">
        <div class="tools stats"><b>{{ data.rows.length }} 行明细 · {{ data.days.length }} 次采购</b><span class="muted">自动识别 {{ data.skippedSubtotals || 0 }} 行小计 · 上传和预览不会入库</span></div>
        <div class="date-cards">
          <button v-for="day in data.days" :key="day.bizDate" type="button" :class="{ active: selectedDate === day.bizDate }" :disabled="busy" @click="selectedDate = day.bizDate">
            <b>{{ day.bizDate || '待填采购日期' }}</b><span>{{ day.sourceCount }} 行 · ¥{{ Number(day.totalAmount).toFixed(2) }}</span>
            <small>{{ savedKeys.includes(sourceKey + ':' + day.bizDate) ? '本次已保存单据' : day.errors.length ? '有待核对项' : `可汇总为 ${day.lines.length} 种商品` }}</small>
          </button>
        </div>
        <div class="tools" v-if="data.rows.some(r => !r.bizDate)"><span>无日期明细统一使用：</span><el-date-picker v-model="defaultDate" type="date" value-format="YYYY-MM-DD" placeholder="选择实际采购日期" /><el-button :disabled="busy" @click="review(false)">应用日期</el-button></div>
        <div class="tools"><b>① 商品与口味核对</b><el-checkbox v-model="showAll">显示全部明细</el-checkbox><span class="muted">默认只展开需要确认的行；销售数量仅供参考，不代表进货比例。</span></div>
        <el-empty v-if="!visibleRows.length" description="当前没有待处理行，可查看下方汇总后带入单据" :image-size="45" />
        <div v-for="row in visibleRows" :key="row.rowNo" class="source-card">
          <div class="row-heading"><b>第 {{ row.rowNo }} 行 · {{ row.sourceName || row.skuCode }}</b><span>{{ row.boxCount == null ? '' : `${row.boxCount} 箱 × ${row.boxSpec} = ` }}{{ row.qty }} 基本单位 · ¥{{ Number(row.totalPrice).toFixed(2) }}</span></div>
          <p class="muted">表中编码：{{ row.skuCode || '未填写' }} <span v-if="row.hint"> · {{ row.hint }}</span></p>
          <p :class="row.needsConfirmation ? 'warning' : 'muted'">{{ row.matchMessage }}</p>
          <div class="candidate-list">
            <button v-for="candidate in row.candidates" :key="candidate.productId" type="button" class="candidate" :disabled="busy"
              :class="{ selected: row.allocations.some(a => a.productId === candidate.productId) }" @click="choose(row, candidate.productId)">
              <strong>{{ candidate.skuCode }} · {{ candidate.productName }}</strong>
              <span>{{ candidate.reason }}<b v-if="candidate.productId === row.suggestedProductId"> · 推荐核对</b></span>
              <span>已导入销售 {{ Number(candidate.salesQty) }} {{ candidate.unit || '件' }}<template v-if="candidate.lastSale"> · 最近 {{ candidate.lastSale.slice(0, 10) }}</template></span>
              <small v-if="candidate.firstSale">统计 {{ candidate.firstSale.slice(0, 10) }} 至 {{ candidate.lastSale?.slice(0, 10) }}</small>
            </button>
          </div>
          <div class="tools"><el-button link :disabled="busy" @click="manualRows.push(row.rowNo)">找不到？搜索全部商品档案</el-button></div>
          <ProductSelect v-if="manualRows.includes(row.rowNo)" :model-value="row.allocations[0]?.productId" lazy placeholder="搜索商品名称或编码，选择后重新核对" @update:model-value="(id: number | null) => { if (id) choose(row, id) }" />
          <div v-for="(allocation, index) in row.allocations" :key="index" class="allocation">
            <el-select v-model="allocation.productId" placeholder="选择实际口味" filterable :disabled="busy" @change="changed(row)">
              <el-option v-for="c in row.candidates" :key="c.productId" :value="c.productId" :label="`${c.skuCode} · ${c.productName}`" />
            </el-select>
            <span>{{ row.boxSpec ? '箱数' : '数量' }}</span><el-input-number style="width: 115px; flex: 0 0 115px" :model-value="allocation.qty == null ? undefined : allocation.qty / (row.boxSpec || 1)" :min="0.001" :precision="3" :controls="false" :disabled="busy" @update:model-value="(value: number | undefined) => setQuantity(row, index, value)" />
            <span class="muted">{{ row.boxSpec ? `箱 = ${allocation.qty || 0} 基本单位` : '基本单位' }}</span>
            <el-button v-if="row.allocations.length > 1" link type="danger" :disabled="busy" @click="row.allocations.splice(index, 1); changed(row)">移除</el-button>
          </div>
          <div class="tools">
            <el-button link :disabled="busy" @click="split(row)">＋ 混装，拆分口味</el-button>
            <el-button v-if="row.allocations.length && !row.confirmed" type="primary" plain size="small" :disabled="busy" @click="confirmRow(row)">确认本行关联</el-button>
            <el-tag v-if="row.confirmed" type="success">已确认 {{ row.allocations.map(a => candidateName(row, a.productId)).join(' / ') }}</el-tag>
          </div>
          <div v-if="row.errors.length" class="warning">{{ row.errors.join('；') }}</div>
        </div>
        <div class="tools"><b>② {{ selectedDate || '本次采购' }} 入库汇总</b><el-tag v-if="dirty" type="warning">关联已改动，点击重新核对更新汇总</el-tag></div>
        <el-table v-if="activeDay" :data="activeDay.lines" border max-height="300" class="summary-table">
          <el-table-column prop="skuCode" label="入库编码" width="125" />
          <el-table-column prop="productName" label="商品名称" min-width="245" />
          <el-table-column label="原表来源" min-width="155"><template #default="{ row }">第 {{ row.sourceRows.join('、') }} 行<small class="muted block">{{ row.sourceNames.join(' / ') }}</small></template></el-table-column>
          <el-table-column prop="qty" label="入库数量" width="105" />
          <el-table-column prop="unitPrice" label="折算单价" width="105" />
          <el-table-column label="原始金额" width="105"><template #default="{ row }">¥{{ Number(row.totalPrice).toFixed(2) }}</template></el-table-column>
          <template #empty><span>先确认上方商品关联，重新核对后显示汇总</span></template>
        </el-table>
        <p class="muted">本日期原表合计：{{ activeDay?.totalQty }} 基本单位 · ¥{{ Number(activeDay?.totalAmount || 0).toFixed(2) }}。只合并已确认指向同一编码的明细，其他口味销售仍沿用各自编码。</p>
        <p v-if="alreadySaved" class="warning">本次操作已经保存这个日期的单据，请在采购列表继续查看。可选择其他日期继续录入。</p>
      </template>
      <el-empty v-else description="直接上传整张拿货表，无需删除日期、说明或小计行" :image-size="60" />
    </div>
    <template #footer>
      <el-button :disabled="busy" @click="close">关闭</el-button>
      <el-button :disabled="busy || !data || !!parseErrors.length" @click="review(false)">重新核对</el-button>
      <el-button type="primary" :loading="busy" :disabled="!activeDay || !!parseErrors.length || alreadySaved" @click="review(true)">带入 {{ selectedDate || '本次采购' }} 入库单</el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
.intro { background: #edf5f0; color: #285f49; padding: 12px 16px; border-radius: 6px; line-height: 1.7; }
.tools, .row-heading { display: flex; align-items: center; flex-wrap: wrap; gap: 12px; margin: 14px 0; }
.tools .el-button { margin-left: 0; }
.muted { color: #718078; font-size: 12px; line-height: 1.7; overflow-wrap: anywhere; }
.block { display: block; }
.warning { color: #b46c14; font-size: 13px; line-height: 1.6; }
.date-cards { display: grid; grid-template-columns: repeat(auto-fit, minmax(180px, 1fr)); gap: 10px; }
.date-cards button { text-align: left; padding: 14px; background: #fff; border: 1px solid #dce4e0; border-radius: 6px; color: #35574b; cursor: pointer; }
.date-cards span, .date-cards small { display: block; margin-top: 6px; }
.date-cards .active { background: #edf5f0; border: 2px solid #2d7052; padding: 13px; }
.source-card { border: 1px solid #e5e9e7; border-radius: 7px; padding: 2px 16px; margin: 12px 0; background: #fffdf8; }
.candidate-list { display: grid; grid-template-columns: repeat(auto-fit, minmax(250px, 1fr)); gap: 8px; }
.candidate { text-align: left; padding: 12px; background: white; border: 1px solid #dae3de; border-radius: 6px; cursor: pointer; color: #39584b; line-height: 1.6; }
.candidate span, .candidate small { display: block; margin-top: 4px; }
.candidate.selected { border-color: #347559; background: #eff7f2; }
.allocation { display: flex; gap: 8px; align-items: center; flex-wrap: wrap; margin: 12px 0; }
.allocation .el-select { flex: 1; min-width: 220px; }
.allocation .el-input-number { width: 115px; }
.source-card :deep(.el-tag) { max-width: 100%; height: auto; white-space: normal; line-height: 1.7; }
:global(.purchase-workbook-dialog) { display: flex; flex-direction: column; max-height: 94vh; margin-bottom: 0; }
:global(.purchase-workbook-dialog .el-dialog__body) { flex: 1 1 auto; min-height: 0; overflow-y: auto; }
:global(.purchase-workbook-dialog .el-dialog__header) { flex-shrink: 0; }
:global(.purchase-workbook-dialog .el-dialog__footer) { flex-shrink: 0; display: flex; gap: 8px; flex-wrap: wrap; justify-content: flex-end; }
:global(.purchase-workbook-dialog .el-dialog__footer .el-button) { margin-left: 0; }
</style>
