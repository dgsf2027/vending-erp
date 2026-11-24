package top.aole.vend.modules.imports.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
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
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 清空已导入销售数据单元测试(纯单测,无外部 DB 依赖)。
 */
@ExtendWith(MockitoExtension.class)
class SalesDataClearServiceTest {

    @Mock
    private SaleRecordMapper saleRecordMapper;
    @Mock
    private ImportBatchMapper batchMapper;
    @Mock
    private ImportErrorMapper errorMapper;
    @Mock
    private AliasPendingMapper aliasPendingMapper;
    @Mock
    private OpLogService opLogService;

    private SalesDataClearService service;

    @BeforeAll
    static void initLambdaCache() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, ImportBatch.class);
        TableInfoHelper.initTableInfo(assistant, AliasPending.class);
        TableInfoHelper.initTableInfo(assistant, ImportError.class);
        TableInfoHelper.initTableInfo(assistant, SaleRecord.class);
    }

    @BeforeEach
    void setUp() {
        service = new SalesDataClearService(
                saleRecordMapper, batchMapper, errorMapper, aliasPendingMapper, opLogService);
    }

    @Test
    void testPreviewComputesSummary() {
        SaleRecord s1 = sale("ORD-1", "10.50", "正常", LocalDateTime.of(2026, 10, 1, 9, 0), null);
        SaleRecord s2 = sale("ORD-2", "20.00", "正常", LocalDateTime.of(2026, 11, 20, 18, 0), 99L);
        SaleRecord s3 = sale("ORD-T", "5.00", "测试", LocalDateTime.of(2026, 11, 21, 10, 0), null);
        when(saleRecordMapper.selectList(any())).thenReturn(Arrays.asList(s1, s2, s3));
        when(saleRecordMapper.selectCount(any())).thenReturn(2L);

        ImportBatch b = new ImportBatch();
        b.setId(7L);
        b.setFileType(ImportBatch.TYPE_SALE);
        b.setBatchStatus(ImportBatch.STATUS_IMPORTED);
        when(batchMapper.selectList(any())).thenReturn(Collections.singletonList(b));
        when(aliasPendingMapper.selectCount(any())).thenReturn(3L);

        ClearSalesPreviewResp resp = service.preview();
        assertEquals(3, resp.getSaleCount());
        assertEquals(0, new BigDecimal("30.50").compareTo(resp.getTotalAmount()));
        assertEquals("2026-10-01", resp.getFirstBizDate());
        assertEquals("2026-11-21", resp.getLastBizDate());
        assertEquals(1, resp.getBatchCount());
        assertEquals(1, resp.getSettledCount());
        assertEquals(3, resp.getPendingAliasCount());
        assertEquals(2, resp.getOfflineKeptCount());
    }

    @Test
    void testClearRejectsWrongConfirmPhrase() {
        ClearSalesReq req = new ClearSalesReq();
        req.setConfirmText("随便输");
        assertThrows(BizException.class, () -> service.clear(req, "老板"));
    }

    @Test
    void testClearRejectsSettledUnlessUnlinkChecked() {
        when(saleRecordMapper.selectCount(any())).thenReturn(5L);
        ClearSalesReq req = new ClearSalesReq();
        req.setConfirmText(SalesDataClearService.CONFIRM_PHRASE);
        req.setUnlinkSettlement(false);

        assertThrows(BizException.class, () -> service.clear(req, "老板"));
    }

    @Test
    void testClearDeletesImportedSalesAndRollsBackBatches() {
        when(saleRecordMapper.selectCount(any())).thenReturn(0L, 1L);
        ImportBatch b = new ImportBatch();
        b.setId(12L);
        b.setFileType(ImportBatch.TYPE_SALE);
        b.setBatchStatus(ImportBatch.STATUS_IMPORTED);
        when(batchMapper.selectList(any())).thenReturn(Collections.singletonList(b));
        when(saleRecordMapper.delete(any())).thenReturn(150);
        when(batchMapper.update(eq(null), any())).thenReturn(1);
        when(aliasPendingMapper.update(eq(null), any())).thenReturn(2);
        when(errorMapper.update(eq(null), any())).thenReturn(4);

        ClearSalesReq req = new ClearSalesReq();
        req.setConfirmText(SalesDataClearService.CONFIRM_PHRASE);
        ClearSalesResp resp = service.clear(req, "老板");

        assertEquals(150, resp.getSaleRemoved());
        assertEquals(1, resp.getBatchesRolledBack());
        assertEquals(2, resp.getPendingAliasCleared());
        assertEquals(4, resp.getImportErrorsIgnored());
        assertEquals(1L, resp.getOfflineKept());
        verify(opLogService).record(eq("老板"), eq("清空导入销售数据"), eq("sale_record"), eq(0L), eq(null), eq(resp));
    }

    private static SaleRecord sale(String orderNo, String amt, String type, LocalDateTime t, Long settlementId) {
        SaleRecord s = new SaleRecord();
        s.setOrderNo(orderNo);
        s.setAmountReceived(new BigDecimal(amt));
        s.setOrderType(type);
        s.setBizTime(t);
        s.setSettlementId(settlementId);
        return s;
    }
}
