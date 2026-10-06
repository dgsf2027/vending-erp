package top.aole.vend.modules.basedata;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.jdbc.core.JdbcTemplate;
import top.aole.vend.common.exception.BizException;
import top.aole.vend.modules.basedata.application.*;
import top.aole.vend.modules.basedata.domain.entity.Product;
import top.aole.vend.modules.basedata.interfaces.dto.Dtos;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@SpringBootTest
class ProductDeletionServiceTest {
    @Autowired ProductDeletionService deletion;
    @Autowired ProductService products;
    @Autowired AliasService aliases;
    @Autowired JdbcTemplate jdbc;
    @SpyBean OpLogService opLog;
    private final List<Long> ids = new ArrayList<>();

    Product create(String suffix) {
        Product p = new Product();
        p.setSkuCode("TSTDEL-" + suffix);
        p.setProductName("删除测试" + suffix);
        p.setRefPrice(new BigDecimal("3.30"));
        Product created = products.create(p, "删除测试");
        ids.add(created.getId());
        Dtos.BindAliasReq alias = new Dtos.BindAliasReq();
        alias.setProductId(created.getId());
        alias.setAliasCode(created.getSkuCode());
        alias.setAliasName(created.getProductName());
        aliases.bind(alias, "删除测试");
        return created;
    }

    @AfterEach void cleanup() {
        reset(opLog);
        for (Long id : ids) {
            jdbc.update("DELETE FROM yc_vend_stock_ledger WHERE product_id=?", id);
            jdbc.update("DELETE FROM yc_vend_sku_alias WHERE product_id=?", id);
            jdbc.update("DELETE FROM yc_vend_price_log WHERE product_id=?", id);
            jdbc.update("DELETE FROM yc_vend_op_log WHERE target_type='product' AND target_id=?", id);
            jdbc.update("DELETE FROM yc_vend_product WHERE id=?", id);
        }
        jdbc.update("DELETE FROM yc_vend_op_log WHERE user_name='删除测试'");
    }

    @Test void unused_product_deletes_dependencies_audits_and_can_be_reimported() {
        Product p = create("FREE");
        deletion.delete(p.getId(), "删除测试");
        for (String table : new String[]{"yc_vend_product", "yc_vend_sku_alias", "yc_vend_price_log"}) {
            String column = table.equals("yc_vend_product") ? "id" : "product_id";
            assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE " + column + "=?", Integer.class, p.getId()));
        }
        String before = jdbc.queryForObject("SELECT before_json FROM yc_vend_op_log WHERE action='删除商品' AND target_id=?", String.class, p.getId());
        assertTrue(before.contains(p.getSkuCode()));
        assertTrue(before.contains("aliases"));
        assertThrows(BizException.class, () -> deletion.delete(p.getId(), "删除测试"));
        Product recreated = create("FREE");
        assertNotEquals(p.getId(), recreated.getId());
    }

    @Test void zero_stock_with_archived_history_still_cannot_be_deleted() {
        Product p = create("USED");
        jdbc.update("INSERT INTO yc_vend_stock_ledger(product_id,doc_id,change_qty,balance_qty,biz_time,is_deleted) VALUES(?,0,0,0,NOW(),1)", p.getId());
        BizException error = assertThrows(BizException.class, () -> deletion.delete(p.getId(), "删除测试"));
        assertTrue(error.getMessage().contains("库存流水"));
        assertNotNull(products.getById(p.getId()));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM yc_vend_sku_alias WHERE product_id=?", Integer.class, p.getId()));
    }

    @Test void audit_failure_rolls_back_product_and_alias_removal() {
        Product p = create("ROLLBACK");
        doThrow(new IllegalStateException("audit unavailable")).when(opLog)
                .record(anyString(), eq("删除商品"), eq("product"), anyLong(), any(), isNull());
        assertThrows(IllegalStateException.class, () -> deletion.delete(p.getId(), "删除测试"));
        assertNotNull(products.getById(p.getId()));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM yc_vend_sku_alias WHERE product_id=?", Integer.class, p.getId()));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM yc_vend_price_log WHERE product_id=?", Integer.class, p.getId()));
    }
}
