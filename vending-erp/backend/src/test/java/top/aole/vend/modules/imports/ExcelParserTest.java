package top.aole.vend.modules.imports;

import org.apache.poi.hssf.usermodel.HSSFWorkbook;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import top.aole.vend.common.exception.BizException;
import top.aole.vend.modules.imports.parser.ExcelParser;
import top.aole.vend.modules.imports.parser.ParsedSheet;
import top.aole.vend.modules.imports.parser.RawSheet;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.time.LocalDateTime;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

/** Real BIFF8 and OOXML workbooks: sales export format detection must not alter row values. */
class ExcelParserTest {
    private final ExcelParser parser = new ExcelParser();

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void readsSalesRowsWithoutChangingIdentifiersAmountsOrDates(boolean xlsx) throws Exception {
        byte[] content;
        try (Workbook book = workbook(xlsx); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = book.createSheet("出货明细_1");
            sheet.createRow(0); // The first nonempty row remains the header.
            Row header = sheet.createRow(1);
            String[] columns = {" 订单号 ", "商品名称", "出货数量", "商品金额(元)", "设备ID", "订单类型", "出货时间", "商品条形码", "公式"};
            for (int i = 0; i < columns.length; i++) header.createCell(i).setCellValue(columns[i]);
            Row row = sheet.createRow(2);
            row.createCell(0).setCellValue("001234567890123456789");
            row.createCell(1).setCellValue(" 饮料 ");
            row.createCell(2).setCellValue(2);
            row.createCell(3).setCellValue(12.9);
            row.createCell(4).setCellValue("AA0582");
            row.createCell(5).setCellValue("正常订单");
            row.createCell(6).setCellValue(LocalDateTime.of(2026, 9, 22, 0, 1, 37));
            CellStyle date = book.createCellStyle();
            date.setDataFormat(book.createDataFormat().getFormat("yyyy-mm-dd hh:mm:ss"));
            row.getCell(6).setCellStyle(date);
            row.createCell(7).setCellValue(6901234567890d);
            Cell formula = row.createCell(8);
            formula.setCellFormula("C3*D3");
            book.getCreationHelper().createFormulaEvaluator().evaluateFormulaCell(formula);
            sheet.createRow(3).createCell(0).setCellValue("  ");
            // Same order on a second line must be retained; no order-level deduplication here.
            Row second = sheet.createRow(4);
            second.createCell(0).setCellValue("001234567890123456789");
            second.createCell(1).setCellValue("另一件商品");
            second.createCell(6).setCellValue("2026-09-22 23:57:20");
            book.createSheet("非导入工作表").createRow(0).createCell(0).setCellValue("ignored");
            book.write(out);
            content = out.toByteArray();
        }

        try (ByteArrayInputStream in = new ByteArrayInputStream(content)) {
            ParsedSheet result = parser.parse(in);
            assertEquals(2, result.getRows().size());
            assertTrue(result.getHeaders().contains("订单号"));
            ParsedSheet.Row first = result.getRows().get(0);
            assertEquals(3, first.getRowNo());
            assertEquals("001234567890123456789", first.get("订单号"));
            assertEquals("饮料", first.get("商品名称"));
            assertEquals("2", first.get("出货数量"));
            assertEquals("12.9", first.get("商品金额(元)"));
            assertEquals("2026-09-22 00:01:37", first.get("出货时间"));
            assertEquals("6901234567890", first.get("商品条形码"));
            assertEquals("25.8", first.get("公式"));
            assertEquals(5, result.getRows().get(1).getRowNo());
            assertEquals("001234567890123456789", result.getRows().get(1).get("订单号"));
            assertEquals("2026-09-22 23:57:20", result.getRows().get(1).get("出货时间"));
            assertNull(result.getRows().get(1).get("商品条形码"));
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void readsNamedRawSheetPreservingDuplicateColumns(boolean xlsx) throws Exception {
        byte[] content;
        try (Workbook book = workbook(xlsx); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            book.createSheet("说明").createRow(0).createCell(0).setCellValue("not selected");
            Sheet selected = book.createSheet("旧采购入库表");
            Row header = selected.createRow(1);
            header.createCell(0).setCellValue("商品编码");
            header.createCell(1).setCellValue("商品编码");
            Row row = selected.createRow(3);
            row.createCell(0).setCellValue("001");
            row.createCell(1).setCellValue("002");
            book.write(out);
            content = out.toByteArray();
        }
        RawSheet result = parser.parseRaw(new ByteArrayInputStream(content), "采购入库");
        assertEquals("旧采购入库表", result.getSheetName());
        assertEquals(2, result.getRows().size());
        assertEquals(Arrays.asList("商品编码", "商品编码"), result.getRows().get(0).getCells());
        assertEquals(Arrays.asList("001", "002"), result.getRows().get(1).getCells());
        assertEquals(4, result.getRows().get(1).getRowNo());
        BizException missing = assertThrows(BizException.class,
                () -> parser.parseRaw(new ByteArrayInputStream(content), "不存在"));
        assertTrue(missing.getMessage().contains("找不到工作表"));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void emptySheetKeepsSpecificBusinessErrors(boolean xlsx) throws Exception {
        byte[] content;
        try (Workbook book = workbook(xlsx); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            book.createSheet("出货明细");
            book.write(out);
            content = out.toByteArray();
        }
        assertTrue(assertThrows(BizException.class,
                () -> parser.parse(new ByteArrayInputStream(content))).getMessage().contains("找不到表头行"));
        assertTrue(assertThrows(BizException.class,
                () -> parser.parseRaw(new ByteArrayInputStream(content), "出货明细")).getMessage().contains("内容为空"));
    }

    @Test
    void emptyNonExcelAndTruncatedOleFilesHaveControlledErrors() {
        byte[][] invalid = {new byte[0], "not excel".getBytes(java.nio.charset.StandardCharsets.UTF_8),
                {(byte) 0xd0, (byte) 0xcf, 0x11, (byte) 0xe0, (byte) 0xa1, (byte) 0xb1, 0x1a, (byte) 0xe1}};
        for (byte[] bytes : invalid) {
            assertFormatError(assertThrows(BizException.class, () -> parser.parse(new ByteArrayInputStream(bytes))));
            assertFormatError(assertThrows(BizException.class, () -> parser.parseRaw(new ByteArrayInputStream(bytes), "出货")));
        }
    }

    private static void assertFormatError(BizException error) {
        assertTrue(error.getMessage().contains(".xls 或 .xlsx"));
        assertFalse(error.getMessage().contains("org.apache"));
    }

    private static Workbook workbook(boolean xlsx) {
        return xlsx ? new XSSFWorkbook() : new HSSFWorkbook();
    }
}
