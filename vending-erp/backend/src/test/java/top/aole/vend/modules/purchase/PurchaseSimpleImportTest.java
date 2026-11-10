package top.aole.vend.modules.purchase;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.*;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import top.aole.vend.common.exception.BizException;
import top.aole.vend.modules.basedata.application.*;
import top.aole.vend.modules.basedata.domain.entity.*;
import top.aole.vend.modules.basedata.infrastructure.mapper.*;
import top.aole.vend.modules.basedata.interfaces.dto.*;
import top.aole.vend.modules.imports.parser.ExcelParser;
import top.aole.vend.modules.imports.service.SalesProductService;
import top.aole.vend.modules.purchase.dto.PurchaseSimpleDtos.*;
import top.aole.vend.modules.purchase.dto.ReceiptCreateReq;
import top.aole.vend.modules.purchase.mapper.PurchaseSalesMatchMapper;
import top.aole.vend.modules.purchase.service.*;
import java.io.*;
import java.math.BigDecimal;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

/** 独立内存数据库：真实事务/归集 SQL；收货边界用记录桩验证金额与回滚，不连接线上。 */
class PurchaseSimpleImportTest {
    JdbcTemplate db; ProductMapper products; SkuAliasMapper aliasMapper; PurchaseSimpleImportService service;
    PurchaseReceiptService receipts; ObjectMapper json = new ObjectMapper(); boolean failConfirm;
    @BeforeEach void setup() {
        DriverManagerDataSource ds = new DriverManagerDataSource("jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE", "sa", "");
        db = new JdbcTemplate(ds);
        db.execute("CREATE TABLE yc_vend_product(id bigint PRIMARY KEY,tenant_id varchar DEFAULT '000000',sku_code varchar,product_name varchar,barcode varchar,unit varchar,box_spec decimal(18,3),product_status varchar,stock_owner_id bigint,shared_stock boolean DEFAULT false,is_deleted int DEFAULT 0)");
        db.execute("CREATE TABLE yc_vend_sku_alias(id bigint AUTO_INCREMENT PRIMARY KEY,tenant_id varchar DEFAULT '000000',product_id bigint,alias_code varchar,alias_barcode varchar DEFAULT '',alias_name varchar,is_deleted int DEFAULT 0)");
        db.execute("CREATE TABLE yc_vend_sale_record(id bigint AUTO_INCREMENT PRIMARY KEY,tenant_id varchar DEFAULT '000000',product_id bigint,alias_name_raw varchar,qty decimal,amount_received decimal(18,2),settlement_id bigint,biz_period varchar,book_period varchar,is_deleted int DEFAULT 0)");
        db.execute("CREATE TABLE yc_vend_period_lock(id bigint AUTO_INCREMENT PRIMARY KEY,tenant_id varchar DEFAULT '000000',period varchar,is_deleted int DEFAULT 0)");
        for (String t : new String[]{"doc_item","stock_ledger","machine_stock_snapshot","purchase_order_item","stocktake_item","prekit_ticket_item","replenish_plan","replenish_config","slot"})
            db.execute("CREATE TABLE yc_vend_"+t+"(id bigint AUTO_INCREMENT PRIMARY KEY,tenant_id varchar DEFAULT '000000',product_id bigint,is_deleted int DEFAULT 0)");
        db.execute("CREATE TABLE yc_vend_purchase_workbook(batch_key varchar PRIMARY KEY,tenant_id varchar DEFAULT '000000',file_name varchar,source_json varchar,file_data blob,result_json varchar,completed_at timestamp)");
        db.execute("CREATE TABLE yc_vend_supplier(id bigint PRIMARY KEY,tenant_id varchar DEFAULT '000000',is_deleted int DEFAULT 0)"); db.update("INSERT INTO yc_vend_supplier(id) VALUES(1)");
        db.execute("CREATE TABLE test_receipt(id bigint AUTO_INCREMENT PRIMARY KEY,biz_date varchar,payload varchar,confirmed boolean DEFAULT false)");
        db.execute("CREATE TABLE test_lock(id int PRIMARY KEY)"); db.update("INSERT INTO test_lock VALUES(1)");
        products=mock(ProductMapper.class); aliasMapper=mock(SkuAliasMapper.class);
        when(products.selectList(any())).thenAnswer(i -> catalog());
        when(products.selectById(any())).thenAnswer(i -> catalog().stream().filter(p -> p.getId().equals(i.getArgument(0))).findFirst().orElse(null));
        when(aliasMapper.selectList(any())).thenAnswer(i -> aliases());
        ProductCodeService codes=mock(ProductCodeService.class); when(codes.lock()).thenAnswer(i -> db.queryForObject("SELECT id FROM test_lock WHERE id=1 FOR UPDATE",Long.class));
        AliasService binding=mock(AliasService.class);
        when(binding.bind(any(),anyString())).thenAnswer(i -> { Dtos.BindAliasReq r=i.getArgument(0);
            int updated=db.update("UPDATE yc_vend_sku_alias SET product_id=? WHERE alias_code=?",r.getProductId(),r.getAliasCode());
            if(updated==0)db.update("INSERT INTO yc_vend_sku_alias(product_id,alias_code,alias_name,alias_barcode) VALUES(?,?,?,?)",r.getProductId(),r.getAliasCode(),r.getAliasName(),r.getAliasBarcode()); return new SkuAlias(); });
        PurchaseSalesMatchMapper sales=mock(PurchaseSalesMatchMapper.class); when(sales.summaries()).thenReturn(Collections.emptyList());
        PurchaseWorkbookService workbook=new PurchaseWorkbookService(new ExcelParser(),products,aliasMapper,sales);
        PurchaseSharedStockService shared=new PurchaseSharedStockService(db,products,codes,binding,mock(OpLogService.class));
        receipts=mock(PurchaseReceiptService.class);
        when(receipts.createDirect(any(),anyLong())).thenAnswer(i -> { ReceiptCreateReq req=i.getArgument(0);
            db.update("INSERT INTO test_receipt(biz_date,payload) VALUES(?,?)",req.getBizDate().toString(),json.writeValueAsString(req.getItems()));
            return db.queryForObject("SELECT MAX(id) FROM test_receipt",Long.class); });
        doAnswer(i -> { db.update("UPDATE test_receipt SET confirmed=true WHERE id=?",(Long)i.getArgument(0));
            if(failConfirm && db.queryForObject("SELECT COUNT(*) FROM test_receipt",Integer.class)>1) throw new BizException("模拟第二张入库失败"); return null;
        }).when(receipts).confirm(anyLong(),anyLong());
        PurchaseSimpleImportService target=new PurchaseSimpleImportService(workbook,shared,receipts,codes,db,json);
        ProxyFactory proxy=new ProxyFactory(target); proxy.setProxyTargetClass(true);
        proxy.addAdvice(new TransactionInterceptor(new DataSourceTransactionManager(ds),new AnnotationTransactionAttributeSource()));
        service=(PurchaseSimpleImportService)proxy.getProxy();
        add(7,"外星人电解质水青柠600ml"); add(8,"外星人电解质水西柚600ml"); add(22,"外星人电解质水荔枝600ml"); add(1,"普通饮料500ml");
        db.update("INSERT INTO yc_vend_sale_record(product_id,alias_name_raw,qty,amount_received,biz_period,book_period) VALUES(8,'外星人电解质水西柚600ml',3,17.4,'2026-10','2026-10')");
    }
    void add(long id,String name) { db.update("INSERT INTO yc_vend_product(id,sku_code,product_name,unit,box_spec,product_status) VALUES(?,?,?,'瓶',15,'在售')",id,String.format("YQ%06d",id),name); }
    List<Product> catalog() { return db.query("SELECT * FROM yc_vend_product",(r,n)->{Product p=new Product();p.setId(r.getLong("id"));p.setSkuCode(r.getString("sku_code"));p.setProductName(r.getString("product_name"));p.setBarcode(r.getString("barcode"));p.setUnit(r.getString("unit"));p.setBoxSpec(r.getBigDecimal("box_spec"));p.setProductStatus(r.getString("product_status"));p.setStockOwnerId((Long)r.getObject("stock_owner_id"));p.setSharedStock(r.getBoolean("shared_stock"));return p;}); }
    List<SkuAlias> aliases() { return db.query("SELECT * FROM yc_vend_sku_alias",(r,n)->{SkuAlias a=new SkuAlias();a.setProductId(r.getLong("product_id"));a.setAliasCode(r.getString("alias_code"));a.setAliasName(r.getString("alias_name"));a.setAliasBarcode(r.getString("alias_barcode"));return a;}); }
    MockMultipartFile file(String name,String... values) throws Exception {
        try(XSSFWorkbook book=new XSSFWorkbook();ByteArrayOutputStream out=new ByteArrayOutputStream()) {
            org.apache.poi.ss.usermodel.Sheet s=book.createSheet("拿货表"); String[] headers={"采购日期","产品名称","商品编码","整件规格","总件数","采购数量","整件价格","总价格"};
            org.apache.poi.ss.usermodel.Row h=s.createRow(0); for(int c=0;c<headers.length;c++)h.createCell(c).setCellValue(headers[c]);
            for(int i=0;i<values.length;i++){String[] cells=values[i].split("\\|",-1);org.apache.poi.ss.usermodel.Row row=s.createRow(i+1);for(int c=0;c<cells.length;c++)row.createCell(c).setCellValue(cells[c]);}
            book.write(out);return new MockMultipartFile("file",name,"",out.toByteArray());
        }
    }
    SimplePreview preview() throws Exception {return service.preview(file("拿货.xlsx","2026-10-19|外星人电解质水|YQ000007|15|42|630|60|2520","2026-10-23|外星人电解质水荔枝|YQ000007|15|5|75|60|300","2026-10-26|普通饮料500ml|YQ000001|15|1|15|50|50"));}
    CommitRequest choice(SimplePreview p) {CommitRequest r=new CommitRequest();r.setBatchKey(p.getBatchKey());r.setSupplierId(1L);for(StockGroup g:p.getGroups()){GroupChoice c=new GroupChoice();c.setKey(g.getKey());c.setTargetId(g.getTargetId());c.setMemberIds(g.getMembers().stream().map(m->m.getProductId()).collect(Collectors.toList()));c.setConfirmed(true);r.getGroups().add(c);}return r;}
    int receiptsCount(){return db.queryForObject("SELECT COUNT(*) FROM test_receipt",Integer.class);}

