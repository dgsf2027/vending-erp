package top.aole.vend.modules.imports;

import org.apache.poi.hssf.usermodel.HSSFWorkbook;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.util.ReflectionTestUtils;
import top.aole.vend.common.exception.BizException;
import top.aole.vend.modules.imports.domain.entity.ImportBatch;
import top.aole.vend.modules.imports.dto.ImportDtos;
import top.aole.vend.modules.imports.mapper.ImportBatchMapper;
import top.aole.vend.modules.imports.service.ImportArchiveService;

import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ImportArchiveServiceTest {
    @TempDir Path temporary;
    private Path storage;
    private ImportBatch batch;
    private ImportArchiveService archives;
    private ImportBatchMapper batches;

    @BeforeEach
    void setUp() throws Exception {
        storage = Files.createDirectory(temporary.resolve("storage"));
        batches = mock(ImportBatchMapper.class);
        archives = new ImportArchiveService(batches);
        ReflectionTestUtils.setField(archives, "storageDir", storage.toString());
        batch = new ImportBatch();
        batch.setId(7L);
        batch.setStatus(ImportBatch.HISTORY_VISIBLE);
        batch.setFileName("九月导入原表.xlsx");
        batch.setBatchNo("TEST-7");
        batch.setBatchStatus(ImportBatch.STATUS_IMPORTED);
        when(batches.selectById(7L)).thenReturn(batch);
    }

    private void save(Workbook workbook) throws Exception {
        Path path = storage.resolve("archive.xlsx");
        try (OutputStream out = Files.newOutputStream(path)) {
            workbook.write(out);
        }
        batch.setArchivePath(path.toString());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void preservesPhysicalRowsDuplicateAndBlankHeadersAcrossSheets(boolean legacyXls) throws Exception {
        try (Workbook workbook = legacyXls ? new HSSFWorkbook() : new XSSFWorkbook()) {
            Sheet first = workbook.createSheet("出货明细");
            Row headers = first.createRow(1); // 第 1 行空白也应保留。
            headers.createCell(0).setCellValue("商品");
            headers.createCell(2).setCellValue("商品");
            Row data = first.createRow(3); // 第 3 行未定义也应保留。
            data.createCell(0).setCellValue("甲");
            data.createCell(1).setCellValue("空表头下的值");
            data.createCell(2).setCellValue("乙");
            workbook.createSheet("备注").createRow(0).createCell(0).setCellValue("保留第二张表");
            save(workbook);
        }

        ImportDtos.FilePreviewResp first = archives.preview(7L, 0, 1, 2);
        assertEquals("九月导入原表.xlsx", first.getFileName());
        assertEquals(Arrays.asList("A", "B", "C"), first.getColumns());
        assertEquals(4, first.getTotal());
        assertEquals(1, first.getRows().get(0).getRowNo());
        assertEquals(Arrays.asList("", "", ""), first.getRows().get(0).getCells());
        assertEquals(Arrays.asList("商品", "", "商品"), first.getRows().get(1).getCells());
        assertEquals("备注", first.getSheets().get(1).getName());
        assertEquals(1, first.getSheets().get(1).getIndex());

        ImportDtos.FilePreviewResp secondPage = archives.preview(7L, 0, 2, 2);
        assertEquals(3, secondPage.getRows().get(0).getRowNo());
        assertEquals(Arrays.asList("甲", "空表头下的值", "乙"), secondPage.getRows().get(1).getCells());
        assertEquals("保留第二张表", archives.preview(7L, 1, 1, 50).getRows().get(0).getCells().get(0));
    }

    @Test
    void usesDisplayedNumberDatesAndCachedFormulasWithoutRecalculating() throws Exception {
        try (Workbook workbook = new XSSFWorkbook()) {
            Row row = workbook.createSheet("格式").createRow(0);
            Cell number = row.createCell(0);
            number.setCellValue(12.5);
            CellStyle amount = workbook.createCellStyle();
            amount.setDataFormat(workbook.createDataFormat().getFormat("0.00"));
            number.setCellStyle(amount);
            Cell date = row.createCell(1);
            date.setCellValue(LocalDateTime.of(2026, 9, 29, 15, 42));
            CellStyle dateFormat = workbook.createCellStyle();
            dateFormat.setDataFormat(workbook.createDataFormat().getFormat("yyyy-mm-dd hh:mm:ss"));
            date.setCellStyle(dateFormat);
            Cell formula = row.createCell(2);
            formula.setCellFormula("A1*2");
            formula.setCellValue(999); // 故意与当前公式结果不同,证明预览只读取缓存。
            row.createCell(3).setCellValue(true);
            row.createCell(4).setCellValue("  原样文本\n第二行  ");
            save(workbook);
        }

        assertEquals(Arrays.asList("12.50", "2026-09-29 15:42:00", "999", "TRUE", "  原样文本\n第二行  "),
                archives.preview(7L, 0, 1, 50).getRows().get(0).getCells());
    }

    @Test
    void boundsWideSheetsWithExplicitWarningAndPreservesOriginalArchive() throws Exception {
        try (Workbook workbook = new XSSFWorkbook()) {
            workbook.createSheet("宽表").createRow(0).createCell(149).setCellValue("末列仍在原文件中");
            save(workbook);
        }
        byte[] original = Files.readAllBytes(archives.file(7L).getPath());
        ImportDtos.FilePreviewResp preview = archives.preview(7L, 0, 1, 50);
        assertEquals(150, preview.getColumnTotal());
        assertEquals(100, preview.getColumns().size());
        assertEquals("AA", preview.getColumns().get(26));
        assertEquals(100, preview.getRows().get(0).getCells().size());
        assertTrue(preview.getWarnings().get(0).contains("前 100 列"));
        assertArrayEquals(original, Files.readAllBytes(archives.file(7L).getPath()));
    }

    @Test
    void handlesEmptySheetMissingSheetInvalidBoundsAndHugePage() throws Exception {
        try (Workbook workbook = new XSSFWorkbook()) {
            workbook.createSheet("空表");
            workbook.createSheet("有内容").createRow(0).createCell(0).setCellValue("标题");
            save(workbook);
        }
        assertEquals(0, archives.preview(7L, 0, 1, 50).getTotal());
        assertTrue(archives.preview(7L, 0, 1, 50).getRows().isEmpty());
        assertTrue(archives.preview(7L, 1, Long.MAX_VALUE, 100).getRows().isEmpty());
        assertThrows(BizException.class, () -> archives.preview(7L, 2, 1, 50));
        assertThrows(BizException.class, () -> archives.preview(7L, -1, 1, 50));
        assertThrows(BizException.class, () -> archives.preview(7L, 0, 0, 50));
        assertThrows(BizException.class, () -> archives.preview(7L, 0, 1, 101));
        assertThrows(BizException.class, () -> archives.preview(7L, 0, 1, 0));
    }

    @Test
    void rejectsMissingOrHiddenBatchButKeepsRolledBackArchiveViewable() throws Exception {
        assertThrows(BizException.class, () -> archives.file(8L));
        try (Workbook workbook = new XSSFWorkbook()) {
            workbook.createSheet("原表");
            save(workbook);
        }
        batch.setStatus(ImportBatch.HISTORY_HIDDEN);
        assertThrows(BizException.class, () -> archives.file(7L));
        assertThrows(BizException.class, () -> archives.preview(7L, 0, 1, 50));
        batch.setStatus(ImportBatch.HISTORY_VISIBLE);
        batch.setBatchStatus(ImportBatch.STATUS_ROLLED_BACK);
        assertNotNull(archives.file(7L));
        assertNotNull(archives.preview(7L, 0, 1, 50));
    }

    @Test
    void rejectsMissingOutsideAndSymlinkEscapesWithoutExposingPaths() throws Exception {
        assertUnavailable();
        batch.setArchivePath(storage.resolve("missing.xlsx").toString());
        assertUnavailable();
        Path outside = Files.write(temporary.resolve("private.xlsx"), new byte[]{1, 2, 3});
        batch.setArchivePath(outside.toString());
        assertUnavailable();
        batch.setArchivePath(storage.resolve("../private.xlsx").toString());
        assertUnavailable();
        Path fileLink = Files.createSymbolicLink(storage.resolve("linked.xlsx"), outside);
        batch.setArchivePath(fileLink.toString());
        assertUnavailable();
        Path directoryLink = Files.createSymbolicLink(storage.resolve("linked-folder"), temporary);
        batch.setArchivePath(directoryLink.resolve("private.xlsx").toString());
        assertUnavailable();
        batch.setArchivePath(storage.toString());
        assertUnavailable();
    }

    @Test
    void corruptArchiveHasFriendlyPreviewErrorAndStillAllowsOriginalDownload() throws Exception {
        batch.setArchivePath(Files.write(storage.resolve("broken.xlsx"), new byte[]{1, 2, 3}).toString());
        BizException exception = assertThrows(BizException.class, () -> archives.preview(7L, 0, 1, 50));
        assertTrue(exception.getMessage().contains("可下载原文件查看"));
        assertFalse(exception.getMessage().contains(storage.toString()));
        assertEquals(3, archives.file(7L).getSize());
    }

    private void assertUnavailable() {
        BizException exception = assertThrows(BizException.class, () -> archives.file(7L));
        assertTrue(exception.getMessage().contains("原始文件未保留或已丢失"));
        assertFalse(exception.getMessage().contains(temporary.toString()));
    }
}
