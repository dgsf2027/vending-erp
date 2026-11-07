<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { onBeforeRouteLeave, useRouter } from 'vue-router'
import { isAxiosError } from 'axios'
import { ElMessage, ElMessageBox } from 'element-plus'
import type { TableInstance } from 'element-plus'
import {
  changeProductStatus,
  deleteProduct,
  pageAliasPending,
  pageProducts,
  type Product,
} from '@/api/basedata'
import { productProfit } from '@/utils/product-profit'
import ProductFormDialog from './ProductFormDialog.vue'
import ProductImportDialog from './SmartProductImportDialog.vue'
import AliasManageDialog from './AliasManageDialog.vue'
import AliasPendingDrawer from './AliasPendingDrawer.vue'

/** 商品档案:按五列表展示价格与参考单件利润,在分页前进行服务端排序。 */
const props = defineProps<{ compact?: boolean }>()
const router = useRouter()

const rows = ref<Product[]>([])
const total = ref(0)
const loading = ref(false)
const pendingCount = ref(0)

const query = reactive({
  current: 1,
  size: 20,
  keyword: '',
  productStatus: '' as string,
  sortBy: 'profit' as 'skuCode' | 'refPrice' | 'refCost' | 'profit',
  sortOrder: 'desc' as 'asc' | 'desc',
})

const STATUS_CHIPS = [
  { label: '全部', value: '', chip: 'c-gray' },
  { label: '在售', value: '在售', chip: 'c-green' },
  { label: '清仓中', value: '清仓中', chip: 'c-amber' },
  { label: '停售', value: '停售', chip: 'c-red' },
]

const formVisible = ref(false)
const editing = ref<Product | null>(null)
const aliasVisible = ref(false)
const aliasProduct = ref<Product | null>(null)
const pendingVisible = ref(false)
const importVisible = ref(false)
const deletingId = ref<number | null>(null)
const table = ref<TableInstance>()
const selected = ref<Product[]>([])
const bulkVisible = ref(false)
const bulkBusy = ref(false)
const bulkStarted = ref(false)
type DeleteState = '待删除' | '删除中' | '已删除' | '未删除' | '待核对' | '未执行'
const bulkRows = ref<{ id: number; skuCode: string; productName: string; state: DeleteState; reason: string }[]>([])
const deletedCount = computed(() => bulkRows.value.filter(row => row.state === '已删除').length)
const processedCount = computed(() => bulkRows.value.filter(row => !['待删除', '删除中'].includes(row.state)).length)
let loadVersion = 0

function clearSelection() {
  selected.value = []
  table.value?.clearSelection()
}

function openBulkDelete() {
  if (loading.value || deletingId.value !== null || bulkBusy.value || !selected.value.length) return
  bulkRows.value = selected.value.filter(row => row.id != null).map(row => ({
    id: row.id!, skuCode: row.skuCode, productName: row.productName, state: '待删除', reason: '',
  }))
  bulkStarted.value = false
  bulkVisible.value = true
}

async function confirmBulkDelete() {
  if (bulkBusy.value || bulkStarted.value || !bulkRows.value.length) return
  bulkBusy.value = true
  bulkStarted.value = true
  try {
    for (const row of bulkRows.value) {
      row.state = '删除中'
      try {
        await deleteProduct(row.id, true)
        row.state = '已删除'
        row.reason = '商品及相关别名已删除'
      } catch (error) {
        if (isAxiosError(error)) {
          row.state = '待核对'
          row.reason = '请求未确认，请刷新商品列表核对是否已删除。'
          for (const remaining of bulkRows.value.filter(item => item.state === '待删除')) {
            remaining.state = '未执行'
            remaining.reason = '请求异常，后续删除已停止。'
          }
          break
        }
        row.state = '未删除'
        row.reason = error instanceof Error ? error.message : '删除失败，请稍后核对。'
      }
    }
    // 每个商品独立调用现有删除接口，成功项不重试，业务引用检查与操作日志仍由服务端执行。
    try { await load() } catch { /* request 已提示刷新失败，保留本次逐项结果供核对。 */ }
  } finally {
    bulkBusy.value = false
  }
}

