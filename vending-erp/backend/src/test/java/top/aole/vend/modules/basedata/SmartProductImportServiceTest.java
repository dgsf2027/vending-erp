package top.aole.vend.modules.basedata;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.h2.jdbcx.JdbcDataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.test.util.ReflectionTestUtils;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import top.aole.vend.common.exception.BizException;
import top.aole.vend.modules.basedata.application.*;
import top.aole.vend.modules.basedata.domain.entity.*;
import top.aole.vend.modules.basedata.infrastructure.mapper.*;
import top.aole.vend.modules.basedata.interfaces.dto.*;
import top.aole.vend.modules.imports.domain.entity.ImportBatch;
import top.aole.vend.modules.imports.mapper.*;
import top.aole.vend.modules.imports.parser.ExcelParser;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

/** 真实 JDBC 事务验证“商品+别名”一行失败一起回滚，目录与 Excel 也走实际实现。 */
class SmartProductImportServiceTest {
    @TempDir Path files;
    JdbcTemplate db;
    SmartProductImportService service;
    ProductService products;
    ProductMapper productMapper;
    AliasService aliases;
    ImportBatchMapper batches;
    @BeforeEach void setup() {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000");
        db = new JdbcTemplate(ds);
        db.execute("CREATE TABLE seq(id int primary key,n bigint)"); db.update("INSERT INTO seq VALUES(1,0)");
        db.execute("CREATE TABLE product(id bigint auto_increment primary key,code varchar(32) unique,name varchar(128),barcode varchar(64),unit varchar(16),pack decimal(12,3),status varchar(20))");
        db.execute("CREATE TABLE alias(code varchar(64),barcode varchar(64),name varchar(128),product_id bigint,unique(code,barcode))");
        ProductCodeMapper codeMapper = mock(ProductCodeMapper.class);
        when(codeMapper.lockSequence()).thenAnswer(i -> db.queryForObject("SELECT n FROM seq WHERE id=1 FOR UPDATE", Long.class));
        when(codeMapper.existingCodes()).thenAnswer(i -> db.queryForList("SELECT code FROM product", String.class));
        when(codeMapper.advance(anyLong())).thenAnswer(i -> db.update("UPDATE seq SET n=? WHERE id=1", (Long)i.getArgument(0)));
        ProductCodeService codes = new ProductCodeService(codeMapper);
        productMapper = mock(ProductMapper.class); products = mock(ProductService.class);
        when(productMapper.selectList(any())).thenAnswer(i -> db.query("SELECT * FROM product", (r,n) -> {
            Product p = new Product(); p.setId(r.getLong("id")); p.setSkuCode(r.getString("code")); p.setProductName(r.getString("name"));
            p.setBarcode(r.getString("barcode")); p.setUnit(r.getString("unit")); p.setBoxSpec(r.getBigDecimal("pack")); p.setProductStatus(r.getString("status")); return p;
        }));
        when(products.create(any(), anyString())).thenAnswer(i -> {
            Product p = i.getArgument(0); assertNull(p.getSkuCode());
            p.setSkuCode(codes.nextCode());
            db.update("INSERT INTO product(code,name,barcode,unit,pack,status) VALUES(?,?,?,?,?,?)",
                p.getSkuCode(),p.getProductName(),p.getBarcode(),p.getUnit(),p.getBoxSpec(),p.getProductStatus());
            p.setId(db.queryForObject("SELECT id FROM product WHERE code=?",Long.class,p.getSkuCode())); return p;
        });
        when(products.getById(anyLong())).thenAnswer(i -> productMapper.selectList(null).stream()
            .filter(p -> p.getId().equals(i.getArgument(0))).findFirst().get());
        SkuAliasMapper aliasMapper = mock(SkuAliasMapper.class);
        when(aliasMapper.selectList(any())).thenAnswer(i -> db.query("SELECT * FROM alias",(r,n) ->
            ProductCatalogMatcherTest.alias(r.getLong("product_id"),r.getString("code"),r.getString("barcode"),r.getString("name"))));
        aliases = mock(AliasService.class);
        when(aliases.bind(any(),anyString())).thenAnswer(i -> {
            Dtos.BindAliasReq r = i.getArgument(0);
            if ("FAIL".equals(r.getAliasName())) throw new BizException("模拟别名保存失败");
            db.update("MERGE INTO alias(code,barcode,name,product_id) KEY(code,barcode) VALUES(?,?,?,?)",
                r.getAliasCode(),r.getAliasBarcode(),r.getAliasName(),r.getProductId()); return new SkuAlias();
        });
        AliasPendingMapper pendings = mock(AliasPendingMapper.class);
        when(pendings.selectList(any())).thenReturn(Collections.emptyList());
        ProductImportService fields = new ProductImportService(new ExcelParser(),productMapper,products,aliases,pendings);
        batches = mock(ImportBatchMapper.class);
        when(batches.insert(any())).thenAnswer(i -> { ((ImportBatch)i.getArgument(0)).setId(1L); return 1; });
        service = new SmartProductImportService(fields,productMapper,aliasMapper,pendings,products,aliases,codes,
            batches,mock(ImportErrorMapper.class),mock(OpLogService.class),new DataSourceTransactionManager(ds));
        ReflectionTestUtils.setField(service,"storageDir",files.toString());
    }
    byte[] excel(String[][] data) throws Exception {
        try (XSSFWorkbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            org.apache.poi.ss.usermodel.Sheet sheet = wb.createSheet("商品");
            String[] headers = {"后台商品编号","商品名称","商品条形码","基本单位","箱规"};
            org.apache.poi.ss.usermodel.Row h = sheet.createRow(0);
            for (int c=0;c<headers.length;c++) h.createCell(c).setCellValue(headers[c]);
            for (int r=0;r<data.length;r++) {
                org.apache.poi.ss.usermodel.Row row = sheet.createRow(r+1);
                for (int c=0;c<data[r].length;c++) row.createCell(c).setCellValue(data[r][c]);
            }
            wb.write(out); return out.toByteArray();
        }
    }
    @Test void newCodeReturnedOriginalIdentifiersPreservedAndRepeatIsOnlyBinding() throws Exception {
        byte[] content = excel(new String[][]{{"00017","测试绿茶500ml","001234","瓶","24"}});
        ProductImportDtos.ParseResp preview = service.parse("商品.xlsx",content);
        assertNotNull(preview.getToken()); assertEquals("00017",preview.getRows().get(0).getSourceCode());
        assertEquals(0,db.queryForObject("SELECT count(*) FROM product",Integer.class));
        ProductImportDtos.CommitResp first = service.commit(preview.getToken(),preview.getRows(),"测试");
        assertEquals(1,first.getCreated()); assertEquals("SP000001",first.getSavedRows().get(0).getSkuCode());
        assertEquals("001234",db.queryForObject("SELECT barcode FROM alias WHERE code='00017'",String.class));
        ProductImportDtos.ParseResp again = service.parse("商品.xlsx",content);
        assertEquals("已匹配",again.getRows().get(0).getAction());
        again.getRows().get(0).setRefPrice("99");
        ProductImportDtos.CommitResp second = service.commit(again.getToken(),again.getRows(),"测试");
        assertEquals(1,second.getMatched()); assertEquals(0,second.getCreated());
        verify(products,never()).update(anyLong(),any(),anyString());
        verify(products,never()).changeStatus(anyLong(),anyString(),anyString());
        again.getRows().get(0).setUpdateExisting(true);
        assertEquals(1,service.commit(again.getToken(),again.getRows(),"测试").getUpdated());
        verify(products).update(anyLong(),any(),eq("测试"));
        org.mockito.ArgumentCaptor<ImportBatch> batch = org.mockito.ArgumentCaptor.forClass(ImportBatch.class);
        verify(batches,atLeastOnce()).insert(batch.capture());
        assertTrue(batch.getValue().getColumnMapJson().contains("smart-product"));
        assertArrayEquals(content,Files.readAllBytes(Paths.get(batch.getValue().getArchivePath())));
    }
    @Test void missingPackagingAndDuplicateRowsAreReviewErrors() throws Exception {
        ProductImportDtos.ParseResp response = service.parse("新货.xlsx",excel(new String[][]{
            {"","新品汽水","007","",""},{"","新品汽水","007","瓶","12"}}));
        assertEquals(2,response.getErrorCount());
        assertTrue(response.getRows().get(1).getErrorMsg().contains("重复"));
        assertEquals(0,db.queryForObject("SELECT count(*) FROM product",Integer.class));
    }
    @Test void aliasFailureRollsBackProductAndSequenceAndKeepsFailedRowForRetry() throws Exception {
        ProductImportDtos.ParseResp preview = service.parse("新货.xlsx",excel(new String[][]{
            {"x","FAIL","001","瓶","12"},{"y","花生米80g","002","袋","24"}}));
        ProductImportDtos.CommitResp result = service.commit(preview.getToken(),preview.getRows(),"测试");
        assertEquals(1,result.getCreated()); assertEquals(1,result.getFailed());
        assertEquals("SP000001",result.getSavedRows().get(0).getSkuCode());
        assertNull(result.getFailedRows().get(0).getSkuCode());
        assertEquals(1,db.queryForObject("SELECT count(*) FROM product",Integer.class));
        ProductImportDtos.Row retry = result.getFailedRows().get(0); retry.setSourceName("修正品名");
        assertEquals("SP000002",service.commit(preview.getToken(),Collections.singletonList(retry),"测试").getSavedRows().get(0).getSkuCode());
    }
    @Test void twoConcurrentImportsOfSameBarcodeCreateOneProduct() throws Exception {
        byte[] content = excel(new String[][]{{"00017","测试绿茶500ml","001234","瓶","24"}});
        ProductImportDtos.ParseResp a = service.parse("a.xlsx",content), b = service.parse("b.xlsx",content);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<ProductImportDtos.CommitResp> first = pool.submit(() -> service.commit(a.getToken(),a.getRows(),"A"));
            Future<ProductImportDtos.CommitResp> second = pool.submit(() -> service.commit(b.getToken(),b.getRows(),"B"));
            ProductImportDtos.CommitResp x = first.get(15,TimeUnit.SECONDS), y = second.get(15,TimeUnit.SECONDS);
            assertEquals(0,x.getFailed()+y.getFailed()); assertEquals(1,x.getCreated()+y.getCreated());
            assertEquals(1,x.getMatched()+y.getMatched()); assertEquals(1,db.queryForObject("SELECT count(*) FROM product",Integer.class));
        } finally { pool.shutdownNow(); }
    }
    @Test void unknownTokenOrDuplicateRowNumbersNeverCreateBatch() {
        ProductImportDtos.Row row = ProductCatalogMatcherTest.row("新品",null);
        assertThrows(BizException.class,() -> service.commit("wrong",Collections.singletonList(row),"测试"));
        assertThrows(BizException.class,() -> service.review(Arrays.asList(row,row)));
        verifyNoInteractions(batches);
    }

    @Test void manualRowsWithoutFileGetCodesAndAReadableArchivedSnapshot() throws Exception {
        ProductImportDtos.Row row = ProductCatalogMatcherTest.row("手工新品饮料", "001000");
        row.setRowNo(9); row.setSourceCode("00089"); row.setUnit("瓶"); row.setBoxSpec("24");
        ProductImportDtos.CommitResp result = service.commitManual(Collections.singletonList(row), "测试");
        assertEquals(1,result.getCreated()); assertEquals("SP000001",result.getSavedRows().get(0).getSkuCode());
        org.mockito.ArgumentCaptor<ImportBatch> batch = org.mockito.ArgumentCaptor.forClass(ImportBatch.class);
        verify(batches).insert(batch.capture());
        try (XSSFWorkbook workbook = new XSSFWorkbook(Files.newInputStream(Paths.get(batch.getValue().getArchivePath())))) {
            assertEquals("手工商品录入",workbook.getSheetAt(0).getSheetName());
            assertEquals("9",workbook.getSheetAt(0).getRow(1).getCell(0).getStringCellValue());
            assertEquals("00089",workbook.getSheetAt(0).getRow(1).getCell(1).getStringCellValue());
            assertEquals("001000",workbook.getSheetAt(0).getRow(1).getCell(3).getStringCellValue());
        }
        assertEquals(1,service.commitManual(Collections.singletonList(row),"测试").getMatched());
        assertEquals(1,db.queryForObject("SELECT count(*) FROM product",Integer.class));
    }
}
