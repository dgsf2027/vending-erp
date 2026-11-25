package top.aole.vend.modules.basedata.application;

import cn.hutool.crypto.digest.DigestUtil;
import cn.hutool.json.JSONUtil;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import top.aole.vend.common.exception.BizException;
import top.aole.vend.modules.basedata.domain.entity.Product;
import top.aole.vend.modules.basedata.infrastructure.mapper.ProductMapper;
import top.aole.vend.modules.basedata.interfaces.dto.Dtos;
import top.aole.vend.modules.imports.service.SalesProductService;
import java.math.BigDecimal;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 把尚未建立独立库存账的重复商品关联到原档案，保留销售原名和原商品记录。
 *
 * <p>归并分两档：
 * <ul>
 *   <li>硬记录（库存流水、机器库存、业务单据、采购订单、配货记录、货道绑定、已结算销售、已完成盘点）：带真实数量或已入账，
 *       一律挡下，必须人工处理；</li>
 *   <li>软记录（补货建议/补货配置/进行中或已作废的盘点行）：系统生成的建议和草稿，随归并自动清理——
 *       补货建议和配置作废，进行中盘点行的数量并入保留商品的同一盘点单（同一实物负库存在此累加），
 *       作废盘点行直接删除。这样自动建档留下的噪音不会再永久挡住归并。</li>
 * </ul>
 */
@Service @RequiredArgsConstructor
public class ProductLinkService {
    private final JdbcTemplate jdbc;
    private final ProductMapper products;
    private final ProductCodeService codes;
    private final AliasService aliases;
    private final OpLogService logs;
    private static final String TENANT="000000";
    /** 硬记录：任何一条（含已归档，保持原有保守口径）都会挡住归并。 */
    private static final String[][] HARD_REFS={
        {"doc_item","业务单据"},{"stock_ledger","库存流水"},{"machine_stock_snapshot","机器库存"},
        {"purchase_order_item","采购订单"},{"prekit_ticket_item","配货记录"},{"slot","货道绑定"}
    };
    @Data public static class Preview {
        private Product source, target;
        private Long requestedSourceId, requestedTargetId;
        private String directionReason;
        private int salesCount, aliasCount;
        private BigDecimal salesQty, salesAmount;
        private List<String> blockers=new ArrayList<>();
        /** 确认时会自动处理的软记录（补货建议、补货配置、进行中/已作废盘点行） */
        private List<String> autoClean=new ArrayList<>();
        private List<String> salesNames=new ArrayList<>();
        private String token;
        public boolean isExecutable(){return blockers.isEmpty();}
    }
    private Product get(Long id) {
        Product p=products.selectById(id);
        if(p==null || !TENANT.equals(p.getTenantId()))throw new BizException("商品不存在，请刷新列表");
        return p;
    }
    public List<Product> candidates(Long sourceId) {
        Product source=get(sourceId);
        return products.selectList(null).stream().filter(p->TENANT.equals(p.getTenantId()) && !p.getId().equals(sourceId)
            && p.getStockOwnerId()==null && "在售".equals(p.getProductStatus())
            && !ProductCatalogMatcher.conflictingSize(source.getProductName(),p.getProductName()))
            .filter(p->score(source,p)>=0.6)
            .sorted(Comparator.comparingDouble((Product p)->score(source,p)).reversed().thenComparing(Product::getId))
            .limit(5).collect(Collectors.toList());
    }
    private double score(Product a,Product b){return ProductCatalogMatcher.similarity(ProductCatalogMatcher.normalize(a.getProductName()),ProductCatalogMatcher.normalize(b.getProductName()));}

    private boolean provisional(Product p) {
        return (p.getBoxSpec()==null || p.getBoxSpec().signum()==0) && "件".equals(p.getUnit())
            && p.getRemark()!=null && p.getRemark().startsWith("销售导入自动建档");
    }

    /** 从任意一行进入都得到同一方向：完整档案优先，其次保留先建档的编码。 */
    private Product retained(Product first,Product second) {
        if(provisional(first)!=provisional(second))return provisional(first)?second:first;
        return first.getId().compareTo(second.getId())<=0?first:second;
    }

