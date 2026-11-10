<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import ProductSelect from '@/components/basedata/ProductSelect.vue'
import LegacyDialog from './PurchaseWorkbookLegacyDialog.vue'
import type { Supplier } from '@/api/basedata'
import { previewSimplePurchase, commitSimplePurchase, purchaseWorkbookTemplate,
  type PurchaseSimplePreview, type PurchaseStockChoice, type PurchaseStockGroup,
  type PurchaseWorkbookDraft, type PurchaseImportKind } from '@/api/purchase'
const props = defineProps<{ modelValue: boolean; savedKeys: string[]; initialKind: PurchaseImportKind; suppliers: Supplier[] }>()
const emit = defineEmits<{ (e: 'update:modelValue', value: boolean): void; (e: 'import', draft: PurchaseWorkbookDraft): void; (e: 'legacy-order'): void; (e: 'completed'): void }>()
const advanced = ref(false), busy = ref(false), error = ref(''), input = ref<HTMLInputElement>()
const data = ref<PurchaseSimplePreview>(), supplierId = ref<number>(), defaultDate = ref('')
const choices = ref<Record<string, PurchaseStockChoice>>({}), rowChoices = ref<Record<number, number>>({})
const showDetails = ref(false), completed = ref<number[]>([])
watch(() => props.modelValue, open => { if (open) advanced.value = props.initialKind === 'order' })
const preview = computed(() => data.value?.preview)
const groupRows = computed(() => new Set(data.value?.groups.flatMap(g => g.sourceRows) || []))
const exceptions = computed(() => (preview.value?.rows || []).filter(r => !groupRows.value.has(r.rowNo) && (r.needsConfirmation || r.errors.length)))
const pending = computed(() => (data.value?.groups.filter(g => !choices.value[g.key]?.confirmed).length || 0) + exceptions.value.filter(r => !rowChoices.value[r.rowNo]).length)
const automatic = computed(() => (preview.value?.rows.length || 0) - groupRows.value.size - exceptions.value.length)
const total = computed(() => (preview.value?.days || []).reduce((sum, d) => sum + Number(d.totalAmount), 0))
const done = computed(() => completed.value.length > 0 || !!data.value?.receiptIds.length)
const canCommit = computed(() => !!data.value && !!supplierId.value && pending.value === 0 && !preview.value?.errors.length && !done.value && (!preview.value?.rows.some(r => !r.bizDate) || !!defaultDate.value))
function close() { if (!busy.value) emit('update:modelValue', false) }
function changeGroup(group: PurchaseStockGroup) {
  const c = choices.value[group.key]; c.confirmed = false
  if (!c.memberIds.includes(c.targetId) && c.memberIds.length) c.targetId = c.memberIds[0]
}
function codeFor(group: PurchaseStockGroup) { return group.members.find(m => m.productId === choices.value[group.key]?.targetId)?.skuCode || '' }
async function upload(event: Event) {
  const element = event.target as HTMLInputElement, file = element.files?.[0]; element.value = ''
  if (!file || busy.value) return
  busy.value = true; error.value = ''; data.value = undefined; completed.value = []; choices.value = {}; rowChoices.value = {}; defaultDate.value = ''; showDetails.value = false
  try {
    if (!/\.xlsx?$/i.test(file.name) || file.size > 5 * 1024 * 1024) throw new Error('请选择不超过 5MB 的 Excel 拿货表')
    data.value = await previewSimplePurchase(file)
    for (const group of data.value.groups) choices.value[group.key] = { key: group.key, targetId: group.targetId, memberIds: group.members.map(m => m.productId), confirmed: false }
  } catch (e) { error.value = e instanceof Error ? e.message : '读取失败，请重新上传' }
  finally { busy.value = false }
}
async function submit() {
  if (!canCommit.value || !data.value || !supplierId.value || busy.value) return
  busy.value = true; error.value = ''
  try {
    const result = await commitSimplePurchase({ batchKey: data.value.batchKey, supplierId: supplierId.value,
      defaultDate: defaultDate.value || undefined, groups: Object.values(choices.value),
      rows: Object.entries(rowChoices.value).map(([rowNo, productId]) => ({ rowNo: Number(rowNo), productId })) })
    completed.value = result.receiptIds
    ElMessage.success(result.alreadyImported ? '这张表已入库，已找回原入库结果' : `已完成 ${result.receiptIds.length} 张采购入库单`)
    emit('completed')
  } catch (e) { error.value = e instanceof Error ? e.message : '提交失败，请重试；同一张表不会重复入库' }
  finally { busy.value = false }
}
async function download() {
  busy.value = true; error.value = ''
  try {
    const blob = await purchaseWorkbookTemplate(), url = URL.createObjectURL(blob)
    const a = document.createElement('a'); a.href = url; a.download = '采购拿货表导入模板.xlsx'; a.click()
    setTimeout(() => URL.revokeObjectURL(url), 1000)
  } catch (e) { error.value = e instanceof Error ? e.message : '模板下载失败' }
  finally { busy.value = false }
}
</script>

