package top.aole.vend.modules.purchase;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.*;
import org.springframework.mock.web.MockMultipartFile;
import top.aole.vend.common.exception.BizException;
import top.aole.vend.modules.basedata.domain.entity.*;
import top.aole.vend.modules.basedata.infrastructure.mapper.*;
import top.aole.vend.modules.imports.parser.ExcelParser;
import top.aole.vend.modules.purchase.dto.PurchaseWorkbookDtos.*;
import top.aole.vend.modules.purchase.mapper.PurchaseSalesMatchMapper;
import top.aole.vend.modules.purchase.mapper.PurchaseSalesMatchMapper.SaleSummary;
import top.aole.vend.modules.purchase.service.PurchaseWorkbookService;
import top.aole.vend.modules.purchase.interfaces.PurchaseWorkbookController;
import java.io.*;
import java.nio.file.*;
import java.math.BigDecimal;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class PurchaseWorkbookTest {
    ProductMapper products; SkuAliasMapper aliases; PurchaseSalesMatchMapper sales; PurchaseWorkbookService service;
    List<Product> catalog;
    String[] headers = {"采购日期", "产品名称", "商品编码", "整件规格", "总件数", "采购数量", "整件价格", "总价格", "匹配商品 / 待核对"};
    @BeforeEach void setup() {
        products = mock(ProductMapper.class); aliases = mock(SkuAliasMapper.class); sales = mock(PurchaseSalesMatchMapper.class);
        catalog = new ArrayList<>(Arrays.asList(product(7, "外星人电解质水0糖版青柠口味600ml"), product(8, "外星人电解质水西柚味低糖600ml"),
            product(22, "外星人电解质水0糖版荔枝海盐口味600ml"), product(23, "外星人电解质水PRO专业版600ml"), product(24, "外星人电解质水椰子味低糖600ml"),
            product(15, "元气森林气泡水维C橙味480ml"), product(16, "元气森林气泡水夏黑葡萄味480ml")));
        when(products.selectList(any())).thenAnswer(i -> catalog);
        when(aliases.selectList(any())).thenReturn(Collections.emptyList()); when(sales.summaries()).thenReturn(Collections.emptyList());
        service = new PurchaseWorkbookService(new ExcelParser(), products, aliases, sales);
    }
    Product product(long id, String name) { Product p = new Product(); p.setId(id); p.setSkuCode(String.format("YQ%06d", id)); p.setProductName(name); p.setBoxSpec(new BigDecimal("15")); p.setUnit("瓶"); p.setProductStatus("在售"); return p; }
    MockMultipartFile file(int headerRow, String[] columns, String[]... data) throws Exception {
        try (Workbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet s = wb.createSheet("拿货表");
            if (headerRow > 0) s.createRow(0).createCell(0).setCellValue("拿货表说明");
            org.apache.poi.ss.usermodel.Row h = s.createRow(headerRow);
            for (int c=0;c<columns.length;c++) h.createCell(c).setCellValue(columns[c]);
            for (int i=0;i<data.length;i++) { org.apache.poi.ss.usermodel.Row r = s.createRow(headerRow+i+1); for(int c=0;c<data[i].length;c++) r.createCell(c).setCellValue(data[i][c]); }
            wb.write(out); return new MockMultipartFile("file","拿货.xlsx","application/octet-stream",out.toByteArray());
        }
    }
    Allocation allocation(long productId, String qty) { Allocation a = new Allocation(); a.setProductId(productId); a.setQty(new BigDecimal(qty)); return a; }
    void equal(String expected, BigDecimal actual) { assertEquals(0, new BigDecimal(expected).compareTo(actual)); }

    @Test void skipsTitlesAndSubtotalsPreservesDatesAndReadsExtraColumns() throws Exception {
        Preview p = service.parse(file(3, headers,
            new String[]{"2026.10.19","元气森林气泡橙水","YQ000015","15","16","240","50","800","说明"},
            new String[]{"","","","","","","总计","800",""},
            new String[]{"2026.10.23","元气森林气泡橙水","YQ000015","15","2","30","50","100",""}));
        assertTrue(p.getErrors().isEmpty()); assertEquals(2,p.getRows().size()); assertEquals(1,p.getSkippedSubtotals());
        assertEquals(2,p.getDays().size()); assertEquals("2026-10-19",p.getDays().get(0).getBizDate()); equal("800",p.getDays().get(0).getTotalAmount());
        assertEquals(5,p.getRows().get(0).getRowNo()); equal("3.3333",p.getRows().get(0).getUnitPrice());
        verify(products,never()).insert(any()); verify(aliases,never()).insert(any());
    }
    @Test void mergesOnlySameDateAndConfirmedProductAndKeepsSourceRows() throws Exception {
        Preview p = service.parse(file(0,headers,
            new String[]{"2026-10-23","元气森林气泡橙水","YQ000015","15","5","75","50","250",""},
            new String[]{"2026-10-23","元气森林气泡橙水","YQ000015","15","4","60","55","220",""},
            new String[]{"2026-10-26","元气森林气泡橙水","YQ000015","15","1","15","50","50",""}));
        assertEquals(2,p.getDays().size()); assertTrue(p.getDays().get(0).getErrors().isEmpty());
        assertEquals(1,p.getDays().get(0).getLines().size()); GroupLine line=p.getDays().get(0).getLines().get(0);
        equal("135",line.getQty()); equal("470",line.getTotalPrice()); equal("3.4815",line.getUnitPrice()); assertEquals(Arrays.asList(2,3),line.getSourceRows());
        equal("50",p.getDays().get(1).getTotalAmount());
    }
    @Test void differentFlavorsSuggestActualSaleCodesAndRequireConfirmation() throws Exception {
        Preview p = service.parse(file(0,headers,
            new String[]{"2026.10.23","外星人电解质水(荔枝)","YQ000007","15","5","75","60","300","外星人电解质水600ml"},
            new String[]{"2026.10.23","外星人电解质水(专业版)","YQ000007","15","4","60","60","240","外星人电解质水600ml"}));
        SourceRow a=p.getRows().get(0), b=p.getRows().get(1);
        assertEquals(22L,a.getSuggestedProductId()); assertEquals(22L,a.getAllocations().get(0).getProductId()); assertTrue(a.isNeedsConfirmation());
        assertEquals(23L,b.getAllocations().get(0).getProductId()); assertFalse(p.getDays().get(0).getErrors().isEmpty());
        p.getRows().forEach(r -> r.setConfirmed(true)); Preview reviewed=service.review(p.getRows(),null);
        assertTrue(reviewed.getDays().get(0).getErrors().isEmpty()); assertEquals(2,reviewed.getDays().get(0).getLines().size()); equal("540",reviewed.getDays().get(0).getTotalAmount());
    }
    @Test void genericSeriesDoesNotGuessFlavorAndShowsRealSalesEvidence() throws Exception {
        SaleSummary sale = new SaleSummary(); sale.setProductId(8L); sale.setSourceName("销售西柚原名"); sale.setQty(new BigDecimal("165")); sale.setFirstSale("2026-10-20 00:00:00"); sale.setLastSale("2026-11-05 22:41:03");
        when(sales.summaries()).thenReturn(Collections.singletonList(sale));
        Preview p=service.parse(file(0,headers,new String[]{"2026.10.19","外星人电解质水","YQ000007","15","42","630","60","2520","外星人电解质水600ml"}));
        SourceRow row=p.getRows().get(0); assertTrue(row.getAllocations().isEmpty()); assertTrue(row.isNeedsConfirmation());
        Candidate c=row.getCandidates().stream().filter(v -> v.getProductId()==8L).findFirst().get();
        equal("165",c.getSalesQty()); assertEquals("2026-11-05 22:41:03",c.getLastSale()); assertEquals(Collections.singletonList("销售西柚原名"),c.getSalesNames());
        assertTrue(p.getDays().get(0).getLines().isEmpty());
    }
    @Test void splitFlavorsConservesOriginalQuantityAndMoney() throws Exception {
        Preview p=service.parse(file(0,headers,new String[]{"2026.10.19","元气森林气泡水","","15","16","240","50","800","待核对 YQ000015 / YQ000016"}));
        SourceRow r=p.getRows().get(0); r.setAllocations(Arrays.asList(allocation(15,"90"),allocation(16,"150"))); r.setConfirmed(true);
        Preview reviewed=service.review(p.getRows(),null); Day day=reviewed.getDays().get(0);
        assertTrue(day.getErrors().isEmpty()); assertEquals(2,day.getLines().size());
        equal("300",day.getLines().get(0).getTotalPrice()); equal("500",day.getLines().get(1).getTotalPrice()); equal("240",day.getTotalQty());
        r.getAllocations().get(1).setQty(new BigDecimal("140"));
        assertTrue(service.review(p.getRows(),null).getDays().get(0).getErrors().stream().anyMatch(e -> e.contains("之和")));
    }
    @Test void duplicatesUnknownIdsAndClearanceDoNotPassReview() throws Exception {
        Preview p=service.parse(file(0,headers,new String[]{"2026.10.19","元气森林气泡水","","15","2","30","50","100",""}));
        SourceRow r=p.getRows().get(0); r.setConfirmed(true); r.setAllocations(Arrays.asList(allocation(15,"15"),allocation(15,"15")));
        assertTrue(service.review(p.getRows(),null).getDays().get(0).getErrors().stream().anyMatch(e -> e.contains("重复")));
        r.setAllocations(Collections.singletonList(allocation(999,"30")));
        assertTrue(service.review(p.getRows(),null).getDays().get(0).getErrors().stream().anyMatch(e -> e.contains("不存在")));
        catalog.stream().filter(v -> v.getId()==15L).findFirst().get().setProductStatus("清仓中"); r.setAllocations(Collections.singletonList(allocation(15,"30")));
        assertTrue(service.review(p.getRows(),null).getDays().get(0).getErrors().stream().anyMatch(e -> e.contains("清仓中")));
    }
    @Test void mismatchedQuantitiesAmountsAndSubtotalsAreReported() throws Exception {
        Preview p=service.parse(file(0,headers,new String[]{"2026.10.19","元气森林气泡橙水","YQ000015","15","16","239","50","800",""},
            new String[]{"","","","","","","总计","799",""}));
        assertTrue(p.getErrors().stream().anyMatch(e -> e.contains("小计")));
        assertTrue(p.getRows().get(0).getErrors().stream().anyMatch(e -> e.contains("采购数量应等于")));
        p.getRows().get(0).setQty(new BigDecimal("240")); p.getRows().get(0).setTotalPrice(new BigDecimal("799"));
        assertTrue(service.review(p.getRows(),null).getRows().get(0).getErrors().stream().anyMatch(e -> e.contains("总价格应等于")));
    }
    @Test void datesMustBeRealAndLegacyTemplateCanUseExplicitDate() throws Exception {
        Preview legacy=service.parse(file(0,new String[]{"商品编码","实收数量","进货单价"},new String[]{"YQ000015","15","3.3333"}));
        assertEquals("",legacy.getDays().get(0).getBizDate());
        Preview reviewed=service.review(legacy.getRows(),"2026-11-10"); assertEquals("2026-11-10",reviewed.getDays().get(0).getBizDate());
        assertTrue(reviewed.getDays().get(0).getErrors().isEmpty());
        legacy.getRows().get(0).setBizDate("2026-02-30"); assertTrue(service.review(legacy.getRows(),null).getRows().get(0).getErrors().contains("请填写有效采购日期"));
    }
    @Test void templateMatchesNineSourceColumnsAndCanBeParsed() throws Exception {
        byte[] bytes=new PurchaseWorkbookController(service).template().getBody();
        try(Workbook wb=WorkbookFactory.create(new ByteArrayInputStream(bytes)); ByteArrayOutputStream out=new ByteArrayOutputStream()) {
            assertEquals("产品名称",wb.getSheetAt(0).getRow(0).getCell(1).getStringCellValue());
            assertEquals("@",wb.getSheetAt(0).getColumnStyle(2).getDataFormatString());
            String[] values={"2026-11-10","元气森林气泡橙水","YQ000015","15","1","15","50","50",""};
            org.apache.poi.ss.usermodel.Row r=wb.getSheetAt(0).createRow(1); for(int i=0;i<values.length;i++) r.createCell(i).setCellValue(values[i]);
            wb.write(out); assertTrue(service.parse(new MockMultipartFile("file","模板.xlsx","",out.toByteArray())).getDays().get(0).getErrors().isEmpty());
        }
    }
    @Test void actualUserWorkbookAndCurrentCatalogReconcileEveryDate() throws Exception {
        String file=System.getProperty("vend.test.purchaseFile"), catalogFile=System.getProperty("vend.test.purchaseCatalog");
        Assumptions.assumeTrue(file!=null && catalogFile!=null);
        catalog=Arrays.asList(new ObjectMapper().readValue(Files.readAllBytes(Paths.get(catalogFile)),Product[].class));
        Preview p=service.parse(new MockMultipartFile("file","元气森林拿货.xlsx","",Files.readAllBytes(Paths.get(file))));
        assertEquals(37,p.getRows().size()); assertEquals(3,p.getSkippedSubtotals()); assertEquals(3,p.getDays().size()); assertTrue(p.getErrors().isEmpty());
        equal("8489",p.getDays().get(0).getTotalAmount()); equal("3489",p.getDays().get(1).getTotalAmount()); equal("1998",p.getDays().get(2).getTotalAmount());
        for(SourceRow r:p.getRows()) { if(r.getAllocations().isEmpty()) r.setAllocations(Collections.singletonList(allocation(r.getCandidates().get(0).getProductId(),r.getQty().toPlainString()))); r.setConfirmed(true); }
        Preview checked=service.review(p.getRows(),null);
        for(Day day:checked.getDays()) {
            assertTrue(day.getErrors().isEmpty(),day.getErrors().toString());
            equal(day.getTotalAmount().toPlainString(),day.getLines().stream().map(GroupLine::getTotalPrice).reduce(BigDecimal.ZERO,BigDecimal::add));
            equal(day.getTotalQty().toPlainString(),day.getLines().stream().map(GroupLine::getQty).reduce(BigDecimal.ZERO,BigDecimal::add));
        }
        assertEquals(37,checked.getDays().stream().mapToInt(d -> d.getLines().size()).sum());
        verify(products,never()).insert(any()); verify(aliases,never()).updateById(any());
    }
}
