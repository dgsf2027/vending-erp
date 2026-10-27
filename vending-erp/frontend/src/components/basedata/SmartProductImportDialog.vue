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
const manualEntry = ref(true)
let generation = 0
let loadedFile: File | null = null
const chosen = computed(() => rows.value.filter(r => selected.value.has(r.rowNo!)))
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
    rows.value = response.rows; token.value = response.token || ''; fileName.value = file.name
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
defineExpose({ importFile: replaceFile })
function edit(row: ProductImportRow, identity = false) {
  dirty.value.add(row.rowNo!)
  if (identity) {
    row.skuCode = ''; row.existingProductId = null; row.selectedProductId = null
    row.confirmNew = false; row.updateExisting = false; row.matchedProductName = null
  }
}
function pick(row: ProductImportRow, id: number | null) {
  edit(row, true); row.selectedProductId = id
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
    if (changed) { edit(row); count++ }
  }
  ElMessage.success('已补齐 ' + count + ' 行的空白单位/箱规，请重新检查')
}
async function recheck() {
  if (!chosen.value.length) return
  busy.value = true
  actionError.value = ''
  try {
    const response = await reviewSmartProductImport(chosen.value)
    const byRow = new Map(response.rows.map(r => [r.rowNo, r]))
    rows.value = rows.value.map(r => byRow.get(r.rowNo) || r)
    for (const r of response.rows) dirty.value.delete(r.rowNo!)
    if (response.errorCount) ElMessage.warning('还有 ' + response.errorCount + ' 行需要核对')
    else ElMessage.success('检查完成，可以确认入档')
  } catch (error) { actionError.value = '检查未完成，已保留填写内容：' + errorText(error) }
  finally { busy.value = false }
}
async function save() {
  if (!canSave.value || busy.value) return
  busy.value = true
  actionError.value = ''
  try {
    const submitted = new Set(chosen.value.map(r => r.rowNo))
    const response = await commitSmartProductImport(token.value, chosen.value, manualEntry.value)
    result.value = response
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
      <div v-if="embedded" class="workspace-heading"><h3>商品导入工作台</h3><span class="muted">直接填写或上传表格，确认后自动编号</span></div>
      <div class="intro">① 上传表格 / 手工添加 → ② 核对商品与包装 → ③ 检查并确认入档。新商品自动编号，已有商品沿用原编码。</div>
      <el-alert v-if="actionError" class="action-error" type="error" :closable="false" :title="actionError" show-icon />
      <div class="toolbar">
        <el-button type="primary" :disabled="busy" @click="input?.click()">{{ fileName ? '重新选择表格' : '选择商品表格' }}</el-button>
        <input ref="input" type="file" accept=".xls,.xlsx" hidden @change="fileChanged">
        <el-button :disabled="busy" @click="template">下载模板</el-button>
        <el-button :disabled="busy" @click="addRow">＋ 手工添加商品</el-button>
        <span class="muted">{{ fileName || '没有表格也可直接填写；后台编号、条码可留空' }}</span>
      </div>
      <div class="entry-grid">
        <div class="batch-fill">
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
        </div>
        <el-table ref="table" :data="visibleRows" row-key="rowNo" max-height="48vh" size="small" border>
          <template #empty>
            <div class="empty-workspace">
              <b>{{ rows.length ? '当前筛选下没有待检查商品' : saved.length ? '本次商品已保存，可继续添加' : '商品录入区已准备好' }}</b>
              <p>可以上传 Excel 批量识别，也可以直接添加商品；编码在确认入档时自动生成。</p>
              <el-button type="primary" plain :disabled="busy" @click="addRow">添加第一行商品</el-button>
            </div>
          </template>
          <el-table-column label="计入" width="55" fixed>
            <template #default="{ row }"><el-checkbox :model-value="selected.has(row.rowNo)" :aria-label="'计入第' + row.rowNo + '行'" @change="toggle(row, $event)" /></template>
          </el-table-column>
          <el-table-column label="商品 / 匹配结果" min-width="330">
            <template #default="{ row }">
              <div class="row-status"><span>第 {{ row.rowNo }} 行</span><el-tag size="small" :type="ready(row) ? 'success' : 'warning'">{{ dirty.has(row.rowNo) ? '修改后待检查' : row.action }}</el-tag></div>
              <el-input v-model="row.productName" placeholder="商品名（含口味、容量）" :aria-label="'商品名称第' + row.rowNo + '行'" @input="edit(row, true)" />
              <div class="match-hint" :class="{ warning: !ready(row) }">{{ row.errorMsg || row.matchReason }}</div>
              <ProductSelect :model-value="row.selectedProductId || row.existingProductId" :initial-product="initialProduct(row)" lazy
                placeholder="选择已有商品（输入名称、条码或编码）" @update:model-value="pick(row, $event)" />
              <div class="candidates">
                <el-button v-for="candidate in row.candidates" :key="candidate.id" size="small" @click="pick(row, candidate.id!)">
                  {{ candidate.skuCode }} · {{ candidate.productName }}
                </el-button>
              </div>
              <el-button link type="primary" @click="newProduct(row)">{{ row.confirmNew ? '已选择按新商品检查' : '确认为不同的新商品' }}</el-button>
            </template>
          </el-table-column>
          <el-table-column label="ERP 编码 / 原始标识" min-width="205">
            <template #default="{ row }">
              <div class="code">{{ row.skuCode || '确认入档时自动编号' }}</div>
              <div class="muted">后台编号</div>
              <el-input v-model="row.sourceCode" placeholder="原后台编号，可空" :aria-label="'后台编号第' + row.rowNo + '行'" @input="edit(row, true)" />
              <div class="muted">瓶身 / 包装条码</div>
              <el-input v-model="row.barcode" placeholder="保留前导零，可空" :aria-label="'条码第' + row.rowNo + '行'" @input="edit(row, true)" />
              <div v-if="row.sourceSpec" class="muted">原规格：{{ row.sourceSpec }}</div>
            </template>
          </el-table-column>
          <el-table-column label="包装（新商品必填）" min-width="155">
            <template #default="{ row }">
              <div class="muted">基本单位</div>
              <el-input v-model="row.unit" placeholder="瓶 / 袋 / 盒" :aria-label="'基本单位第' + row.rowNo + '行'" @input="edit(row)" />
              <div class="muted">每箱数量</div>
              <el-input v-model="row.boxSpec" placeholder="单件包装填1" :aria-label="'每箱数量第' + row.rowNo + '行'" @input="edit(row)" />
            </template>
          </el-table-column>
          <el-table-column label="资料与价格" min-width="190">
            <template #default="{ row }">
              <el-input v-model="row.category" placeholder="商品分类（可空）" aria-label="商品分类" @input="edit(row)" />
              <div class="price-fields">
                <el-input v-model="row.refCost" placeholder="成本 / 件" aria-label="参考成本" @input="edit(row)" />
                <el-input v-model="row.refPrice" placeholder="售价 / 件" aria-label="参考售价" @input="edit(row)" />
              </div>
              <el-checkbox v-if="row.existingProductId || row.selectedProductId" v-model="row.updateExisting" @change="edit(row)">更新已有商品资料</el-checkbox>
              <div class="muted">{{ row.updateExisting ? '将更新本行填有内容的资料和价格' : '已有商品默认只建立对应关系' }}</div>
            </template>
          </el-table-column>
          <el-table-column label="操作" width="66" fixed="right">
            <template #default="{ row }"><el-button link type="danger" :aria-label="'移除第' + row.rowNo + '行'" @click="removeRow(row)">移除</el-button></template>
          </el-table-column>
        </el-table>
        <div class="toolbar pagination">
          <span class="muted">改动后点击“重新检查”。取消勾选可暂不导入该行。</span>
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
      <span class="footer-note">{{ canSave ? '勾选行已检查，可以入档' : rows.length ? '请补齐资料、确认商品后重新检查' : '先添加商品或选择商品表格' }}</span>
      <el-button :disabled="busy || !chosen.length" @click="recheck">重新检查</el-button>
      <el-button type="primary" :disabled="!canSave || busy" :loading="busy" @click="save">确认入档（{{ chosen.length }} 行）</el-button>
    </div>
    <template v-if="!embedded" #footer>
      <span v-if="rows.length" class="footer-note">{{ canSave ? '已检查的勾选行可以入档' : '请补齐资料、确认对应商品后重新检查' }}</span>
      <el-button :disabled="busy" @click="close">{{ saved.length ? '完成' : '关闭' }}</el-button>
      <el-button :disabled="busy || !chosen.length" @click="recheck">重新检查</el-button>
      <el-button type="primary" :disabled="!canSave || busy" :loading="busy" @click="save">确认入档（{{ chosen.length }} 行）</el-button>
    </template>
  </component>
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
</style>
