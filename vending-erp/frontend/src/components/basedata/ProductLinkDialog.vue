<script setup lang="ts">
import { ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import ProductSelect from './ProductSelect.vue'
import { productLinkCandidates, previewProductLink, linkExistingProduct, type Product, type ProductLinkPreview } from '@/api/basedata'
const props = defineProps<{ product: Product | null }>()
const visible = defineModel<boolean>('visible', { required: true })
const emit = defineEmits<{ (e: 'linked'): void }>()
const candidates = ref<Product[]>([])
const targetId = ref<number | null>(null)
const selected = ref<Product | null>(null)
const preview = ref<ProductLinkPreview | null>(null)
const loading = ref(false), saving = ref(false), acknowledged = ref(false)
const error = ref(''), candidateError = ref('')
let version = 0, candidateVersion = 0
async function loadCandidates() {
  const current = ++candidateVersion, id = props.product?.id
  candidates.value = []; candidateError.value = ''
  if (!visible.value || !id) return
  try { const rows = await productLinkCandidates(id); if (current === candidateVersion) candidates.value = rows }
  catch { if (current === candidateVersion) candidateError.value = '相似商品读取失败，仍可按名称或编码搜索' }
}
watch([visible, () => props.product?.id], () => {
  ++version; targetId.value = null; selected.value = null; preview.value = null
  acknowledged.value = false; error.value = ''; loading.value = false
  loadCandidates()
})
async function review() {
  const current = ++version, sourceId = props.product?.id, target = targetId.value
  preview.value = null; acknowledged.value = false; error.value = ''; loading.value = false
  if (!visible.value || !sourceId || !target) return
  if (sourceId === target) { error.value = '请选择需要保留的旧商品，不能关联自身'; return }
  loading.value = true
  try { const data = await previewProductLink(sourceId, target); if (current === version) preview.value = data }
  catch (e) { if (current === version) error.value = e instanceof Error ? e.message : '读取关联影响失败，请重试' }
  finally { if (current === version) loading.value = false }
}
watch(targetId, review)
function choose(product: Product) { selected.value = product; targetId.value = product.id! }
async function confirm() {
  const p = preview.value
  if (saving.value || loading.value || !p?.executable || !acknowledged.value || p.source.id !== props.product?.id || p.target.id !== targetId.value) return
  saving.value = true; error.value = ''
  try {
    await linkExistingProduct(p.source.id!, p.target.id!, p.token)
    ElMessage.success(`关联成功，以后统一使用 ${p.target.skuCode}`)
    visible.value = false; emit('linked')
  } catch (e) { error.value = e instanceof Error ? e.message : '关联未完成，请重新核对'; preview.value = null; acknowledged.value = false }
  finally { saving.value = false }
}
</script>

<template>
  <el-dialog v-model="visible" title="关联已有商品 · 别名绑定" width="min(740px, 94vw)" :close-on-click-modal="false" :close-on-press-escape="!saving" :show-close="!saving">
    <div class="source"><span class="mini">新增 / 重复商品</span><b>{{ product?.skuCode }} · {{ product?.productName }}</b></div>
    <p>选择需要保留的旧商品。关联后，新名称和原编码都会指向旧商品，以后的销售导入沿用旧编码。</p>
    <p v-if="candidateError" class="mini">{{ candidateError }}</p>
    <div v-if="candidates.length" class="candidates">
      <span class="mini">相似商品，点击选择</span>
      <el-button v-for="item in candidates" :key="item.id" :type="targetId === item.id ? 'primary' : 'default'" plain :disabled="saving" @click="choose(item)">{{ item.skuCode }} · {{ item.productName }}</el-button>
    </div>
    <label class="target-label">保留哪个商品及编码</label>
    <div :inert="saving || undefined"><ProductSelect v-if="visible" v-model="targetId" :initial-product="selected" lazy placeholder="搜索旧商品名称、编码或条码" /></div>
    <div v-loading="loading" class="impact">
      <el-alert v-if="error" :title="error" type="error" :closable="false" />
      <el-button v-if="error && targetId" :disabled="saving" style="margin-top: 8px" @click="review">重新核对</el-button>
      <template v-if="preview">
        <el-alert v-if="!preview.executable" type="warning" title="暂时不能关联" :closable="false" show-icon>
          <p v-for="reason in preview.blockers" :key="reason">{{ reason }}</p>
        </el-alert>
        <template v-else>
          <div class="summary"><strong>统一使用 {{ preview.target.skuCode }} · {{ preview.target.productName }}</strong>
            <p>已有 {{ preview.salesCount }} 笔销售、{{ Number(preview.salesQty) }} 件，销售金额 ¥{{ Number(preview.salesAmount).toFixed(2) }}，将归到这个旧商品。</p>
            <p>原销售名称、数量和金额保留。旧商品的价格、箱规及库存数量保持不变；重复档案从商品列表收起，可在旧商品的“管理别名”中查看对应关系。</p>
          </div>
          <el-checkbox v-model="acknowledged" :disabled="saving" class="confirmation">我确认是同一个商品，将新增名称和历史销售关联到上述旧编码</el-checkbox>
        </template>
      </template>
    </div>
    <template #footer><el-button :disabled="saving" @click="visible = false">取消</el-button>
      <el-button type="primary" :loading="saving" :disabled="loading || !preview?.executable || !acknowledged" @click="confirm">确认关联</el-button></template>
  </el-dialog>
</template>

<style scoped>
.source,.summary { background: #edf5f1; border-radius: 8px; padding: 12px 14px; }
.source { display: flex; flex-direction: column; gap: 6px; }
.candidates { display: flex; flex-direction: column; align-items: flex-start; gap: 8px; margin: 14px 0; }
.candidates .el-button { margin-left: 0; max-width: 100%; height: auto; white-space: normal; padding: 9px 12px; text-align: left; }
.target-label { display: block; margin: 16px 0 8px; font-weight: 600; }
.impact { margin-top: 16px; min-height: 32px; }
.summary p { margin: 10px 0 0; line-height: 1.65; }
.confirmation { height: auto; margin-top: 16px; align-items: flex-start; }
.confirmation :deep(.el-checkbox__label) { white-space: normal; line-height: 1.5; }
</style>
