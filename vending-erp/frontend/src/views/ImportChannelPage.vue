<script setup lang="ts">
import { computed } from 'vue'
import { useRoute } from 'vue-router'
import Imports from './Imports.vue'

const route = useRoute()
const channel = computed(() => String(route.meta.importChannel))
</script>

<template>
  <!-- 每个通道有独立状态，切换时不会沿用上一通道的文件或预览。 -->
  <Imports :key="channel" :channel="channel" />
</template>
