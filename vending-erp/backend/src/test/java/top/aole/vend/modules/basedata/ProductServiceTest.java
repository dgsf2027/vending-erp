package top.aole.vend.modules.basedata;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import top.aole.vend.common.exception.BizException;
import top.aole.vend.modules.basedata.application.ProductService;
import top.aole.vend.modules.basedata.domain.entity.PriceLog;
import top.aole.vend.modules.basedata.domain.entity.Product;
import top.aole.vend.modules.basedata.infrastructure.mapper.PriceLogMapper;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 商品档案 service 真库单测(vend_dev @3308)。
 * 测试数据以 TSTP- 前缀自造,@AfterEach 物理清理(含 op_log/price_log)。
 */
@SpringBootTest
class ProductServiceTest {

    private static final String P = "TSTP-";

    @Autowired
    private ProductService productService;
    @Autowired
    private PriceLogMapper priceLogMapper;
    @Autowired
    private JdbcTemplate jdbc;

    @AfterEach
    void cleanup() {
        jdbc.update("DELETE pl FROM yc_vend_price_log pl JOIN yc_vend_product p ON pl.product_id = p.id WHERE p.sku_code LIKE ?", P + "%");
        jdbc.update("DELETE ol FROM yc_vend_op_log ol JOIN yc_vend_product p ON ol.target_id = p.id AND ol.target_type = 'product' WHERE p.sku_code LIKE ?", P + "%");
        jdbc.update("DELETE FROM yc_vend_product WHERE sku_code LIKE ?", P + "%");
    }

    private Product newProduct(String code) {
        Product p = new Product();
        p.setSkuCode(code);
        p.setProductName("测试商品-" + code);
        p.setCategory("饮料");
        p.setUnit("瓶");
        p.setBoxSpec(new BigDecimal("24"));
        p.setRefPrice(new BigDecimal("4.5000"));
        return p;
    }

    @Test
    void create_then_page_and_detail() {
        Product created = productService.create(newProduct(P + "001"), "单测");
        assertNotNull(created.getId());
        assertEquals("在售", created.getProductStatus());

        Page<Product> page = productService.page(1, 10, P + "001", null, null);
        assertEquals(1, page.getRecords().size());
        assertEquals(P + "001", page.getRecords().get(0).getSkuCode());

        // 重复编码必须拦截
        BizException dup = assertThrows(BizException.class,
                () -> productService.create(newProduct(P + "001"), "单测"));
        assertTrue(dup.getMessage().contains("已存在"));

        // 新建带售价 → price_log 有初始记录
        List<PriceLog> logs = priceLogMapper.selectList(new LambdaQueryWrapper<PriceLog>()
                .eq(PriceLog::getProductId, created.getId()));
        assertEquals(1, logs.size());
    }

    @Test
    void update_price_writes_price_log_and_op_log() {
        Product created = productService.create(newProduct(P + "002"), "单测");
        Product patch = newProduct(P + "002");
        patch.setRefPrice(new BigDecimal("5.0000"));
        Product after = productService.update(created.getId(), patch, "单测");
        assertEquals(0, new BigDecimal("5.0000").compareTo(after.getRefPrice()));

        List<PriceLog> logs = priceLogMapper.selectList(new LambdaQueryWrapper<PriceLog>()
                .eq(PriceLog::getProductId, created.getId())
                .orderByAsc(PriceLog::getId));
        assertEquals(2, logs.size()); // 新建 1 条 + 改价 1 条
        PriceLog last = logs.get(1);
        assertEquals(0, new BigDecimal("4.5000").compareTo(last.getOldPrice()));
        assertEquals(0, new BigDecimal("5.0000").compareTo(last.getNewPrice()));
        assertEquals("手工", last.getChangeSource());

        Integer opCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM yc_vend_op_log WHERE target_type='product' AND target_id=? AND action='修改'",
                Integer.class, created.getId());
        assertNotNull(opCount);
        assertTrue(opCount >= 1, "编辑必须写 op_log");
    }

    @Test
    void status_flow_stop_sale_is_not_delete() {
        Product created = productService.create(newProduct(P + "003"), "单测");

        Product clearing = productService.changeStatus(created.getId(), "清仓中", "单测");
        assertEquals("清仓中", clearing.getProductStatus());
        assertNotNull(clearing.getClearanceSince(), "进入清仓中要记 clearance_since");

        Product stopped = productService.changeStatus(created.getId(), "停售", "单测");
        assertEquals("停售", stopped.getProductStatus());
        // 停售≠删除:记录还在,详情能查到
        assertNotNull(productService.getById(created.getId()));

        Product resumed = productService.changeStatus(created.getId(), "在售", "单测");
        assertEquals("在售", resumed.getProductStatus());
        assertNull(resumed.getClearanceSince(), "恢复在售要清掉 clearance_since");

        assertThrows(BizException.class,
                () -> productService.changeStatus(created.getId(), "已删除", "单测"));
    }
    @Test
    void profit_sort_is_global_before_paging_and_missing_prices_last() {
        String[] codes = {"SORT-A", "SORT-B", "SORT-C", "SORT-D", "SORT-E"};
        String[] prices = {"1.80", "6.30", "1.00", "1.00", null};
        String[] costs = {"1.00", "3.24", "2.00", "0.00", "0.00"};
        for (int i = 0; i < codes.length; i++) {
            Product product = newProduct(P + codes[i]);
            product.setRefPrice(prices[i] == null ? null : new BigDecimal(prices[i]));
            product.setRefCost(new BigDecimal(costs[i]));
            productService.create(product, "单测");
        }
        Page<Product> first = productService.page(1, 2, P + "SORT-", null, null, "profit", "desc");
        assertEquals(5, first.getTotal());
        assertEquals(P + "SORT-B", first.getRecords().get(0).getSkuCode());
        assertEquals(P + "SORT-D", first.getRecords().get(1).getSkuCode());
        Page<Product> second = productService.page(2, 2, P + "SORT-", null, null, "profit", "desc");
        assertEquals(P + "SORT-A", second.getRecords().get(0).getSkuCode());
        assertEquals(P + "SORT-C", second.getRecords().get(1).getSkuCode());
        Page<Product> last = productService.page(3, 2, P + "SORT-", null, null, "profit", "desc");
        assertEquals(P + "SORT-E", last.getRecords().get(0).getSkuCode());
        Page<Product> ascending = productService.page(1, 10, P + "SORT-", null, null, "profit", "asc");
        assertEquals(P + "SORT-C", ascending.getRecords().get(0).getSkuCode());
        assertEquals(P + "SORT-E", ascending.getRecords().get(4).getSkuCode());
        productService.changeStatus(first.getRecords().get(0).getId(), "停售", "单测");
        assertEquals(P + "SORT-B", productService.page(1, 2, P + "SORT-", null, "停售", "profit", "desc")
                .getRecords().get(0).getSkuCode());
    }

    @Test
    void sort_rejects_untrusted_columns_and_directions() {
        assertThrows(BizException.class, () -> productService.page(1, 2, null, null, null,
                "ref_price; DELETE FROM yc_vend_product", "asc"));
        assertThrows(BizException.class, () -> productService.page(1, 2, null, null, null,
                "profit", "desc; --"));
    }

}
