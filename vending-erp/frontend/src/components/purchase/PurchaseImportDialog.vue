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
  <el-dialog :model-value="modelValue" :title="`${title} · 列表导入`" width="min(820px, 94vw)"
    :close-on-click-modal="false" @update:model-value="emit('update:modelValue', $event)">
    <el-alert type="info" :closable="false" show-icon>
      <template #title>每个文件导入一张单据的商品明细，供应商和日期在下一步填写。</template>
      使用商品档案中的商品编码；数量按基本单位填写（瓶、袋等），单价按基本单位填写。
      仅支持一个工作表的 .xlsx，最多 500 行、5MB。导入校验不会生成单据或变动库存。
      {{ kind === 'order' ? '预计单价可留空。' : '实收数量和进货单价必须大于 0。' }}
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
        <p>校验通过，共 {{ preview.rows.length }} 行。请核对商品和金额：</p>
        <el-table :data="preview.rows" max-height="350" size="small">
          <el-table-column prop="rowNo" label="Excel 行" width="80" />
          <el-table-column prop="skuCode" label="商品编码" width="120" />
          <el-table-column prop="productName" label="商品名称" min-width="180" />
          <el-table-column prop="qty" :label="kind === 'receipt' ? '实收数量' : '订购数量'" width="100" />
          <el-table-column label="单价" width="100"><template #default="{ row }">{{ row.unitPrice ?? '待填写' }}</template></el-table-column>
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
</style>
