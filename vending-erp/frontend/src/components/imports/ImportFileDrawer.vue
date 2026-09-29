<script setup lang="ts">
import { ref, watch } from 'vue'
import { isAxiosError } from 'axios'
import { importsApi, type ImportBatch, type ImportFilePreview } from '@/api/imports'
import { isMobile } from '@/utils/viewport'

const props = defineProps<{ modelValue: boolean; batch: ImportBatch | null }>()
const emit = defineEmits<{ 'update:modelValue': [value: boolean] }>()

const content = ref<ImportFilePreview | null>(null)
const loading = ref(false)
const downloading = ref(false)
const error = ref('')
const sheetIndex = ref(0)
const page = ref(1)
const pageSize = 50
let requestId = 0

async function loadFile() {
  if (!props.batch || !props.modelValue) return
  const id = ++requestId
  loading.value = true
  error.value = ''
  try {
    const result = await importsApi.filePreview(props.batch.id, sheetIndex.value, page.value, pageSize)
    if (id !== requestId) return
    content.value = result
    sheetIndex.value = result.sheetIndex
    page.value = result.current
  } catch (cause) {
    if (id !== requestId) return
    error.value = isAxiosError(cause)
      ? (cause.code === 'ECONNABORTED' || cause.code === 'ETIMEDOUT'
          ? '表格读取超时，请重新加载，或下载原文件查看'
          : '表格加载失败，请检查网络后重新加载')
      : cause instanceof Error ? cause.message : '表格加载失败，请重新加载'
  } finally {
    if (id === requestId) loading.value = false
  }
}

watch(() => [props.modelValue, props.batch?.id] as const, ([visible]) => {
  ++requestId // 关闭或切换批次后，不接收上一次请求的内容。
  content.value = null
  error.value = ''
  loading.value = false
  sheetIndex.value = 0
  page.value = 1
  if (visible) void loadFile()
})

function changeSheet() {
  page.value = 1
  void loadFile()
}

async function downloadFile() {
  if (!props.batch || downloading.value) return
  const batch = props.batch
  downloading.value = true
  try {
    const blob = await importsApi.downloadFile(batch.id)
    const url = URL.createObjectURL(blob)
    const link = document.createElement('a')
    link.href = url
    link.download = batch.fileName
    document.body.appendChild(link)
    link.click()
    link.remove()
    setTimeout(() => URL.revokeObjectURL(url), 1000)
  } catch {
    // API 拦截器已展示具体错误，保留预览供继续核对。
  } finally {
    downloading.value = false
  }
}
</script>

<template>
  <el-drawer
    :model-value="modelValue"
    title="查看导入表格"
    :size="isMobile ? '100%' : '90%'"
    class="import-file-drawer"
    @update:model-value="emit('update:modelValue', $event)"
  >
    <div class="file-viewer">
      <div class="file-heading">
        <div class="file-identity">
          <h3>{{ batch?.fileName }}</h3>
          <p>{{ batch?.batchNo }} · {{ batch?.fileType }}</p>
        </div>
        <el-button :loading="downloading" @click="downloadFile">下载原文件</el-button>
      </div>
      <p class="file-note">显示原文件单元格内容，行号与 Excel 一致。查看完整格式、图片或批注，请下载原文件。</p>

      <div v-if="content" class="file-controls">
        <label for="import-file-sheet">工作表</label>
        <el-select
          id="import-file-sheet"
          v-model="sheetIndex"
          aria-label="工作表"
          :disabled="loading"
          class="file-sheet-select"
          @change="changeSheet"
        >
          <el-option v-for="sheet in content.sheets" :key="sheet.index" :label="sheet.name" :value="sheet.index" />
        </el-select>
        <span class="file-count">共 {{ content.total }} 行</span>
      </div>

      <div v-loading="loading" class="file-content" :aria-busy="loading" element-loading-text="正在读取表格…">
        <div v-if="error" class="file-error" role="alert">
          <p>{{ error }}</p>
          <el-button @click="loadFile">重新加载</el-button>
        </div>
        <template v-else-if="content && !loading">
          <el-alert
            v-for="warning in content.warnings ?? []"
            :key="warning"
            :title="warning"
            type="warning"
            :closable="false"
            class="file-warning"
          />
          <el-table
            v-if="content.columns.length"
            :data="content.rows"
            border
            height="100%"
            size="small"
            empty-text="此工作表没有内容"
            class="file-table"
          >
            <el-table-column prop="rowNo" label="行号" width="70" fixed />
            <el-table-column v-for="(column, index) in content.columns" :key="column" :label="column" min-width="160">
              <template #default="{ row }"><span class="file-cell">{{ row.cells[index] }}</span></template>
            </el-table-column>
          </el-table>
          <el-empty v-else description="此工作表没有内容" />
        </template>
      </div>

      <div v-if="content && !error" class="file-pagination">
        <span class="file-page-note">每页 {{ pageSize }} 行</span>
        <el-pagination
          v-model:current-page="page"
          :total="content.total"
          :page-size="pageSize"
          :pager-count="isMobile ? 3 : 7"
          :small="isMobile"
          :disabled="loading"
          layout="prev, pager, next"
          @current-change="loadFile"
        />
      </div>
    </div>
  </el-drawer>
</template>

<style scoped>
.file-viewer {
  height: 100%;
  display: flex;
  flex-direction: column;
  gap: 16px;
  color: var(--ink);
}

.file-heading {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 16px;
}

.file-identity { min-width: 0; }
.file-identity h3 { margin: 0 0 8px; font-size: 16px; overflow-wrap: anywhere; }
.file-identity p, .file-note { margin: 0; font-size: 13px; line-height: 1.6; color: var(--ink2); }
.file-heading > .el-button { flex-shrink: 0; }
.file-controls { display: flex; align-items: center; flex-wrap: wrap; gap: 12px; font-size: 14px; }
.file-sheet-select { width: 240px; max-width: 100%; }
.file-count, .file-page-note { font-size: 13px; color: var(--ink2); }
.file-content { flex: 1; min-height: 0; display: flex; flex-direction: column; }
.file-warning { flex-shrink: 0; margin-bottom: 8px; }
.file-warning :deep(.el-alert__title) { color: var(--ink2); }
.file-table { flex: 1; min-height: 0; --el-table-header-text-color: var(--ink2); }
.file-cell { white-space: pre-wrap; overflow-wrap: anywhere; font-variant-numeric: tabular-nums; }
.file-error { margin: auto; text-align: center; color: var(--ink2); }
.file-pagination { display: flex; align-items: center; justify-content: space-between; gap: 8px; }

@media (max-width: 768px) {
  .file-viewer { gap: 12px; }
  .file-heading { flex-wrap: wrap; gap: 12px; }
  .file-controls { gap: 8px; }
  .file-sheet-select { flex: 1; min-width: 140px; }
  .file-count { width: 100%; }
}
</style>
