package top.aole.vend.modules.imports.service;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.h2.jdbcx.JdbcDataSource;
import org.apache.ibatis.session.*;
import org.apache.ibatis.mapping.Environment;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import top.aole.vend.common.exception.BizException;
import top.aole.vend.modules.basedata.application.*;
import top.aole.vend.modules.basedata.domain.entity.*;
import top.aole.vend.modules.basedata.infrastructure.mapper.*;
import top.aole.vend.modules.basedata.interfaces.dto.Dtos;
import top.aole.vend.modules.imports.domain.entity.ImportBatch;
import top.aole.vend.modules.imports.dto.ImportDtos.*;
import top.aole.vend.modules.imports.mapper.*;
import top.aole.vend.modules.imports.parser.ExcelParser;
import top.aole.vend.modules.doc.mapper.DocHeadMapper;
import top.aole.vend.modules.doc.service.DocService;
import top.aole.vend.modules.prekit.service.PrekitService;
import top.aole.vend.modules.settle.service.SettleBillService;
import top.aole.vend.modules.stock.domain.entity.SaleRecord;
import top.aole.vend.modules.stock.mapper.*;
import top.aole.vend.modules.stock.service.StockService;
import java.io.*;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

/** Real Excel ingestion, matcher and sequence SQL, with JDBC-backed stores and real rollback/locking. */
class SalesProductAutoCreateTest {
    @TempDir Path files;
    JdbcTemplate db;
    TransactionTemplate tx;
    ImportService service;
    ProductService products;
    AliasService aliases;
    AliasPendingMapper pending;
    Map<Long,ImportBatch> batches = new ConcurrentHashMap<>();
    static final String[] HEADERS = {"订单号","商品名称","出货数量","商品金额(元)","设备ID","订单类型","出货时间","商品条形码","商品分类"};

