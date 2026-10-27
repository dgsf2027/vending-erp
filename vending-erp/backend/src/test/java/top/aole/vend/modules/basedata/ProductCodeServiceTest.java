package top.aole.vend.modules.basedata;

import org.junit.jupiter.api.Test;
import org.h2.jdbcx.JdbcDataSource;
import org.apache.ibatis.session.*;
import org.apache.ibatis.mapping.Environment;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import top.aole.vend.modules.basedata.application.ProductCodeService;
import top.aole.vend.modules.basedata.infrastructure.mapper.ProductCodeMapper;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class ProductCodeServiceTest {
    @Test void concurrentTransactionsAllocateUniqueCodesAfterExistingAndDeletedCodes() throws Exception {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:codes;MODE=MySQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000");
        JdbcTemplate db = new JdbcTemplate(ds);
        db.execute("CREATE TABLE yc_vend_code_sequence(sequence_key varchar(32) primary key,next_value bigint not null)");
        db.update("INSERT INTO yc_vend_code_sequence VALUES('product_sku',0)");
        db.execute("CREATE TABLE yc_vend_product(sku_code varchar(32) unique,is_deleted int default 0)");
        db.update("INSERT INTO yc_vend_product VALUES('SP000500',1),('sp700',0),('LEGACY123',0)");
        Configuration config = new Configuration(new Environment("test", new SpringManagedTransactionFactory(), ds));
        config.addMapper(ProductCodeMapper.class);
        SqlSessionTemplate session = new SqlSessionTemplate(new SqlSessionFactoryBuilder().build(config));
        ProductCodeService service = new ProductCodeService(session.getMapper(ProductCodeMapper.class));
        TransactionTemplate transaction = new TransactionTemplate(new DataSourceTransactionManager(ds));
        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            List<Future<String>> futures = new ArrayList<>();
            for (int i = 0; i < 24; i++) futures.add(pool.submit(() -> transaction.execute(s -> {
                String code = service.nextCode(); db.update("INSERT INTO yc_vend_product(sku_code) VALUES(?)", code); return code;
            })));
            Set<String> values = new HashSet<>();
            for (Future<String> future : futures) values.add(future.get(20, TimeUnit.SECONDS));
            assertEquals(24, values.size()); assertTrue(values.contains("SP000701")); assertTrue(values.contains("SP000724"));
            assertThrows(IllegalStateException.class, () -> transaction.execute(s -> {
                service.nextCode(); throw new IllegalStateException("rollback");
            }));
            assertEquals("SP000725", transaction.execute(s -> service.nextCode()));
        } finally { pool.shutdownNow(); }
    }
}
