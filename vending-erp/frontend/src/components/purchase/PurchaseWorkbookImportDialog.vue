<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import ProductSelect from '@/components/basedata/ProductSelect.vue'
import LegacyDialog from './PurchaseWorkbookLegacyDialog.vue'
import { getProduct, type Supplier } from '@/api/basedata'
import {
  previewSimplePurchase, commitSimplePurchase, purchaseWorkbookTemplate,
  type PurchaseSimplePreview, type PurchaseStockChoice, type PurchaseStockGroup,
  type PurchaseWorkbookDraft, type PurchaseImportKind, type PurchaseWorkbookRow,
  type PurchaseWorkbookSection,
} from '@/api/purchase'

const props = defineProps<{ modelValue: boolean; savedKeys: string[]; initialKind: PurchaseImportKind; suppliers: Supplier[] }>()
const emit = defineEmits<{
  (e: 'update:modelValue', value: boolean): void
  (e: 'import', draft: PurchaseWorkbookDraft): void
  (e: 'legacy-order'): void
  (e: 'completed'): void
}>()

const advanced = ref(false)
const busy = ref(false)
const error = ref('')
const input = ref<HTMLInputElement>()
const data = ref<PurchaseSimplePreview>()
const supplierId = ref<number>()
const defaultDate = ref('')
const choices = ref<Record<string, PurchaseStockChoice>>({})
const rowChoices = ref<Record<number, number>>({})
const editingRows = ref<Record<number, boolean>>({})
const productMeta = ref<Record<number, { skuCode: string; productName: string }>>({})
const filterMode = ref<'all' | 'unmatched'>('all')
const showSharedPanel = ref(false)
const rememberBindings = ref(true)
const completed = ref<number[]>([])

watch(() => props.modelValue, open => {
  if (open) advanced.value = props.initialKind === 'order'
})

const preview = computed(() => data.value?.preview)
const done = computed(() => completed.value.length > 0 || !!data.value?.receiptIds.length)

const groupByRow = computed(() => {
  const map: Record<number, PurchaseStockGroup> = {}
  for (const g of data.value?.groups || []) {
    for (const rowNo of g.sourceRows) map[rowNo] = g
  }
  return map
})

const rowByNo = computed(() => {
  const map: Record<number, PurchaseWorkbookRow> = {}
  for (const row of preview.value?.rows || []) map[row.rowNo] = row
  return map
})

const IGNORABLE_BIND_ERRORS = [
  '请选择对应的销售商品',
  '请确认这行的口味',
  '请填写有效采购日期',
]

function dataErrorsOf(row: PurchaseWorkbookRow): string[] {
  return (row.errors || []).filter(msg => !IGNORABLE_BIND_ERRORS.some(prefix => msg.includes(prefix)))
}

function isAutoMatched(row: PurchaseWorkbookRow): boolean {
  if (dataErrorsOf(row).length > 0) return false
  if (row.matchStatus === 'matched') return true
  if (row.matchStatus === 'unmatched') return false
  return !row.needsConfirmation && row.allocations.length === 1 && row.allocations[0]?.productId != null
}

function rowBindingKind(row: PurchaseWorkbookRow): 'manual' | 'shared' | 'auto' | 'unmatched' {
  if (dataErrorsOf(row).length > 0) return 'unmatched'
  if (rowChoices.value[row.rowNo]) return 'manual'
  const group = groupByRow.value[row.rowNo]
  if (group && choices.value[group.key]?.confirmed) return 'shared'
  if (isAutoMatched(row)) return 'auto'
  return 'unmatched'
}

function isRowGreen(row: PurchaseWorkbookRow): boolean {
  return rowBindingKind(row) !== 'unmatched'
}

function effectiveProductId(row: PurchaseWorkbookRow): number | null {
  if (rowChoices.value[row.rowNo]) return rowChoices.value[row.rowNo]
  const group = groupByRow.value[row.rowNo]
  if (group && choices.value[group.key]?.confirmed) {
    const choice = choices.value[group.key]
    const suggested = row.suggestedProductId
    if (suggested && !choice.memberIds.includes(suggested)) return suggested
    return choice.targetId
  }
  if (row.matchedProductId) return row.matchedProductId
  if (isAutoMatched(row)) return row.allocations[0]?.productId ?? null
  return null
}