    public Preview preview(Long sourceId,Long targetId) {
        Preview p=new Preview();p.setRequestedSourceId(sourceId);p.setRequestedTargetId(targetId);
        Product first=get(sourceId),second=get(targetId),source=first,target=second;
        if(first.getStockOwnerId()==null && second.getStockOwnerId()==null && !sourceId.equals(targetId)) {
            target=retained(first,second);source=target.getId().equals(sourceId)?second:first;
            sourceId=source.getId();targetId=target.getId();
        }
        p.setSource(source);p.setTarget(target);
        p.setDirectionReason(provisional(source) && !provisional(target)
            ?"自动保留原商品的完整资料，新销售名称同步为别名。"
            :"自动保留先建档的商品编码，另一个名称同步为别名。");
        List<String> blockers=p.getBlockers();
        List<String> autoClean=p.getAutoClean();
        if(sourceId.equals(targetId))blockers.add("不能关联商品自身，请选择需要保留的旧商品");
        if(source.getStockOwnerId()!=null)blockers.add("这个商品已经关联其他库存商品，请刷新后查看");
        if(target.getStockOwnerId()!=null || !"在售".equals(target.getProductStatus()))blockers.add("请选择独立且在售的旧商品");
        if(Boolean.TRUE.equals(source.getSharedStock()) || count("SELECT COUNT(*) FROM yc_vend_product WHERE tenant_id=? AND stock_owner_id=? AND is_deleted=0",sourceId)>0)
            blockers.add("该商品已有其他商品共用库存，不能作为新增商品再次关联");
        if(ProductCatalogMatcher.conflictingSize(source.getProductName(),target.getProductName()))blockers.add("两个商品容量不同，请核对商品规格");
        boolean provisional=provisional(source);
        if(!provisional && !Objects.equals(source.getUnit(),target.getUnit()))blockers.add("基本单位不同，请先核对瓶、箱等数量单位");
        if(!provisional && source.getBoxSpec()!=null && target.getBoxSpec()!=null && source.getBoxSpec().compareTo(target.getBoxSpec())!=0)
            blockers.add("每箱数量不同，请先核对箱规");
        for(String[] ref:HARD_REFS) if(count("SELECT COUNT(*) FROM yc_vend_"+ref[0]+" WHERE tenant_id=? AND product_id=?",sourceId)>0)
            blockers.add("新增商品已有"+ref[1]+"，请先处理独立库存或单据，不能直接归并");
        stocktakePlan(sourceId,blockers,autoClean);
        replenishPlan(sourceId,blockers,autoClean);
        if(count("SELECT COUNT(*) FROM yc_vend_sale_record s WHERE s.tenant_id=? AND s.product_id=? AND s.is_deleted=0 AND (s.settlement_id IS NOT NULL OR EXISTS (SELECT 1 FROM yc_vend_period_lock l WHERE l.tenant_id=s.tenant_id AND l.is_deleted=0 AND (l.period=s.book_period OR l.period=s.biz_period)))",sourceId)>0)
            blockers.add("新增商品已有结算或锁账销售，不能直接改动历史归属");
        p.setSalesCount(count("SELECT COUNT(*) FROM yc_vend_sale_record WHERE tenant_id=? AND product_id=? AND is_deleted=0",sourceId));
        p.setSalesQty(jdbc.queryForObject("SELECT COALESCE(SUM(qty),0) FROM yc_vend_sale_record WHERE tenant_id=? AND product_id=? AND is_deleted=0",BigDecimal.class,TENANT,sourceId));
        p.setSalesAmount(jdbc.queryForObject("SELECT COALESCE(SUM(amount_received),0) FROM yc_vend_sale_record WHERE tenant_id=? AND product_id=? AND is_deleted=0",BigDecimal.class,TENANT,sourceId));
        p.setSalesNames(jdbc.queryForList("SELECT DISTINCT alias_name_raw FROM yc_vend_sale_record WHERE tenant_id=? AND product_id=? AND is_deleted=0 AND alias_name_raw IS NOT NULL ORDER BY alias_name_raw",String.class,TENANT,sourceId));
        p.setAliasCount(count("SELECT COUNT(*) FROM yc_vend_sku_alias WHERE tenant_id=? AND product_id=? AND is_deleted=0",sourceId));
        for(String key:bindingKeys(p)) {
            Integer conflicts=jdbc.queryForObject("SELECT COUNT(*) FROM yc_vend_sku_alias WHERE tenant_id=? AND alias_code=? AND product_id NOT IN (?,?) AND is_deleted=0",Integer.class,TENANT,key,sourceId,targetId);
            if(conflicts!=null && conflicts>0){blockers.add("名称或原编码已绑定第三个商品，请先核对已有别名");break;}
        }
        if(source.getBarcode()!=null && !source.getBarcode().trim().isEmpty()) {
            String barcode=source.getBarcode().trim();
            Integer conflicts=jdbc.queryForObject("SELECT COUNT(*) FROM yc_vend_sku_alias WHERE tenant_id=? AND alias_barcode=? AND product_id NOT IN (?,?) AND is_deleted=0",Integer.class,TENANT,barcode,sourceId,targetId);
            Integer productConflicts=jdbc.queryForObject("SELECT COUNT(*) FROM yc_vend_product WHERE tenant_id=? AND barcode=? AND id NOT IN (?,?) AND stock_owner_id IS NULL AND is_deleted=0",Integer.class,TENANT,barcode,sourceId,targetId);
            if(conflicts>0 || productConflicts>0)blockers.add("条码还指向第三个商品，请先核对条码绑定");
        }
        p.setToken(DigestUtil.sha256Hex(JSONUtil.toJsonStr(p)));
        return p;
    }

