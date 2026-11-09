<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { parseMachineList, reviewMachineList, commitMachineList, downloadMachineTemplate,
  type MachineImportRow, type MachineImportResult } from '@/api/basedata'

const props = defineProps<{ visible: boolean }>()
const emit = defineEmits<{ (e: 'update:visible', value: boolean): void; (e: 'saved'): void }>()
type EditableRow = MachineImportRow & { selected: boolean }
const input = ref<HTMLInputElement>()
const busy = ref(false)
const error = ref('')
const fileName = ref('')
const sourceRows = ref(0)
const headerRow = ref(0)
const rows = ref<EditableRow[]>([])
const result = ref<MachineImportResult | null>(null)
const selected = computed(() => rows.value.filter(r => r.selected))
const count = (action: string) => rows.value.filter(r => r.action === action).length

watch(() => props.visible, value => {
  if (value) {
    rows.value = []; result.value = null; error.value = ''; fileName.value = ''; sourceRows.value = 0
  }
})
function close() { if (!busy.value) emit('update:visible', false) }
function edited(row: EditableRow) { row.action = '待检查'; row.message = '已修改，导入时会重新检查' }
function chooseAll(value: boolean) { rows.value.forEach(r => { r.selected = value && r.action !== '已存在' }) }
function message(e: unknown) { return e instanceof Error ? e.message : '操作失败，请重试' }
async function upload(event: Event) {
  const element = event.target as HTMLInputElement
  const file = element.files?.[0]; element.value = ''
  if (!file || busy.value) return
  if (!/\.xlsx?$/i.test(file.name) || file.size > 20 * 1024 * 1024) {
    error.value = '请选择不超过 20MB 的 .xls 或 .xlsx 文件'; return
  }
  busy.value = true; error.value = ''; rows.value = []; result.value = null; fileName.value = ''
  try {
    const data = await parseMachineList(file)
    rows.value = data.rows.map(row => ({ ...row, selected: row.action !== '已存在' }))
    fileName.value = file.name; sourceRows.value = data.sourceRows; headerRow.value = data.headerRowNo
  } catch (e) { error.value = message(e) }
  finally { busy.value = false }
}
async function download() {
  busy.value = true; error.value = ''
  try {
    const blob = await downloadMachineTemplate()
    const url = URL.createObjectURL(blob)
    const link = document.createElement('a'); link.href = url; link.download = '机器列表导入模板.xlsx'
    document.body.appendChild(link); link.click(); link.remove()
    window.setTimeout(() => URL.revokeObjectURL(url), 1000)
  } catch (e) { error.value = message(e) }
  finally { busy.value = false }
}
async function checkAndImport(commit: boolean) {
  if (!selected.value.length || busy.value) return
  busy.value = true; error.value = ''
  try {
    const wanted = selected.value
    const checked = await reviewMachineList(wanted)
    checked.rows.forEach((row, index) => Object.assign(wanted[index], row))
    if (checked.errorCount) { error.value = `有 ${checked.errorCount} 台机器需要修改，请按行内提示核对，或取消勾选。`; return }
    if (!commit) { ElMessage.success('检查完成，可以确认导入'); return }
    result.value = await commitMachineList(checked.rows)
    emit('saved')
    ElMessage.success(`已新增 ${result.value.created} 台机器，跳过 ${result.value.skipped} 台已有机器`)
  } catch (e) { error.value = message(e) }
  finally { busy.value = false }
}
</script>