function productLabel(row: PurchaseWorkbookRow): string {
  const id = effectiveProductId(row)
  if (!id) return ''
  if (row.matchedProductId === id && row.matchedProductName) {
    return row.matchedSkuCode ? `${row.matchedSkuCode} · ${row.matchedProductName}` : row.matchedProductName
  }
  const meta = productMeta.value[id]
  if (meta) return meta.skuCode ? `${meta.skuCode} · ${meta.productName}` : meta.productName
  const cand = row.candidates?.find(c => c.productId === id)
  if (cand) return `${cand.skuCode} · ${cand.productName}`
  return `商品 #${id}`
}

function sameNameUnmatchedCount(row: PurchaseWorkbookRow): number {
  if (!row.sourceName) return 0
  return (preview.value?.rows || []).filter(
    other => other.rowNo !== row.rowNo && other.sourceName === row.sourceName && !isRowGreen(other) && dataErrorsOf(other).length === 0,
  ).length
}

function applyToSameName(row: PurchaseWorkbookRow) {
  const targetId = rowChoices.value[row.rowNo]
  if (!targetId || !row.sourceName) return
  let applied = 0
  for (const other of preview.value?.rows || []) {
    if (other.rowNo !== row.rowNo && other.sourceName === row.sourceName && !isRowGreen(other) && dataErrorsOf(other).length === 0) {
      rowChoices.value[other.rowNo] = targetId
      editingRows.value[other.rowNo] = false
      applied++
    }
  }
  if (applied > 0) ElMessage.success(`已同步绑定另外 ${applied} 行「${row.sourceName}」`)
}

async function bindRow(row: PurchaseWorkbookRow, productId: number | null, known?: { skuCode: string; productName: string }) {
  if (!productId) {
    delete rowChoices.value[row.rowNo]
    return
  }
  rowChoices.value[row.rowNo] = productId
  editingRows.value[row.rowNo] = false
  if (known) {
    productMeta.value[productId] = known
    return
  }
  const cand = row.candidates?.find(c => c.productId === productId)
  if (cand) {
    productMeta.value[productId] = { skuCode: cand.skuCode, productName: cand.productName }
    return
  }
  if (!productMeta.value[productId]) {
    try {
      const product = await getProduct(productId)
      productMeta.value[productId] = { skuCode: product.skuCode, productName: product.productName }
    } catch {
      // 保留 ID 展示，提交时由后端核验商品是否存在。
    }
  }
}

function resetRowBinding(row: PurchaseWorkbookRow) {
  delete rowChoices.value[row.rowNo]
  editingRows.value[row.rowNo] = false
}

const displaySections = computed<Array<PurchaseWorkbookSection & { rows: PurchaseWorkbookRow[]; matchedCount: number; unmatchedCount: number }>>(() => {
  if (!preview.value) return []
  const rawSections: PurchaseWorkbookSection[] = preview.value.sections?.length
    ? preview.value.sections
    : (preview.value.days || []).map((day, idx) => {
        const dayRows = preview.value!.rows.filter(r => (r.bizDate || '') === (day.bizDate || ''))
        const boxSum = dayRows.every(r => r.boxCount != null) && dayRows.length
          ? dayRows.reduce((s, r) => s + Number(r.boxCount || 0), 0)
          : null
        return {
          key: day.bizDate || `sec-${idx + 1}`,
          title: day.bizDate ? `采购日期：${day.bizDate}` : '采购明细（待补日期）',
          bizDate: day.bizDate,
          note: null,
          totalBoxCount: boxSum,
          totalQty: Number(day.totalQty || 0),
          totalAmount: Number(day.totalAmount || 0),
          rowNos: dayRows.map(r => r.rowNo),
        }
      })

  return rawSections.map(sec => {
    const allRows = sec.rowNos.map(no => rowByNo.value[no]).filter(Boolean)
    const matchedCount = allRows.filter(isRowGreen).length
    const unmatchedCount = allRows.length - matchedCount
    const visibleRows = filterMode.value === 'unmatched'
      ? allRows.filter(r => !isRowGreen(r) || editingRows.value[r.rowNo])
      : allRows
    return { ...sec, rows: visibleRows, matchedCount, unmatchedCount }
  })
})