onBeforeRouteLeave(() => {
  if (bulkBusy.value) {
    ElMessage.warning('正在批量删除，请等待本次处理完成。')
    return false
  }
})

function changeSort({ prop, order }: { prop: string; order: string | null }) {
  query.sortBy = (order ? prop : 'profit') as typeof query.sortBy
  query.sortOrder = order === 'ascending' ? 'asc' : 'desc'
  query.current = 1
  load()
}

async function load() {
  const version = ++loadVersion
  clearSelection()
  loading.value = true
  try {
    const page = await pageProducts({
      current: query.current,
      size: query.size,
      keyword: query.keyword || undefined,
      productStatus: query.productStatus || undefined,
      sortBy: query.sortBy,
      sortOrder: query.sortOrder,
    })
    if (version !== loadVersion) return
    const lastPage = Math.max(1, Math.ceil(page.total / query.size))
    if (query.current > lastPage) {
      query.current = lastPage
      await load()
      return
    }
    rows.value = page.records
    total.value = page.total
  } finally {
    if (version === loadVersion) loading.value = false
  }
}

async function loadPendingCount() {
  const page = await pageAliasPending({ current: 1, size: 1, pendingStatus: '待绑定' })
  pendingCount.value = page.total
}

function filterStatus(value: string) {
  query.productStatus = value
  query.current = 1
  load()
}

function openCreate() {
  editing.value = null
  formVisible.value = true
}

function openEdit(row: Product) {
  editing.value = row
  formVisible.value = true
}

function openAlias(row: Product) {
  aliasProduct.value = row
  aliasVisible.value = true
}

async function flip(row: Product, target: string) {
  const tips: Record<string, string> = {
    停售: '停售 ≠ 删除:有流水的商品永不删,只是不再采购/补货/售卖,历史照查。',
    清仓中: '清仓中:停止采购、不进补货建议,仍允许上架/退货/报损,把仓库残余卖完。',
    在售: '恢复在售:重新参与采购与补货计算。',
  }
  await ElMessageBox.confirm(`${tips[target]}\n确认把「${row.productName}」置为「${target}」?`, '状态流转', {
    type: 'warning',
  })
  await changeProductStatus(row.id!, target)
  ElMessage.success(`已置为「${target}」(op_log 已留痕)`)
  load()
}

async function removeProduct(row: Product) {
  const confirmed = await ElMessageBox.confirm(
    `确认删除「${row.productName}」(${row.skuCode})？仅未被业务使用的商品可删除，相关别名一并移除。删除后无法在页面恢复，但会保留操作日志；已有业务记录或配置关联时会拒绝删除。`,
    '删除商品', { type: 'warning', confirmButtonText: '确认删除', cancelButtonText: '取消' },
  ).then(() => true, () => false)
  if (!confirmed) return
  deletingId.value = row.id!
  try {
    await deleteProduct(row.id!)
    ElMessage.success('商品已删除，操作日志已保留')
    if (rows.value.length === 1 && query.current > 1) query.current--
    await load()
  } finally {
    deletingId.value = null
  }
}

async function handleAction(command: string, row: Product) {
  if (command === 'edit') openEdit(row)
  else if (command === 'alias') openAlias(row)
  else if (command === 'detail') await router.push(`/products/${row.id}`)
  else if (command === 'delete') await removeProduct(row)
  else if (['在售', '清仓中', '停售'].includes(command)) {
    try { await flip(row, command) } catch (error) {
      if (error !== 'cancel' && error !== 'close') throw error
    }
  }
}

function statusChip(status?: string) {
  if (status === '在售') return 'c-green'
  if (status === '清仓中') return 'c-amber'
  return 'c-red'
}

onMounted(() => {
  load()
  loadPendingCount()
})

defineExpose({ reload: load })
</script>

