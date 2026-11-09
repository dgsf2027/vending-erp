<script setup lang="ts">
import { computed, nextTick, ref, watch } from 'vue'
import { ElDialog, ElMessage, ElMessageBox, type TableInstance } from 'element-plus'
import {
  parseSmartProductImport, reviewSmartProductImport, commitSmartProductImport, downloadSmartProductTemplate,
  type ProductImportRow, type ProductImportCommitResp, type Product,
} from '@/api/basedata'
import ProductSelect from './ProductSelect.vue'

const props = defineProps<{ visible?: boolean; initialFile?: File | null; embedded?: boolean }>()
const emit = defineEmits<{ (e: 'update:visible', value: boolean): void; (e: 'saved'): void }>()
const rows = ref<ProductImportRow[]>([])
const selected = ref(new Set<number>())
const dirty = ref(new Set<number>())
const token = ref('')
const fileName = ref('')
const busy = ref(false)
const onlyReview = ref(false)
const page = ref(1)
const input = ref<HTMLInputElement>()
const result = ref<ProductImportCommitResp | null>(null)
const saved = ref<ProductImportRow[]>([])
const bulkUnit = ref('')
const bulkPack = ref('')
const table = ref<TableInstance>()
const workspace = ref<HTMLElement>()
const actionError = ref('')
const parseHints = ref<string[]>([])
const manualEntry = ref(true)
const showBatch = ref(false)
const detailRow = ref<ProductImportRow | null>(null)
let generation = 0
let loadedFile: File | null = null
const chosen = computed(() => rows.value.filter(r => selected.value.has(r.rowNo!)))
const chosenExisting = computed(() => chosen.value.filter(r => r.existingProductId || r.selectedProductId))
const allUpdateExisting = computed(() => chosenExisting.value.length > 0 && chosenExisting.value.every(r => r.updateExisting))
const someUpdateExisting = computed(() => chosenExisting.value.some(r => r.updateExisting))
const ready = (r: ProductImportRow) => !dirty.value.has(r.rowNo!) && ['新增商品', '已匹配'].includes(r.action || '')
const counts = computed(() => ({
  new: chosen.value.filter(r => ready(r) && r.action === '新增商品').length,
  matched: chosen.value.filter(r => ready(r) && r.action === '已匹配').length,
  review: chosen.value.filter(r => !ready(r)).length,
}))
const filtered = computed(() => rows.value.filter(r => !onlyReview.value || !ready(r)))
const visibleRows = computed(() => filtered.value.slice((page.value - 1) * 30, page.value * 30))
const canSave = computed(() => chosen.value.length > 0 && counts.value.review === 0 && (!!token.value || manualEntry.value))
watch(onlyReview, () => { page.value = 1 })
watch(() => filtered.value.length, length => { page.value = Math.min(page.value, Math.max(1, Math.ceil(length / 30))) })

watch(() => props.visible || props.embedded, async visible => {
  if (!visible) return
  if (props.initialFile && props.initialFile !== loadedFile) await replaceFile(props.initialFile)
}, { immediate: true })

function errorText(error: unknown) {
  const requestError = error as { response?: { status?: number; data?: { message?: string } }; message?: string }
  if (requestError.response?.status === 404) return '接口暂不可用，请刷新页面后重试；若仍失败，请联系维护人员。'
  return requestError.response?.data?.message || requestError.message || '请求失败，请稍后重试'
}