const allRows = computed(() => preview.value?.rows || [])
const matchedCount = computed(() => allRows.value.filter(isRowGreen).length)
const unmatchedCount = computed(() => allRows.value.length - matchedCount.value)
const totalQty = computed(() => allRows.value.reduce((sum, r) => sum + Number(r.qty || 0), 0))
const totalAmount = computed(() => allRows.value.reduce((sum, r) => sum + Number(r.totalPrice || 0), 0))
const hasBlockingRowErrors = computed(() => allRows.value.some(r => dataErrorsOf(r).length > 0))
const needsDefaultDate = computed(() => allRows.value.some(r => !r.bizDate))
const receiptCount = computed(() => {
  const dates = new Set(allRows.value.map(r => r.bizDate || defaultDate.value).filter(Boolean))
  return Math.max(dates.size, preview.value?.days?.length || 0)
})

const canCommit = computed(() =>
  !!data.value
  && !!supplierId.value
  && unmatchedCount.value === 0
  && !preview.value?.errors.length
  && !hasBlockingRowErrors.value
  && !done.value
  && (!needsDefaultDate.value || !!defaultDate.value),
)

function close() {
  if (!busy.value) emit('update:modelValue', false)
}

function changeGroup(group: PurchaseStockGroup) {
  const c = choices.value[group.key]
  c.confirmed = false
  if (!c.memberIds.includes(c.targetId) && c.memberIds.length) c.targetId = c.memberIds[0]
}

function codeFor(group: PurchaseStockGroup) {
  return group.members.find(m => m.productId === choices.value[group.key]?.targetId)?.skuCode || ''
}

function fmtNum(v: number | string | null | undefined): string {
  if (v == null || v === '') return '—'
  const n = Number(v)
  if (!Number.isFinite(n)) return String(v)
  return Number.isInteger(n) ? String(n) : String(Number(n.toFixed(3)))
}

function fmtMoney(v: number | string | null | undefined): string {
  if (v == null || v === '') return '—'
  const n = Number(v)
  if (!Number.isFinite(n)) return String(v)
  return Number.isInteger(n) ? String(n) : n.toFixed(2)
}

async function upload(event: Event) {
  const element = event.target as HTMLInputElement
  const file = element.files?.[0]
  element.value = ''
  if (!file || busy.value) return
  busy.value = true
  error.value = ''
  data.value = undefined
  completed.value = []
  choices.value = {}
  rowChoices.value = {}
  editingRows.value = {}
  productMeta.value = {}
  defaultDate.value = ''
  filterMode.value = 'all'
  showSharedPanel.value = false
  try {
    if (!/\.xlsx?$/i.test(file.name) || file.size > 5 * 1024 * 1024) {
      throw new Error('请选择不超过 5MB 的 Excel 拿货表')
    }
    const res = await previewSimplePurchase(file)
    data.value = res
    for (const row of res.preview.rows) {
      if (row.matchedProductId && row.matchedProductName) {
        productMeta.value[row.matchedProductId] = {
          skuCode: row.matchedSkuCode || '',
          productName: row.matchedProductName,
        }
      }
      for (const c of row.candidates || []) {
        productMeta.value[c.productId] = { skuCode: c.skuCode, productName: c.productName }
      }
    }
    for (const group of res.groups) {
      choices.value[group.key] = {
        key: group.key,
        targetId: group.targetId,
        memberIds: group.members.map(m => m.productId),
        confirmed: false,
      }
    }
  } catch (e) {
    error.value = e instanceof Error ? e.message : '读取失败，请重新上传'
  } finally {
    busy.value = false
  }
}

async function submit() {
  if (!canCommit.value || !data.value || !supplierId.value || busy.value) return
  busy.value = true
  error.value = ''
  try {
    const confirmedGroups = Object.values(choices.value).filter(c => c.confirmed)
    const result = await commitSimplePurchase({
      batchKey: data.value.batchKey,
      supplierId: supplierId.value,
      defaultDate: defaultDate.value || undefined,
      rememberBindings: rememberBindings.value,
      groups: confirmedGroups,
      rows: Object.entries(rowChoices.value).map(([rowNo, productId]) => ({ rowNo: Number(rowNo), productId })),
    })
    completed.value = result.receiptIds
    ElMessage.success(result.alreadyImported ? '这张表已入库，已找回原入库结果' : `已完成 ${result.receiptIds.length} 张采购入库单`)
    emit('completed')
  } catch (e) {
    error.value = e instanceof Error ? e.message : '提交失败，请重试；同一张表不会重复入库'
  } finally {
    busy.value = false
  }
}