<template>
  <div>
    <div class="ledger-card" style="display: flex; gap: 8px; align-items: center; flex-wrap: wrap">
      <span
        v-for="c in STATUS_CHIPS"
        :key="c.value"
        class="chip filter-chip"
        :class="[c.chip, { active: query.productStatus === c.value }]"
        @click="filterStatus(c.value)"
      >
        {{ c.label }}
      </span>
      <span
        class="chip filter-chip"
        style="background: #fff; border: 1.5px dashed var(--amber); color: var(--amber)"
        @click="pendingVisible = true"
      >
        ⚠️ 待绑别名 {{ pendingCount }}
      </span>
      <el-input
        v-model="query.keyword"
        placeholder="搜索商品名 / 编码 / 条码…"
        clearable
        style="margin-left: auto; width: 230px"
        @keyup.enter="query.current = 1; load()"
        @clear="query.current = 1; load()"
      />
      <el-button @click="importVisible = true">⬆ 导入商品列表</el-button>
      <el-button type="danger" plain :disabled="!selected.length || loading || deletingId !== null || bulkBusy" @click="openBulkDelete">
        批量删除{{ selected.length ? `（${selected.length}）` : '' }}
      </el-button>
      <el-button type="primary" @click="openCreate">＋ 新建商品</el-button>
    </div>

    <div class="ledger-card" style="padding: 0; overflow: hidden">
      <div class="selection-bar">
        <span>已选 <b>{{ selected.length }}</b> 个商品 · 表头勾选可全选本页</span>
        <el-button v-if="selected.length" link type="primary" @click="clearSelection">取消选择</el-button>
        <span class="mini">翻页或切换筛选后清空选择</span>
      </div>
      <el-table ref="table" row-key="id" :data="rows" v-loading="loading" :default-sort="{ prop: 'profit', order: 'descending' }" @selection-change="selected = $event" @sort-change="changeSort" @row-dblclick="openEdit">
        <el-table-column type="selection" width="48" fixed="left" :selectable="() => !bulkBusy && !loading && deletingId === null" />
        <el-table-column prop="skuCode" label="商品编号" width="180" sortable="custom" :sort-orders="['descending', 'ascending']">
          <template #default="{ row }">
            <span class="num mini" style="white-space: nowrap">{{ row.skuCode }}</span>
          </template>
        </el-table-column>
        <el-table-column label="商品名称" min-width="180">
          <template #default="{ row }">
            <b class="name-link" @click="router.push(`/products/${row.id}`)">{{ row.productName }} ↗</b>
            <span v-if="row.category" class="mini" style="display: block">{{ row.category }}</span>
            <span v-if="row.legacyCode" class="chip c-gray" style="margin-left: 6px">原码 {{ row.legacyCode }}</span>
          </template>
        </el-table-column>
        <el-table-column prop="refPrice" label="商品售价(元)" width="120" align="right" sortable="custom" :sort-orders="['descending', 'ascending']">
          <template #default="{ row }">
            <span class="num">{{ row.refPrice != null ? Number(row.refPrice).toFixed(2) : '—' }}</span>
          </template>
        </el-table-column>
        <el-table-column prop="refCost" label="拿货价(元)" width="110" align="right" sortable="custom" :sort-orders="['descending', 'ascending']">
          <template #default="{ row }">
            <span class="num">{{ row.refCost != null ? Number(row.refCost).toFixed(2) : '—' }}</span>
          </template>
        </el-table-column>
        <el-table-column prop="profit" label="利润(元/件)" width="120" align="right" sortable="custom" :sort-orders="['descending', 'ascending']">
          <template #default="{ row }">
            <span class="num" :style="{ color: (productProfit(row.refPrice, row.refCost) ?? 0) < 0 ? 'var(--red)' : 'var(--green)' }">
              {{ productProfit(row.refPrice, row.refCost)?.toFixed(2) ?? '—' }}
            </span>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="75">
          <template #default="{ row }">
            <span class="chip" :class="statusChip(row.productStatus)">{{ row.productStatus }}</span>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="100" align="center">
          <template #default="{ row }">
            <el-dropdown trigger="click" @command="(command: string) => handleAction(command, row)">
              <el-button size="small" :disabled="deletingId === row.id" :aria-label="`${row.productName}的操作`">
                {{ deletingId === row.id ? '删除中…' : '操作 ▾' }}
              </el-button>
              <template #dropdown>
                <el-dropdown-menu>
                  <el-dropdown-item command="edit">编辑商品</el-dropdown-item>
                  <el-dropdown-item command="alias">管理别名</el-dropdown-item>
                  <el-dropdown-item command="detail">查看详情</el-dropdown-item>
                  <el-dropdown-item v-if="row.productStatus === '在售'" command="清仓中" divided>转为清仓</el-dropdown-item>
                  <el-dropdown-item v-if="row.productStatus !== '停售'" command="停售" :divided="row.productStatus !== '在售'">停售商品</el-dropdown-item>
                  <el-dropdown-item v-if="row.productStatus !== '在售'" command="在售">恢复在售</el-dropdown-item>
                  <el-dropdown-item command="delete" divided class="delete-action">删除商品</el-dropdown-item>
                </el-dropdown-menu>
              </template>
            </el-dropdown>
          </template>
        </el-table-column>
      </el-table>
      <div style="display: flex; justify-content: flex-end; padding: 10px 14px">
        <el-pagination
          v-model:current-page="query.current"
          v-model:page-size="query.size"
          :total="total"
          layout="total, prev, pager, next"
          @current-change="load"
        />
      </div>
    </div>
    <el-dialog v-model="bulkVisible" :title="bulkStarted ? '批量删除结果' : '确认批量删除'" width="min(820px, 94vw)"
      :close-on-click-modal="!bulkBusy" :close-on-press-escape="!bulkBusy" :show-close="!bulkBusy">
      <el-alert v-if="!bulkStarted" type="warning" :closable="false" show-icon
        :title="`将删除以下 ${bulkRows.length} 个商品及相关别名，删除后无法在页面恢复。`"
        description="仅未使用的商品可以删除；有业务记录或配置关联的商品会保留，并显示原因。" />
      <el-alert v-else :type="bulkBusy ? 'info' : deletedCount === bulkRows.length ? 'success' : 'warning'" :closable="false" show-icon
        :title="bulkBusy ? `正在处理 ${processedCount} / ${bulkRows.length} 个商品` : `处理完成：已删除 ${deletedCount} 个，未删除或待核对 ${bulkRows.length - deletedCount} 个`" />
      <el-table :data="bulkRows" max-height="380" style="margin-top: 16px" border>
        <el-table-column prop="skuCode" label="商品编码" width="120" />
        <el-table-column prop="productName" label="商品名称" min-width="160" />
        <el-table-column v-if="bulkStarted" prop="state" label="结果" width="85" />
        <el-table-column v-if="bulkStarted" prop="reason" label="说明" min-width="220" />
      </el-table>
      <template #footer>
        <el-button :disabled="bulkBusy" @click="bulkVisible = false">{{ bulkStarted ? '完成' : '取消' }}</el-button>
        <el-button v-if="!bulkStarted || bulkBusy" type="danger" :loading="bulkBusy" :disabled="bulkStarted" @click="confirmBulkDelete">
          {{ bulkBusy ? '删除中，请稍候' : `确认删除 ${bulkRows.length} 个商品` }}
        </el-button>
      </template>
    </el-dialog>
    <p v-if="!props.compact" class="ledger-foot-note">
      利润 = 商品售价 − 拿货价,默认按利润从高到低排列;点击金额列可切换排序。这里是档案参考单件利润,实际销售毛利请查看报表。
    </p>

    <ProductFormDialog v-model:visible="formVisible" :product="editing" @saved="load" />
    <ProductImportDialog v-model:visible="importVisible" @saved="load(); loadPendingCount()" />
    <AliasManageDialog v-model:visible="aliasVisible" :product="aliasProduct" @changed="loadPendingCount" />
    <AliasPendingDrawer v-model:visible="pendingVisible" @changed="loadPendingCount(); load()" />
  </div>
</template>

<style scoped>
.selection-bar { display: flex; align-items: center; flex-wrap: wrap; gap: 12px; padding: 10px 14px; border-bottom: 1px solid var(--el-border-color-lighter); font-size: 12px; }
.delete-action { color: var(--el-color-danger); }
:deep(.el-table__header .cell) {
  white-space: nowrap;
  font-size: 12px;
}
:deep(.el-table__header .caret-wrapper) {
  width: 16px;
}
:deep(.el-table__header .sort-caret) {
  left: 3px;
}
</style>