<template>
  <LegacyDialog v-if="advanced" :model-value="modelValue" :saved-keys="savedKeys" initial-kind="order"
    @update:model-value="emit('update:modelValue', $event)" @import="emit('import', $event)" @legacy-order="emit('legacy-order')" />
  <el-dialog v-else :model-value="modelValue" title="采购入库 · 确认口味即可" width="min(960px, 96vw)" top="4vh"
    class="simple-purchase-dialog" :close-on-click-modal="false" :close-on-press-escape="!busy" :show-close="!busy" :before-close="close">
    <div class="simple-purchase" v-loading="busy">
      <div class="intro">正常商品自动匹配；同系列口味只确认一次共用库存。提交后按原表日期直接入库。</div>
      <div class="toolbar">
        <el-button type="primary" :disabled="busy" @click="input?.click()">{{ data ? '重新选择拿货表' : '选择拿货表' }}</el-button>
        <input ref="input" type="file" accept=".xls,.xlsx" hidden @change="upload">
        <el-button :disabled="busy" @click="download">下载模板</el-button>
        <span class="muted">{{ preview?.fileName || '支持原拿货表，多次采购可以一起上传' }}</span>
      </div>
      <el-alert v-if="error" type="error" :closable="false" :title="error" show-icon />
      <el-alert v-for="message in preview?.errors" :key="message" type="error" :closable="false" :title="message" />
      <el-result v-if="done" icon="success" title="这张拿货表已完成入库" :sub-title="`已按采购日期生成 ${completed.length || data?.receiptIds.length} 张入库单，重复提交不会再次增加库存。`">
        <template #extra><el-button type="primary" @click="close">返回采购列表</el-button></template>
      </el-result>
      <template v-else-if="data && preview">
        <div class="overview">
          <div><strong>{{ preview.rows.length }}</strong><span>行采购明细</span></div>
          <div><strong>{{ preview.days.length }}</strong><span>次采购</span></div>
          <div><strong>¥{{ total.toFixed(2) }}</strong><span>原表总金额</span></div>
        </div>
        <div class="supplier"><b>供应商</b><el-select v-model="supplierId" placeholder="选择一次，应用到本表全部采购" filterable :disabled="busy">
          <el-option v-for="s in suppliers" :key="s.id" :value="s.id!" :label="s.supplierName" />
        </el-select></div>
        <div v-if="preview.rows.some(r => !r.bizDate)" class="supplier"><b>补充日期</b><el-date-picker v-model="defaultDate" type="date" value-format="YYYY-MM-DD" placeholder="仅用于原表未写日期的行" /></div>
        <div class="ready"><b>✓ {{ automatic }} 行商品已自动匹配</b><span>数量、箱规和金额沿用原表，无需逐行确认。</span></div>
        <div v-if="data.groups.length" class="section-title"><b>确认哪些口味共用库存</b><span class="muted">跨日期的同系列商品只确认一次，不用填写各口味箱数。</span></div>
        <div v-for="group in data.groups" :key="group.key" class="stock-group" :class="{ confirmed: choices[group.key].confirmed }">
          <div class="group-heading"><div><b>{{ group.name }}</b><small>{{ group.sourceRows.length }} 行采购 · 统一使用 {{ codeFor(group) }}</small></div>
            <el-button v-if="choices[group.key].confirmed" link @click="choices[group.key].confirmed = false">已确认 · 修改</el-button>
          </div>
          <template v-if="!choices[group.key].confirmed">
            <el-checkbox-group v-model="choices[group.key].memberIds" :disabled="busy" @change="changeGroup(group)">
              <div v-for="member in group.members" :key="member.productId" class="flavor">
                <el-checkbox :value="member.productId">{{ member.productName }}</el-checkbox>
                <span class="muted">已售 {{ Number(member.salesQty) }} {{ member.unit }} · {{ member.skuCode }}</span>
              </div>
            </el-checkbox-group>
            <div class="group-footer"><span class="muted">勾选的口味共用总库存，原销售名称保留；未勾选的口味保留独立库存。</span>
              <el-button type="primary" plain :disabled="!choices[group.key].memberIds.length || busy" @click="choices[group.key].confirmed = true">确认共用库存</el-button>
            </div>
          </template>
        </div>
        <div v-if="exceptions.length" class="section-title"><b>另有 {{ exceptions.length }} 行需要核对</b><span class="muted">只处理不能确定的商品或原表数据问题。</span></div>
        <div v-for="row in exceptions" :key="row.rowNo" class="exception">
          <b>第 {{ row.rowNo }} 行 · {{ row.sourceName || row.skuCode }}</b>
          <ProductSelect v-model="rowChoices[row.rowNo]" lazy placeholder="选择对应商品后自动带入原数量和金额" />
          <small class="muted">{{ row.qty }} 基本单位 · ¥{{ Number(row.totalPrice).toFixed(2) }}；{{ row.matchMessage }}</small>
        </div>
        <el-button class="details-button" link @click="showDetails = !showDetails">{{ showDetails ? '收起' : '查看' }}全部明细与日期汇总</el-button>
        <div v-if="showDetails" class="details">
          <p v-for="day in preview.days" :key="day.bizDate">{{ day.bizDate || '待补日期' }} · {{ day.sourceCount }} 行 · ¥{{ Number(day.totalAmount).toFixed(2) }}</p>
          <el-table :data="preview.rows" max-height="280" border>
            <el-table-column prop="bizDate" label="采购日期" width="112" /><el-table-column prop="sourceName" label="原表商品" min-width="200" />
            <el-table-column prop="boxCount" label="箱数" width="70" /><el-table-column prop="qty" label="基本数量" width="90" /><el-table-column prop="totalPrice" label="金额" width="90" />
          </el-table>
        </div>
        <p class="muted">确认入库会保存口味的共用关系，已有及后续销售按该库存编码归集；采购日期、数量和原始金额保持一致。</p>
      </template>
      <el-empty v-else description="上传拿货表，系统自动整理商品、日期和金额" :image-size="60" />
      <el-button v-if="!done" class="advanced-button" link :disabled="busy" @click="advanced = true">尚未收到货？改用订货单导入</el-button>
    </div>
    <template #footer>
      <span v-if="data && !done" class="footer-hint">{{ pending ? `还有 ${pending} 项待确认` : !supplierId ? '请选择供应商' : '已准备好，确认后直接增加库存' }}</span>
      <el-button :disabled="busy" @click="close">关闭</el-button>
      <el-button v-if="!done" type="primary" :disabled="!canCommit" :loading="busy" @click="submit">确认并全部入库{{ preview ? `（${preview.days.length} 次采购）` : '' }}</el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
