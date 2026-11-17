<script setup lang="ts">
import ProductPanel from '@/components/basedata/ProductPanel.vue'

/**
 * 商品档案列表页(对照 mockup p3 的列表部分)。
 * 单品分析下钻页(体检报告式)属 M1-6,列表行已留「详情 ▸」跳转钩子。
 */
</script>

<template>
  <div class="ledger-page">
    <div class="ledger-crumb">园区小卖 ERP / 商品 · 单品分析</div>
    <div class="ledger-title">
      <h2>商品档案</h2>
      <span class="sub">按商品售价、拿货价和单件利润查看商品,利润从高到低一目了然</span>
    </div>
    <p class="ledger-note">
      重复建档的商品可点<b>关联已有商品</b>，确认后沿用旧编码；其他别名可在操作菜单中管理。勾选后可批量删除未使用档案。
    </p>
    <ProductPanel />
  </div>
</template>