async function download() {
  busy.value = true
  error.value = ''
  try {
    const blob = await purchaseWorkbookTemplate()
    const url = URL.createObjectURL(blob)
    const a = document.createElement('a')
    a.href = url
    a.download = '采购拿货表导入模板.xlsx'
    a.click()
    setTimeout(() => URL.revokeObjectURL(url), 1000)
  } catch (e) {
    error.value = e instanceof Error ? e.message : '模板下载失败'
  } finally {
    busy.value = false
  }
}
</script>

<template>
  <LegacyDialog
    v-if="advanced"
    :model-value="modelValue"
    :saved-keys="savedKeys"
    initial-kind="order"
    @update:model-value="emit('update:modelValue', $event)"
    @import="emit('import', $event)"
    @legacy-order="emit('legacy-order')"
  />
  <el-dialog
    v-else
    :model-value="modelValue"
    title="采购入库 · 按表格导入"
    width="min(1240px, 96vw)"
    top="3vh"
    class="simple-purchase-dialog"
    :close-on-click-modal="false"
    :close-on-press-escape="!busy"
    :show-close="!busy"
    :before-close="close"
  >
    <div class="simple-purchase" v-loading="busy">
      <div class="toolbar">
        <el-button type="primary" :disabled="busy" @click="input?.click()">
          {{ data ? '重新选择表格' : '选择采购/送货单表格' }}
        </el-button>
        <input ref="input" type="file" accept=".xls,.xlsx" hidden @change="upload">
        <el-button :disabled="busy" @click="download">下载模板</el-button>
        <span class="muted">{{ preview?.fileName || '支持送货单整理表及拿货原表；绿色=系统已自动匹配，红色=未匹配可手动绑定' }}</span>
        <el-button v-if="!done" class="advanced-link" link :disabled="busy" @click="advanced = true">
          尚未收到货？改用订货单导入
        </el-button>
      </div>

      <el-alert v-if="error" type="error" :closable="false" :title="error" show-icon class="mb-10px" />
      <el-alert v-for="message in preview?.errors" :key="message" type="error" :closable="false" :title="message" show-icon class="mb-8px" />

      <el-result
        v-if="done"
        icon="success"
        title="这张拿货表已完成入库"
        :sub-title="`已按采购日期生成 ${completed.length || data?.receiptIds.length} 张入库单，重复提交不会再次增加库存。`"
      >
        <template #extra>
          <el-button type="primary" @click="close">返回采购列表</el-button>
        </template>
      </el-result>

      <template v-else-if="data && preview">
        <!-- 顶部统一控制与统计条 -->
        <div class="control-bar">
          <div class="control-field">
            <b>供应商</b>
            <el-select v-model="supplierId" placeholder="选择供应商（应用到本表全部）" filterable :disabled="busy" size="small" style="width: 240px">
              <el-option v-for="s in suppliers" :key="s.id" :value="s.id!" :label="s.supplierName" />
            </el-select>
          </div>
          <div v-if="needsDefaultDate" class="control-field">
            <b>采购日期</b>
            <el-date-picker v-model="defaultDate" type="date" value-format="YYYY-MM-DD" placeholder="补充无日期行的日期" size="small" style="width: 160px" />
          </div>
          <div class="legend-stats">
            <span class="pill pill-green">● 已匹配 {{ matchedCount }} 行</span>
            <span class="pill" :class="unmatchedCount > 0 ? 'pill-red' : 'pill-neutral'">● 未匹配 {{ unmatchedCount }} 行</span>
            <span class="pill pill-summary">共 {{ allRows.length }} 行 · {{ fmtNum(totalQty) }} 件 · ¥{{ totalAmount.toFixed(2) }}</span>
          </div>
          <div class="control-right">
            <el-radio-group v-model="filterMode" size="small">
              <el-radio-button value="all">全部 ({{ allRows.length }})</el-radio-button>
              <el-radio-button value="unmatched">只看红色 ({{ unmatchedCount }})</el-radio-button>
            </el-radio-group>
            <el-checkbox v-model="rememberBindings" size="small">记住手动绑定</el-checkbox>
          </div>
        </div>

        <!-- 可选：同系列共用库存快捷设置（默认折叠，不打散主表格） -->
        <div v-if="data.groups.length" class="shared-banner">
          <span>检测到 {{ data.groups.length }} 组同系列口味商品，可直接在下方红色行逐行绑定，或点右侧一键合并共用库存</span>
          <el-button link type="primary" size="small" @click="showSharedPanel = !showSharedPanel">
            {{ showSharedPanel ? '收起共用库存设置' : '设置共用库存（可选）' }}
          </el-button>
        </div>
        <div v-if="showSharedPanel && data.groups.length" class="shared-panel">
          <div v-for="group in data.groups" :key="group.key" class="stock-group" :class="{ confirmed: choices[group.key].confirmed }">
            <div class="group-heading">
              <div>
                <b>{{ group.name }}</b>
                <small>{{ group.sourceRows.length }} 行采购 · 统一使用 {{ codeFor(group) }}</small>
              </div>
              <el-button v-if="choices[group.key].confirmed" link @click="choices[group.key].confirmed = false">已确认 · 修改</el-button>
            </div>
            <template v-if="!choices[group.key].confirmed">
              <el-checkbox-group v-model="choices[group.key].memberIds" :disabled="busy" @change="changeGroup(group)">
                <div v-for="member in group.members" :key="member.productId" class="flavor">
                  <el-checkbox :value="member.productId">{{ member.productName }}</el-checkbox>
                  <span class="muted">已售 {{ Number(member.salesQty) }} {{ member.unit }} · {{ member.skuCode }}</span>
                </div>
              </el-checkbox-group>
              <div class="group-footer">
                <span class="muted">勾选的口味共用总库存，原销售名称保留；未勾选的保留独立库存。</span>
                <el-button type="primary" plain size="small" :disabled="!choices[group.key].memberIds.length || busy" @click="choices[group.key].confirmed = true">
                  确认共用库存
                </el-button>
              </div>
            </template>
          </div>
        </div>

        <!-- 主视图：按送货单 / 日期分块的整洁表格（直接对照截图样式） -->
        <div v-if="preview.sheetTitle" class="sheet-main-title">{{ preview.sheetTitle }}</div>

        <div v-for="sec in displaySections" :key="sec.key" class="delivery-block">
          <div class="delivery-banner">
            <span class="delivery-title">{{ sec.title }}</span>
            <span class="delivery-badges">
              <span class="mini-pill mini-green">绿 {{ sec.matchedCount }}</span>
              <span v-if="sec.unmatchedCount > 0" class="mini-pill mini-red">红 {{ sec.unmatchedCount }}</span>
            </span>
          </div>

          <div class="sheet-scroll">
            <table class="delivery-sheet">
              <thead>
                <tr>
                  <th class="col-name">产品名称</th>
                  <th class="col-num">整件规格</th>
                  <th class="col-num">总件数</th>
                  <th class="col-num">采购数量</th>
                  <th class="col-num">整件价格（元）</th>
                  <th class="col-num">总价格（元）</th>
                  <th class="col-match">系统商品匹配 / 手动绑定</th>
                </tr>
              </thead>
              <tbody>
                <tr v-if="!sec.rows.length">
                  <td colspan="7" class="empty-filter-row">本单所有商品均已匹配为绿色 ✓</td>
                </tr>
                <tr
                  v-for="row in sec.rows"
                  :key="row.rowNo"
                  :class="isRowGreen(row) ? 'row-matched' : 'row-unmatched'"
                >
                  <td class="cell-name">
                    <div class="source-name-main">{{ row.sourceName || row.skuCode }}</div>
                    <div v-if="row.skuCode" class="source-sub">表内编码：{{ row.skuCode }}</div>
                  </td>
                  <td class="cell-num">{{ fmtNum(row.boxSpec) }}</td>
                  <td class="cell-num">{{ fmtNum(row.boxCount) }}</td>
                  <td class="cell-num cell-strong">{{ fmtNum(row.qty) }}</td>
                  <td class="cell-num">{{ fmtMoney(row.boxPrice) }}</td>
                  <td class="cell-num cell-strong">{{ fmtMoney(row.totalPrice) }}</td>
                  <td class="cell-match">
                    <div v-if="dataErrorsOf(row).length" class="row-error-text">
                      {{ dataErrorsOf(row).join('；') }}
                    </div>
                    <!-- 绿色：已自动匹配 / 已手动绑定 / 共用库存 -->
                    <template v-else-if="isRowGreen(row) && !editingRows[row.rowNo]">
                      <div class="match-ok-line">
                        <span class="status-tag tag-green">
                          {{ rowBindingKind(row) === 'manual' ? '已绑定' : rowBindingKind(row) === 'shared' ? '共用库存' : '自动匹配' }}
                        </span>
                        <span class="matched-product-text" :title="productLabel(row)">{{ productLabel(row) }}</span>
                        <el-button link type="primary" size="small" :disabled="busy" @click="editingRows[row.rowNo] = true">修改</el-button>
                        <el-button
                          v-if="rowBindingKind(row) === 'manual' && sameNameUnmatchedCount(row) > 0"
                          link
                          type="success"
                          size="small"
                          :disabled="busy"
                          @click="applyToSameName(row)"
                        >
                          同步其余 {{ sameNameUnmatchedCount(row) }} 行同名
                        </el-button>
                      </div>
                    </template>
                    <!-- 红色未匹配 或 正在修改绑定 -->
                    <template v-else>
                      <div class="match-edit-box">
                        <div class="match-edit-top">
                          <span class="status-tag" :class="isRowGreen(row) ? 'tag-green' : 'tag-red'">
                            {{ isRowGreen(row) ? '修改绑定' : '未匹配' }}
                          </span>
                          <div class="bind-select-wrap">
                            <ProductSelect
                              :model-value="rowChoices[row.rowNo] ?? effectiveProductId(row)"
                              lazy
                              placeholder="搜索系统商品名/编码进行手动绑定"
                              @update:model-value="(id: number | null) => bindRow(row, id)"
                            />
                          </div>
                          <el-button v-if="editingRows[row.rowNo]" link size="small" @click="editingRows[row.rowNo] = false">完成</el-button>
                          <el-button v-if="rowChoices[row.rowNo] && isAutoMatched(row)" link size="small" @click="resetRowBinding(row)">恢复自动</el-button>
                        </div>
                        <div v-if="row.candidates?.length" class="candidate-chips">
                          <span class="chip-hint">候选：</span>
                          <button
                            v-for="cand in row.candidates.slice(0, 4)"
                            :key="cand.productId"
                            type="button"
                            class="cand-chip"
                            :class="{ active: (rowChoices[row.rowNo] ?? effectiveProductId(row)) === cand.productId }"
                            :disabled="busy"
                            @click="bindRow(row, cand.productId, { skuCode: cand.skuCode, productName: cand.productName })"
                          >
                            <span v-if="cand.productId === row.suggestedProductId" class="cand-rec">推荐</span>
                            {{ cand.productName }}
                            <small>{{ cand.skuCode }}</small>
                          </button>
                        </div>
                      </div>
                    </template>
                  </td>
                </tr>
              </tbody>
              <tfoot>
                <tr class="sheet-total-row">
                  <td class="cell-name">合计</td>
                  <td class="cell-num" />
                  <td class="cell-num">{{ sec.totalBoxCount != null ? fmtNum(sec.totalBoxCount) : '' }}</td>
                  <td class="cell-num">{{ fmtNum(sec.totalQty) }}</td>
                  <td class="cell-num" />
                  <td class="cell-num">{{ fmtMoney(sec.totalAmount) }}</td>
                  <td class="cell-match total-match-note">
                    已匹配 {{ sec.matchedCount }} / {{ sec.matchedCount + sec.unmatchedCount }} 行
                  </td>
                </tr>
              </tfoot>
            </table>
          </div>

          <div v-if="sec.note" class="delivery-note">{{ sec.note }}</div>
        </div>
      </template>

      <el-empty v-else description="上传采购/送货单表格，按原表样式展示；绿色自动匹配，红色可直接手动绑定" :image-size="60" />
    </div>

    <template #footer>
      <span v-if="data && !done" class="footer-hint" :class="{ 'hint-red': unmatchedCount > 0 || hasBlockingRowErrors }">
        <template v-if="preview?.errors.length || hasBlockingRowErrors">原表存在数量或金额核对错误，请先核查红色提示</template>
        <template v-else-if="unmatchedCount > 0">还有 {{ unmatchedCount }} 行红色未匹配，请在表格右侧手动绑定后入库</template>
        <template v-else-if="!supplierId">全部已匹配为绿色 ✓，请选择供应商后确认入库</template>
        <template v-else-if="needsDefaultDate && !defaultDate">请补充采购日期后确认入库</template>
        <template v-else>全部 {{ allRows.length }} 行已匹配为绿色 ✓，确认后按送货/采购日期直接入库</template>
      </span>
      <el-button :disabled="busy" @click="close">关闭</el-button>
      <el-button v-if="!done" type="primary" :disabled="!canCommit" :loading="busy" @click="submit">
        确认并全部入库{{ preview ? `（${receiptCount} 次采购 · ¥${totalAmount.toFixed(2)}）` : '' }}
      </el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
