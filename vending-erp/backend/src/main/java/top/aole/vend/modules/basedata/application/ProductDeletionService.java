package top.aole.vend.modules.basedata.application;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import top.aole.vend.common.exception.BizException;
import top.aole.vend.modules.basedata.domain.entity.Product;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 只删除未被业务使用的商品;历史引用保留,附属别名与初始价格日志一起清理并留审计快照。 */
@Service
@RequiredArgsConstructor
public class ProductDeletionService {
    private final ProductService productService;
    private final JdbcTemplate jdbc;
    private final OpLogService opLogService;

    private static final String[][] REFERENCES = {
            {"yc_vend_sale_record", "销售记录"},
            {"yc_vend_stock_ledger", "库存流水"},
            {"yc_vend_doc_item", "业务单据"},
            {"yc_vend_purchase_order_item", "采购订单"},
            {"yc_vend_machine_stock_snapshot", "机器库存快照"},
            {"yc_vend_stocktake_item", "盘点记录"},
            {"yc_vend_prekit_ticket_item", "配货记录"},
            {"yc_vend_replenish_plan", "补货计划"},
            {"yc_vend_slot", "货道绑定"},
            {"yc_vend_replenish_config", "补货配置"},
    };

    @Transactional(rollbackFor = Exception.class)
    public void delete(Long id, String operator) {
        // 先锁定商品,防止重复删除;表名来自固定清单,商品 ID 始终以参数传入。
        List<Long> locked = jdbc.queryForList(
                "SELECT id FROM yc_vend_product WHERE id=? AND is_deleted=0 FOR UPDATE", Long.class, id);
        if (locked.isEmpty()) throw new BizException("商品不存在或已删除");
        Product before = productService.getById(id);
        for (String[] reference : REFERENCES) {
            // 已归档/红冲/软删的业务行也保护,不让历史记录失去商品信息。
            Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM " + reference[0] + " WHERE product_id=?",
                    Integer.class, id);
            if (count != null && count > 0) {
                throw new BizException("「" + before.getProductName() + "」已有" + reference[1]
                        + ",不能删除。请先解除配置关联;有历史业务记录的商品请使用停售。");
            }
        }
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("product", before);
        snapshot.put("aliases", jdbc.queryForList("SELECT * FROM yc_vend_sku_alias WHERE product_id=?", id));
        snapshot.put("prices", jdbc.queryForList("SELECT * FROM yc_vend_price_log WHERE product_id=?", id));
        // 唯一键不含 is_deleted:未使用档案物理删除后,相同编号可重新建档/导入。
        jdbc.update("DELETE FROM yc_vend_sku_alias WHERE product_id=?", id);
        jdbc.update("DELETE FROM yc_vend_price_log WHERE product_id=?", id);
        jdbc.update("UPDATE yc_vend_alias_pending SET suggest_product_id=NULL WHERE suggest_product_id=?", id);
        jdbc.update("DELETE FROM yc_vend_product WHERE id=?", id);
        opLogService.record(operator, "删除商品", "product", id, snapshot, null);
    }
}
