<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, ref, watch } from 'vue'
import { isAxiosError } from 'axios'
import { ElMessage } from 'element-plus'
import type { TableInstance } from 'element-plus'
import ProductSelect from '@/components/basedata/ProductSelect.vue'
import { getProduct } from '@/api/basedata'
import type { Supplier } from '@/api/basedata'
import {
  previewPurchasePhoto, type PurchasePhotoCandidate, type PurchasePhotoDraft, type PurchasePhotoLine, type PurchasePhotoPreview,
} from '@/api/purchase'

const props = defineProps<{ modelValue: boolean; suppliers: Supplier[] }>()
const emit = defineEmits<{
  (e: 'update:modelValue', value: boolean): void
  (e: 'import', draft: PurchasePhotoDraft): void
}>()

type UnitMode = 'base' | 'box'
type ReviewLine = PurchasePhotoLine & { include: boolean; productLoading?: boolean; manual?: boolean }
const busy = ref(false)
const imageUrl = ref('')
const fileName = ref('')
const selectedFile = ref<File | null>(null)
const requestError = ref('')
const manualMode = ref(false)
const elapsedSeconds = ref(0)
const preview = ref<PurchasePhotoPreview | null>(null)
const lines = ref<ReviewLine[]>([])
const supplierId = ref<number | null>(null)
const bizDate = ref('')
const acknowledged = ref(false)
const batchUnit = ref<UnitMode | 'recognized'>('recognized')
const onlyPending = ref(false)
const showOriginal = ref(true)
const reviewTable = ref<TableInstance>()
const productControls = new Map<number, InstanceType<typeof ProductSelect>>()
const highlightedRow = ref<number | null>(null)
const lastRemoved = ref<Array<{ line: ReviewLine; index: number }>>([])
let nextRowNo = 1
let generation = 0
let controller: AbortController | null = null
let elapsedTimer: ReturnType<typeof setInterval> | null = null

function stopRecognition() {
  generation++
  controller?.abort()
  controller = null
  if (elapsedTimer !== null) clearInterval(elapsedTimer)
  elapsedTimer = null
  busy.value = false
}

function clear() {
  stopRecognition()
  if (imageUrl.value) URL.revokeObjectURL(imageUrl.value)
  imageUrl.value = ''
  fileName.value = ''
  selectedFile.value = null
  requestError.value = ''
  manualMode.value = false
  elapsedSeconds.value = 0
  preview.value = null
  lines.value = []
  supplierId.value = null
  bizDate.value = ''
  acknowledged.value = false
  batchUnit.value = 'recognized'
  onlyPending.value = false
  highlightedRow.value = null
  lastRemoved.value = []
  productControls.clear()
  nextRowNo = 1
}
watch(() => props.modelValue, (open) => { if (!open) clear() })
watch(lines, () => { acknowledged.value = false }, { deep: true })
onBeforeUnmount(clear)

async function onPick(event: Event) {
  const input = event.target as HTMLInputElement
  const file = input.files?.[0]
  input.value = ''
  if (!file) return
  if (!/\.(jpe?g|png)$/i.test(file.name) || !file.size || file.size > 10 * 1024 * 1024) {
    ElMessage.error('请选择不超过 10MB 的 JPG 或 PNG 照片')
    return
  }
  clear()
  selectedFile.value = file
  fileName.value = file.name
  imageUrl.value = URL.createObjectURL(file)
  await recognize()
}