.toolbar {
  display: flex;
  gap: 10px;
  align-items: center;
  flex-wrap: wrap;
  margin-bottom: 12px;
}
.toolbar .el-button { margin-left: 0; }
.advanced-link { margin-left: auto !important; }
.muted { color: #6e7d75; font-size: 12px; line-height: 1.6; overflow-wrap: anywhere; }

.control-bar {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 12px 16px;
  padding: 10px 14px;
  background: #f5f7fa;
  border: 1px solid #dcdfe6;
  border-radius: 6px;
  margin-bottom: 12px;
}
.control-field { display: flex; align-items: center; gap: 8px; font-size: 13px; }
.legend-stats { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; }
.control-right { margin-left: auto; display: flex; align-items: center; gap: 12px; flex-wrap: wrap; }

.pill {
  display: inline-flex;
  align-items: center;
  padding: 3px 10px;
  border-radius: 999px;
  font-size: 12px;
  font-weight: 600;
}
.pill-green { background: #e3f7ea; color: #16794b; border: 1px solid #a9e2be; }
.pill-red { background: #ffeaea; color: #cf1322; border: 1px solid #ffa39e; }
.pill-neutral { background: #eef1f5; color: #606266; border: 1px solid #dcdfe6; }
.pill-summary { background: #eef4fc; color: #2b579a; border: 1px solid #c5daf6; }

.shared-banner {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  padding: 6px 12px;
  background: #f4f8ff;
  border: 1px dashed #b9d1f4;
  border-radius: 5px;
  font-size: 12px;
  color: #35547a;
  margin-bottom: 10px;
}
.shared-panel { margin-bottom: 12px; }
.stock-group { border: 1px solid #dce5df; border-radius: 7px; padding: 12px 14px; margin: 8px 0; background: #fff; }
.stock-group.confirmed { background: #f1f7f3; }
.group-heading { display: flex; align-items: center; justify-content: space-between; gap: 8px; }
.group-heading small { display: block; color: #76847d; margin-top: 4px; }
.flavor { display: flex; justify-content: space-between; gap: 12px; align-items: center; border-top: 1px solid #edf0ed; padding: 5px 0; }
.group-footer { display: flex; justify-content: space-between; align-items: center; gap: 10px; margin-top: 8px; flex-wrap: wrap; }

.sheet-main-title {
  text-align: center;
  font-size: 18px;
  font-weight: 700;
  color: #1f2937;
  margin: 6px 0 10px;
  letter-spacing: 0.5px;
}

.delivery-block {
  margin-bottom: 18px;
  border: 1px solid #c9d4e3;
  border-radius: 6px;
  overflow: hidden;
  background: #fff;
}
.delivery-banner {
  display: flex;
  align-items: center;
  justify-content: center;
  position: relative;
  padding: 8px 14px;
  background: #f8fafc;
  border-bottom: 1px solid #cfd9e6;
}
.delivery-title {
  font-size: 15px;
  font-weight: 700;
  color: #111827;
}
.delivery-badges {
  position: absolute;
  right: 12px;
  display: flex;
  gap: 6px;
}
.mini-pill {
  font-size: 11px;
  font-weight: 600;
  padding: 2px 8px;
  border-radius: 999px;
}
.mini-green { background: #dff5e7; color: #16794b; }
.mini-red { background: #ffe4e4; color: #cf1322; }

.sheet-scroll { overflow-x: auto; }
.delivery-sheet {
  width: 100%;
  border-collapse: collapse;
  font-size: 13px;
  font-variant-numeric: tabular-nums;
}
.delivery-sheet thead th {
  background: #4472c4;
  color: #fff;
  font-weight: 700;
  padding: 8px 10px;
  border: 1px solid #355da3;
  text-align: center;
  white-space: nowrap;
}
.delivery-sheet td {
  padding: 7px 10px;
  border: 1px solid #d5dce6;
  vertical-align: middle;
}
.col-name { min-width: 165px; }
.col-num { width: 88px; }
.col-match { min-width: 340px; }

.cell-name { text-align: center; }
.source-name-main { font-weight: 600; color: #1f2937; }
.source-sub { font-size: 11px; color: #6b7280; margin-top: 2px; }
.cell-num { text-align: center; color: #1f2937; }
.cell-strong { font-weight: 600; }
.cell-match { text-align: left; }

/* 绿色：系统自动匹配或已完成手动绑定 */
tr.row-matched {
  background: #eefaf2;
}
tr.row-matched td:first-child {
  box-shadow: inset 4px 0 0 #269c57;
}

/* 红色：系统无法自动匹配，需手动绑定 */
tr.row-unmatched {
  background: #fff1f0;
}
tr.row-unmatched td:first-child {
  box-shadow: inset 4px 0 0 #dc2626;
}
tr.row-unmatched .source-name-main {
  color: #b42318;
}

/* 合计行：与截图一致的淡黄色高亮 */
tr.sheet-total-row {
  background: #fdf2cc;
  font-weight: 700;
}
tr.sheet-total-row td {
  border-top: 2px solid #d9c27a;
  color: #111827;
}
.total-match-note {
  font-size: 12px;
  font-weight: 600;
  color: #5c4b14;
}

.delivery-note {
  padding: 6px 12px;
  font-size: 12px;
  font-style: italic;
  color: #6b7280;
  background: #fcfcfd;
  border-top: 1px solid #e5e7eb;
}

.match-ok-line {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}
.matched-product-text {
  color: #14532d;
  font-weight: 600;
  font-size: 12.5px;
  flex: 1;
  min-width: 140px;
}

.status-tag {
  display: inline-block;
  padding: 2px 7px;
  border-radius: 4px;
  font-size: 11px;
  font-weight: 700;
  white-space: nowrap;
}
.tag-green { background: #1f9d55; color: #fff; }
.tag-red { background: #dc2626; color: #fff; }

.match-edit-box {
  display: flex;
  flex-direction: column;
  gap: 6px;
}
.match-edit-top {
  display: flex;
  align-items: center;
  gap: 8px;
}
.bind-select-wrap {
  flex: 1;
  min-width: 200px;
}
.candidate-chips {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 5px;
}
.chip-hint { font-size: 11px; color: #7f1d1d; }
.cand-chip {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  padding: 2px 8px;
  border-radius: 4px;
  border: 1px solid #fca5a5;
  background: #fff;
  color: #7f1d1d;
  font-size: 11.5px;
  cursor: pointer;
  line-height: 1.4;
}
.cand-chip:hover { background: #fee2e2; border-color: #dc2626; }
.cand-chip.active { background: #dcfce7; border-color: #16a34a; color: #14532d; }
.cand-chip small { color: #6b7280; font-size: 10.5px; }
.cand-rec { background: #dc2626; color: #fff; font-size: 10px; padding: 0 4px; border-radius: 3px; font-weight: 600; }

.row-error-text {
  color: #cf1322;
  font-size: 12px;
  font-weight: 600;
}
.empty-filter-row {
  text-align: center;
  color: #16794b;
  padding: 14px !important;
  background: #f3fbf6;
}

.footer-hint { flex: 1; color: #16794b; font-size: 12.5px; text-align: left; font-weight: 600; }
.footer-hint.hint-red { color: #cf1322; }

:global(.simple-purchase-dialog) { display: flex; flex-direction: column; max-height: 94vh; margin-bottom: 0; }
:global(.simple-purchase-dialog .el-dialog__body) { overflow-y: auto; min-height: 0; flex: 1; padding-top: 12px; }
:global(.simple-purchase-dialog .el-dialog__footer) { flex-shrink: 0; display: flex; align-items: center; gap: 10px; flex-wrap: wrap; }
:global(.simple-purchase-dialog .el-dialog__footer .el-button) { margin-left: 0; }

@media (max-width: 768px) {
  .control-right { margin-left: 0; }
  .delivery-banner { justify-content: flex-start; padding-right: 90px; }
  .footer-hint { flex-basis: 100%; }
}
</style>
