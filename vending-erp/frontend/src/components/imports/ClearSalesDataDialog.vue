<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { importsApi, type ClearSalesPreviewResp } from '@/api/imports'

const CONFIRM_PHRASE = '确认清空销售数据'

const props = defineProps<{
  modelValue: boolean
}>()

const emit = defineEmits<{
  (e: 'update:modelValue', v: boolean): void
  (e: 'cleared'): void
}>()

const loading = ref(false)
const submitting = ref(false)
const preview = ref<ClearSalesPreviewResp | null>(null)
const confirmText = ref('')
const unlinkSettlement = ref(false)

async function loadPreview() {
  loading.value = true
  try {
    preview.value = await importsApi.clearSalesPreview()
  } finally {
    loading.value = false
  }
}

watch(
  () => props.modelValue,
  (open) => {
    if (open) {
      confirmText.value = ''
      unlinkSettlement.value = false
      loadPreview()
    }
  },
)

const hasData = computed(() => {
  const p = preview.value
  return !!p && (p.saleCount > 0 || p.batchCount > 0 || p.pendingAliasCount > 0)
})

const canSubmit = computed(() => {
  if (!hasData.value || submitting.value) return false
  if (confirmText.value.trim() !== CONFIRM_PHRASE) return false
  if ((preview.value?.settledCount ?? 0) > 0 && !unlinkSettlement.value) return false
  return true
})

const money2 = (v: number | string | null | undefined) =>
  v == null
    ? '¥0.00'
    : `¥${Number(v).toLocaleString('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 2 })}`

async function submit() {
  if (!canSubmit.value) return
  submitting.value = true
  try {
    const res = await importsApi.clearSales(confirmText.value.trim(), unlinkSettlement.value)
    ElMessage.success(
      `已清空导入销售记录 ${res.saleRemoved} 条（回滚销售批次 ${res.batchesRolledBack} 个），经营驾驶舱与报表已同步更新`,
    )
    emit('cleared')
    emit('update:modelValue', false)
  } finally {
    submitting.value = false
  }
}
</script>

<template>
  <el-dialog
    :model-value="modelValue"
    title="清空已导入销售数据"
    width="min(560px, 94vw)"
    :close-on-click-modal="false"
    @update:model-value="emit('update:modelValue', $event)"
  >
    <div v-loading="loading" class="clear-body">
      <el-alert
        type="warning"
        :closable="false"
        show-icon
        title="此操作将永久删除系统中已导入的销售记录，用于清空后重新导入销售表"
        description="商品档案、商品别名、采购入库、补货单、仓库流水、盘点、机器/供应商/资金账户及手工「线下补录」记录都会完整保留。"
      />

      <div v-if="preview" class="summary-grid">
        <div class="sum-item">
          <span class="lb">待清空销售记录</span>
          <b class="val num" style="color: var(--red)">{{ preview.saleCount.toLocaleString() }} 条</b>
        </div>
        <div class="sum-item">
          <span class="lb">对应实收合计</span>
          <b class="val num">{{ money2(preview.totalAmount) }}</b>
        </div>
        <div class="sum-item">
          <span class="lb">数据日期范围</span>
          <b class="val num">
            {{ preview.firstBizDate && preview.lastBizDate ? `${preview.firstBizDate} ~ ${preview.lastBizDate}` : '暂无' }}
          </b>
        </div>
        <div class="sum-item">
          <span class="lb">待回滚批次 / 待绑别名</span>
          <b class="val num">{{ preview.batchCount }} 批 / {{ preview.pendingAliasCount }} 条</b>
        </div>
      </div>

      <p v-if="preview && preview.offlineKeptCount > 0" class="mini keep-note">
        ℹ️ 另有 {{ preview.offlineKeptCount }} 条手工「线下补录」销售记录将自动保留，不受本次清理影响。
      </p>

      <el-alert
        v-if="preview && preview.settledCount > 0"
        type="error"
        :closable="false"
        show-icon
        :title="`检测到 ${preview.settledCount} 条导入销售已关联平台结算单`"
        class="mt-10"
      >
        <template #default>
          <el-checkbox v-model="unlinkSettlement">
            我已知晓，强制清空这些销售记录并解除与平台结算单的关联
          </el-checkbox>
        </template>
      </el-alert>

      <el-alert
        v-if="preview && !hasData"
        type="info"
        :closable="false"
        show-icon
        title="当前系统中没有可清理的导入销售数据"
        class="mt-10"
      />

      <div v-if="hasData" class="confirm-box">
        <div class="confirm-label">
          请输入 <code>{{ CONFIRM_PHRASE }}</code> 以确认清空：
        </div>
        <el-input
          v-model="confirmText"
          :placeholder="`请输入：${CONFIRM_PHRASE}`"
          clearable
          @keyup.enter="submit"
        />
      </div>
    </div>

    <template #footer>
      <el-button :disabled="submitting" @click="emit('update:modelValue', false)">取消</el-button>
      <el-button type="danger" :loading="submitting" :disabled="!canSubmit" @click="submit">
        确认清空销售数据
      </el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
.clear-body {
  display: flex;
  flex-direction: column;
  gap: 12px;
}
.summary-grid {
  display: grid;
  grid-template-columns: repeat(2, 1fr);
  gap: 10px;
}
.sum-item {
  background: #faf7ef;
  border: 1px solid var(--line);
  border-radius: 8px;
  padding: 10px 12px;
  display: flex;
  flex-direction: column;
  gap: 4px;
}
.sum-item .lb {
  font-size: 12px;
  color: var(--ink2);
}
.sum-item .val {
  font-size: 15px;
  color: var(--ink);
}
.keep-note {
  margin: 0;
  color: var(--ink2);
}
.mt-10 {
  margin-top: 2px;
}
.confirm-box {
  display: flex;
  flex-direction: column;
  gap: 6px;
  padding-top: 4px;
}
.confirm-label {
  font-size: 13px;
  color: var(--ink);
}
.confirm-label code {
  background: #fbeae7;
  color: #b43425;
  padding: 2px 6px;
  border-radius: 4px;
  font-weight: 700;
}
@media (max-width: 560px) {
  .summary-grid {
    grid-template-columns: 1fr;
  }
}
</style>