function errorMessage(error: unknown): string {
  if (isAxiosError(error)) {
    if (error.code === 'ECONNABORTED' || error.code === 'ETIMEDOUT') {
      return '识别等待超过 150 秒，请稍后重新识别，或换一张更清晰的照片。'
    }
    if (error.code === 'ERR_NETWORK') return '无法连接识别服务，请检查网络后重新识别。'
    const status = error.response?.status
    if (status === 401) return '登录已过期，请重新登录后识别。'
    if (status === 403) return '当前账号没有照片识别权限，请联系管理员。'
    if (status === 413) return '照片过大，请选择不超过 10MB 的 JPG 或 PNG 照片。'
    if (status === 504) return '识别服务响应超时，请稍后重新识别。'
    if (status === 502 || status === 503) return '识别服务暂时不可用，请稍后重新识别。'
    const message = error.response?.data?.message
    if (typeof message === 'string' && /[\u4e00-\u9fff]/.test(message)) return message
  }
  if (error instanceof Error && /[\u4e00-\u9fff]/.test(error.message)) return error.message
  return '照片识别失败，请重新识别，或换一张清晰、完整的单据照片。'
}

async function recognize() {
  // 已有核对结果或手工明细时不重试，避免覆盖用户修改。
  if (!selectedFile.value || busy.value || preview.value || manualMode.value) return
  const requestId = ++generation
  const requestController = new AbortController()
  controller = requestController
  requestError.value = ''
  elapsedSeconds.value = 0
  const startedAt = Date.now()
  elapsedTimer = setInterval(() => {
    elapsedSeconds.value = Math.floor((Date.now() - startedAt) / 1000)
  }, 1000)
  busy.value = true
  try {
    const result = await previewPurchasePhoto(selectedFile.value, requestController.signal)
    if (requestId !== generation) return
    if (!result || !Array.isArray(result.rows) || !result.rows.length) {
      throw new Error('未识别到商品明细，请换一张清晰、完整的单据照片，或对照原图手工录入。')
    }
    lines.value = result.rows.map((line) => ({
      ...line,
      sourceUnit: line.sourceUnit ?? null,
      sourceSpec: line.sourceSpec ?? null,
      include: !line.crossedOut,
      unitMode: line.unitMode === 'base' || line.unitMode === 'box' ? line.unitMode : null,
    }))
    nextRowNo = Math.max(...lines.value.map(line => line.rowNo)) + 1
    preview.value = result
    supplierId.value = result.supplierId
    bizDate.value = result.bizDate || ''
  } catch (error) {
    if (requestId === generation && !requestController.signal.aborted) {
      requestError.value = errorMessage(error)
    }
  } finally {
    if (requestId === generation) {
      busy.value = false
      controller = null
      if (elapsedTimer !== null) clearInterval(elapsedTimer)
      elapsedTimer = null
    }
  }
}

function startManualEntry() {
  if (busy.value || !requestError.value || preview.value) return
  manualMode.value = true
  requestError.value = ''
  addManualLine()
}

function setProductControl(rowNo: number, control: unknown) {
  if (control) productControls.set(rowNo, control as InstanceType<typeof ProductSelect>)
  else productControls.delete(rowNo)
}

async function addManualLine() {
  const line: ReviewLine = {
    rowNo: nextRowNo++, sourceName: null, productId: null,
    sourceUnit: null, sourceSpec: null,
    productName: null, skuCode: null, boxSpec: null, unit: null,
    qty: null, unitPrice: null, sourceAmount: null, calculatedAmount: null,
    crossedOut: false, uncertain: false, warnings: [], include: true, manual: true,
    unitMode: batchUnit.value === 'recognized' ? null : batchUnit.value,
  }
  lines.value.push(line)
  highlightedRow.value = line.rowNo
  // 新行始终出现在表格底部，主动滚动并聚焦，避免用户误以为按钮没生效而反复点击。
  await nextTick()
  if (!props.modelValue || !lines.value.includes(line)) return
  reviewTable.value?.setScrollTop(Number.MAX_SAFE_INTEGER)
  reviewTable.value?.setScrollLeft(0)
  productControls.get(line.rowNo)?.focus()
}

const emptyManualLines = computed(() => lines.value.filter(line => line.manual &&
  !line.productId && !line.sourceName?.trim() && line.qty == null && line.unitPrice == null && line.boxSpec == null))

