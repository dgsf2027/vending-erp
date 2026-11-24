package top.aole.vend.modules.imports.service;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import top.aole.vend.common.exception.BizException;
import top.aole.vend.modules.basedata.application.OpLogService;
import top.aole.vend.modules.basedata.domain.entity.AliasPending;
import top.aole.vend.modules.basedata.infrastructure.mapper.AliasPendingMapper;
import top.aole.vend.modules.imports.domain.entity.ImportBatch;
import top.aole.vend.modules.imports.domain.entity.ImportError;
import top.aole.vend.modules.imports.dto.ImportDtos.ClearSalesPreviewResp;
import top.aole.vend.modules.imports.dto.ImportDtos.ClearSalesReq;
import top.aole.vend.modules.imports.dto.ImportDtos.ClearSalesResp;
import top.aole.vend.modules.imports.mapper.ImportBatchMapper;
import top.aole.vend.modules.imports.mapper.ImportErrorMapper;
import top.aole.vend.modules.stock.domain.entity.SaleRecord;
import top.aole.vend.modules.stock.mapper.SaleRecordMapper;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 清空已导入销售数据(用于重新整理导入销售记录):
 * <ul>
 *   <li>物理删除 yc_vend_sale_record 中所有导入/非线下补录销售记录(避免 uk_sale_order 唯一约束挡住重导);</li>
 *   <li>将「出货明细 / 期初-历史销售」已导入批次统一标为「已回滚」,关联待处理错误标「已忽略」,待绑定别名标「忽略」;</li>
 *   <li>完全保留商品档案、商品别名、采购入库、补货单、库存流水、盘点单、机器/供应商/资金账户及手工「线下补录」记录;</li>
 *   <li>操作需二次口令确认并写入 op_log 留痕。</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
public class SalesDataClearService {

    public static final String CONFIRM_PHRASE = "确认清空销售数据";

    private static final DateTimeFormatter DAY_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final List<String> SALE_BATCH_TYPES = Arrays.asList(
            ImportBatch.TYPE_SALE, ImportBatch.TYPE_INITIAL_SALE);
    private static final List<String> PENDING_ALIAS_STATUSES = Arrays.asList("待绑定", "待处理");

    private final SaleRecordMapper saleRecordMapper;
    private final ImportBatchMapper batchMapper;
    private final ImportErrorMapper errorMapper;
    private final AliasPendingMapper aliasPendingMapper;
    private final OpLogService opLogService;

    public ClearSalesPreviewResp preview() {
        List<SaleRecord> importedSales = saleRecordMapper.selectList(importedSalesWrapper());
        ClearSalesPreviewResp resp = new ClearSalesPreviewResp();
        resp.setSaleCount(importedSales.size());

        BigDecimal totalAmt = BigDecimal.ZERO;
        LocalDate minDate = null;
        LocalDate maxDate = null;
        long settledCount = 0;
        for (SaleRecord s : importedSales) {
            if (s.getAmountReceived() != null && !"测试".equals(s.getOrderType())) {
                totalAmt = totalAmt.add(s.getAmountReceived());
            }
            if (s.getBizTime() != null) {
                LocalDate d = s.getBizTime().toLocalDate();
                if (minDate == null || d.isBefore(minDate)) {
                    minDate = d;
                }
                if (maxDate == null || d.isAfter(maxDate)) {
                    maxDate = d;
                }
            }
            if (s.getSettlementId() != null) {
                settledCount++;
            }
        }
        resp.setTotalAmount(totalAmt.setScale(2, RoundingMode.HALF_UP));
        resp.setFirstBizDate(minDate == null ? null : minDate.format(DAY_FMT));
        resp.setLastBizDate(maxDate == null ? null : maxDate.format(DAY_FMT));
        resp.setSettledCount(settledCount);

        List<ImportBatch> activeBatches = activeSaleBatches();
        resp.setBatchCount(activeBatches.size());

        List<Long> batchIds = activeBatches.stream().map(ImportBatch::getId).collect(Collectors.toList());
        if (!batchIds.isEmpty()) {
            Long pendingAliases = aliasPendingMapper.selectCount(new LambdaQueryWrapper<AliasPending>()
                    .in(AliasPending::getFirstBatchId, batchIds)
                    .in(AliasPending::getPendingStatus, PENDING_ALIAS_STATUSES));
            resp.setPendingAliasCount(pendingAliases == null ? 0 : pendingAliases);
        }

        Long offlineCount = saleRecordMapper.selectCount(offlineSalesWrapper());
        resp.setOfflineKeptCount(offlineCount == null ? 0 : offlineCount);
        return resp;
    }