    @BeforeEach void setup() {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:"+UUID.randomUUID()+";MODE=MySQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=20000");
        db = new JdbcTemplate(ds);
        tx = new TransactionTemplate(new DataSourceTransactionManager(ds));
        tx.setIsolationLevel(org.springframework.transaction.TransactionDefinition.ISOLATION_READ_COMMITTED);
        db.execute("CREATE TABLE yc_vend_code_sequence(sequence_key varchar(32) primary key,next_value bigint not null)");
        db.update("INSERT INTO yc_vend_code_sequence VALUES('product_sku',19),('sales_product_yq',25)");
        db.execute("CREATE TABLE yc_vend_product(id bigint auto_increment primary key,sku_code varchar(32) unique,name varchar(128),barcode varchar(64),unit varchar(16),pack decimal(12,3),category varchar(32),is_deleted int default 0)");
        db.execute("CREATE TABLE alias(code varchar(64),barcode varchar(64),name varchar(128),product_id bigint,unique(code,barcode))");
        db.execute("CREATE TABLE sale(order_no varchar(128),order_type varchar(20),product_id bigint,qty decimal(12,3),amount decimal(12,3),unique(order_no,order_type))");
        Configuration config = new Configuration(new Environment("test",new SpringManagedTransactionFactory(),ds));
        config.addMapper(ProductCodeMapper.class);
        ProductCodeService codes = new ProductCodeService(new SqlSessionTemplate(new SqlSessionFactoryBuilder().build(config)).getMapper(ProductCodeMapper.class));
        ProductMapper pm = mock(ProductMapper.class);
        when(pm.selectList(any())).thenAnswer(i -> db.query("SELECT * FROM yc_vend_product WHERE is_deleted=0",(r,n) -> {
            Product p = new Product(); p.setId(r.getLong("id")); p.setSkuCode(r.getString("sku_code"));
            p.setProductName(r.getString("name")); p.setBarcode(r.getString("barcode")); p.setUnit(r.getString("unit"));
            p.setBoxSpec(r.getBigDecimal("pack")); p.setCategory(r.getString("category")); return p;
        }));
        products = mock(ProductService.class);
        when(products.create(any(),anyString())).thenAnswer(i -> {
            Product p = i.getArgument(0);
            assertEquals("件",p.getUnit()); assertEquals(0,p.getBoxSpec().signum());
            assertNull(p.getRefCost()); assertNull(p.getRefPrice());
            db.update("INSERT INTO yc_vend_product(sku_code,name,barcode,unit,pack,category) VALUES(?,?,?,?,?,?)",
                p.getSkuCode(),p.getProductName(),p.getBarcode(),p.getUnit(),p.getBoxSpec(),p.getCategory());
            p.setId(db.queryForObject("SELECT id FROM yc_vend_product WHERE sku_code=?",Long.class,p.getSkuCode())); return p;
        });
        when(products.getById(anyLong())).thenAnswer(i -> pm.selectList(null).stream().filter(p -> p.getId().equals(i.getArgument(0))).findFirst().get());
        SkuAliasMapper am = mock(SkuAliasMapper.class);
        when(am.selectList(any())).thenAnswer(i -> db.query("SELECT * FROM alias",(r,n) -> {
            SkuAlias a = new SkuAlias(); a.setProductId(r.getLong("product_id")); a.setAliasCode(r.getString("code"));
            a.setAliasBarcode(r.getString("barcode")); a.setAliasName(r.getString("name")); return a;
        }));
        aliases = mock(AliasService.class);
        when(aliases.bind(any(),anyString())).thenAnswer(i -> {
            Dtos.BindAliasReq a = i.getArgument(0);
            if ("故障商品".equals(a.getAliasName())) throw new BizException("simulated alias failure");
            db.update("MERGE INTO alias(code,barcode,name,product_id) KEY(code,barcode) VALUES(?,?,?,?)",
                a.getAliasCode(),a.getAliasBarcode(),a.getAliasName(),a.getProductId()); return new SkuAlias();
        });
        pending = mock(AliasPendingMapper.class);
        when(pending.insert(any())).thenAnswer(i -> { ((AliasPending)i.getArgument(0)).setId(1L); return 1; });
        ImportBatchMapper bm = mock(ImportBatchMapper.class);
        AtomicLong ids = new AtomicLong();
        when(bm.insert(any())).thenAnswer(i -> { ImportBatch b=i.getArgument(0); b.setId(ids.incrementAndGet()); batches.put(b.getId(),b); return 1; });
        when(bm.selectById(any())).thenAnswer(i -> batches.get(i.getArgument(0)));
        ImportQueryMapper queries = mock(ImportQueryMapper.class);
        when(queries.existingSaleKeys()).thenAnswer(i -> db.query("SELECT order_no,order_type FROM sale",(r,n) -> {
            Map<String,Object> m = new HashMap<>(); m.put("orderNo",r.getString(1)); m.put("orderType",r.getString(2)); return m;
        }));
        SaleRecordMapper sales = mock(SaleRecordMapper.class);
        when(sales.insert(any())).thenAnswer(i -> {
            SaleRecord s=i.getArgument(0);
            return db.update("INSERT INTO sale VALUES(?,?,?,?,?)",s.getOrderNo(),s.getOrderType(),s.getProductId(),s.getQty(),s.getAmountReceived());
        });
        MachineMapper machines = mock(MachineMapper.class);
        Machine machine = new Machine(); machine.setId(1L); machine.setDeviceId("D");
        when(machines.selectList(any())).thenReturn(Collections.singletonList(machine));
        service = new ImportService(new ExcelParser(),new AliasMatcher(am,pending),new SalesProductService(pm,am,products,aliases,codes),
            aliases,mock(DocService.class),mock(StockService.class),mock(SettleBillService.class),mock(OpLogService.class),mock(PrekitService.class),
            bm,mock(ImportErrorMapper.class),queries,sales,machines,pm,mock(PriceLogMapper.class),mock(DocHeadMapper.class),
            mock(StockLedgerMapper.class),mock(MachineStockSnapshotMapper.class));
        ReflectionTestUtils.setField(service,"storageDir",files.toString());
    }
    byte[] excel(String[] headers,String[]... rows) throws Exception {
        try (XSSFWorkbook wb=new XSSFWorkbook(); ByteArrayOutputStream out=new ByteArrayOutputStream()) {
            org.apache.poi.ss.usermodel.Sheet s=wb.createSheet("销售");
            for(int r=0;r<=rows.length;r++) {
                org.apache.poi.ss.usermodel.Row row=s.createRow(r);
                String[] values=r==0?headers:rows[r-1];
                for(int c=0;c<values.length;c++) row.createCell(c).setCellValue(values[c]);
            }
            wb.write(out); return out.toByteArray();
        }
    }
    String[] row(String order,String name) { return new String[]{order,name,"1","5","D","正常订单","2026-11-03 10:00:00","","饮料"}; }
    PreviewResp upload(byte[] data) { return service.upload(ImportBatch.TYPE_SALE,"销售.xlsx",data); }
    CommitResp commit(PreviewResp p) { return tx.execute(s -> service.confirm(p.getToken(),"测试")); }
    int count(String table) { return db.queryForObject("SELECT count(*) FROM "+table,Integer.class); }
    long sequence() { return db.queryForObject("SELECT next_value FROM yc_vend_code_sequence WHERE sequence_key='sales_product_yq'",Long.class); }

