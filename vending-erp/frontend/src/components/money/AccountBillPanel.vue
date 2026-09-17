<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import type { AccountRow } from '@/api/money'
import { listAccountBills, previewAccountBills, importAccountBills, type AccountBill, type BillPreview, type BillTotals, type BillQuery } from '@/api/account-bills'
import AccountBillTable from './AccountBillTable.vue'
const props = defineProps<{ accounts: AccountRow[]; modelValue: boolean }>()
const emit = defineEmits<{ (event: 'update:modelValue', value: boolean): void }>()
const realAccounts = computed(() => props.accounts.filter(a => !a.isVirtual && a.status === 1))
const rows = ref<AccountBill[]>([])
const totals = ref<BillTotals | null>(null)
const total = ref(0)
const loading = ref(false)
const query = reactive<BillQuery>({ current: 1, size: 20 })
const dates = ref<[string, string] | null>(null)
const loadError = ref('')
let loadGeneration = 0
async function load(reset = false) {
  if (reset) query.current = 1
  query.from = dates.value?.[0]; query.to = dates.value?.[1]
  const version = ++loadGeneration
  loading.value = true; loadError.value = ''
  try {
    const data = await listAccountBills({ ...query })
    if (version !== loadGeneration) return
    rows.value = data.page.records; total.value = data.page.total; totals.value = data.totals
  } catch (e) { if (version === loadGeneration) loadError.value = e instanceof Error ? e.message : '账单读取失败' }
  finally { if (version === loadGeneration) loading.value = false }
}
const fileInput = ref<HTMLInputElement>()
const file = ref<File | null>(null)
const accountId = ref<number | null>(null)
const preview = ref<BillPreview | null>(null)
const checking = ref(false)
const importing = ref(false)
const error = ref('')
let generation = 0
watch(() => props.modelValue, () => {
  generation++; file.value = null; preview.value = null; accountId.value = null; error.value = ''; checking.value = false
})
watch(accountId, () => { generation++; preview.value = null; checking.value = false })
function selectFile(event: Event) {
  const input = event.target as HTMLInputElement
  generation++; preview.value = null; error.value = ''; file.value = input.files?.[0] || null; input.value = ''
  if (file.value && (!/\.xlsx?$/i.test(file.value.name) || file.value.size > 5 * 1024 * 1024 || !file.value.size)) {
    file.value = null; error.value = '请选择不超过 5MB 的非空 .xls 或 .xlsx 账单'
  }
}
async function check() {
  if (!file.value) return
  const version = ++generation
  checking.value = true; preview.value = null; error.value = ''
  try {
    const data = await previewAccountBills(file.value, accountId.value)
    if (version === generation) preview.value = data
  } catch (e) { if (version === generation) error.value = e instanceof Error ? e.message : '账单校验失败' }
  finally { if (version === generation) checking.value = false }
}
async function confirmImport() {
  if (!file.value || !preview.value || preview.value.errors.length || importing.value) return
  importing.value = true
  try {
    const result = await importAccountBills(file.value, accountId.value)
    ElMessage.success(`已导入 ${result.inserted} 条，重复跳过 ${result.skipped} 条`)
    emit('update:modelValue', false)
    dates.value = null; query.accountId = undefined; query.status = undefined
    await load(true)
  } catch (e) { error.value = e instanceof Error ? e.message : '导入失败；可重新预览后重试' }
  finally { importing.value = false }
}
function close() { if (!importing.value) emit('update:modelValue', false) }
const money = (v: string | number) => Number(v).toFixed(2)
onMounted(() => load())
</script>
<template>
  <section class="ledger-card">
    <h3>渠道账单明细</h3>
    <p class="mini">按原文件保留收益、手续费、到账日期和结算状态。账单合计不等于账户余额；核实到账账户与手续费口径后，再走资金与对账中的结算核销。</p>
    <div class="bill-actions">
      <el-date-picker v-model="dates" type="daterange" value-format="YYYY-MM-DD" start-placeholder="账单开始日期" end-placeholder="账单结束日期" style="max-width: 310px" />
      <el-select v-model="query.accountId" clearable placeholder="全部资金账户" style="width: 180px">
        <el-option v-for="a in accounts" :key="a.id" :label="a.accountName" :value="a.id" />
      </el-select>
      <el-input v-model="query.status" clearable placeholder="结算状态，如：成功" style="width: 170px" />
      <el-button :loading="loading" @click="load(true)">查询</el-button>
    </div>
    <el-alert v-if="loadError" :title="loadError" type="error" :closable="false" />
    <div v-if="totals" class="bill-totals">
      <span>筛选结果 {{ totals.count }} 笔</span>
      <span>收益金额 ¥{{ money(totals.incomeAmount) }}</span>
      <span>支付手续费 ¥{{ money(totals.feeAmount) }}</span>
      <span>结算金额 ¥{{ money(totals.settlementAmount) }}</span>
      <span>成功结算 ¥{{ money(totals.successfulSettlementAmount) }}</span>
    </div>
    <AccountBillTable v-loading="loading" :rows="rows" />
    <el-pagination v-model:current-page="query.current" :page-size="query.size" :total="total" layout="prev, pager, next, total" @current-change="load()" />
  </section>
  <el-dialog :model-value="modelValue" title="账单信息导入" width="min(1100px, 96vw)" :close-on-click-modal="false"
    :close-on-press-escape="!importing" :show-close="!importing" @update:model-value="close">
    <el-alert type="info" :closable="false" title="支持原始 .xls / .xlsx 账单（单工作表，最多 1000 行、5MB），无需改列名。" />
    <p>保留账单时间、收益金额、支付手续费、到账时间、结算金额、结算状态及其余原始字段；同一笔账单重复上传会跳过，金额或状态冲突会阻止导入。</p>
    <p class="mini">导入不会创建资金账户、设置期初或重复记收入。可先不关联账户，账单原值仍完整保留。</p>
    <div class="bill-actions">
      <el-select v-model="accountId" :disabled="checking || importing" clearable placeholder="关联到账账户（可暂不选）" style="width: 260px">
        <el-option v-for="a in realAccounts" :key="a.id" :label="a.accountName" :value="a.id" />
      </el-select>
      <el-button :disabled="checking || importing" @click="fileInput?.click()">选择账单文件</el-button>
      <input ref="fileInput" type="file" hidden accept=".xls,.xlsx" :disabled="checking || importing" @change="selectFile" />
      <span class="file-name">{{ file?.name }}</span>
      <el-button :disabled="!file || importing" :loading="checking" @click="check">读取并预览</el-button>
    </div>
    <el-alert v-if="error" :title="error" type="error" :closable="false" />
    <template v-if="preview">
      <div class="bill-totals">
        <span>文件 {{ preview.totals.count }} 笔</span><span>新增 {{ preview.newCount }}，重复 {{ preview.duplicateCount }}</span>
        <span>收益 ¥{{ money(preview.totals.incomeAmount) }}</span><span>手续费 ¥{{ money(preview.totals.feeAmount) }}</span><span>结算 ¥{{ money(preview.totals.settlementAmount) }}</span>
      </div>
      <el-alert v-if="preview.errors.length" type="error" :closable="false" title="存在冲突，整批不会导入，请先核对。" />
      <ul class="bill-errors"><li v-for="(message, i) in preview.errors" :key="i">{{ message }}</li></ul>
      <AccountBillTable :rows="preview.rows" preview />
    </template>
    <template #footer>
      <el-button :disabled="importing" @click="close">取消</el-button>
      <el-button type="primary" :loading="importing" :disabled="checking || !preview || !!preview.errors.length || !preview.newCount" @click="confirmImport">确认导入账单</el-button>
    </template>
  </el-dialog>
</template>
<style scoped>
.bill-actions, .bill-totals { display: flex; flex-wrap: wrap; align-items: center; gap: 12px; margin: 14px 0; }
.bill-totals { font-variant-numeric: tabular-nums; }
.file-name { overflow-wrap: anywhere; }
.bill-errors { color: var(--el-color-danger); max-height: 160px; overflow: auto; }
</style>