.intro,.ready { background:#edf5f0; border-radius:7px; padding:13px 16px; color:#285f49; line-height:1.6; }
.toolbar,.supplier,.section-title,.group-footer { display:flex; gap:12px; align-items:center; flex-wrap:wrap; margin:16px 0; }
.toolbar .el-button { margin-left:0; }.muted { color:#76847d; font-size:12px; line-height:1.7; overflow-wrap:anywhere; }
.overview { display:grid; grid-template-columns:1fr 1fr 1.5fr; gap:12px; margin:20px 0; }.overview > div { padding:16px; background:#f6f8f6; border-radius:7px; }
.overview strong { display:block; font-size:22px; color:#285f49; }.overview span { display:block; margin-top:6px; color:#76847d; font-size:12px; }
.supplier .el-select { width:360px; max-width:100%; }.ready span { display:block; font-size:12px; margin-top:4px; }
.stock-group { border:1px solid #dce5df; border-radius:7px; padding:16px; margin:12px 0; }.stock-group.confirmed { background:#f1f7f3; }
.group-heading { display:flex; align-items:center; justify-content:space-between; gap:8px; }.group-heading small { display:block; color:#76847d; margin-top:6px; }
.stock-group .el-checkbox-group { margin-top:12px; }.flavor { display:flex; justify-content:space-between; gap:12px; align-items:center; border-top:1px solid #edf0ed; padding:7px 0; }
.flavor .el-checkbox { height:auto; min-height:28px; margin-right:0; max-width:100%; }.flavor :deep(.el-checkbox__label) { white-space:normal; line-height:1.7; }
.group-footer { justify-content:space-between; margin-bottom:0; }.group-footer span { flex:1; min-width:180px; }
.exception { display:grid; gap:10px; padding:14px; border:1px solid #ead7b9; border-radius:6px; margin:12px 0; }.details-button { margin-top:10px; }.advanced-button { margin-top:14px; }
.footer-hint { flex:1; color:#718078; font-size:12px; text-align:left; }.details { margin-top:12px; }
:global(.simple-purchase-dialog) { display:flex; flex-direction:column; max-height:92vh; margin-bottom:0; }
:global(.simple-purchase-dialog .el-dialog__body) { overflow-y:auto; min-height:0; flex:1; }
:global(.simple-purchase-dialog .el-dialog__footer) { flex-shrink:0; display:flex; align-items:center; gap:10px; flex-wrap:wrap; }
:global(.simple-purchase-dialog .el-dialog__footer .el-button) { margin-left:0; }
@media(max-width:600px) { .flavor { align-items:flex-start; flex-direction:column; gap:0; }.overview { gap:6px; }.overview > div { padding:10px; }.overview strong { font-size:18px; }.footer-hint { flex-basis:100%; } }
</style>
