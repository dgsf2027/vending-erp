package top.aole.vend.modules.imports;

import cn.hutool.core.util.IdUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import top.aole.vend.common.auth.AuthTokenService;
import top.aole.vend.modules.basedata.domain.entity.OpLog;
import top.aole.vend.modules.basedata.infrastructure.mapper.OpLogMapper;
import top.aole.vend.modules.imports.domain.entity.ImportBatch;
import top.aole.vend.modules.imports.mapper.ImportBatchMapper;
import top.aole.vend.modules.imports.service.ImportService;
import top.aole.vend.modules.imports.service.InitialImportService;
import top.aole.vend.modules.stock.domain.entity.SaleRecord;
import top.aole.vend.modules.stock.mapper.SaleRecordMapper;
import top.aole.vend.modules.task.mapper.TaskQueryMapper;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.doReturn;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test-imports")
@Transactional
class ImportBatchDeleteTest {
    @Autowired MockMvc mvc;
    @Autowired AuthTokenService tokens;
    @SpyBean ImportBatchMapper batches;
    @Autowired JdbcTemplate jdbc;
    @Autowired ImportService imports;
    @Autowired InitialImportService initialImports;
    @Autowired SaleRecordMapper sales;
    @Autowired OpLogMapper logs;
    @Autowired TaskQueryMapper tasks;
    @TempDir Path archiveDir;

    private ImportBatch batch(String type, String state) {
        ImportBatch batch = new ImportBatch();
        batch.setBatchNo("TEST-" + IdUtil.fastSimpleUUID().substring(0, 20));
        batch.setFileType(type);
        batch.setFileName("测试导入.xlsx");
        batch.setBatchStatus(state);
        batches.insert(batch);
        return batches.selectById(batch.getId());
    }

