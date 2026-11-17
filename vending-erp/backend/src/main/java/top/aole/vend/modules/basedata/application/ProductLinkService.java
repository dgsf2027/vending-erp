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

/** 把尚未建立独立库存账的重复商品关联到原档案，保留销售原名和原商品记录。 */
@Service @RequiredArgsConstructor
public class ProductLinkService {
    private final JdbcTemplate jdbc;
    private final ProductMapper products;
    private final ProductCodeService codes;
    private final AliasService aliases;
    private final OpLogService logs;
    private static final String TENANT="000000";
    private static final String[][] REFS={
        {"doc_item","业务单据"},{"stock_ledger","库存流水"},{"machine_stock_snapshot","机器库存"},
        {"purchase_order_item","采购订单"},{"stocktake_item","盘点记录"},{"prekit_ticket_item","配货记录"},
        {"replenish_plan","补货计划"},{"replenish_config","补货配置"},{"slot","货道绑定"}
    };
    @Data public static class Preview {
        private Product source, target;
        private int salesCount, aliasCount;
        private BigDecimal salesQty, salesAmount;
        private List<String> blockers=new ArrayList<>();
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

    public Preview preview(Long sourceId,Long targetId) {
        Preview p=new Preview(); Product source=get(sourceId),target=get(targetId);p.setSource(source);p.setTarget(target);
        List<String> blockers=p.getBlockers();
        if(sourceId.equals(targetId))blockers.add("不能关联商品自身，请选择需要保留的旧商品");
        if(source.getStockOwnerId()!=null)blockers.add("这个商品已经关联其他库存商品，请刷新后查看");
        if(target.getStockOwnerId()!=null || !"在售".equals(target.getProductStatus()))blockers.add("请选择独立且在售的旧商品");
        if(Boolean.TRUE.equals(source.getSharedStock()) || count("SELECT COUNT(*) FROM yc_vend_product WHERE tenant_id=? AND stock_owner_id=? AND is_deleted=0",sourceId)>0)
            blockers.add("该商品已有其他商品共用库存，不能作为新增商品再次关联");
        if(ProductCatalogMatcher.conflictingSize(source.getProductName(),target.getProductName()))blockers.add("两个商品容量不同，请核对商品规格");
        boolean provisional=(source.getBoxSpec()==null || source.getBoxSpec().signum()==0) && "件".equals(source.getUnit())
            && source.getRemark()!=null && source.getRemark().startsWith("销售导入自动建档");
        if(!provisional && !Objects.equals(source.getUnit(),target.getUnit()))blockers.add("基本单位不同，请先核对瓶、箱等数量单位");
        if(!provisional && source.getBoxSpec()!=null && target.getBoxSpec()!=null && source.getBoxSpec().compareTo(target.getBoxSpec())!=0)
            blockers.add("每箱数量不同，请先核对箱规");
        for(String[] ref:REFS) if(count("SELECT COUNT(*) FROM yc_vend_"+ref[0]+" WHERE tenant_id=? AND product_id=?",sourceId)>0)
            blockers.add("新增商品已有"+ref[1]+"，请先处理独立库存或单据，不能直接归并");
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
        jdbc.queryForList("SELECT id FROM yc_vend_sale_record WHERE tenant_id=? AND product_id=? AND is_deleted=0 ORDER BY id FOR UPDATE",Long.class,TENANT,sourceId);
        Preview p=preview(sourceId,targetId);
        if(!p.isExecutable())throw new BizException(String.join("；",p.getBlockers()));
        if(token==null || !token.equals(p.getToken()))throw new BizException("商品或销售记录已变化，请重新核对关联影响");
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
    private void bind(Long target,String code,String name,String barcode,String operator){
        Dtos.BindAliasReq req=new Dtos.BindAliasReq();req.setProductId(target);req.setAliasCode(code);req.setAliasName(name);req.setAliasBarcode(barcode);req.setBindSource("重复商品关联");aliases.bind(req,operator);
    }
}
