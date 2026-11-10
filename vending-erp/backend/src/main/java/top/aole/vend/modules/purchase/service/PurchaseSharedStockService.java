package top.aole.vend.modules.purchase.service;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import top.aole.vend.common.exception.BizException;
import top.aole.vend.modules.basedata.application.*;
import top.aole.vend.modules.basedata.domain.entity.Product;
import top.aole.vend.modules.basedata.infrastructure.mapper.ProductMapper;
import top.aole.vend.modules.basedata.interfaces.dto.Dtos;
import top.aole.vend.modules.imports.service.SalesProductService;
import java.util.*;

/** 首次建库存时归集已导入的口味销售；已有独立库存账需另行迁移，不静默改历史库存。 */
@Service
@RequiredArgsConstructor
public class PurchaseSharedStockService {
    private final JdbcTemplate jdbc;
    private final ProductMapper products;
    private final ProductCodeService codes;
    private final AliasService aliases;
    private final OpLogService logs;
    private static final String TENANT = "000000";
    private static final String[] STOCK_TABLES = {"yc_vend_doc_item", "yc_vend_stock_ledger",
        "yc_vend_machine_stock_snapshot", "yc_vend_purchase_order_item", "yc_vend_stocktake_item",
        "yc_vend_prekit_ticket_item", "yc_vend_replenish_plan", "yc_vend_replenish_config", "yc_vend_slot"};

    @Transactional(propagation = Propagation.MANDATORY)
    public void merge(Long targetId, List<Long> memberIds, String name, String operator) {
        codes.lock();
        Product target = products.selectById(targetId);
        if (target == null || target.getStockOwnerId() != null || !memberIds.contains(targetId))
            throw new BizException("库存商品已变化，请重新上传核对");
        List<Product> members = new ArrayList<>();
        for (Long id : new TreeSet<>(memberIds)) {
            Product member = products.selectById(id);
            if (member == null || member.getStockOwnerId() != null || !"在售".equals(member.getProductStatus()))
                throw new BizException("口味商品状态已变化，请重新上传核对");
            if (!PurchaseWorkbookService.sameFamily(target.getProductName(), member.getProductName())
                || !Objects.equals(target.getUnit(), member.getUnit()) || target.getBoxSpec() == null
                || member.getBoxSpec() == null || target.getBoxSpec().compareTo(member.getBoxSpec()) != 0)
                throw new BizException("不同系列、容量、单位或箱规不能共用库存");
            members.add(member);
            if (id.equals(targetId)) continue;
            for (String table : STOCK_TABLES) {
                // 固定表名白名单；带商品索引的锁定读，与写入事务隔离。
                List<Long> refs = jdbc.queryForList("SELECT id FROM " + table
                    + " WHERE tenant_id=? AND product_id=? AND is_deleted=0 LIMIT 1 FOR UPDATE", Long.class, TENANT, id);
                if (!refs.isEmpty()) throw new BizException(member.getProductName() + " 已有独立库存、货道或单据记录，不能直接归并。请保留该口味独立库存后再导入。");
            }
            Integer settled = jdbc.queryForObject("SELECT COUNT(*) FROM yc_vend_sale_record s WHERE s.tenant_id=? AND s.product_id=? AND s.is_deleted=0 AND (s.settlement_id IS NOT NULL OR EXISTS (SELECT 1 FROM yc_vend_period_lock l WHERE l.tenant_id=s.tenant_id AND l.is_deleted=0 AND (l.period=s.book_period OR l.period=s.biz_period)))", Integer.class, TENANT, id);
            if (settled != null && settled > 0) throw new BizException(member.getProductName() + " 已有结算或锁账销售，需保留历史账后单独处理共用库存");
        }
        // 全部检查通过才写；外层事务失败时，关联、销售归属及入库一起回滚。
        for (Product member : members) {
            Long id = member.getId();
            List<String> saleNames = jdbc.queryForList("SELECT DISTINCT alias_name_raw FROM yc_vend_sale_record WHERE tenant_id=? AND product_id=? AND is_deleted=0", String.class, TENANT, id);
            jdbc.update("UPDATE yc_vend_sku_alias SET product_id=? WHERE tenant_id=? AND product_id=? AND is_deleted=0", targetId, TENANT, id);
            bind(targetId, member.getSkuCode(), member.getProductName(), operator);
            if (member.getBarcode() != null && !member.getBarcode().trim().isEmpty()) {
                Dtos.BindAliasReq barcode = new Dtos.BindAliasReq(); barcode.setProductId(targetId);
                barcode.setAliasCode("SHARED/" + member.getSkuCode()); barcode.setAliasBarcode(member.getBarcode());
                barcode.setAliasName(member.getProductName()); barcode.setBindSource("采购共用库存"); aliases.bind(barcode, operator);
            }
            bind(targetId, SalesProductService.aliasCode(member.getProductName()), member.getProductName(), operator);
            for (String saleName : saleNames) if (saleName != null && !saleName.trim().isEmpty())
                bind(targetId, SalesProductService.aliasCode(saleName), saleName, operator);
            if (!id.equals(targetId)) {
                jdbc.update("UPDATE yc_vend_sale_record SET product_id=? WHERE tenant_id=? AND product_id=? AND is_deleted=0", targetId, TENANT, id);
                jdbc.update("UPDATE yc_vend_product SET stock_owner_id=?,product_status='停售' WHERE tenant_id=? AND id=? AND is_deleted=0", targetId, TENANT, id);
            }
        }
        jdbc.update("UPDATE yc_vend_product SET product_name=?,shared_stock=1 WHERE tenant_id=? AND id=? AND is_deleted=0", name, TENANT, targetId);
        Map<String, Object> after = new LinkedHashMap<>(); after.put("stockProductId", targetId); after.put("name", name); after.put("members", memberIds);
        logs.record(operator, "确认口味共用库存", "product", targetId, members, after);
    }

    private void bind(Long id, String code, String name, String operator) {
        Dtos.BindAliasReq req = new Dtos.BindAliasReq(); req.setProductId(id); req.setAliasCode(code);
        req.setAliasName(name); req.setBindSource("采购共用库存"); aliases.bind(req, operator);
    }
}
