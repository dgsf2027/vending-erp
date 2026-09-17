<script setup lang="ts">
import type { AccountBill } from '@/api/account-bills'
defineProps<{ rows: AccountBill[]; preview?: boolean }>()
const money = (v: string | number) => Number(v).toFixed(2)
</script>
<template>
  <el-table :data="rows" size="small" max-height="480">
    <el-table-column v-if="preview" prop="sourceRow" label="Excel 行" width="85" />
    <el-table-column prop="billDate" label="账单时间" width="110" fixed />
    <el-table-column label="收益金额" width="110" align="right"><template #default="{ row }">{{ money(row.incomeAmount) }}</template></el-table-column>
    <el-table-column label="支付手续费" width="110" align="right"><template #default="{ row }">{{ money(row.feeAmount) }}</template></el-table-column>
    <el-table-column prop="arrivalDate" label="到账时间" width="110"><template #default="{ row }">{{ row.arrivalDate || '未到账' }}</template></el-table-column>
    <el-table-column label="结算金额" width="110" align="right"><template #default="{ row }">{{ money(row.settlementAmount) }}</template></el-table-column>
    <el-table-column prop="settlementStatus" label="结算状态" width="100" />
    <el-table-column prop="settlementInfo" label="结算信息" min-width="130" />
    <el-table-column prop="sourceAccount" label="账号" min-width="130" />
    <el-table-column prop="ownerName" label="姓名" width="100" />
    <el-table-column prop="sourceRole" label="角色" width="100" />
    <el-table-column prop="channel" label="打款渠道" width="100" />
    <el-table-column prop="merchantNo" label="渠道商户号" min-width="190" />
    <el-table-column prop="sourceFile" label="来源文件" min-width="220" />
  </el-table>
</template>