function removeLines(targets: ReviewLine[]) {
  const rowNumbers = new Set(targets.map(line => line.rowNo))
  lastRemoved.value = lines.value.flatMap((line, index) => rowNumbers.has(line.rowNo) ? [{ line, index }] : [])
  lines.value = lines.value.filter(line => !rowNumbers.has(line.rowNo))
  if (highlightedRow.value != null && rowNumbers.has(highlightedRow.value)) highlightedRow.value = null
}

function undoRemove() {
  for (const { line, index } of lastRemoved.value) lines.value.splice(Math.min(index, lines.value.length), 0, line)
  lastRemoved.value = []
}

const included = computed(() => lines.value.filter((line) => line.include))
function detectedUnit(line: ReviewLine): UnitMode | null {
  const source = line.sourceUnit?.normalize('NFKC').replace(/\s+/g, '')
  if (!source) return null
  if (source === line.unit?.normalize('NFKC').replace(/\s+/g, '')) return 'base'
  return ['箱', '件', '整箱', '整件'].includes(source) ? 'box' : null
}
function applyBatchUnit() {
  for (const line of included.value) {
    line.unitMode = batchUnit.value === 'recognized' ? detectedUnit(line) : batchUnit.value
  }
}
function includeLine(line: ReviewLine) {
  if (line.include && batchUnit.value !== 'recognized') line.unitMode = batchUnit.value
}
function chooseCandidate(line: ReviewLine, candidate: PurchasePhotoCandidate) {
  line.productId = candidate.productId
  line.productName = candidate.productName
  line.skuCode = candidate.skuCode
  line.boxSpec = candidate.boxSpec == null ? null : Number(candidate.boxSpec)
  line.unit = candidate.unit
  line.matchKind = 'manual'
  line.productLoading = false
  line.unitMode = batchUnit.value === 'recognized' ? detectedUnit(line) : batchUnit.value
}
function otherCandidates(line: ReviewLine) {
  return (line.candidates || []).filter(candidate => candidate.productId !== line.productId)
}
async function selectProduct(line: ReviewLine, id: number | null) {
  line.productId = id
  line.productName = null
  line.skuCode = null
  line.matchKind = 'manual'
  line.unitMode = batchUnit.value === 'recognized' ? null : batchUnit.value
  line.boxSpec = null
  line.unit = null
  line.productLoading = !!id
  if (!id) return
  try {
    const product = await getProduct(id)
    if (line.productId === id) {
      line.boxSpec = product.boxSpec == null ? null : Number(product.boxSpec)
      line.unit = product.unit || null
      line.productName = product.productName
      line.skuCode = product.skuCode
      line.unitMode = batchUnit.value === 'recognized' ? detectedUnit(line) : batchUnit.value
      line.productLoading = false
    }
  } catch {
    if (line.productId === id) {
      line.productLoading = false
      line.productId = null
      ElMessage.error('读取商品资料失败，请重新选择商品')
    }
  }
}
function purchaseQty(line: ReviewLine): number {
  return Number(line.qty || 0) * (line.unitMode === 'box' ? Number(line.boxSpec || 0) : 1)
}
function basicPrice(line: ReviewLine): number {
  const divisor = line.unitMode === 'box' ? Number(line.boxSpec || 0) : 1
  return divisor > 0 ? Math.round(Number(line.unitPrice || 0) / divisor * 10000) / 10000 : 0
}
const total = computed(() => included.value.reduce((sum, line) =>
  sum + Number(line.qty || 0) * Number(line.unitPrice || 0), 0))
const totalDiff = computed(() => preview.value?.declaredTotal != null &&
  Math.abs(total.value - Number(preview.value.declaredTotal)) > 0.01)
