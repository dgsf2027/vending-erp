package top.aole.vend.modules.purchase;

import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import top.aole.vend.common.exception.BizException;
import top.aole.vend.modules.basedata.domain.entity.Product;
import top.aole.vend.modules.basedata.infrastructure.mapper.ProductMapper;
import top.aole.vend.modules.purchase.service.PurchaseImportService;
import top.aole.vend.modules.purchase.interfaces.PurchaseImportController;
import org.springframework.mock.web.MockHttpServletResponse;

import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.util.Collections;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PurchaseImportTest {
    private ProductMapper mapper;
    private PurchaseImportService service;
    private Product product;

    @BeforeEach void setup() {
        mapper = mock(ProductMapper.class);
        service = new PurchaseImportService(mapper);
        product = new Product();
        product.setId(7L); product.setSkuCode("SP001"); product.setProductName("测试商品"); product.setProductStatus("在售");
        when(mapper.selectList(any())).thenReturn(Collections.singletonList(product));
    }
    private MockMultipartFile file(String kind, String[]... rows) throws Exception {
        try (Workbook book = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = book.createSheet("商品明细");
            String[] headers = {"商品编码", kind.equals("receipt") ? "实收数量" : "订购数量", kind.equals("receipt") ? "进货单价" : "预计单价"};
            Row header = sheet.createRow(0);
            for (int c = 0; c < 3; c++) header.createCell(c).setCellValue(headers[c]);
            for (int i = 0; i < rows.length; i++) {
                Row row = sheet.createRow(i + 1);
                for (int c = 0; c < rows[i].length; c++) row.createCell(c).setCellValue(rows[i][c]);
            }
            book.write(out);
            return new MockMultipartFile("file", "test.xlsx", "application/octet-stream", out.toByteArray());
        }
    }
    @Test void receiptResolvesSkuAndPreservesDecimalsWithoutWriting() throws Exception {
        PurchaseImportService.Preview p = service.preview(file("receipt", new String[]{"SP001", "1.125", "2.1234"}), "receipt");
        assertTrue(p.getErrors().isEmpty());
        assertEquals(7L, p.getRows().get(0).getProductId());
        assertEquals(new BigDecimal("1.125"), p.getRows().get(0).getQty());
        assertEquals(new BigDecimal("2.1234"), p.getRows().get(0).getUnitPrice());
        verify(mapper).selectList(any()); verifyNoMoreInteractions(mapper);
    }
    @Test void orderAllowsMissingPriceAndSkipsBlankRows() throws Exception {
        PurchaseImportService.Preview p = service.preview(file("order", new String[]{"", "", ""}, new String[]{"SP001", "3", ""}), "order");
        assertTrue(p.getErrors().isEmpty()); assertEquals(1, p.getRows().size());
        assertEquals(3, p.getRows().get(0).getRowNo()); assertNull(p.getRows().get(0).getUnitPrice());
    }
    @Test void invalidNumbersAndDuplicateCodesHaveRowErrors() throws Exception {
        PurchaseImportService.Preview p = service.preview(file("receipt", new String[]{"SP001", "0", "2"}, new String[]{"SP001", "3", "2"}), "receipt");
        assertEquals(2, p.getErrors().size()); assertTrue(p.getErrors().get(0).startsWith("第 2 行"));
        assertTrue(p.getErrors().get(1).contains("重复"));
    }
    @Test void unknownSkuAndClearanceAreRejected() throws Exception {
        assertTrue(service.preview(file("receipt", new String[]{"missing", "1", "2"}), "receipt").getErrors().get(0).contains("不存在"));
        product.setProductStatus("清仓中");
        assertTrue(service.preview(file("receipt", new String[]{"SP001", "1", "2"}), "receipt").getErrors().get(0).contains("清仓中"));
    }
    @Test void wrongTemplateEmptyDataAndCorruptFileAreRejected() throws Exception {
        assertThrows(BizException.class, () -> service.preview(file("order", new String[]{"SP001", "1", "2"}), "receipt"));
        assertThrows(BizException.class, () -> service.preview(file("order"), "order"));
        assertThrows(BizException.class, () -> service.preview(new MockMultipartFile("file", "bad.xlsx", "", new byte[]{1,2,3}), "receipt"));
        assertThrows(BizException.class, () -> service.preview(file("order"), "other"));
    }
    @Test void numericPrecisionAndFormulaAreNotSilentlyRounded() throws Exception {
        try (Workbook book = new XSSFWorkbook(file("receipt", new String[]{"SP001", "1", "2"}).getInputStream()); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Cell price = book.getSheetAt(0).getRow(1).getCell(2);
            price.setCellValue(2.1234);
            CellStyle style = book.createCellStyle(); style.setDataFormat(book.createDataFormat().getFormat("0.00")); price.setCellStyle(style);
            book.write(out);
            PurchaseImportService.Preview p = service.preview(new MockMultipartFile("file", "price.xlsx", "", out.toByteArray()), "receipt");
            assertEquals(new BigDecimal("2.1234"), p.getRows().get(0).getUnitPrice());
            price.setCellFormula("1+1"); out.reset(); book.write(out);
            assertTrue(service.preview(new MockMultipartFile("file", "formula.xlsx", "", out.toByteArray()), "receipt").getErrors().get(0).contains("公式"));
        }
    }
    @Test void generatedTemplatesCanBeFilledAndParsed() throws Exception {
        for (String kind : new String[]{"order", "receipt"}) {
            MockHttpServletResponse response = new MockHttpServletResponse();
            new PurchaseImportController(service).template(kind, response);
            try (Workbook book = new XSSFWorkbook(new java.io.ByteArrayInputStream(response.getContentAsByteArray())); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                Row row = book.getSheetAt(0).createRow(1);
                row.createCell(0).setCellValue("SP001"); row.createCell(1).setCellValue(2); row.createCell(2).setCellValue(1.25);
                book.write(out);
                assertTrue(service.preview(new MockMultipartFile("file", "template.xlsx", "", out.toByteArray()), kind).getErrors().isEmpty());
            }
        }
    }
}