    @Test void previewHasNoWritesAndRepeatedImportsReuseCodesAndPersistHistory() throws Exception {
        byte[] data=excel(HEADERS,row("A","冰茶柚子500ml"),row("B","冰茶柚子500ml"));
        PreviewResp p=upload(data); assertTrue(p.isColumnsOk()); assertEquals(0,count("yc_vend_product")); assertEquals(25,sequence());
        CommitResp first=commit(p); assertEquals(2,first.getRowOk()); assertEquals(1,first.getAutoCreatedProducts().size());
        assertEquals("YQ000026",first.getAutoCreatedProducts().get(0).getSkuCode()); assertEquals(1,count("alias"));
        assertTrue(batches.get(first.getBatchId()).getColumnMapJson().contains("YQ000026"));
        CommitResp repeat=commit(upload(data)); assertEquals(2,repeat.getRowDup()); assertTrue(repeat.getAutoCreatedProducts().isEmpty());
        assertEquals(26,sequence()); assertEquals(2,count("sale"));
        CommitResp next=commit(upload(excel(HEADERS,row("C","冰茶蜜桃500ml"))));
        assertEquals("YQ000027",next.getAutoCreatedProducts().get(0).getSkuCode());
        verify(products,never()).update(anyLong(),any(),anyString());
    }
    @Test void existingCodeIsReusedAndConflictingNameRequiresBinding() throws Exception {
        db.update("INSERT INTO yc_vend_product(sku_code,name,barcode,unit,pack) VALUES('SP000001','原商品500ml','001','瓶',12),('YQ000009','同名商品500ml','002','瓶',24),('SP000002','同名商品500ml','003','瓶',24)");
        CommitResp result=commit(upload(excel(HEADERS,row("A","原商品500ml"),row("B","同名商品500ml"))));
        assertEquals(2,result.getRowOk()); assertEquals(1,result.getPendingBind()); assertTrue(result.getAutoCreatedProducts().isEmpty());
        assertEquals(25,sequence()); assertEquals(3,count("yc_vend_product"));
        assertEquals(1,db.queryForObject("SELECT count(*) FROM sale WHERE product_id IS NOT NULL",Integer.class));
        org.mockito.ArgumentCaptor<AliasPending> capture=org.mockito.ArgumentCaptor.forClass(AliasPending.class);
        verify(pending).insert(capture.capture()); assertTrue(capture.getValue().getAliasCode().startsWith("SALE/"));
    }
    @Test void concurrentImportsOfSameNameAndSameOrderCreateOnlyOneRecord() throws Exception {
        byte[] data=excel(HEADERS,row("A","并发新品500ml"));
        PreviewResp a=upload(data),b=upload(data);
        ExecutorService pool=Executors.newFixedThreadPool(2);
        try {
            Future<CommitResp> x=pool.submit(() -> commit(a)),y=pool.submit(() -> commit(b));
            CommitResp first=x.get(20,TimeUnit.SECONDS),second=y.get(20,TimeUnit.SECONDS);
            assertEquals(1,first.getRowOk()+second.getRowOk()); assertEquals(1,first.getRowDup()+second.getRowDup());
            assertEquals(1,count("yc_vend_product")); assertEquals(26,sequence()); assertEquals(1,count("sale"));
        } finally { pool.shutdownNow(); }
    }
    @Test void badRowsDoNotAllocateCodesAndUnknownRefundsOrTestsStayPending() throws Exception {
        String[] badMachine=row("A","未知机商品"); badMachine[4]="MISSING";
        String[] badQty=row("B","零数量商品"); badQty[2]="0";
        String[] refund=row("C","退款未知商品"); refund[5]="退款订单";
        String[] test=row("D","测试未知商品"); test[5]="测试订单";
        CommitResp r=commit(upload(excel(HEADERS,badMachine,badQty,refund,test)));
        assertEquals(2,r.getRowFail()); assertEquals(2,r.getPendingBind()); assertEquals(0,count("yc_vend_product")); assertEquals(25,sequence());
    }
    @Test void concurrentDifferentOrdersReuseTheFirstCommittedProduct() throws Exception {
        PreviewResp a=upload(excel(HEADERS,row("A","同一新品500ml"))),b=upload(excel(HEADERS,row("B","同一新品500ml")));
        ExecutorService pool=Executors.newFixedThreadPool(2);
        try {
            Future<CommitResp> x=pool.submit(() -> commit(a)),y=pool.submit(() -> commit(b));
            CommitResp first=x.get(20,TimeUnit.SECONDS),second=y.get(20,TimeUnit.SECONDS);
            assertEquals(2,first.getRowOk()+second.getRowOk());
            assertEquals(1,first.getAutoCreatedProducts().size()+second.getAutoCreatedProducts().size());
            assertEquals(1,count("yc_vend_product")); assertEquals(26,sequence()); assertEquals(2,count("sale"));
            assertEquals(1,db.queryForObject("SELECT count(DISTINCT product_id) FROM sale",Integer.class));
        } finally { pool.shutdownNow(); }
    }
    @Test void manuallyResolvedNameConflictIsRememberedInLaterSales() throws Exception {
        db.update("INSERT INTO yc_vend_product(sku_code,name,barcode,unit,pack) VALUES('SP000001','同名商品500ml','001','瓶',12),('SP000002','同名商品500ml','002','瓶',24)");
        long id=db.queryForObject("SELECT id FROM yc_vend_product WHERE sku_code='SP000002'",Long.class);
        db.update("INSERT INTO alias VALUES(?,?,?,?)",SalesProductService.aliasCode("同名商品500ml"),"","同名商品500ml",id);
        CommitResp result=commit(upload(excel(HEADERS,row("A","同名商品500ml"))));
        assertEquals(1,result.getRowOk()); assertEquals(0,result.getPendingBind()); assertEquals(25,sequence());
        assertEquals(id,db.queryForObject("SELECT product_id FROM sale",Long.class));
    }
    @Test void storageFailureRollsBackAllNewProductsAliasesSalesAndSequence() throws Exception {
        PreviewResp p=upload(excel(HEADERS,row("A","成功后也应撤销"),row("B","故障商品")));
        assertThrows(BizException.class,() -> commit(p));
        assertEquals(0,count("yc_vend_product")); assertEquals(0,count("alias")); assertEquals(0,count("sale")); assertEquals(25,sequence());
    }
    @Test void yuanqiSalesHeadersAreRecognizedButNegativeRowsAreNotInventedAsNormalSales() throws Exception {
        String[] headers={"点位名称","柜子编码","订单时间","订单编号","商品名称","支付时间","支付金额","优惠券抵扣金额","销售数量","支付渠道（微信/支付宝）","品类","品类编码"};
        byte[] data=excel(headers,new String[]{"测试机","D","2026-11-03 10:00:00","A","元气新品500ml","","5","0","1","微信","饮料","1"},
            new String[]{"测试机","D","2026-11-03 10:00:00","B","不确定退款","","-5","0","-1","微信","饮料","1"});
        PreviewResp p=upload(data); assertTrue(p.isColumnsOk()); assertTrue(p.getWarnings().stream().anyMatch(w -> w.contains("元气")));
        CommitResp result=commit(p); assertEquals(1,result.getRowOk()); assertEquals(1,result.getRowFail());
        assertEquals("饮料",db.queryForObject("SELECT category FROM yc_vend_product",String.class));
        assertEquals("YQ000026",result.getAutoCreatedProducts().get(0).getSkuCode());
    }
}