function amountDiff(line: ReviewLine) {
  return line.sourceAmount != null && Math.abs(Number(line.sourceAmount)
    - Number(line.qty || 0) * Number(line.unitPrice || 0)) > 0.01
}
function issues(line: ReviewLine): string[] {
  const messages: string[] = []
  if (line.productLoading) messages.push('正在读取商品资料')
  else if (!line.productId) messages.push(line.candidates?.length ? '请选择一个候选商品' : '请搜索商品档案')
  if (!line.unitMode) messages.push('请在上方统一选择数量单位')
  if (line.unitMode === 'box' && !(Number.isInteger(Number(line.boxSpec)) && Number(line.boxSpec) > 0)) messages.push('请填写每箱数量')
  if (!(Number(line.qty) > 0) || !(Number(line.unitPrice) > 0)) messages.push('请补齐数量和单价')
  return messages
}
const unresolved = computed(() => included.value.filter(line => issues(line).length))
const suggested = computed(() => included.value.filter(line => line.matchKind === 'suggested'))
const pending = (line: ReviewLine) => line.include && (issues(line).length > 0 || line.matchKind === 'suggested' || line.uncertain || amountDiff(line))
// 新增行在填写期间保持可见，避免最后一项输入到一半就被“只看待核对”筛掉。
const visibleLines = computed(() => onlyPending.value ? lines.value.filter(line =>
  pending(line) || line.rowNo === highlightedRow.value) : lines.value)
const pendingCount = computed(() => lines.value.filter(pending).length)
function rowClass({ row }: { row: ReviewLine }): string {
  return [!row.include ? 'photo-excluded-row' : issues(row).length ? 'photo-pending-row' : '',
    row.rowNo === highlightedRow.value ? 'photo-new-row' : ''].filter(Boolean).join(' ')
}
const reviewNeeded = computed(() => totalDiff.value || suggested.value.length > 0 ||
  included.value.some(line => line.uncertain || amountDiff(line)))
const headerWarnings = computed(() => (preview.value?.warnings || []).filter(warning =>
  !(warning.includes('日期') && bizDate.value) && !(warning.includes('供应商') && supplierId.value) && !warning.includes('合计')))
const canApply = computed(() => !busy.value && !requestError.value &&
  (!!preview.value?.rows.length || manualMode.value) && !!supplierId.value && !!bizDate.value &&
  included.value.length > 0 && included.value.every((line) =>
    !line.productLoading && !!line.productId && !!line.unitMode && Number(line.qty) > 0 && Number(line.unitPrice) > 0 &&
    (line.unitMode !== 'box' || (Number.isInteger(Number(line.boxSpec)) && Number(line.boxSpec) > 0))) &&
  (!reviewNeeded.value || acknowledged.value))

function apply() {
  if (!canApply.value || !supplierId.value) return
  emit('import', {
    documentNo: preview.value?.documentNo || null,
    bizDate: bizDate.value,
    supplierId: supplierId.value,
    declaredTotal: preview.value?.declaredTotal ?? null,
    rows: included.value.map((line) => ({
      productId: line.productId!, sourceName: line.sourceName,
      qty: purchaseQty(line), unitPrice: basicPrice(line),
      boxSpec: line.unitMode === 'box' ? Number(line.boxSpec) : null,
      boxCount: line.unitMode === 'box' ? Number(line.qty) : null,
      boxPrice: line.unitMode === 'box' ? Number(line.unitPrice) : null,
      totalPrice: Number(line.qty) * Number(line.unitPrice),
    })),
  })
  emit('update:modelValue', false)
}
</script>