    private void deleteBatch(Long id) throws Exception {
        mvc.perform(delete("/v1/imports/batches/{id}", id)
                        .header("Authorization", "Bearer " + tokens.issue(9L, "删除测试员", "老板")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(200));
    }

    @ParameterizedTest
    @ValueSource(strings = {"出货明细", "系统补货记录", "商品列表", "期初-商品别名", "期初-历史采购", "期初-历史销售"})
    void removesOnlySelectedHistoryAndPreservesBatchMetadata(String type) throws Exception {
        ImportBatch keep = batch(type, ImportBatch.STATUS_IMPORTED);
        ImportBatch remove = batch(type, ImportBatch.STATUS_IMPORTED);
        long total = imports.pageBatches(1, 100, type).getTotal();
        int taskCount = tasks.importBatchOk(LocalDate.now());

        deleteBatch(remove.getId());

        assertEquals(total - 1, imports.pageBatches(1, 100, type).getTotal());
        assertFalse(imports.pageBatches(1, 100, type).getRecords().stream()
                .anyMatch(b -> b.getId().equals(remove.getId())));
        assertTrue(imports.pageBatches(1, 100, type).getRecords().stream()
                .anyMatch(b -> b.getId().equals(keep.getId())));
        ImportBatch stored = batches.selectById(remove.getId());
        assertNotNull(stored, "业务来源批次仍应可追溯");
        assertEquals(ImportBatch.STATUS_IMPORTED, stored.getBatchStatus());
        assertEquals(taskCount, tasks.importBatchOk(LocalDate.now()), "清理历史不改变导入任务完成状态");
    }

    @Test
    void preservesSalesArchiveAndLogsAuthenticatedOperatorOnce() throws Exception {
        ImportBatch batch = batch(ImportBatch.TYPE_SALE, ImportBatch.STATUS_IMPORTED);
        Path archive = Files.write(archiveDir.resolve("original.xlsx"), new byte[]{1, 2, 3});
        batch.setArchivePath(archive.toString());
        batches.updateById(batch);
        SaleRecord sale = new SaleRecord();
        sale.setOrderNo("DELETE-" + IdUtil.fastSimpleUUID());
        sale.setMachineId(1L);
        sale.setQty(BigDecimal.ONE);
        sale.setAmountReceived(new BigDecimal("5.00"));
        sale.setOrderType("正常订单");
        sale.setBizTime(LocalDateTime.now());
        sale.setBizPeriod("2026-09");
        sale.setBookPeriod("2026-09");
        sale.setImportBatchId(batch.getId());
        sales.insert(sale);

        deleteBatch(batch.getId());
        deleteBatch(batch.getId());

        assertNotNull(sales.selectById(sale.getId()));
        assertArrayEquals(new byte[]{1, 2, 3}, Files.readAllBytes(archive));
        assertEquals(archive.toString(), batches.selectById(batch.getId()).getArchivePath());
        java.util.List<OpLog> audit = logs.selectList(new LambdaQueryWrapper<OpLog>()
                .eq(OpLog::getTargetType, "import_batch").eq(OpLog::getTargetId, batch.getId()));
        assertEquals(1, audit.size(), "重复删除不重复记录操作");
        assertEquals("删除测试员", audit.get(0).getUserName());
        assertNotNull(audit.get(0).getBeforeJson());
        assertNotNull(audit.get(0).getAfterJson());
    }

    @Test
    void preservesInitialImportProgress() throws Exception {
        ImportBatch batch = batch(ImportBatch.TYPE_INITIAL_PRODUCT, ImportBatch.STATUS_IMPORTED);
        assertTrue(initialImports.status().getStep1().isDone());

        deleteBatch(batch.getId());

        assertTrue(initialImports.status().getStep1().isDone());
        assertEquals(batch.getBatchNo(), initialImports.status().getStep1().getBatchNo());
    }

    @Test
    void allowsDeletingRolledBackHistory() throws Exception {
        ImportBatch batch = batch(ImportBatch.TYPE_SALE, ImportBatch.STATUS_ROLLED_BACK);
        deleteBatch(batch.getId());
        assertFalse(imports.pageBatches(1, 100, null).getRecords().stream()
                .anyMatch(b -> b.getId().equals(batch.getId())));
        assertEquals(ImportBatch.STATUS_ROLLED_BACK, batches.selectById(batch.getId()).getBatchStatus());
    }

    @Test
    void rollbackWithStaleBatchSnapshotDoesNotRestoreDeletedHistory() throws Exception {
        ImportBatch beforeDelete = batch(ImportBatch.TYPE_SALE, ImportBatch.STATUS_IMPORTED);
        deleteBatch(beforeDelete.getId());
        // 模拟回滚先读到可见批次,删除随后提交,回滚最后写入的交错顺序。
        // 只替换该次读取,回滚、SQL 更新和最终断言都使用真实数据库。
        doReturn(beforeDelete).when(batches).selectById(beforeDelete.getId());

        assertTrue(imports.rollback(beforeDelete.getId(), "回滚测试员").isSuccess());

        assertEquals(0, jdbc.queryForObject("SELECT status FROM yc_vend_import_batch WHERE id=?",
                Integer.class, beforeDelete.getId()));
        assertEquals("已回滚", jdbc.queryForObject("SELECT batch_status FROM yc_vend_import_batch WHERE id=?",
                String.class, beforeDelete.getId()));
    }

    @Test
    void rejectsProcessingBatchWithoutChangingHistory() throws Exception {
        ImportBatch batch = batch(ImportBatch.TYPE_SALE, ImportBatch.STATUS_PROCESSING);
        mvc.perform(delete("/v1/imports/batches/{id}", batch.getId())
                        .header("Authorization", "Bearer " + tokens.issue(9L, "测试员", "老板")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(500));
        assertTrue(imports.pageBatches(1, 100, null).getRecords().stream()
                .anyMatch(b -> b.getId().equals(batch.getId())));
    }

    @Test
    void rejectsMissingBatch() throws Exception {
        mvc.perform(delete("/v1/imports/batches/{id}", Long.MAX_VALUE)
                        .header("Authorization", "Bearer " + tokens.issue(9L, "测试员", "老板")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(500));
    }

    @Test
    void requiresLogin() throws Exception {
        ImportBatch batch = batch(ImportBatch.TYPE_SALE, ImportBatch.STATUS_IMPORTED);
        mvc.perform(delete("/v1/imports/batches/{id}", batch.getId())).andExpect(status().isUnauthorized());
        assertTrue(imports.pageBatches(1, 100, null).getRecords().stream()
                .anyMatch(b -> b.getId().equals(batch.getId())));
    }
}