<template>
  <el-dialog :model-value="visible" title="导入机器列表" width="min(1180px, 96vw)" top="4vh" class="machine-import-dialog"
    :close-on-click-modal="false" :close-on-press-escape="!busy" :show-close="!busy" :before-close="close">
    <div class="machine-import" v-loading="busy" element-loading-text="正在处理机器列表…">
      <template v-if="!result">
        <div class="intro">直接上传“元气森林机子编号清单”。系统自动跳过标题说明，识别机子编号和点位名称，核对后一次导入。</div>
        <div class="toolbar">
          <el-button type="primary" :disabled="busy" @click="input?.click()">{{ fileName ? '重新选择表格' : '选择机器表格' }}</el-button>
          <input ref="input" type="file" accept=".xls,.xlsx" hidden @change="upload">
          <el-button :disabled="busy" @click="download">下载模板</el-button>
          <span class="muted">支持 .xls / .xlsx，也可以上传含“柜子编码、点位名称”的销售表</span>
        </div>
        <el-alert v-if="error" :title="error" type="error" show-icon :closable="false" class="notice" />
        <div v-if="fileName" class="file-note">{{ fileName }} · 表头在第 {{ headerRow }} 行 · {{ sourceRows }} 条记录，合并为 {{ rows.length }} 台机器</div>
        <div class="toolbar" v-if="rows.length">
          <b>已勾选 {{ selected.length }} / {{ rows.length }} 台</b>
          <el-tag type="success">待新增 {{ count('待新增') }}</el-tag>
          <el-tag type="info">已存在 {{ count('已存在') }}</el-tag>
          <el-tag type="warning">待检查 {{ count('待检查') }}</el-tag>
          <el-button link :disabled="busy" @click="chooseAll(true)">全选可导入</el-button>
          <el-button link :disabled="busy" @click="chooseAll(false)">全不选</el-button>
        </div>
        <el-table :data="rows" border class="preview-table" max-height="420">
          <el-table-column label="导入" width="58" align="center">
            <template #default="{ row }"><el-checkbox v-model="row.selected" :disabled="busy || row.action === '已存在'" :aria-label="`导入第${row.rowNo}行`" /></template>
          </el-table-column>
          <el-table-column label="机子编号（后台设备ID）" min-width="240">
            <template #default="{ row }">
              <el-input v-model="row.deviceId" :disabled="busy || row.action === '已存在'" aria-label="机子编号" @input="edited(row)" />
              <small class="muted">来源第 {{ row.rowNo }} 行<span v-if="row.sourceRowCount > 1"> · 合并 {{ row.sourceRowCount }} 条</span></small>
            </template>
          </el-table-column>
          <el-table-column label="机器名称 / 点位" min-width="280">
            <template #default="{ row }">
              <el-input v-model="row.machineName" :disabled="busy || row.action === '已存在'" placeholder="机器名称" aria-label="机器名称" @input="edited(row)" />
              <el-input v-model="row.location" :disabled="busy || row.action === '已存在'" placeholder="点位 / 位置" aria-label="点位" class="location" @input="edited(row)" />
              <small v-if="row.warning" class="warning">{{ row.warning }}</small>
            </template>
          </el-table-column>
          <el-table-column label="内部编号（可留空）" min-width="175">
            <template #default="{ row }"><el-input v-model="row.machineCode" :disabled="busy || row.action === '已存在'" placeholder="确认后自动编号" aria-label="内部编号" @input="edited(row)" /></template>
          </el-table-column>
          <el-table-column label="处理结果" min-width="210">
            <template #default="{ row }">
              <el-tag :type="row.action === '待检查' ? 'warning' : row.action === '已存在' ? 'info' : 'success'">{{ row.action }}</el-tag>
              <div class="row-message" :class="{ warning: row.action === '待检查' }">{{ row.message }}</div>
            </template>
          </el-table-column>
          <template #empty>
            <div class="empty-state"><b>两列就能导入机器</b><p>机子编号（柜子编码） + 点位名称（表内最近记录）</p><p>上传后在这里预览，可直接修改名称和点位。</p></div>
          </template>
        </el-table>
        <p class="muted footnote">机子编号原样保存，用于匹配销售与补货记录。内部编号留空时自动生成 M 编号；已有设备跳过并保留原档案。货道在导入后单独配置。</p>
      </template>
      <template v-else>
        <el-alert type="success" :closable="false" show-icon :title="`导入完成：新增 ${result.created} 台，跳过已有 ${result.skipped} 台`" />
        <el-table :data="result.rows" border max-height="420" class="result-table">
          <el-table-column prop="machineCode" label="内部编号" min-width="120" />
          <el-table-column prop="deviceId" label="机子编号" min-width="230" />
          <el-table-column prop="machineName" label="机器名称" min-width="230" />
          <el-table-column prop="action" label="结果" width="100" />
        </el-table>
        <p class="muted">机器列表已刷新。需要设置货道时，点击对应机器的“货道配置”。</p>
      </template>
    </div>
    <template #footer>
      <el-button :disabled="busy" @click="close">{{ result ? '完成' : '取消' }}</el-button>
      <template v-if="!result">
        <el-button :disabled="busy || !selected.length" @click="checkAndImport(false)">重新检查</el-button>
        <el-button type="primary" :loading="busy" :disabled="!selected.length" @click="checkAndImport(true)">确认导入（{{ selected.length }} 台）</el-button>
      </template>
    </template>
  </el-dialog>
</template>

<style scoped>
.machine-import { min-width: 0; }
.intro { padding: 12px 16px; background: #edf5f0; color: #285e4b; border-radius: 6px; line-height: 1.7; }
.toolbar { display: flex; gap: 10px; align-items: center; flex-wrap: wrap; margin: 16px 0; }
.toolbar .el-button { margin-left: 0; }
.muted, .file-note { color: #6e8178; font-size: 12px; line-height: 1.8; }
.file-note { overflow-wrap: anywhere; }
.notice { margin: 14px 0; }
.location { margin-top: 6px; }
.warning { color: #b66a12; }
.row-message { margin-top: 6px; font-size: 12px; line-height: 1.6; }
.empty-state { line-height: 1.8; padding: 28px 16px; white-space: normal; }
.footnote { margin-bottom: 0; }
.result-table { margin: 16px 0; }
:global(.machine-import-dialog) { display: flex; flex-direction: column; max-height: 92vh; margin-bottom: 0; }
:global(.machine-import-dialog .el-dialog__body) { flex: 1 1 auto; min-height: 0; overflow-y: auto; }
:global(.machine-import-dialog .el-dialog__header) { flex-shrink: 0; }
:global(.machine-import-dialog .el-dialog__footer) { flex-shrink: 0; display: flex; flex-wrap: wrap; justify-content: flex-end; gap: 8px; }
:global(.machine-import-dialog .el-dialog__footer .el-button) { margin-left: 0; }
</style>