<template>
  <el-dialog :model-value="modelValue" title="拍照识别拿货单" width="min(1500px, 97vw)" top="3vh"
    :close-on-click-modal="false" @update:model-value="emit('update:modelValue', $event)">
    <div class="photo-actions">
      <label class="photo-pick">
        {{ fileName ? '重新选择照片' : '选择拿货单照片（JPG / PNG）' }}
        <input type="file" accept="image/jpeg,image/png,.jpg,.jpeg,.png" hidden @change="onPick" />
      </label>
      <span class="mini">自动匹配商品 → 统一设置单位 → 核对后生成草稿</span>
      <el-button v-if="imageUrl" link type="primary" @click="showOriginal = !showOriginal">{{ showOriginal ? '收起原图' : '展开原图' }}</el-button>
    </div>
    <div v-if="imageUrl" class="photo-layout" :class="{ 'photo-layout-wide': !showOriginal }">
      <div v-show="showOriginal" class="photo-original">
        <div class="photo-original-title">原始单据 <span>{{ fileName }}</span></div>
        <img :src="imageUrl" alt="原始拿货单照片" />
      </div>
      <div class="photo-review" :aria-busy="busy">
        <div v-if="busy" class="photo-progress" role="status" aria-live="polite">
          <p>正在识别商品、数量和金额 · 已等待 {{ elapsedSeconds }} 秒</p>
          <p class="mini">识别完成后会自动填入明细，最长等待 150 秒。请保持窗口打开。</p>
        </div>
        <template v-if="requestError">
          <el-alert class="photo-error" type="error" :closable="false" title="照片识别未完成" :description="requestError" show-icon />
          <div class="photo-error-actions">
            <el-button type="primary" @click="recognize">重新识别</el-button>
            <el-button @click="startManualEntry">对照原图手工录入</el-button>
          </div>
        </template>
        <template v-if="preview || manualMode">
          <el-alert v-if="manualMode" type="info" :closable="false" title="手工录入：请对照原图填写。" />
          <div v-if="preview" class="photo-meta">
            <span>单号 <b>{{ preview.documentNo || '未识别' }}</b></span>
            <span>送货方 <b>{{ preview.supplierName || '未识别' }}</b></span>
          </div>
          <div class="photo-fields">
            <el-select v-model="supplierId" filterable clearable placeholder="选择供应商（必选）" aria-label="供应商">
              <el-option v-for="supplier in suppliers" :key="supplier.id" :label="supplier.supplierName" :value="supplier.id!" />
            </el-select>
            <el-date-picker v-model="bizDate" type="date" value-format="YYYY-MM-DD" placeholder="入库日期（必选）" />
          </div>
          <div class="photo-batch">
            <div class="photo-batch-heading"><b>整单数量单位</b><span class="mini">作用于计入的行，个别商品可在下方调整</span></div>
            <el-radio-group v-model="batchUnit" aria-label="整单数量单位" @change="applyBatchUnit">
              <el-radio-button label="recognized">按识别结果</el-radio-button>
              <el-radio-button label="box">全部按箱</el-radio-button>
              <el-radio-button label="base">全部按基本单位</el-radio-button>
            </el-radio-group>
            <el-button v-if="batchUnit !== 'recognized'" link type="primary" class="photo-batch-repeat" @click="applyBatchUnit">重新统一全部行</el-button>
            <div class="mini photo-batch-hint">{{ batchUnit === 'box' ? '数量为箱数，单价为每箱价；每行按各自商品箱规折算。' : batchUnit === 'base' ? '数量和单价按商品的瓶、袋、盒等基本单位填写。' : '未识别单位时，在这里统一选择即可，无需逐行设置。' }}</div>
          </div>
          <el-alert v-for="warning in headerWarnings" :key="warning" type="warning" :closable="false" :title="warning" class="mb-8px" />
          <div class="photo-summary">
            <span>计入 <b>{{ included.length }}</b> 行 · 已匹配 <b>{{ included.filter(row => row.productId).length }}</b> 行<span v-if="suggested.length">（含 {{ suggested.length }} 行推荐）</span><span class="photo-warning" v-if="unresolved.length"> · 待补齐 {{ unresolved.length }} 行</span></span>
            <el-checkbox v-model="onlyPending">只看待核对（{{ pendingCount }}）</el-checkbox>
          </div>
          <el-table ref="reviewTable" :data="visibleLines" size="small" max-height="490" row-key="rowNo" class="photo-table"
            :row-class-name="rowClass">
            <el-table-column label="计入" width="46"><template #default="{ row }"><el-checkbox v-model="row.include" :aria-label="`计入第${row.rowNo}行`" @change="includeLine(row)" /></template></el-table-column>
            <el-table-column label="匹配商品 / 票面名称" min-width="240">
              <template #default="{ row }">
                <ProductSelect v-if="row.include" :ref="control => setProductControl(row.rowNo, control)" :model-value="row.productId" :initial-product="row.productId && row.productName ? { id: row.productId, productName: row.productName, skuCode: row.skuCode || '' } : null" lazy placeholder="搜索商品或选择下方候选" @update:model-value="selectProduct(row, $event)" />
                <div class="photo-source">
                  <span class="photo-row-no">{{ row.rowNo }}.</span>
                  <el-tag v-if="row.rowNo === highlightedRow" size="small">新增行</el-tag>
                  <el-input v-if="manualMode || row.manual" v-model="row.sourceName" size="small" placeholder="票面商品名" />
                  <span v-else>票面：{{ row.sourceName || '未识别商品名' }}<span v-if="row.sourceSpec"> · {{ row.sourceSpec }}</span></span>
                  <el-tag v-if="row.include && row.matchKind === 'suggested'" type="warning" size="small">推荐 · 请核对</el-tag>
                  <el-tag v-else-if="row.include && row.productId" type="success" size="small">{{ row.matchKind === 'manual' ? '已选择' : '已匹配' }}</el-tag>
                </div>
                <div v-if="row.include && otherCandidates(row).length && (row.matchKind === 'suggested' || !row.productId)" class="photo-candidates">
                  <el-button v-for="candidate in otherCandidates(row)" :key="candidate.productId" size="small"
                    :type="row.productId === candidate.productId ? 'primary' : 'default'" plain @click="chooseCandidate(row, candidate)">
                    {{ candidate.productName }} · {{ candidate.skuCode }}
                  </el-button>
                </div>
                <div v-if="row.include && issues(row).length" class="photo-warning">{{ issues(row).join('；') }}</div>
                <div v-if="row.include && row.uncertain" class="photo-warning">字迹不清，请对照原图核对</div>
                <span v-if="row.crossedOut" class="mini">票面疑似划掉，默认排除</span>
              </template>
            </el-table-column>
            <el-table-column label="数量单位 / 箱规" width="150">
              <template #default="{ row }">
                <div v-if="row.include" class="photo-unit">
                  <el-select v-model="row.unitMode" placeholder="选择单位" size="small" aria-label="此行数量单位">
                    <el-option label="整箱" value="box" />
                    <el-option :label="row.unit ? `基本单位（${row.unit}）` : '基本单位'" value="base" />
                  </el-select>
                  <div v-if="row.unitMode === 'box'" class="photo-box-spec">
                    <span>每箱</span><el-input-number v-model="row.boxSpec" class="photo-pack-input" :controls="false" :min="1" :precision="0" size="small" placeholder="数量" aria-label="每箱数量" /><span>{{ row.unit || '个' }}</span>
                  </div>
                  <div class="mini">票面单位：{{ row.sourceUnit || '未标明' }}</div>
                </div>
              </template>
            </el-table-column>
            <el-table-column label="数量" width="86"><template #default="{ row }"><el-input-number v-if="row.include" v-model="row.qty" :controls="false" :min="0.001" size="small" aria-label="票面数量" /><span v-else>{{ row.qty ?? '—' }}</span></template></el-table-column>
            <el-table-column label="单价（元）" width="96"><template #default="{ row }"><el-input-number v-if="row.include" v-model="row.unitPrice" :controls="false" :min="0.0001" :precision="4" size="small" aria-label="票面单价" /><span v-else>{{ row.unitPrice ?? '—' }}</span></template></el-table-column>
            <el-table-column label="金额 / 入库折算" width="152">
              <template #default="{ row }">
                <b>{{ row.include ? (Number(row.qty || 0) * Number(row.unitPrice || 0)).toFixed(2) : '—' }}</b>
                <div v-if="row.include && row.unitMode && !issues(row).length" class="mini">入库 {{ purchaseQty(row) }} {{ row.unit || '基本单位' }}<br />单价 ¥{{ basicPrice(row).toFixed(4) }}</div>
                <div v-if="row.include && amountDiff(row)" class="photo-warning">票面金额 {{ row.sourceAmount }}，有差异</div>
              </template>
            </el-table-column>
            <el-table-column label="操作" width="64" fixed="right">
              <template #default="{ row }"><el-button link type="danger" :aria-label="`删除第${row.rowNo}行`" title="从本次明细中删除，可撤销" @click="removeLines([row])">删除</el-button></template>
            </el-table-column>
            <template #empty>{{ onlyPending ? '没有待核对的行，可查看全部明细' : '暂无商品明细' }}</template>
          </el-table>
          <div class="photo-row-actions">
            <el-button type="primary" plain @click="addManualLine">＋ 手工添加一行</el-button>
            <el-button v-if="emptyManualLines.length" plain @click="removeLines(emptyManualLines)">清理空白行（{{ emptyManualLines.length }}）</el-button>
            <span v-if="highlightedRow !== null" class="mini" role="status">已定位到第 {{ highlightedRow }} 行</span>
          </div>
          <div v-if="lastRemoved.length" class="photo-undo" role="status">已从本次明细删除 {{ lastRemoved.length }} 行 <el-button link type="primary" @click="undoRemove">撤销删除</el-button></div>
          <div class="photo-totals"><span>票面合计 {{ preview?.declaredTotal ?? '未识别' }} 元</span><span>本次合计 <b>¥{{ total.toFixed(2) }}</b></span></div>
          <el-alert v-if="totalDiff" type="warning" :closable="false" title="合计与票面不一致，请核对划掉的行及金额。" />
          <el-checkbox v-if="reviewNeeded" v-model="acknowledged" class="photo-acknowledged">我已核对推荐商品、模糊字迹和金额差异，按当前明细继续</el-checkbox>
        </template>
      </div>
    </div>
    <template #footer>
      <div class="photo-footer">
        <span class="mini">{{ unresolved.length ? `还有 ${unresolved.length} 行需要补齐` : !supplierId || !bizDate ? '请选择供应商和入库日期' : reviewNeeded && !acknowledged ? '核对完成后，勾选整单确认即可继续' : '生成草稿后确认入库，库存才会更新' }}</span>
        <div><el-button @click="emit('update:modelValue', false)">取消</el-button><el-button type="primary" :disabled="!canApply || busy" @click="apply">生成入库草稿</el-button></div>
      </div>
    </template>
  </el-dialog>
