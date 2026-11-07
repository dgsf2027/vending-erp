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
      勾选商品后可<b>批量删除</b>未使用档案；单个商品可在操作菜单中编辑、管理别名。有业务记录的商品保留历史档案，请使用停售。
    </p>
    <ProductPanel />
  </div>
</template>
