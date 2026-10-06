<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  changeProductStatus,
  deleteProduct,
  pageAliasPending,
  pageProducts,
  type Product,
} from '@/api/basedata'
import { productProfit } from '@/utils/product-profit'
import ProductFormDialog from './ProductFormDialog.vue'
import ProductImportDialog from './ProductImportDialog.vue'
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

function changeSort({ prop, order }: { prop: string; order: string | null }) {
  query.sortBy = (order ? prop : 'profit') as typeof query.sortBy
  query.sortOrder = order === 'ascending' ? 'asc' : 'desc'
  query.current = 1
  load()
}

async function load() {
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
    rows.value = page.records
    total.value = page.total
  } finally {
    loading.value = false
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
      <el-button type="primary" @click="openCreate">＋ 新建商品</el-button>
    </div>

    <div class="ledger-card" style="padding: 0; overflow: hidden">
      <el-table :data="rows" v-loading="loading" :default-sort="{ prop: 'profit', order: 'descending' }" @sort-change="changeSort" @row-dblclick="openEdit">
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