</template>

<style scoped>
.photo-actions { display: flex; align-items: center; flex-wrap: wrap; gap: 12px; margin-bottom: 16px; }
.photo-pick { color: #fff; background: #2d6b50; padding: 9px 16px; border-radius: 6px; cursor: pointer; }
.photo-layout { display: grid; grid-template-columns: minmax(220px, 26%) minmax(0, 1fr); gap: 20px; }
.photo-layout-wide { grid-template-columns: minmax(0, 1fr); }
.photo-original { min-width: 0; max-height: 740px; overflow: auto; background: #f4f6f5; border: 1px solid #e5eae7; border-radius: 8px; align-self: start; }
.photo-original-title { padding: 10px; font-size: 13px; font-weight: 600; }
.photo-original-title span { display: block; font-weight: 400; font-size: 11px; overflow-wrap: anywhere; margin-top: 4px; color: #738078; }
.photo-original img { display: block; width: 100%; height: auto; }
.photo-review { min-width: 0; overflow-wrap: anywhere; }
.photo-progress { padding: 16px 0; }
.photo-error-actions { display: flex; flex-wrap: wrap; gap: 8px; margin-top: 12px; }
.photo-fields { display: flex; gap: 10px; margin: 10px 0 14px; }
.photo-fields > * { flex: 1; min-width: 0; }
.photo-meta { display: flex; gap: 16px; flex-wrap: wrap; font-size: 12px; color: #69766f; }
.photo-batch { background: #f1f7f3; border: 1px solid #dce9df; border-radius: 8px; padding: 12px; margin-bottom: 10px; }
.photo-batch-heading { display: flex; flex-wrap: wrap; align-items: center; gap: 10px; margin-bottom: 8px; }
.photo-batch-hint { margin-top: 8px; }
.photo-batch-repeat { margin: 6px 0 0 10px; font-size: 12px; }
.photo-summary { display: flex; align-items: center; justify-content: space-between; flex-wrap: wrap; gap: 8px; padding: 4px 0 8px; font-size: 12px; }
.photo-source { display: flex; align-items: center; flex-wrap: wrap; gap: 4px; font-size: 12px; margin-top: 5px; color: #6c7771; }
.photo-source .el-input { width: 160px; }
.photo-row-no { color: #8c9690; }
.photo-unit { display: flex; flex-direction: column; gap: 5px; }
.photo-box-spec { display: flex; align-items: center; gap: 4px; font-size: 12px; }
.photo-box-spec > span { white-space: nowrap; flex-shrink: 0; }
.photo-box-spec .photo-pack-input { width: 58px; min-width: 0; flex: 0 0 58px; }
.photo-table :deep(.el-input-number) { max-width: 100%; width: 100%; }
.photo-table :deep(.cell) { padding: 6px; }
.photo-table :deep(.photo-excluded-row) { color: #929b96; background: #f6f7f6; }
.photo-table :deep(.photo-pending-row) { background: #fffbf2; }
.photo-table :deep(.photo-new-row > td:first-child) { box-shadow: inset 3px 0 #2d6b50; }
.photo-row-actions { display: flex; align-items: center; gap: 10px; flex-wrap: wrap; padding: 12px 0 4px; }
.photo-row-actions .el-button { margin-left: 0; }
.photo-undo { display: flex; align-items: center; flex-wrap: wrap; gap: 10px; padding: 8px 12px; margin-top: 8px; background: #f1f7f3; border-radius: 6px; }
.photo-candidates { display: flex; flex-wrap: wrap; gap: 5px; margin-top: 7px; }
.photo-candidates .el-button { margin-left: 0; max-width: 100%; height: auto; padding: 5px 7px; }
.photo-candidates :deep(.el-button > span) { white-space: normal; overflow-wrap: anywhere; text-align: left; }
.photo-warning { color: #a65d00; font-size: 12px; margin-top: 3px; }
.photo-totals { display: flex; justify-content: space-between; flex-wrap: wrap; gap: 8px; font-size: 13px; margin: 12px 0; }
.photo-totals b { color: #2d6b50; font-size: 20px; }
.photo-acknowledged { height: auto; white-space: normal; margin-top: 10px; }
.photo-acknowledged :deep(.el-checkbox__label) { white-space: normal; }
.photo-footer { display: flex; justify-content: space-between; align-items: center; flex-wrap: wrap; gap: 10px; text-align: left; }
@media (max-width: 1050px) {
  .photo-layout { grid-template-columns: minmax(0, 1fr); }
  .photo-original { max-height: 230px; }
  .photo-original img { max-width: 420px; margin: auto; }
}
@media (max-width: 600px) {
  .photo-fields { flex-direction: column; }
  .photo-fields > * { flex: none; width: 100%; }
  .photo-fields :deep(.el-date-editor.el-input) { width: 100%; }
  .photo-batch :deep(.el-radio-button__inner) { padding: 8px; font-size: 12px; }
  .photo-footer { justify-content: flex-end; }
}
</style>