async function upload(file: File) {
  if (!/\.xlsx?$/i.test(file.name) || file.size > 20 * 1024 * 1024) {
    ElMessage.warning('请选择不超过 20MB 的 .xls 或 .xlsx 文件'); return
  }
  const version = ++generation
  actionError.value = ''
  busy.value = true
  try {
    const response = await parseSmartProductImport(file)
    if (version !== generation || !(props.visible || props.embedded)) return
    rows.value = displayRows(response.rows); token.value = response.token || ''; fileName.value = file.name
    parseHints.value = response.warnings || []
    detailRow.value = null
    selected.value = new Set(rows.value.map(r => r.rowNo!)); dirty.value = new Set()
    result.value = null; saved.value = []; page.value = 1; manualEntry.value = false; loadedFile = file
  } catch (error) {
    if (version === generation) actionError.value = '读取「' + file.name + '」失败：' + errorText(error)
  } finally { if (version === generation) busy.value = false }
}
async function fileChanged(event: Event) {
  const element = event.target as HTMLInputElement
  const file = element.files?.[0]
  element.value = ''
  if (file) await replaceFile(file)
}
async function replaceFile(file: File) {
  if (busy.value) return
  if (rows.value.length) {
    try { await ElMessageBox.confirm('重新上传将替换当前未入档的预览内容。', '重新选择表格', { type: 'warning' }) }
    catch { return }
  }
  await upload(file)
}
async function addRow() {
  if (busy.value || rows.value.length >= 5000) return
  if (!rows.value.length) {
    manualEntry.value = true; token.value = ''; fileName.value = ''; loadedFile = null
    parseHints.value = []
  }
  const rowNo = Math.max(0, ...rows.value.map(r => r.rowNo!), ...saved.value.map(r => r.rowNo!)) + 1
  rows.value.push({ rowNo, skuCode: '', productName: '', unit: bulkUnit.value.trim(), boxSpec: bulkPack.value.trim(), action: '待填写' })
  selected.value.add(rowNo); dirty.value.add(rowNo)
  onlyReview.value = false; page.value = Math.ceil(rows.value.length / 30)
  await nextTick()
  table.value?.setScrollTop(100000)
  await nextTick()
  workspace.value?.querySelector<HTMLInputElement>('input[aria-label="商品名称第' + rowNo + '行"]')?.focus({ preventScroll: true })
}
function removeRow(row: ProductImportRow) {
  rows.value = rows.value.filter(r => r.rowNo !== row.rowNo)
  selected.value.delete(row.rowNo!); dirty.value.delete(row.rowNo!)
}
async function template() {
  actionError.value = ''; busy.value = true
  try { await downloadSmartProductTemplate() }
  catch (error) { actionError.value = '模板下载失败：' + errorText(error) }
  finally { busy.value = false }
}
defineExpose({ importFile: replaceFile, busy, hasPendingRows: computed(() => rows.value.length > 0) })
function edit(row: ProductImportRow, identity = false) {
  dirty.value.add(row.rowNo!)
  if (identity) {
    row.skuCode = ''; row.existingProductId = null; row.selectedProductId = null
    row.confirmNew = false; row.updateExisting = false; row.matchedProductName = null
  }
}
function pick(row: ProductImportRow, id: number | null) {
  // 用户明确选择沿用已有档案时，放弃本行指定编码，避免把旧编号绑定给错误商品。
  if (id && row.requestedSkuCode) {
    if (row.sourceCode === row.requestedSkuCode) row.sourceCode = ''
    row.requestedSkuCode = ''
  }
  edit(row, true); row.selectedProductId = id
}
function changeCode(row: ProductImportRow, value: string) {
  if (row.sourceCode === row.requestedSkuCode) row.sourceCode = value
  row.requestedSkuCode = value
  edit(row, true)
}
function newProduct(row: ProductImportRow) {
  edit(row, true); row.confirmNew = true
}
function initialProduct(row: ProductImportRow): Product | null {
  const id = row.selectedProductId || row.existingProductId
  return row.candidates?.find(p => p.id === id)
    || (id === row.existingProductId && id ? { id, skuCode: row.skuCode, productName: row.matchedProductName || row.productName } : null)
}
function toggle(row: ProductImportRow, value: unknown) {
  if (value) selected.value.add(row.rowNo!)
  else selected.value.delete(row.rowNo!)
}
function fillBlanks() {
  if (!bulkUnit.value.trim() && !bulkPack.value.trim()) return
  let count = 0
  for (const row of chosen.value) {
    let changed = false
    if (!row.unit?.trim() && bulkUnit.value.trim()) { row.unit = bulkUnit.value.trim(); changed = true }
    if (!row.boxSpec?.trim() && bulkPack.value.trim()) { row.boxSpec = bulkPack.value.trim(); changed = true }
    if (changed) { changeCaseCost(row); count++ }
  }
  ElMessage.success('已补齐 ' + count + ' 行的空白单位/箱规，请重新检查')
}
function setUpdateExisting(value: unknown) {
  for (const row of chosenExisting.value) { row.updateExisting = value === true; edit(row) }
}
function nonnegativeNumber(value?: string | null) {
  if (!value?.trim() || !/^(?:\d+(?:\.\d*)?|\.\d+)$/.test(value.trim())) return null
  const number = Number(value)
  return Number.isFinite(number) && number >= 0 ? number : null
}
function displayRows(values: ProductImportRow[]) {
  return values.map(row => {
    const cost = nonnegativeNumber(row.refCost)
    return cost === null ? row : { ...row, refCost: cost.toFixed(4) }
  })
}
function changeCaseCost(row: ProductImportRow) {
  const pack = nonnegativeNumber(row.boxSpec), total = nonnegativeNumber(row.caseCost)
  if (pack && Number.isInteger(pack) && total !== null) row.refCost = (total / pack).toFixed(4)
  edit(row)
}
function changeUnitCost(row: ProductImportRow) {
  const pack = nonnegativeNumber(row.boxSpec), cost = nonnegativeNumber(row.refCost)
  if (pack && Number.isInteger(pack) && cost !== null) row.caseCost = (cost * pack).toFixed(2)
  edit(row)
}
async function recheck(quiet = false) {
  if (!chosen.value.length || busy.value) return false
  busy.value = true
  actionError.value = ''
  try {
    const response = await reviewSmartProductImport(chosen.value)
    const byRow = new Map(displayRows(response.rows).map(r => [r.rowNo, r]))
    rows.value = rows.value.map(r => byRow.get(r.rowNo) || r)
    for (const r of response.rows) dirty.value.delete(r.rowNo!)
    if (response.errorCount) ElMessage.warning('还有 ' + response.errorCount + ' 行需要核对')
    else if (!quiet) ElMessage.success('检查完成，可以确认入档')
    return response.errorCount === 0
  } catch (error) { actionError.value = '检查未完成，已保留填写内容：' + errorText(error) }
  finally { busy.value = false }
  return false
}
async function checkAndSave() {
  if (busy.value || !chosen.value.length) return
  // 任何改动都重新请求校验；只有本次校验成功才能进入保存，网络错误不能复用旧结果。
  if (!canSave.value && !await recheck(true)) { onlyReview.value = true; return }
  await save()
}
async function save() {
  if (!canSave.value || busy.value) return
  busy.value = true
  actionError.value = ''
  try {
    const submitted = new Set(chosen.value.map(r => r.rowNo))
    const response = await commitSmartProductImport(token.value, chosen.value, manualEntry.value)
    result.value = response
    detailRow.value = null
    saved.value.push(...(response.savedRows || []))
    const failed = new Map((response.failedRows || []).map(r => [r.rowNo, r]))
    rows.value = rows.value.flatMap(r => failed.has(r.rowNo) ? [failed.get(r.rowNo)!] : submitted.has(r.rowNo) ? [] : [r])
    selected.value = new Set((response.failedRows || []).map(r => r.rowNo!)); dirty.value = new Set()
    page.value = 1
    emit('saved')
    if (response.failed) ElMessage.warning('已保留失败行，请修改后重新检查；成功行无需重复提交')
    else ElMessage.success('商品已入档，下方可查看编码')
  } catch (error) { actionError.value = '保存未完成，已保留填写内容，请重新检查后重试：' + errorText(error) }
  finally { busy.value = false }
}
function close() {
  if (busy.value) return
  generation++
  emit('update:visible', false)
}
function downloadCodes() {
  // Excel 公式前缀转成文本，避免商品名称被当成公式执行。
  const cell = (v: unknown) => '"' + String(v ?? '').replace(/^[=+@\-\t\r]/, "'$&").replace(/"/g, '""') + '"'
  const lines = [['ERP编码', '商品名称', '后台商品编号', '商品条形码', '结果'],
    ...saved.value.map(r => [r.skuCode, r.matchedProductName || r.productName, r.sourceCode, r.barcode, r.action])]
  const blob = new Blob(['\uFEFF' + lines.map(line => line.map(cell).join(',')).join('\r\n')], { type: 'text/csv;charset=utf-8' })
  const url = URL.createObjectURL(blob)
  const link = document.createElement('a'); link.href = url; link.download = '商品编码对照.csv'; link.click()
  URL.revokeObjectURL(url)
}
</script>

<template>
  <component :is="embedded ? 'section' : ElDialog" :model-value="visible" title="商品列表 · 自动建档与编号" width="96vw" top="3vh"
    :close-on-click-modal="false" :close-on-press-escape="!busy" :show-close="!busy" :before-close="close"
    class="smart-product-import" :class="{ 'embedded-workspace': embedded }" @closed="busy = false">
    <div ref="workspace" v-loading="busy" element-loading-text="正在检查和处理商品…">
      <div v-if="embedded" class="workspace-heading"><h3>商品导入工作台</h3><span class="muted">直接上传商品价格表，核对后入档</span></div>
      <div class="intro">支持你的商品价格表：商品编码、商品名称、售价、拿货价、每箱数量、整箱价。自动跳过顶部标题和说明，已有编码直接使用。</div>
      <el-alert v-if="actionError" class="action-error" type="error" :closable="false" :title="actionError" show-icon />
      <el-alert v-if="parseHints.length" class="parse-hints" type="info" :closable="false" :title="parseHints.join(' ')" show-icon />
      <div class="toolbar">
        <el-button type="primary" :disabled="busy" @click="input?.click()">{{ fileName ? '重新选择表格' : '选择商品表格' }}</el-button>
        <input ref="input" type="file" accept=".xls,.xlsx" hidden @change="fileChanged">
        <el-button :disabled="busy" @click="template">下载模板</el-button>
        <el-button :disabled="busy" @click="addRow">＋ 手工添加商品</el-button>
        <el-button link @click="showBatch = !showBatch">{{ showBatch ? '收起批量设置' : '批量设置单位 / 箱规' }}</el-button>
        <span class="muted">{{ fileName || '支持 .xls / .xlsx，也可手工添加' }}</span>
      </div>
      <div class="entry-grid">
        <div v-if="showBatch" class="batch-fill">
          <b>批量补齐空白包装</b>
          <el-input v-model="bulkUnit" placeholder="基本单位，如瓶" aria-label="批量基本单位" style="width: 145px" />
          <el-input v-model="bulkPack" placeholder="每箱数量，如24" aria-label="批量每箱数量" style="width: 155px" />
          <el-button :disabled="!chosen.length" @click="fillBlanks">填入勾选行</el-button>
          <span class="muted">只填空白项；不同包装可在行内调整。</span>
        </div>
        <div class="toolbar summary">
          <b>已勾选 {{ chosen.length }} / {{ rows.length }} 行</b>
          <el-tag type="success">待新增 {{ counts.new }}</el-tag>
          <el-tag>已匹配 {{ counts.matched }}</el-tag>
          <el-tag type="warning">待检查 {{ counts.review }}</el-tag>
          <el-checkbox v-model="onlyReview">只看待检查</el-checkbox>
          <el-button link @click="selected = new Set(rows.map(r => r.rowNo!))">全选</el-button>
          <el-button link @click="selected = new Set()">全不选</el-button>
          <el-checkbox v-if="chosenExisting.length" :model-value="allUpdateExisting" :indeterminate="someUpdateExisting && !allUpdateExisting" @change="setUpdateExisting">更新已有商品资料和价格</el-checkbox>
        </div>
        <el-table ref="table" :data="visibleRows" row-key="rowNo" max-height="58vh" size="small" border>
          <template #empty>
            <div class="empty-workspace">
              <b>{{ rows.length ? '当前筛选下没有待检查商品' : saved.length ? '本次商品已保存，可继续添加' : '商品录入区已准备好' }}</b>
              <p>可直接上传含售价、拿货价和箱规的商品价格表。没有编码可留空，旧版模板也可使用。</p>
              <el-button type="primary" plain :disabled="busy" @click="addRow">添加第一行商品</el-button>
            </div>
          </template>
          <el-table-column label="计入" width="50" fixed>
            <template #default="{ row }"><el-checkbox :model-value="selected.has(row.rowNo)" :aria-label="'计入第' + row.rowNo + '行'" @change="toggle(row, $event)" /></template>
          </el-table-column>
          <el-table-column label="商品编码" width="125">
            <template #default="{ row }">
              <el-input :model-value="row.requestedSkuCode || row.skuCode || ''" placeholder="留空自动编号"
                :aria-label="'商品编码第' + row.rowNo + '行'" @input="changeCode(row, $event)" />
            </template>
          </el-table-column>
          <el-table-column label="商品名称" min-width="245">
            <template #default="{ row }">
              <el-input v-model="row.productName" placeholder="商品名（含口味、容量）" :aria-label="'商品名称第' + row.rowNo + '行'" @input="edit(row, true)" />
              <div v-if="row.errorMsg && !dirty.has(row.rowNo)" class="match-hint warning">{{ row.errorMsg }}</div>
            </template>
          </el-table-column>
          <el-table-column label="销售单价（元）" width="110">
            <template #default="{ row }">
              <el-input v-model="row.refPrice" placeholder="选填" :aria-label="'销售单价第' + row.rowNo + '行'" @input="edit(row)" />
            </template>
          </el-table-column>
          <el-table-column label="拿货价（元/件）" width="120">
            <template #default="{ row }">
              <el-input v-model="row.refCost" placeholder="单瓶/罐价" :aria-label="'拿货价第' + row.rowNo + '行'" @input="changeUnitCost(row)" />
            </template>
          </el-table-column>
          <el-table-column label="每箱数量" width="90">
            <template #default="{ row }">
              <el-input v-model="row.boxSpec" placeholder="单件填1" :aria-label="'每箱数量第' + row.rowNo + '行'" @input="changeCaseCost(row)" />
            </template>
          </el-table-column>
          <el-table-column label="整箱价（元）" width="110">
            <template #default="{ row }">
              <el-input v-model="row.caseCost" placeholder="选填" :aria-label="'整箱拿货价第' + row.rowNo + '行'" @input="changeCaseCost(row)" />
            </template>
          </el-table-column>
          <el-table-column label="单位" width="70">
            <template #default="{ row }">
              <el-input v-model="row.unit" placeholder="如：瓶" :aria-label="'基本单位第' + row.rowNo + '行'" @input="edit(row)" />
            </template>
          </el-table-column>
          <el-table-column label="处理结果" width="120">
            <template #default="{ row }">
              <el-tag size="small" :type="ready(row) ? 'success' : 'warning'">{{ dirty.has(row.rowNo) ? '待检查' : row.action === '已匹配' ? '沿用已有商品' : row.action === '新增商品' ? '可新增' : '需处理' }}</el-tag>
              <div v-if="row.existingProductId" class="match-hint">{{ row.updateExisting ? '将更新资料和价格' : '保留原资料和价格' }}</div>
              <el-button v-if="!ready(row) && row.candidates?.length" class="resolve-link" link type="primary" :aria-label="'处理第' + row.rowNo + '行'" @click="detailRow = row">核对匹配</el-button>
            </template>
          </el-table-column>
          <el-table-column label="操作" width="115" fixed="right">
            <template #default="{ row }">
              <el-button link type="primary" :aria-label="'更多资料第' + row.rowNo + '行'" @click="detailRow = row">更多资料</el-button>
              <el-button link type="danger" :aria-label="'移除第' + row.rowNo + '行'" @click="removeRow(row)">移除</el-button>
            </template>
          </el-table-column>
        </el-table>
        <div class="toolbar pagination">
          <span class="muted">价格按基本单位保存。修改整箱价或每箱数量会重算拿货价；条码、分类在“更多资料”中填写。</span>
          <el-pagination v-model:current-page="page" :total="filtered.length" :page-size="30" layout="prev, pager, next, total" />
        </div>
      </div>
      <el-alert v-if="result" :type="result.failed ? 'warning' : 'success'" :closable="false"
        :title="'本次：新增 ' + result.created + ' · 沿用编码 ' + (result.matched || 0) + ' · 更新资料 ' + result.updated + ' · 未入档 ' + result.failed"
        description="成功行已保存；失败行保留在上方，可修改后再检查。原始表格和错误明细可在导入中心的批次历史查看。" />
      <div v-if="saved.length" class="saved-codes">
        <div class="toolbar"><b>已保存的商品编码（{{ saved.length }}）</b><el-button size="small" @click="downloadCodes">下载编码对照表</el-button></div>
        <el-table :data="saved" max-height="220" size="small">
          <el-table-column prop="skuCode" label="ERP 编码" width="145" />
          <el-table-column label="商品名称" min-width="220"><template #default="{ row }">{{ row.matchedProductName || row.productName }}</template></el-table-column>
          <el-table-column prop="sourceCode" label="后台商品编号" />
          <el-table-column prop="barcode" label="条码" />
          <el-table-column prop="action" label="结果" width="100" />
        </el-table>
      </div>
    </div>
    <div v-if="embedded" class="workspace-actions">
      <span class="footer-note">{{ canSave ? '勾选行已准备好' : rows.length ? '填写完成后点击右侧按钮，系统会先检查' : '先添加商品或选择商品表格' }}</span>
      <el-button :disabled="busy || !chosen.length" @click="recheck()">重新检查</el-button>
      <el-button type="primary" :disabled="!chosen.length || busy" :loading="busy" @click="checkAndSave">{{ canSave ? '确认入档' : '检查并导入' }}（{{ chosen.length }} 行）</el-button>
    </div>
    <template v-if="!embedded" #footer>
      <span v-if="rows.length" class="footer-note">{{ canSave ? '已检查的勾选行可以入档' : '请补齐资料、确认对应商品后重新检查' }}</span>
      <el-button :disabled="busy" @click="close">{{ saved.length ? '完成' : '关闭' }}</el-button>
      <el-button :disabled="busy || !chosen.length" @click="recheck()">重新检查</el-button>
      <el-button type="primary" :disabled="!chosen.length || busy" :loading="busy" @click="checkAndSave">{{ canSave ? '确认入档' : '检查并导入' }}（{{ chosen.length }} 行）</el-button>
    </template>
  </component>
  <el-dialog :model-value="!!detailRow" title="更多资料 / 商品匹配" width="620px" append-to-body :close-on-click-modal="false" @close="detailRow = null">
    <div v-if="detailRow" class="product-details">
      <b>{{ detailRow.productName || '未填写商品名称' }}</b>
      <p class="muted">{{ detailRow.errorMsg || detailRow.matchReason || '以下资料选填' }}</p>
      <el-form label-position="top">
        <el-form-item label="对应已有商品（选中后沿用它的编码）">
          <ProductSelect :model-value="detailRow.selectedProductId || detailRow.existingProductId" :initial-product="initialProduct(detailRow)" lazy
            placeholder="输入名称、条码或编码查找" @update:model-value="pick(detailRow!, $event)" />
          <div class="candidates">
            <el-button v-for="candidate in detailRow.candidates" :key="candidate.id" size="small" @click="pick(detailRow!, candidate.id!)">{{ candidate.skuCode }} · {{ candidate.productName }}</el-button>
          </div>
          <el-button v-if="!detailRow.requestedSkuCode" link type="primary" @click="newProduct(detailRow)">{{ detailRow.confirmNew ? '已选择按新商品检查' : '确认为不同的新商品' }}</el-button>
        </el-form-item>
        <div class="detail-grid">
          <el-form-item label="条码"><el-input v-model="detailRow.barcode" placeholder="保留前导零，可空" :aria-label="'条码第' + detailRow.rowNo + '行'" @input="edit(detailRow!, true)" /></el-form-item>
          <el-form-item label="来源后台编号"><el-input v-model="detailRow.sourceCode" placeholder="原后台编号，可空" :aria-label="'后台编号第' + detailRow.rowNo + '行'" @input="edit(detailRow!, true)" /></el-form-item>
          <el-form-item label="商品分类"><el-input v-model="detailRow.category" aria-label="商品分类" @input="edit(detailRow!)" /></el-form-item>
          <el-form-item label="参考成本 / 基本单位"><el-input v-model="detailRow.refCost" aria-label="参考成本" @input="changeUnitCost(detailRow!)" /></el-form-item>
          <el-form-item label="参考售价 / 基本单位"><el-input v-model="detailRow.refPrice" aria-label="参考售价" @input="edit(detailRow!)" /></el-form-item>
        </div>
        <el-checkbox v-if="detailRow.existingProductId || detailRow.selectedProductId" v-model="detailRow.updateExisting" @change="edit(detailRow!)">更新已有商品资料</el-checkbox>
        <div class="muted">已有商品默认只建立对应关系；勾选后才更新本行填写的资料与价格。</div>
      </el-form>
    </div>
    <template #footer><el-button type="primary" @click="detailRow = null">完成，返回列表</el-button></template>
  </el-dialog>
</template>

<style scoped>
.intro { padding: 10px 14px; background: #eef6f1; color: #295b47; border-radius: 6px; }
.toolbar, .batch-fill { display: flex; align-items: center; flex-wrap: wrap; gap: 10px; margin: 12px 0; }
.batch-fill { padding: 12px; background: #f5f7fa; border-radius: 6px; }
.muted, .match-hint { font-size: 12px; line-height: 1.6; color: #66756e; margin: 4px 0; }
.warning { color: #ad6509; }
.row-status { display: flex; gap: 10px; align-items: center; margin-bottom: 6px; color: #777; }
.code { color: #28674d; font-weight: 700; margin-bottom: 8px; overflow-wrap: anywhere; }
.candidates { display: flex; gap: 4px; flex-wrap: wrap; margin-top: 6px; }
.candidates .el-button { margin: 0; height: auto; white-space: normal; text-align: left; padding: 6px; }
.price-fields { display: flex; gap: 5px; margin-top: 7px; }
.pagination { justify-content: space-between; }
.footer-note { color: #66756e; font-size: 12px; margin-right: 12px; }
.saved-codes { margin-top: 16px; }
.embedded-workspace { padding: 18px; border: 1px solid #d9e5dd; border-radius: 8px; background: white; margin-bottom: 16px; scroll-margin-top: 18px; min-width: 0; }
.workspace-heading { display: flex; gap: 14px; align-items: baseline; flex-wrap: wrap; margin-bottom: 12px; }
.workspace-heading h3 { margin: 0; font-size: 20px; color: #25513e; }
.workspace-actions { display: flex; align-items: center; justify-content: flex-end; flex-wrap: wrap; gap: 8px; padding: 12px 0 0; position: sticky; bottom: 0; background: white; z-index: 2; }
.empty-workspace { padding: 22px 14px; line-height: 1.8; color: #65776b; }
.empty-workspace p { margin: 7px 0 12px; font-size: 13px; }
.action-error { margin-top: 12px; }
.parse-hints { margin-top: 12px; }
.resolve-link { display: block; margin: 5px 0 0; }
.detail-grid { display: grid; grid-template-columns: 1fr 1fr; gap: 0 16px; }
.product-details .el-form-item { margin-bottom: 16px; }
:deep(.el-table .cell) { padding-top: 7px; padding-bottom: 7px; }
@media (max-width: 640px) { .detail-grid { grid-template-columns: 1fr; } .embedded-workspace { padding: 10px; } }
</style>
