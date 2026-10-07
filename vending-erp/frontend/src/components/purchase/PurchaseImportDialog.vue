<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { previewPurchaseImport, purchaseImportTemplate, type PurchaseImportKind, type PurchaseImportLine, type PurchaseImportPreview } from '@/api/purchase'

const props = defineProps<{ modelValue: boolean; kind: PurchaseImportKind }>()
const emit = defineEmits<{
  (event: 'update:modelValue', value: boolean): void
  (event: 'import', rows: PurchaseImportLine[]): void
}>()
const title = computed(() => props.kind === 'receipt' ? '采购入库单' : '订货单')
const boxMode = computed(() => props.kind === 'receipt' && !!preview.value?.rows.some((row) => row.boxSpec != null))
const previewTotal = computed(() => (preview.value?.rows || []).reduce((sum, row) =>
  sum + Number(row.totalPrice ?? (Number(row.qty) * Number(row.unitPrice || 0))), 0))
const fileInput = ref<HTMLInputElement>()
const fileName = ref('')
const busy = ref(false)
const downloading = ref(false)
const preview = ref<PurchaseImportPreview | null>(null)
const error = ref('')
let generation = 0
watch(() => [props.modelValue, props.kind], () => {
  generation++
  fileName.value = ''
  preview.value = null
  error.value = ''
  busy.value = false
  if (fileInput.value) fileInput.value.value = ''
})
async function selectFile(event: Event) {
  const input = event.target as HTMLInputElement
  const file = input.files?.[0]
  input.value = ''
  preview.value = null
  error.value = ''
  if (!file) return
  fileName.value = file.name
  if (!file.name.toLowerCase().endsWith('.xlsx') || file.size > 5 * 1024 * 1024 || !file.size) {
    error.value = '请选择不超过 5MB 的非空 .xlsx 文件'
    return
  }
  const requestGeneration = ++generation
  busy.value = true
  try {
    const result = await previewPurchaseImport(props.kind, file)
    if (requestGeneration === generation) preview.value = result
  } catch (e) {
    if (requestGeneration === generation) error.value = e instanceof Error ? e.message : '文件校验失败，请重新选择文件'
  } finally {
    if (requestGeneration === generation) busy.value = false
  }
}
async function downloadTemplate() {
  downloading.value = true
  try {
    const blob = await purchaseImportTemplate(props.kind)
    const url = URL.createObjectURL(blob)
    const link = document.createElement('a')
    link.href = url
    link.download = `${title.value}导入模板.xlsx`
    link.click()
    setTimeout(() => URL.revokeObjectURL(url), 1000)
  } catch {
    // request 已展示错误信息。
  } finally {
    downloading.value = false
  }
}
function apply() {
  if (busy.value || !preview.value?.rows.length || preview.value.errors.length) return
  const rows = preview.value.rows
  emit('import', rows)
  emit('update:modelValue', false)
  ElMessage.success(`已带入 ${rows.length} 行明细，请选择供应商、核对后保存`)
}
</script>

<template>
  <el-dialog :model-value="modelValue" :title="`${title} · 列表导入`" width="min(1120px, 96vw)"
    :close-on-click-modal="false" @update:model-value="emit('update:modelValue', $event)">
    <el-alert type="info" :closable="false" show-icon>
      <template #title>每个文件导入一张单据的商品明细，供应商和日期在下一步填写。</template>
      <template v-if="kind === 'receipt'">
        按表格填写「商品编码、商品名称、整件规格、总件数、采购数量、整件价格、总价格」。
        采购数量 = 整件规格 × 总件数，总价格 = 整件价格 × 总件数；系统会折算基本单位进货价。
      </template>
      <template v-else>使用商品档案编码填写订购数量和预计单价；预计单价可留空。</template>
      仅支持一个工作表的 .xlsx，最多 500 行、5MB。上传只做校验和预览，不会改变库存。
    </el-alert>
    <div class="import-actions">
      <el-button :loading="downloading" @click="downloadTemplate">下载模板</el-button>
      <el-button type="primary" :loading="busy" @click="fileInput?.click()">选择 Excel 文件</el-button>
      <input ref="fileInput" type="file" accept=".xlsx" hidden :disabled="busy" @change="selectFile" />
      <span class="file-name">{{ fileName }}</span>
    </div>
    <el-alert v-if="error" :title="error" type="error" :closable="false" />
    <template v-if="preview">
      <el-alert v-if="preview.errors.length" type="error" :closable="false" title="校验未通过，请修正以下问题后重新上传；不会导入部分明细。" />
      <ul v-if="preview.errors.length" class="import-errors">
        <li v-for="(message, index) in preview.errors" :key="index">{{ message }}</li>
      </ul>
      <template v-else>
        <p class="preview-summary">校验通过：{{ preview.rows.length }} 种商品<span v-if="kind === 'receipt'"> · 采购数量 {{ preview.rows.reduce((sum, row) => sum + Number(row.qty), 0) }} · 总金额 ¥{{ previewTotal.toFixed(2) }}</span>。请核对后带入单据：</p>
        <el-table :data="preview.rows" max-height="350" size="small" border>
          <el-table-column prop="rowNo" label="Excel 行" width="75" />
          <el-table-column prop="skuCode" label="商品编码" min-width="145" />
          <el-table-column label="商品名称（档案匹配）" min-width="175">
            <template #default="{ row }">
              <div>{{ row.productName }}</div>
              <div v-if="row.sourceName && row.sourceName !== row.productName" class="name-note">表格名称：{{ row.sourceName }}</div>
            </template>
          </el-table-column>
          <template v-if="boxMode">
            <el-table-column prop="boxSpec" label="整件规格" width="90" align="right" />
            <el-table-column prop="boxCount" label="总件数" width="80" align="right" />
            <el-table-column prop="qty" label="采购数量" width="90" align="right" />
            <el-table-column prop="boxPrice" label="整件价格 ¥" width="100" align="right" />
            <el-table-column label="总价格 ¥" width="100" align="right"><template #default="{ row }">{{ Number(row.totalPrice).toFixed(2) }}</template></el-table-column>
            <el-table-column prop="unitPrice" label="折算单价 ¥/基本单位" width="150" align="right" />
          </template>
          <template v-else>
            <el-table-column prop="qty" :label="kind === 'receipt' ? '实收数量' : '订购数量'" width="100" align="right" />
            <el-table-column label="单价 ¥" width="100" align="right"><template #default="{ row }">{{ row.unitPrice ?? '待填写' }}</template></el-table-column>
          </template>
        </el-table>
      </template>
    </template>
    <template #footer>
      <el-button @click="emit('update:modelValue', false)">取消</el-button>
      <el-button type="primary" :disabled="busy || !preview?.rows.length || !!preview?.errors.length" @click="apply">导入明细并核对</el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
.import-actions { display: flex; align-items: center; flex-wrap: wrap; gap: 10px; margin: 16px 0; }
.file-name { overflow-wrap: anywhere; }
.import-errors { max-height: 240px; overflow: auto; color: var(--el-color-danger); padding-left: 24px; }
.preview-summary { margin: 16px 0 10px; font-weight: 600; }
.name-note { color: var(--el-color-warning-dark-2); font-size: 12px; }
</style>