    /** 盘点：进行中/已作废的行可自动处理，已完成的盘点盈亏已入账，必须人工核对。 */
    private void stocktakePlan(Long sourceId,List<String> blockers,List<String> autoClean) {
        List<Map<String,Object>> rows=jdbc.queryForList(
            "SELECT i.id,i.stocktake_id,h.st_no,h.st_status FROM yc_vend_stocktake_item i "
            + "JOIN yc_vend_stocktake h ON h.id=i.stocktake_id "
            + "WHERE i.tenant_id=? AND i.product_id=? AND i.is_deleted=0",TENANT,sourceId);
        int draft=0,voided=0;List<String> finished=new ArrayList<>();
        for(Map<String,Object> row:rows) {
            String st=String.valueOf(row.get("st_status"));
            if("已完成".equals(st)) { String no=String.valueOf(row.get("st_no")); if(!finished.contains(no))finished.add(no); }
            else if("已作废".equals(st)) voided++;
            else draft++;
        }
        if(!finished.isEmpty()) {
            List<String> shown=finished.size()>3?finished.subList(0,3):finished;
            blockers.add("盘点单 "+String.join("、",shown)+(finished.size()>3?" 等":"")+" 已完成，盘点盈亏已入账，请先人工核对");
        }
        if(draft>0) autoClean.add(draft+" 条进行中盘点行：数量并入保留商品的同一盘点单，不重复计库存");
        if(voided>0) autoClean.add(voided+" 条已作废盘点行：直接清理");
    }

    /** 补货建议/配置是系统生成的建议，随归并作废；已采纳的已转采购单，必须人工处理。 */
    private void replenishPlan(Long sourceId,List<String> blockers,List<String> autoClean) {
        Integer adopted=count("SELECT COUNT(*) FROM yc_vend_replenish_plan WHERE tenant_id=? AND product_id=? AND is_deleted=0 AND plan_status='已采纳'",sourceId);
        if(adopted!=null && adopted>0)blockers.add("有已采纳的补货建议（已转采购单），请先处理采购单据");
        Integer plans=count("SELECT COUNT(*) FROM yc_vend_replenish_plan WHERE tenant_id=? AND product_id=? AND is_deleted=0 AND plan_status<>'已采纳'",sourceId);
        if(plans!=null && plans>0)autoClean.add(plans+" 条补货建议：作废（不产生采购单）");
        Integer configs=count("SELECT COUNT(*) FROM yc_vend_replenish_config WHERE tenant_id=? AND product_id=? AND is_deleted=0",sourceId);
        if(configs!=null && configs>0)autoClean.add(configs+" 条补货配置：随商品清理（保留商品的配置优先）");
    }

