<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { isAxiosError } from 'axios'
import { ElMessage } from 'element-plus'
import ProductSelect from '@/components/basedata/ProductSelect.vue'
import { getProduct } from '@/api/basedata'
import type { Supplier } from '@/api/basedata'
import {
  previewPurchasePhoto, type PurchasePhotoDraft, type PurchasePhotoLine, type PurchasePhotoPreview,
} from '@/api/purchase'

const props = defineProps<{ modelValue: boolean; suppliers: Supplier[] }>()
const emit = defineEmits<{
  (e: 'update:modelValue', value: boolean): void
  (e: 'import', draft: PurchasePhotoDraft): void
}>()

type ReviewLine = PurchasePhotoLine & { include: boolean }
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

function addManualLine() {
  lines.value.push({
    rowNo: lines.value.length + 1, sourceName: null, productId: null,
    sourceUnit: null, sourceSpec: null,
    productName: null, skuCode: null, boxSpec: null, unit: null,
    qty: null, unitPrice: null, sourceAmount: null, calculatedAmount: null,
    crossedOut: false, uncertain: true, warnings: [], include: true, unitMode: null,
  })
}

const included = computed(() => lines.value.filter((line) => line.include))
async function selectProduct(line: ReviewLine, id: number | null) {
  line.productId = id
  // 识别时的口径依赖当时匹配的商品；换商品后必须重新确认。
  line.unitMode = null
  line.boxSpec = null
  line.unit = null
  if (!id) return
  try {
    const product = await getProduct(id)
    if (line.productId === id) {
      line.boxSpec = product.boxSpec == null ? null : Number(product.boxSpec)
      line.unit = product.unit || null
    }
  } catch {
    ElMessage.error('读取商品箱规失败，请重新选择商品')
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
const reviewNeeded = computed(() => totalDiff.value || included.value.some((line) =>
  line.uncertain || (line.sourceAmount != null &&
    Math.abs(Number(line.sourceAmount) - Number(line.qty || 0) * Number(line.unitPrice || 0)) > 0.01)))
const canApply = computed(() => !busy.value && !requestError.value &&
  (!!preview.value?.rows.length || manualMode.value) && !!supplierId.value && !!bizDate.value &&
  included.value.length > 0 && included.value.every((line) =>
    !!line.productId && !!line.unitMode && Number(line.qty) > 0 && Number(line.unitPrice) > 0 &&
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
  <el-dialog :model-value="modelValue" title="📷 拍照识别拿货单" width="min(1320px, 97vw)"
    :close-on-click-modal="false" @update:model-value="emit('update:modelValue', $event)">
    <el-alert type="info" :closable="false" class="mb-12px"
      title="上传照片后自动识别并填写明细；请核对商品、数量、单价和单位。不明确的单位需手动选择，划掉的行默认排除。只生成草稿，不会自动入库。" />
    <div class="photo-actions">
      <label class="photo-pick">
        {{ fileName ? '重新选择照片' : '选择拿货单照片（JPG / PNG）' }}
        <input type="file" accept="image/jpeg,image/png,.jpg,.jpeg,.png" hidden @change="onPick" />
      </label>
      <span v-if="fileName" class="mini">{{ fileName }}</span>
    </div>
    <div v-if="imageUrl" class="photo-layout">
      <div class="photo-original"><img :src="imageUrl" alt="原始拿货单照片" /></div>
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
          <el-alert v-if="manualMode" type="info" :closable="false" title="手工录入：请对照左侧原图填写，当前没有 AI 识别结果。" />
          <div v-if="preview" class="photo-meta">
            <span>票面单号：<b>{{ preview.documentNo || '未识别' }}</b></span>
            <span>识别的送货方：<b>{{ preview.supplierName || '未识别' }}</b></span>
          </div>
          <div class="photo-fields">
            <el-select v-model="supplierId" filterable clearable placeholder="选择供应商（必选）">
              <el-option v-for="supplier in suppliers" :key="supplier.id" :label="supplier.supplierName" :value="supplier.id!" />
            </el-select>
            <el-date-picker v-model="bizDate" type="date" value-format="YYYY-MM-DD" placeholder="入库日期（必选）" />
          </div>
          <el-alert v-for="warning in (preview?.warnings || [])" :key="warning" type="warning"
            :closable="false" :title="warning" class="mb-8px" />
          <el-table :data="lines" size="small" max-height="460">
            <el-table-column label="计入" width="60"><template #default="{ row }"><el-checkbox v-model="row.include" /></template></el-table-column>
            <el-table-column label="票面商品 / 档案匹配" min-width="220">
              <template #default="{ row }">
                <div class="photo-source">{{ row.rowNo }}. <el-input v-if="row.include" v-model="row.sourceName" size="small" placeholder="票面商品名（可修改）" style="width: 175px" /><span v-else>{{ row.sourceName || '未识别商品名' }}</span></div>
                <div v-if="row.sourceUnit || row.sourceSpec" class="mini">票面单位：{{ row.sourceUnit || '未识别' }} · 规格：{{ row.sourceSpec || '未识别' }}</div>
                <ProductSelect v-if="row.include" :model-value="row.productId" placeholder="核对或搜索商品档案" @update:model-value="selectProduct(row, $event)" />
                <div v-if="row.include" class="photo-unit">
                  <el-select v-model="row.unitMode" placeholder="票面数量单位" style="width: 130px">
                    <el-option label="基本单位" value="base" />
                    <el-option label="整箱" value="box" />
                  </el-select>
                  <el-input-number v-if="row.unitMode === 'box'" v-model="row.boxSpec" :controls="false" :min="1" :precision="0"
                    size="small" style="width: 110px" placeholder="每箱数量" />
                </div>
                <div v-if="row.include && row.unitMode" class="mini">
                  入库 {{ purchaseQty(row) }} {{ row.unit || '基本单位' }} · 折算单价 ¥{{ basicPrice(row).toFixed(4) }}
                </div>
                <span v-if="row.crossedOut" class="mini">票面疑似划掉，默认排除</span>
                <div v-for="warning in row.warnings" :key="warning" class="photo-warning">{{ warning }}</div>
              </template>
            </el-table-column>
            <el-table-column label="票面数量" width="100"><template #default="{ row }"><el-input-number v-if="row.include" v-model="row.qty" :controls="false" :min="0.001" size="small" style="width: 86px" /><span v-else>{{ row.qty ?? '—' }}</span></template></el-table-column>
            <el-table-column label="票面单价" width="100"><template #default="{ row }"><el-input-number v-if="row.include" v-model="row.unitPrice" :controls="false" :min="0.0001" :precision="4" size="small" style="width: 86px" /><span v-else>{{ row.unitPrice ?? '—' }}</span></template></el-table-column>
            <el-table-column label="票面金额" width="92"><template #default="{ row }">{{ row.sourceAmount ?? '—' }}</template></el-table-column>
            <el-table-column label="复算金额" width="95"><template #default="{ row }">{{ row.include ? (Number(row.qty || 0) * Number(row.unitPrice || 0)).toFixed(2) : '—' }}</template></el-table-column>
          </el-table>
          <el-button link type="primary" @click="addManualLine">＋ 手工添加一行</el-button>
          <div class="photo-totals">票面合计 {{ preview?.declaredTotal ?? '未识别' }} 元 · 当前选择行复算 {{ total.toFixed(2) }} 元</div>
          <el-alert v-if="totalDiff" type="warning" :closable="false" title="票面合计与当前复算不一致，请检查划掉的行及手写数字。" />
          <el-checkbox v-if="reviewNeeded" v-model="acknowledged" class="photo-acknowledged">我已对照照片核对不清楚的字迹和金额差异，仍按当前明细继续</el-checkbox>
        </template>
      </div>
    </div>
    <template #footer>
      <el-button @click="emit('update:modelValue', false)">取消</el-button>
      <el-button type="primary" :disabled="!canApply || busy" @click="apply">生成入库草稿</el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
.photo-actions { display: flex; align-items: center; flex-wrap: wrap; gap: 12px; margin-bottom: 12px; overflow-wrap: anywhere; }
.photo-pick { color: #fff; background: #2d6b50; padding: 8px 14px; border-radius: 5px; cursor: pointer; }
.photo-layout { display: grid; grid-template-columns: minmax(260px, 38%) minmax(0, 1fr); gap: 16px; }
.photo-original { min-width: 0; max-height: 640px; overflow: auto; background: #eee; }
.photo-original img { display: block; width: 100%; height: auto; }
.photo-review { min-width: 0; overflow-wrap: anywhere; }
.photo-progress { padding: 16px 0; }
.photo-error-actions { display: flex; flex-wrap: wrap; gap: 8px; margin-top: 12px; }
.photo-fields { display: flex; gap: 10px; margin: 10px 0; }
.photo-fields > * { flex: 1; min-width: 0; }
.photo-meta { display: flex; gap: 16px; flex-wrap: wrap; font-size: 13px; }
.photo-source { font-size: 12px; margin-bottom: 4px; }
.photo-unit { display: flex; flex-wrap: wrap; gap: 6px; margin: 6px 0 3px; }
.photo-warning { color: #b66300; font-size: 11px; margin-top: 3px; }
.photo-totals { font-size: 13px; font-weight: 600; margin: 12px 0; }
.photo-acknowledged { height: auto; white-space: normal; }
.photo-acknowledged :deep(.el-checkbox__label) { white-space: normal; }
@media (max-width: 800px) {
  .photo-layout { grid-template-columns: minmax(0, 1fr); }
  .photo-original { max-height: 260px; }
  .photo-fields { flex-direction: column; }
  .photo-fields > * { flex: none; width: 100%; }
  .photo-fields :deep(.el-date-editor.el-input) { width: 100%; }
}
</style>
