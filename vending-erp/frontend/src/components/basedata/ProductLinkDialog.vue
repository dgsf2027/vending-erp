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
const loading = ref(false), saving = ref(false)
const error = ref(''), candidateError = ref('')
let version = 0, candidateVersion = 0
async function loadCandidates() {
  const current = ++candidateVersion, id = props.product?.id
  candidates.value = []; candidateError.value = ''
  if (!visible.value || !id) return
  try {
    const rows = await productLinkCandidates(id)
    if (current === candidateVersion) {
      candidates.value = rows
      if (rows.length === 1 && !targetId.value) choose(rows[0])
    }
  }
  catch { if (current === candidateVersion) candidateError.value = '相似商品读取失败，仍可按名称或编码搜索' }
}
watch([visible, () => props.product?.id], () => {
  ++version; targetId.value = null; selected.value = null; preview.value = null
  error.value = ''; loading.value = false
  loadCandidates()
})
async function review() {
  const current = ++version, sourceId = props.product?.id, target = targetId.value
  preview.value = null; error.value = ''; loading.value = false
  if (!visible.value || !sourceId || !target) return
  if (sourceId === target) { error.value = '请选择另一个重复商品，不能关联自身'; return }
  loading.value = true
  try { const data = await previewProductLink(sourceId, target); if (current === version) preview.value = data }
  catch (e) { if (current === version) error.value = e instanceof Error ? e.message : '读取关联影响失败，请重试' }
  finally { if (current === version) loading.value = false }
}
watch(targetId, review)
function choose(product: Product) { selected.value = product; targetId.value = product.id! }
async function confirm() {
  const p = preview.value
  if (saving.value || loading.value || !p?.executable || p.requestedSourceId !== props.product?.id || p.requestedTargetId !== targetId.value) return
  saving.value = true; error.value = ''
  try {
    await linkExistingProduct(p.requestedSourceId, p.requestedTargetId, p.token)
    ElMessage.success(`已同步关联，统一使用 ${p.target.skuCode}`)
    visible.value = false; emit('linked')
  } catch (e) { error.value = e instanceof Error ? e.message : '关联未完成，请重新核对'; preview.value = null }
  finally { saving.value = false }
}
</script>

<template>
  <el-dialog v-model="visible" title="相同商品 · 统一编码" width="min(740px, 94vw)" :close-on-click-modal="false" :close-on-press-escape="!saving" :show-close="!saving">
    <div class="source"><span class="mini">当前商品</span><b>{{ product?.skuCode }} · {{ product?.productName }}</b></div>
    <p>选择另一个相同商品，系统自动保留原商品编码。无论从哪一行进入，都可以直接确认统一。</p>
    <p v-if="candidateError" class="mini">{{ candidateError }}</p>
    <div v-if="candidates.length" class="candidates">
      <span class="mini">可能是同一商品</span>
      <el-button v-for="item in candidates" :key="item.id" :type="targetId === item.id ? 'primary' : 'default'" plain :disabled="saving" @click="choose(item)">{{ item.skuCode }} · {{ item.productName }}</el-button>
    </div>
    <label class="target-label">需要一起统一编码的商品</label>
    <div :inert="saving || undefined"><ProductSelect v-if="visible" v-model="targetId" :initial-product="selected" lazy placeholder="搜索另一个商品名称、编码或条码" /></div>
    <div v-loading="loading" class="impact">
      <el-alert v-if="error" :title="error" type="error" :closable="false" />
      <el-button v-if="error && targetId" :disabled="saving" style="margin-top: 8px" @click="review">重新核对</el-button>
      <template v-if="preview">
        <el-alert v-if="!preview.executable" type="warning" title="这两份档案仍需核对" :closable="false" show-icon>
          <p v-for="reason in preview.blockers" :key="reason">{{ reason }}</p>
        </el-alert>
        <template v-else>
          <div class="summary"><strong>统一使用 {{ preview.target.skuCode }} · {{ preview.target.productName }}</strong>
            <p>{{ preview.directionReason }}</p>
            <p><b>{{ preview.source.skuCode }} · {{ preview.source.productName }}</b> → {{ preview.target.skuCode }}</p>
            <p>同步关联 {{ preview.salesCount }} 笔销售、{{ Number(preview.salesQty) }} 件，金额 ¥{{ Number(preview.salesAmount).toFixed(2) }}。原销售名称、数量和金额保留。</p>
            <p>单位、箱规和价格沿用保留的原商品，原库存不重复增加。以后销售和采购导入这两个名称或编码，都对应同一个商品。</p>
          </div>
        </template>
      </template>
    </div>
    <template #footer><el-button :disabled="saving" @click="visible = false">取消</el-button>
      <el-button type="primary" :loading="saving" :disabled="loading || !preview?.executable" @click="confirm">确认同一商品并统一编码</el-button></template>
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
</style>