    @Test void oneFamilyAcrossDatesRequiresOneConfirmationAndKeepsSalesNames() throws Exception {
        db.update("UPDATE yc_vend_product SET barcode='BAR-8' WHERE id=8");
        db.update("UPDATE yc_vend_product SET barcode='BAR-7' WHERE id=7");
        SimplePreview p=preview();assertEquals(1,p.getGroups().size());assertEquals(2,p.getGroups().get(0).getSourceRows().size());assertEquals(0,receiptsCount());
        CommitResult result=service.commit(choice(p),"测试");assertEquals(3,result.getReceiptIds().size());
        assertEquals(7L,db.queryForObject("SELECT product_id FROM yc_vend_sale_record",Long.class));
        assertEquals("外星人电解质水西柚600ml",db.queryForObject("SELECT alias_name_raw FROM yc_vend_sale_record",String.class));
        assertEquals(0,new BigDecimal("17.40").compareTo(db.queryForObject("SELECT amount_received FROM yc_vend_sale_record",BigDecimal.class)));
        assertEquals(7L,db.queryForObject("SELECT stock_owner_id FROM yc_vend_product WHERE id=8",Long.class));
        ProductImportDtos.Row next=new ProductImportDtos.Row();next.setProductName("外星人电解质水西柚600ml");next.setBarcode("BAR-8");next.setSourceCode(SalesProductService.aliasCode(next.getProductName()));
        new ProductCatalogMatcher(catalog(),aliases()).resolveForSales(next);assertEquals(7L,next.getExistingProductId());
        assertEquals(3,db.queryForObject("SELECT COUNT(*) FROM test_receipt WHERE confirmed=true",Integer.class));
        assertTrue(db.queryForObject("SELECT payload FROM test_receipt WHERE biz_date='2026-10-26'",String.class).contains("\"amount\":50"));
    }
    @Test void missingSupplierOrGroupConfirmationMakesNoChanges() throws Exception {
        SimplePreview p=preview();CommitRequest r=choice(p);r.setSupplierId(null);assertThrows(BizException.class,()->service.commit(r,"测试"));
        r.setSupplierId(1L);r.getGroups().clear();assertThrows(BizException.class,()->service.commit(r,"测试"));assertEquals(0,receiptsCount());
        assertEquals(8L,db.queryForObject("SELECT product_id FROM yc_vend_sale_record",Long.class));
    }
    @Test void failedLaterReceiptRollsBackMappingsAndEarlierReceiptsThenRetryIsIdempotent() throws Exception {
        CommitRequest r=choice(preview());failConfirm=true;assertThrows(BizException.class,()->service.commit(r,"测试"));assertEquals(0,receiptsCount());
        assertEquals(8L,db.queryForObject("SELECT product_id FROM yc_vend_sale_record",Long.class));assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM yc_vend_sku_alias",Integer.class));
        assertNull(db.queryForObject("SELECT result_json FROM yc_vend_purchase_workbook",String.class));
        failConfirm=false;CommitResult first=service.commit(r,"测试");CommitResult retry=service.commit(r,"测试");assertTrue(retry.isAlreadyImported());assertEquals(first.getReceiptIds(),retry.getReceiptIds());assertEquals(3,receiptsCount());
    }
    @Test void concurrentSubmitCreatesOnlyOneSetOfReceipts() throws Exception {
        CommitRequest r=choice(preview());ExecutorService threads=Executors.newFixedThreadPool(2);
        try{Future<CommitResult>a=threads.submit(()->service.commit(r,"甲"));Future<CommitResult>b=threads.submit(()->service.commit(r,"乙"));assertEquals(a.get(15,TimeUnit.SECONDS).getReceiptIds(),b.get(15,TimeUnit.SECONDS).getReceiptIds());assertEquals(3,receiptsCount());}finally{threads.shutdownNow();}
    }
    @Test void independentInventoryAndClosedSalesCannotBeSilentlyMerged() throws Exception {
        CommitRequest r=choice(preview());db.update("INSERT INTO yc_vend_stock_ledger(product_id) VALUES(8)");assertThrows(BizException.class,()->service.commit(r,"测试"));assertEquals(0,receiptsCount());
        db.update("DELETE FROM yc_vend_stock_ledger");db.update("INSERT INTO yc_vend_period_lock(period) VALUES('2026-10')");assertThrows(BizException.class,()->service.commit(r,"测试"));assertEquals(0,receiptsCount());
    }
    @Test void excludedExplicitFlavorKeepsItsOwnInventory() throws Exception {
        CommitRequest r=choice(preview());r.getGroups().get(0).setMemberIds(Arrays.asList(7L,8L));service.commit(r,"测试");
        assertNull(db.queryForObject("SELECT stock_owner_id FROM yc_vend_product WHERE id=22",Long.class));
        assertTrue(db.queryForObject("SELECT payload FROM test_receipt WHERE biz_date='2026-10-23'",String.class).contains("\"productId\":22"));
    }
    @Test void renamedWorkbookIsSameBatchAndRepeatPurchasesUseSavedMapping() throws Exception {
        SimplePreview first=preview();service.commit(choice(first),"测试");SimplePreview again=preview();assertEquals(first.getBatchKey(),again.getBatchKey());assertEquals(3,again.getReceiptIds().size());
        SimplePreview next=service.preview(file("以后.xlsx","2026-11-11|外星人电解质水|YQ000007|15|1|15|60|60","2026-11-11|外星人电解质水西柚600ml|YQ000008|15|1|15|60|60"));
        assertTrue(next.getGroups().isEmpty());assertTrue(next.getPreview().getDays().get(0).getErrors().isEmpty());service.commit(choice(next),"测试");assertEquals(4,receiptsCount());
    }
    @Test void invalidOriginalNumbersCannotBeBypassedByChoices() throws Exception {
        SimplePreview p=service.preview(file("错误.xlsx","2026-10-19|外星人电解质水|YQ000007|15|42|629|60|2520"));
        assertThrows(BizException.class,()->service.commit(choice(p),"测试"));assertEquals(0,receiptsCount());
    }
    @Test void actualUserFileProducesThreeDatesAndOnlyFamilyConfirmations() throws Exception {
        String input=System.getProperty("vend.test.purchaseFile"),catalogFile=System.getProperty("vend.test.purchaseCatalog");Assumptions.assumeTrue(input!=null&&catalogFile!=null);
        db.update("DELETE FROM yc_vend_sale_record");db.update("DELETE FROM yc_vend_product");
        for(Product p:json.readValue(Files.readAllBytes(Paths.get(catalogFile)),Product[].class))db.update("INSERT INTO yc_vend_product(id,sku_code,product_name,unit,box_spec,product_status) VALUES(?,?,?,?,?,?)",p.getId(),p.getSkuCode(),p.getProductName(),p.getUnit(),p.getBoxSpec(),p.getProductStatus());
        SimplePreview p=service.preview(new MockMultipartFile("file","元气森林拿货_已匹配统一商品编码.xlsx","",Files.readAllBytes(Paths.get(input))));
        assertEquals(37,p.getPreview().getRows().size());assertEquals(3,p.getGroups().size());
        Set<Integer> grouped=p.getGroups().stream().flatMap(g->g.getSourceRows().stream()).collect(Collectors.toSet());
        List<Integer> extra=p.getPreview().getRows().stream().filter(r->!grouped.contains(r.getRowNo())&&(r.isNeedsConfirmation()||!r.getErrors().isEmpty())).map(r->r.getRowNo()).collect(Collectors.toList());
        String fixture=System.getProperty("vend.test.simpleFixture");if(fixture!=null)Files.write(Paths.get(fixture),json.writeValueAsBytes(p));
        assertTrue(extra.isEmpty(),"其他需确认行："+extra);
        service.commit(choice(p),"真实文件隔离测试");assertEquals(3,receiptsCount());
        BigDecimal sum=BigDecimal.ZERO;for(String payload:db.queryForList("SELECT payload FROM test_receipt",String.class))for(ReceiptCreateReq.Item item:json.readValue(payload,ReceiptCreateReq.Item[].class))sum=sum.add(item.getAmount());
        assertEquals(0,new BigDecimal("13976").compareTo(sum));
    }
}
