package top.aole.vend.modules.basedata;

import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import top.aole.vend.common.exception.BizException;
import top.aole.vend.modules.basedata.application.*;
import top.aole.vend.modules.basedata.domain.entity.*;
import top.aole.vend.modules.basedata.infrastructure.mapper.ProductMapper;
import top.aole.vend.modules.basedata.interfaces.dto.*;
import top.aole.vend.modules.imports.service.SalesProductService;
import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 独立内存数据库验证真实归并 SQL、事务回滚及销售再次导入匹配。 */
class ProductLinkServiceTest {
    JdbcTemplate db; ProductLinkService service; OpLogService logs;
    @BeforeEach void setup() {
        DriverManagerDataSource ds=new DriverManagerDataSource("jdbc:h2:mem:"+UUID.randomUUID()+";MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE","sa","");
        db=new JdbcTemplate(ds);
        db.execute("CREATE TABLE yc_vend_product(id bigint PRIMARY KEY,tenant_id varchar DEFAULT '000000',sku_code varchar,product_name varchar,barcode varchar,unit varchar,box_spec decimal(18,3),ref_price decimal,ref_cost decimal,product_status varchar,stock_owner_id bigint,shared_stock boolean DEFAULT false,remark varchar,is_deleted int DEFAULT 0)");
        db.execute("CREATE TABLE yc_vend_sku_alias(id bigint AUTO_INCREMENT PRIMARY KEY,tenant_id varchar DEFAULT '000000',product_id bigint,alias_code varchar,alias_barcode varchar DEFAULT '',alias_name varchar,is_deleted int DEFAULT 0)");
        db.execute("CREATE TABLE yc_vend_sale_record(id bigint AUTO_INCREMENT PRIMARY KEY,tenant_id varchar DEFAULT '000000',product_id bigint,alias_name_raw varchar,qty decimal,amount_received decimal(18,2),settlement_id bigint,biz_period varchar,book_period varchar,is_deleted int DEFAULT 0)");
        db.execute("CREATE TABLE yc_vend_period_lock(id bigint AUTO_INCREMENT PRIMARY KEY,tenant_id varchar DEFAULT '000000',period varchar,is_deleted int DEFAULT 0)");
        for(String t:new String[]{"doc_item","stock_ledger","machine_stock_snapshot","purchase_order_item","stocktake_item","prekit_ticket_item","replenish_plan","replenish_config","slot"})
            db.execute("CREATE TABLE yc_vend_"+t+"(id bigint AUTO_INCREMENT PRIMARY KEY,tenant_id varchar DEFAULT '000000',product_id bigint,is_deleted int DEFAULT 0)");
        db.execute("CREATE TABLE test_lock(id int PRIMARY KEY)");db.update("INSERT INTO test_lock VALUES(1)");
        db.update("INSERT INTO yc_vend_product(id,sku_code,product_name,unit,box_spec,product_status,ref_price,ref_cost) VALUES(1,'YQ000001','达利园青梅绿茶500ml','瓶',15,'在售',3,2)");
        db.update("INSERT INTO yc_vend_product(id,sku_code,product_name,barcode,unit,box_spec,product_status,remark) VALUES(26,'YQ000026','500ml达利园青梅绿茶','BAR26','件',0,'在售','销售导入自动建档；箱规待补')");
        db.update("INSERT INTO yc_vend_sale_record(product_id,alias_name_raw,qty,amount_received,biz_period,book_period) VALUES(26,'500ml达利园青梅绿茶',3,8.4,'2026-11','2026-11')");
        db.update("INSERT INTO yc_vend_sku_alias(product_id,alias_code,alias_name) VALUES(26,'BACKEND-26','500ml达利园青梅绿茶')");
        ProductMapper products=mock(ProductMapper.class);when(products.selectById(any())).thenAnswer(i->catalog().stream().filter(p->p.getId().equals(i.getArgument(0))).findFirst().orElse(null));when(products.selectList(any())).thenAnswer(i->catalog());
        ProductCodeService codes=mock(ProductCodeService.class);when(codes.lock()).thenAnswer(i->db.queryForObject("SELECT id FROM test_lock WHERE id=1 FOR UPDATE",Long.class));
        AliasService aliases=mock(AliasService.class);when(aliases.bind(any(),anyString())).thenAnswer(i->{Dtos.BindAliasReq r=i.getArgument(0);String bar=r.getAliasBarcode()==null?"":r.getAliasBarcode();
            int n=db.update("UPDATE yc_vend_sku_alias SET product_id=? WHERE alias_code=? AND alias_barcode=?",r.getProductId(),r.getAliasCode(),bar);
            if(n==0)db.update("INSERT INTO yc_vend_sku_alias(product_id,alias_code,alias_barcode,alias_name) VALUES(?,?,?,?)",r.getProductId(),r.getAliasCode(),bar,r.getAliasName());return new SkuAlias();});
        logs=mock(OpLogService.class);
        ProxyFactory proxy=new ProxyFactory(new ProductLinkService(db,products,codes,aliases,logs));proxy.setProxyTargetClass(true);
        proxy.addAdvice(new TransactionInterceptor(new DataSourceTransactionManager(ds),new AnnotationTransactionAttributeSource()));service=(ProductLinkService)proxy.getProxy();
    }
    List<Product> catalog(){return db.query("SELECT * FROM yc_vend_product ORDER BY id",(r,n)->{Product p=new Product();p.setId(r.getLong("id"));p.setTenantId(r.getString("tenant_id"));p.setSkuCode(r.getString("sku_code"));p.setProductName(r.getString("product_name"));p.setBarcode(r.getString("barcode"));p.setUnit(r.getString("unit"));p.setBoxSpec(r.getBigDecimal("box_spec"));p.setRefPrice(r.getBigDecimal("ref_price"));p.setRefCost(r.getBigDecimal("ref_cost"));p.setProductStatus(r.getString("product_status"));p.setStockOwnerId((Long)r.getObject("stock_owner_id"));p.setSharedStock(r.getBoolean("shared_stock"));p.setRemark(r.getString("remark"));return p;});}
    void commit(){ProductLinkService.Preview p=service.preview(26L,1L);assertTrue(p.isExecutable(),p.getBlockers().toString());service.link(26L,1L,p.getToken(),"测试");}
    void untouched(){assertNull(db.queryForObject("SELECT stock_owner_id FROM yc_vend_product WHERE id=26",Long.class));assertEquals(26L,db.queryForObject("SELECT product_id FROM yc_vend_sale_record",Long.class));}
    @Test void linksDuplicateAndNextSaleUsesOldCodeWithoutChangingAmountsOrStock(){
        assertEquals(1L,service.candidates(26L).get(0).getId());
        ProductLinkService.Preview p=service.preview(26L,1L);assertEquals(1,p.getSalesCount());untouched();commit();
        assertEquals(1L,db.queryForObject("SELECT stock_owner_id FROM yc_vend_product WHERE id=26",Long.class));
        assertEquals(1L,db.queryForObject("SELECT product_id FROM yc_vend_sale_record",Long.class));
        assertEquals("500ml达利园青梅绿茶",db.queryForObject("SELECT alias_name_raw FROM yc_vend_sale_record",String.class));
        assertEquals(0,new BigDecimal("8.40").compareTo(db.queryForObject("SELECT amount_received FROM yc_vend_sale_record",BigDecimal.class)));
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM yc_vend_stock_ledger",Integer.class));
        assertEquals("达利园青梅绿茶500ml",catalog().get(0).getProductName());assertEquals(0,new BigDecimal("2").compareTo(catalog().get(0).getRefCost()));
        List<SkuAlias> aliases=db.query("SELECT * FROM yc_vend_sku_alias",(r,n)->{SkuAlias a=new SkuAlias();a.setProductId(r.getLong("product_id"));a.setAliasCode(r.getString("alias_code"));a.setAliasName(r.getString("alias_name"));a.setAliasBarcode(r.getString("alias_barcode"));return a;});
        for(String code:new String[]{"YQ000026","BACKEND-26",SalesProductService.aliasCode("500ml达利园青梅绿茶")}){
            ProductImportDtos.Row row=new ProductImportDtos.Row();row.setProductName("500ml达利园青梅绿茶");row.setSourceCode(code);row.setBarcode("BAR26");new ProductCatalogMatcher(catalog(),aliases).resolveForSales(row);
            assertEquals("已匹配",row.getAction(),row.getErrorMsg());assertEquals("YQ000001",row.getSkuCode());
        }
        int before=db.queryForObject("SELECT COUNT(*) FROM yc_vend_sku_alias",Integer.class);service.link(26L,1L,p.getToken(),"重试");assertEquals(before,db.queryForObject("SELECT COUNT(*) FROM yc_vend_sku_alias",Integer.class));
    }
    @ParameterizedTest @ValueSource(strings={"doc_item","stock_ledger","machine_stock_snapshot","purchase_order_item","stocktake_item","prekit_ticket_item","replenish_plan","replenish_config","slot"})
    void refusesExistingIndependentRecordsEvenArchived(String table){db.update("INSERT INTO yc_vend_"+table+"(product_id,is_deleted) VALUES(26,1)");assertFalse(service.preview(26L,1L).isExecutable());assertThrows(BizException.class,()->service.link(26L,1L,"旧预览","测试"));untouched();}
    @Test void refusesSettledAndLockedSales(){db.update("UPDATE yc_vend_sale_record SET settlement_id=1");assertFalse(service.preview(26L,1L).isExecutable());db.update("UPDATE yc_vend_sale_record SET settlement_id=NULL");db.update("INSERT INTO yc_vend_period_lock(period) VALUES('2026-11')");assertFalse(service.preview(26L,1L).isExecutable());untouched();}
    @Test void refusesSelfCapacityAndExistingOwner(){assertFalse(service.preview(26L,26L).isExecutable());db.update("UPDATE yc_vend_product SET product_name='青梅绿茶1L' WHERE id=26");assertFalse(service.preview(26L,1L).isExecutable());db.update("UPDATE yc_vend_product SET stock_owner_id=99 WHERE id=1");assertFalse(service.preview(26L,1L).isExecutable());untouched();}
    @Test void refusesAliasHijack(){db.update("INSERT INTO yc_vend_sku_alias(product_id,alias_code) VALUES(99,'YQ000026')");assertFalse(service.preview(26L,1L).isExecutable());untouched();}
    @Test void stalePreviewCannotChangeNewSales(){String token=service.preview(26L,1L).getToken();db.update("UPDATE yc_vend_sale_record SET qty=4");assertThrows(BizException.class,()->service.link(26L,1L,token,"测试"));untouched();}
    @Test void rollbackIncludesAliasesSalesAndProducts(){String token=service.preview(26L,1L).getToken();doThrow(new RuntimeException("模拟日志失败")).when(logs).record(anyString(),anyString(),anyString(),anyLong(),any(),any());assertThrows(RuntimeException.class,()->service.link(26L,1L,token,"测试"));untouched();assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM yc_vend_sku_alias",Integer.class));}
    @Test void simultaneousRetryLinksOnlyOnce() throws Exception {String token=service.preview(26L,1L).getToken();ExecutorService pool=Executors.newFixedThreadPool(2);try{List<Future<?>> jobs=new ArrayList<>();for(int n=0;n<2;n++)jobs.add(pool.submit(()->service.link(26L,1L,token,"测试")));for(Future<?> job:jobs)job.get(10,TimeUnit.SECONDS);assertEquals(1L,db.queryForObject("SELECT product_id FROM yc_vend_sale_record",Long.class));verify(logs,times(1)).record(anyString(),anyString(),anyString(),anyLong(),any(),any());}finally{pool.shutdownNow();}}
    @Test void enteringOriginalProductAutomaticallyKeepsItsCodeAndExistingInventory() {
        db.update("INSERT INTO yc_vend_doc_item(product_id) VALUES(1)");
        db.update("INSERT INTO yc_vend_stock_ledger(product_id) VALUES(1)");
        ProductLinkService.Preview p=service.preview(1L,26L);
        assertTrue(p.isExecutable(),p.getBlockers().toString());
        assertEquals(1L,p.getRequestedSourceId());assertEquals(26L,p.getRequestedTargetId());
        assertEquals(26L,p.getSource().getId());assertEquals(1L,p.getTarget().getId());
        service.link(1L,26L,p.getToken(),"从旧商品进入");
        assertEquals(1L,db.queryForObject("SELECT product_id FROM yc_vend_stock_ledger",Long.class));
        assertEquals(1L,db.queryForObject("SELECT product_id FROM yc_vend_doc_item",Long.class));
        assertEquals(1L,db.queryForObject("SELECT product_id FROM yc_vend_sale_record",Long.class));
        assertEquals("YQ000001",catalog().get(0).getSkuCode());assertEquals("瓶",catalog().get(0).getUnit());
        assertEquals(0,new BigDecimal("15").compareTo(catalog().get(0).getBoxSpec()));
        service.link(1L,26L,p.getToken(),"重试");
        verify(logs,times(1)).record(anyString(),anyString(),anyString(),anyLong(),any(),any());
    }
    @Test void completeDuplicateProductsKeepEarlierCodeFromEitherEntry() {
        db.update("UPDATE yc_vend_product SET unit='瓶',box_spec=15 WHERE id=26");
        assertEquals(1L,service.preview(1L,26L).getTarget().getId());
        assertEquals(1L,service.preview(26L,1L).getTarget().getId());
    }
    @Test void bothProductsWithInventoryRemainBlockedFromEitherEntry() {
        db.update("INSERT INTO yc_vend_stock_ledger(product_id) VALUES(1),(26)");
        assertFalse(service.preview(1L,26L).isExecutable());assertFalse(service.preview(26L,1L).isExecutable());
        assertThrows(BizException.class,()->service.link(1L,26L,"旧预览","测试"));untouched();
    }
    @Test void reverseEntryTokenCannotAuthorizeAnotherPairOrChangedSales() {
        String token=service.preview(1L,26L).getToken();
        assertThrows(BizException.class,()->service.link(26L,1L,token,"测试"));
        db.update("UPDATE yc_vend_sale_record SET qty=5");
        assertThrows(BizException.class,()->service.link(1L,26L,token,"测试"));untouched();
    }
}