    @Transactional(rollbackFor = Exception.class)
    public ClearSalesResp clear(ClearSalesReq req, String operator) {
        if (req == null || !CONFIRM_PHRASE.equals(StrUtil.trim(req.getConfirmText()))) {
            throw new BizException("确认口令不正确，请输入「" + CONFIRM_PHRASE + "」");
        }

        Long settled = saleRecordMapper.selectCount(importedSalesWrapper().isNotNull(SaleRecord::getSettlementId));
        long settledCount = settled == null ? 0 : settled;
        if (settledCount > 0 && !req.isUnlinkSettlement()) {
            throw new BizException("有 " + settledCount
                    + " 条导入销售已被平台结算单核销，请先撤销对应结算单，或勾选「同时解除平台结算单关联」后再清空");
        }

        List<ImportBatch> activeBatches = activeSaleBatches();
        List<Long> batchIds = activeBatches.stream().map(ImportBatch::getId).collect(Collectors.toList());

        // 1. 物理删除所有导入销售记录(含历史导入,排除手工线下补录),确保 uk_sale_order 不挡重导
        int saleRemoved = saleRecordMapper.delete(importedSalesWrapper());

        // 2. 销售导入批次统一标为「已回滚」,关联错误标「已忽略」,待绑别名标「忽略」
        int batchesRolledBack = 0;
        int pendingAliasCleared = 0;
        int errorsIgnored = 0;
        if (!batchIds.isEmpty()) {
            batchesRolledBack = batchMapper.update(null, new LambdaUpdateWrapper<ImportBatch>()
                    .in(ImportBatch::getId, batchIds)
                    .set(ImportBatch::getBatchStatus, ImportBatch.STATUS_ROLLED_BACK)
                    .set(ImportBatch::getUpdateUser, ImportService.IMPORT_USER));

            pendingAliasCleared = aliasPendingMapper.update(null, new LambdaUpdateWrapper<AliasPending>()
                    .in(AliasPending::getFirstBatchId, batchIds)
                    .in(AliasPending::getPendingStatus, PENDING_ALIAS_STATUSES)
                    .set(AliasPending::getPendingStatus, "忽略"));

            errorsIgnored = errorMapper.update(null, new LambdaUpdateWrapper<ImportError>()
                    .in(ImportError::getBatchId, batchIds)
                    .eq(ImportError::getResolveStatus, "待处理")
                    .set(ImportError::getResolveStatus, "已忽略"));
        }

        Long offlineKept = saleRecordMapper.selectCount(offlineSalesWrapper());

        ClearSalesResp resp = new ClearSalesResp();
        resp.setSaleRemoved(saleRemoved);
        resp.setBatchesRolledBack(batchesRolledBack);
        resp.setPendingAliasCleared(pendingAliasCleared);
        resp.setImportErrorsIgnored(errorsIgnored);
        resp.setOfflineKept(offlineKept == null ? 0 : offlineKept);

        opLogService.record(operator, "清空导入销售数据", "sale_record", 0L, null, resp);
        return resp;
    }

    private List<ImportBatch> activeSaleBatches() {
        return batchMapper.selectList(new LambdaQueryWrapper<ImportBatch>()
                .in(ImportBatch::getFileType, SALE_BATCH_TYPES)
                .eq(ImportBatch::getBatchStatus, ImportBatch.STATUS_IMPORTED));
    }

    /** 导入销售记录筛选口径:排除手工「线下补录」(offline_flag=1 或 order_type='线下补录') */
    private static LambdaQueryWrapper<SaleRecord> importedSalesWrapper() {
        return new LambdaQueryWrapper<SaleRecord>()
                .and(w -> w.eq(SaleRecord::getOfflineFlag, false).or().isNull(SaleRecord::getOfflineFlag))
                .and(w -> w.ne(SaleRecord::getOrderType, "线下补录").or().isNull(SaleRecord::getOrderType));
    }

    private static LambdaQueryWrapper<SaleRecord> offlineSalesWrapper() {
        return new LambdaQueryWrapper<SaleRecord>()
                .eq(SaleRecord::getOfflineFlag, true)
                .or()
                .eq(SaleRecord::getOrderType, "线下补录");
    }
}