    private int count(String sql,Long id){return jdbc.queryForObject(sql,Integer.class,TENANT,id);}
    private Set<String> bindingKeys(Preview p){
        Set<String> keys=new LinkedHashSet<>();keys.add(p.getSource().getSkuCode());keys.add(SalesProductService.aliasCode(p.getSource().getProductName()));
        for(String name:p.getSalesNames())if(!name.trim().isEmpty())keys.add(SalesProductService.aliasCode(name));
        return keys;
    }
    @Transactional(rollbackFor=Exception.class)
    public void link(Long sourceId,Long targetId,String token,String operator) {
        codes.lock();
        // 与商品编辑、销售导入、库存建单共用锁；已结算销售不得改归属。
        jdbc.queryForList("SELECT id FROM yc_vend_product WHERE tenant_id=? AND id IN (?,?) ORDER BY id FOR UPDATE",Long.class,TENANT,sourceId,targetId);
        Product source=get(sourceId),target=get(targetId);
        if(Objects.equals(source.getStockOwnerId(),targetId) && target.getStockOwnerId()==null)return; // 网络重试幂等
        if(Objects.equals(target.getStockOwnerId(),sourceId) && source.getStockOwnerId()==null)return;
        jdbc.queryForList("SELECT id FROM yc_vend_sale_record WHERE tenant_id=? AND product_id IN (?,?) AND is_deleted=0 ORDER BY id FOR UPDATE",Long.class,TENANT,sourceId,targetId);
        Preview p=preview(sourceId,targetId);
        if(!p.isExecutable())throw new BizException(String.join("；",p.getBlockers()));
        if(token==null || !token.equals(p.getToken()))throw new BizException("商品或销售记录已变化，请重新核对关联影响");
        source=p.getSource();target=p.getTarget();sourceId=source.getId();targetId=target.getId();
        cleanupSoftRecords(sourceId,targetId);
        jdbc.update("UPDATE yc_vend_sku_alias SET product_id=? WHERE tenant_id=? AND product_id=? AND is_deleted=0",targetId,TENANT,sourceId);
        bind(targetId,source.getSkuCode(),source.getProductName(),null,operator);
        bind(targetId,SalesProductService.aliasCode(source.getProductName()),source.getProductName(),null,operator);
        if(source.getBarcode()!=null && !source.getBarcode().trim().isEmpty())bind(targetId,source.getSkuCode(),source.getProductName(),source.getBarcode().trim(),operator);
        for(String name:p.getSalesNames())if(!name.trim().isEmpty())bind(targetId,SalesProductService.aliasCode(name),name,null,operator);
        jdbc.update("UPDATE yc_vend_sale_record SET product_id=? WHERE tenant_id=? AND product_id=? AND is_deleted=0",targetId,TENANT,sourceId);
        jdbc.update("UPDATE yc_vend_product SET stock_owner_id=?,product_status='停售' WHERE tenant_id=? AND id=? AND is_deleted=0",targetId,TENANT,sourceId);
        jdbc.update("UPDATE yc_vend_product SET shared_stock=1 WHERE tenant_id=? AND id=? AND is_deleted=0",TENANT,targetId);
        logs.record(operator,"关联已有商品","product",sourceId,p,Collections.singletonMap("保留商品编码",target.getSkuCode()));
    }

