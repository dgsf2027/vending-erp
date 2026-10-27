<script setup lang="ts">
import { ref, watch } from 'vue'
import type { ElSelect } from 'element-plus'
import { getProduct, pageProducts, type Product } from '@/api/basedata'

/**
 * 商品远程搜索下拉:输入 名称/编码/条码 搜索,选中 emit 商品 id。
 * 多处复用:货道绑SKU / 别名绑定 / 参数覆盖行。
 */
const props = defineProps<{
  modelValue?: number | null
  placeholder?: string
  initialProduct?: Product | null
  lazy?: boolean
}>()

const emit = defineEmits<{
  (e: 'update:modelValue', value: number | null): void
}>()

const options = ref<Product[]>(props.initialProduct ? [props.initialProduct] : [])
const loading = ref(false)
const selectRef = ref<InstanceType<typeof ElSelect>>()
defineExpose({ focus: () => selectRef.value?.focus() })

async function search(keyword: string) {
  loading.value = true
  try {
    const page = await pageProducts({ current: 1, size: 20, keyword: keyword || undefined })
    const selected = options.value.find((p) => p.id === props.modelValue)
    options.value = selected && !page.records.some((p) => p.id === selected.id)
      ? [selected, ...page.records] : page.records
  } finally {
    loading.value = false
  }
}

function onChange(value: number | null) {
  emit('update:modelValue', value)
}
function onVisibleChange(visible: boolean) {
  if (visible && props.lazy) search('')
}

// 初始载入一页,便于直接下拉选
if (!props.lazy) search('')
watch(() => props.initialProduct, (product) => {
  if (product && !options.value.some(p => p.id === product.id)) options.value.unshift(product)
})
watch(() => props.modelValue, async (id) => {
  if (!id || options.value.some((p) => p.id === id)) return
  try {
    const product = await getProduct(id)
    if (props.modelValue === id && !options.value.some((p) => p.id === id)) options.value.unshift(product)
  } catch {
    // 已删除的商品由父表单核对，不阻断其他商品选择。
  }
}, { immediate: true })
</script>

<template>
  <el-select
    ref="selectRef"
    :model-value="props.modelValue"
    filterable
    remote
    clearable
    :remote-method="search"
    :loading="loading"
    :placeholder="props.placeholder || '搜商品名/编码/条码'"
    style="width: 100%"
    @update:model-value="onChange"
    @visible-change="onVisibleChange"
  >
    <el-option
      v-for="p in options"
      :key="p.id"
      :value="p.id!"
      :label="`${p.skuCode} · ${p.productName}`"
    >
      <span class="num" style="margin-right: 8px">{{ p.skuCode }}</span>
      <span>{{ p.productName }}</span>
      <span class="mini" style="margin-left: 8px">{{ p.productStatus }}</span>
    </el-option>
  </el-select>
</template>
