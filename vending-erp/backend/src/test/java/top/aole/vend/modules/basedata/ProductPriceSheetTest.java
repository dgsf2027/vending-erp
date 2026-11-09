package top.aole.vend.modules.basedata;

import org.apache.poi.hssf.usermodel.HSSFWorkbook;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import top.aole.vend.modules.basedata.application.*;
import top.aole.vend.modules.basedata.infrastructure.mapper.*;
import top.aole.vend.modules.basedata.interfaces.dto.ProductImportDtos;
import top.aole.vend.modules.imports.parser.ExcelParser;
import java.io.*;
import java.math.BigDecimal;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ProductPriceSheetTest {
    private final ProductImportService service = new ProductImportService(new ExcelParser(), mock(ProductMapper.class),
            mock(ProductService.class), mock(AliasService.class), mock(AliasPendingMapper.class));

    static byte[] priceSheet(boolean oldFormat, boolean formulaCost, boolean includeCost) throws Exception {
        try (Workbook wb = oldFormat ? new HSSFWorkbook() : new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = wb.createSheet("商品价格表");
            sheet.createRow(1).createCell(0).setCellValue("测试商品名称与销售价格");
            sheet.createRow(2).createCell(0).setCellValue("下面是商品明细，售价和拿货价均为单件价");
            sheet.createRow(3).createCell(0).setCellValue("来源：本地测试");
            String[] headers = {"商品编码", "商品名称", "常见销售单价（元）", "拿货价（元/瓶或罐）", "每箱瓶/罐数", "整箱拿货价（元）"};
            Row header = sheet.createRow(5);
            for (int c=0;c<headers.length;c++) header.createCell(c).setCellValue(headers[c]);
            String[] names = {"测试绿茶500ml", "测试可乐摩登罐330ml", "测试饮料", "测试盒装牛奶250ml"};
            for (int i=0;i<names.length;i++) {
                int r=i+6;
                Row row=sheet.createRow(r);
                row.createCell(0).setCellValue(String.format("YQ%06d",i+1));
                row.createCell(1).setCellValue(names[i]);
                row.createCell(2).setCellValue(4.8);
                if (includeCost) {
                    if (formulaCost) row.createCell(3).setCellFormula("F"+(r+1)+"/E"+(r+1));
                    else row.createCell(3).setCellValue("3.6667");
                }
                row.createCell(4).setCellValue(15);
                row.createCell(5).setCellValue(55);
            }
            wb.getCreationHelper().createFormulaEvaluator().evaluateAll();
            wb.write(out); return out.toByteArray();
        }
    }

    @Test void titledXlsxAndXlsReadActualHeadersFormulaCostsAndPhysicalRowNumbers() throws Exception {
        for (boolean oldFormat : new boolean[]{false,true}) {
            ProductImportDtos.ParseResp parsed=service.parseForSmart("商品价格表"+(oldFormat?".xls":".xlsx"),priceSheet(oldFormat,true,true));
            assertEquals(4,parsed.getRowTotal());
            assertEquals(6,parsed.getHeaders().size());
            assertTrue(parsed.getWarnings().stream().anyMatch(s->s.contains("第 6 行")));
            ProductImportDtos.Row first=parsed.getRows().get(0);
            assertEquals(7,first.getRowNo()); assertEquals("YQ000001",first.getRequestedSkuCode());
            assertEquals("4.8",first.getRefPrice()); assertEquals("15",first.getBoxSpec()); assertEquals("55",first.getCaseCost());
            assertEquals(55.0/15,new BigDecimal(first.getRefCost()).doubleValue(),0.00000001);
            assertEquals("瓶",first.getUnit()); assertEquals("罐",parsed.getRows().get(1).getUnit());
            assertNull(parsed.getRows().get(2).getUnit(),"不能按箱规把未知包装猜成瓶");
            assertNull(parsed.getRows().get(3).getUnit(),"盒装液体不能猜成瓶");
        }
    }

    @Test void missingUnitCostIsDerivedWhileExplicitZeroAndConflictAreNotSilentlyOverwritten() throws Exception {
        ProductImportDtos.ParseResp derived=service.parseForSmart("价格.xlsx",priceSheet(false,false,false));
        assertEquals("3.6667",derived.getRows().get(0).getRefCost());
        try (Workbook wb=new XSSFWorkbook(new ByteArrayInputStream(priceSheet(false,false,true))); ByteArrayOutputStream out=new ByteArrayOutputStream()) {
            wb.getSheetAt(0).getRow(6).getCell(3).setCellValue(0);
            wb.getSheetAt(0).getRow(7).getCell(5).setCellValue(0);
            wb.getSheetAt(0).getRow(7).getCell(3).setBlank();
            wb.write(out);
            ProductImportDtos.ParseResp parsed=service.parse("价格.xlsx",out.toByteArray());
            assertEquals("0",parsed.getRows().get(0).getRefCost());
            assertTrue(parsed.getRows().get(0).getErrorMsg().contains("不一致"));
            assertEquals("0",parsed.getRows().get(1).getRefCost());
            assertNull(parsed.getRows().get(1).getErrorMsg());
        }
    }

    @Test void otherImportChannelsStillReadFirstNonemptyRow() throws Exception {
        assertEquals("测试商品名称与销售价格",new ExcelParser().parse(new ByteArrayInputStream(priceSheet(false,true,true))).getHeaders().get(0));
    }
}