    /**
     * 软记录随归并处理：进行中盘点行的数量并入保留商品（同一盘点单里累加，同一实物不会重复计库存），
     * 已作废盘点行、补货建议、补货配置一律逻辑删除。已完成盘点和已采纳建议在预览阶段已被挡下，这里只做兜底判断。
     */
    private void cleanupSoftRecords(Long sourceId,Long targetId) {
        List<Map<String,Object>> items=jdbc.queryForList(
            "SELECT i.id,i.stocktake_id,i.slot_no,i.book_qty,i.actual_qty,i.diff_qty,i.diff_amount,h.st_no,h.st_status "
            + "FROM yc_vend_stocktake_item i JOIN yc_vend_stocktake h ON h.id=i.stocktake_id "
            + "WHERE i.tenant_id=? AND i.product_id=? AND i.is_deleted=0",TENANT,sourceId);
        for(Map<String,Object> item:items) {
            Long itemId=((Number)item.get("id")).longValue();
            Long stocktakeId=((Number)item.get("stocktake_id")).longValue();
            String st=String.valueOf(item.get("st_status"));
            if("已完成".equals(st))throw new BizException("盘点单"+item.get("st_no")+"已完成，不能自动归并");
            if("已作废".equals(st)){jdbc.update("UPDATE yc_vend_stocktake_item SET is_deleted=1 WHERE tenant_id=? AND id=?",TENANT,itemId);continue;}
            Object slot=item.get("slot_no");
            List<Map<String,Object>> same=slot==null
                ? jdbc.queryForList("SELECT id,book_qty,actual_qty,diff_amount FROM yc_vend_stocktake_item WHERE tenant_id=? AND stocktake_id=? AND product_id=? AND is_deleted=0 AND slot_no IS NULL",TENANT,stocktakeId,targetId)
                : jdbc.queryForList("SELECT id,book_qty,actual_qty,diff_amount FROM yc_vend_stocktake_item WHERE tenant_id=? AND stocktake_id=? AND product_id=? AND is_deleted=0 AND slot_no=?",TENANT,stocktakeId,targetId,slot);
            BigDecimal book=dec(item.get("book_qty")),actual=dec(item.get("actual_qty"));
            if(same.isEmpty()) {
                jdbc.update("UPDATE yc_vend_stocktake_item SET product_id=? WHERE tenant_id=? AND id=?",targetId,TENANT,itemId);
                continue;
            }
            Map<String,Object> keep=same.get(0);
            BigDecimal newBook=dec(keep.get("book_qty")).add(book);
            BigDecimal newActual=dec(keep.get("actual_qty")).add(actual);
            Object keepAmount=keep.get("diff_amount"),srcAmount=item.get("diff_amount");
            jdbc.update("UPDATE yc_vend_stocktake_item SET book_qty=?,actual_qty=?,diff_qty=?,diff_amount=? WHERE tenant_id=? AND id=?",
                newBook,newActual,newActual.subtract(newBook),
                keepAmount==null&&srcAmount==null?null:dec(keepAmount).add(dec(srcAmount)),
                TENANT,keep.get("id"));
            jdbc.update("UPDATE yc_vend_stocktake_item SET is_deleted=1 WHERE tenant_id=? AND id=?",TENANT,itemId);
        }
        jdbc.update("UPDATE yc_vend_replenish_plan SET is_deleted=1 WHERE tenant_id=? AND product_id=? AND is_deleted=0 AND plan_status<>'已采纳'",TENANT,sourceId);
        jdbc.update("UPDATE yc_vend_replenish_config SET is_deleted=1 WHERE tenant_id=? AND product_id=? AND is_deleted=0",TENANT,sourceId);
    }

    private static BigDecimal dec(Object v) {
        if(v==null)return BigDecimal.ZERO;
        if(v instanceof BigDecimal)return (BigDecimal)v;
        return new BigDecimal(String.valueOf(v));
    }
    private void bind(Long target,String code,String name,String barcode,String operator){
        Dtos.BindAliasReq req=new Dtos.BindAliasReq();req.setProductId(target);req.setAliasCode(code);req.setAliasName(name);req.setAliasBarcode(barcode);req.setBindSource("重复商品关联");aliases.bind(req,operator);
    }
}
