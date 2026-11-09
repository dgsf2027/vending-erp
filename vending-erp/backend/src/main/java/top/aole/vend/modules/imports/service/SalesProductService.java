package top.aole.vend.modules.imports.service;

import cn.hutool.core.util.StrUtil;
import cn.hutool.crypto.digest.DigestUtil;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import top.aole.vend.common.exception.BizException;
import top.aole.vend.modules.basedata.application.*;
import top.aole.vend.modules.basedata.domain.entity.Product;
import top.aole.vend.modules.basedata.infrastructure.mapper.ProductMapper;
import top.aole.vend.modules.basedata.infrastructure.mapper.SkuAliasMapper;
import top.aole.vend.modules.basedata.interfaces.dto.Dtos;
import top.aole.vend.modules.basedata.interfaces.dto.ProductImportDtos.Row;
import java.math.BigDecimal;
import java.util.List;
import top.aole.vend.modules.imports.dto.ImportDtos.SalesProductPreview;

/** 在销售确认事务中识别或建档；预览不会创建商品。 */
@Service
@RequiredArgsConstructor
public class SalesProductService {
    private final ProductMapper products;
    private final SkuAliasMapper aliases;
    private final ProductService productService;
    private final AliasService aliasService;
    private final ProductCodeService codes;

    @Transactional(propagation = Propagation.MANDATORY)
    public void lockImport() { codes.lock(); }

    public static String aliasCode(String name) {
        return "SALE/" + DigestUtil.md5Hex(ProductCatalogMatcher.normalize(name));
    }

    @Data @AllArgsConstructor
    public static class Resolution {
        private Product product;
        private boolean created;
        private String reviewReason;
    }

    /** 一次加载档案，预览只读，不分配编码、不创建别名。 */
    public void previewMatches(List<SalesProductPreview> items) {
        ProductCatalogMatcher matcher = new ProductCatalogMatcher(products.selectList(null), aliases.selectList(null));
        for (SalesProductPreview item : items) {
            Row row = new Row();
            row.setProductName(item.getSourceName()); row.setBarcode(item.getBarcode());
            row.setSourceCode(aliasCode(item.getSourceName()));
            matcher.resolveForSales(row);
            item.setStatus(row.getAction()); item.setSkuCode(row.getSkuCode());
            item.setProductName(row.getMatchedProductName());
            item.setReason(row.getErrorMsg() == null ? row.getMatchReason() : row.getErrorMsg());
        }
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public Resolution resolve(String name, String barcode, String category, boolean allowCreate, String operator) {
        name = StrUtil.trim(name);
        barcode = StrUtil.trim(barcode);
        if (StrUtil.isBlank(name) || name.length() > 128) throw new BizException("商品名称须为 1 至 128 个字符");
        if (barcode != null && barcode.length() > 64) throw new BizException("商品条码最多 64 个字符");
        if (category != null && category.length() > 32) throw new BizException("商品分类最多 32 个字符");
        // 所有商品创建、指定编码和别名绑定共用该锁，锁后重读，避免并发重复建档。
        codes.lock();
        Row row = new Row(); row.setProductName(name); row.setBarcode(barcode); row.setSourceCode(aliasCode(name));
        new ProductCatalogMatcher(products.selectList(null), aliases.selectList(null)).resolveForSales(row);
        boolean created = ProductCatalogMatcher.NEW.equals(row.getAction());
        if ((!created && !ProductCatalogMatcher.MATCHED.equals(row.getAction())) || (created && !allowCreate)) {
            return new Resolution(null, false, created ? "非正常销售记录中的未知商品，请核对后绑定" : row.getErrorMsg());
        }
        Product product;
        if (created) {
            product = new Product();
            product.setSkuCode(codes.nextSalesCode());
            product.setProductName(name); product.setBarcode(StrUtil.blankToDefault(barcode, null));
            product.setCategory(StrUtil.blankToDefault(StrUtil.trim(category), null));
            product.setUnit("件");
            // 0 表示箱规未设置；采购整箱换算已有 >0 守卫，不能伪填 1 箱=1 件。
            product.setBoxSpec(BigDecimal.ZERO);
            product.setRemark("销售导入自动建档；箱规、进货成本待补充。基本单位暂按件。");
            product = productService.create(product, operator);
        } else {
            product = productService.getById(row.getExistingProductId());
        }
        // 一个名称/条码组合一个来源键；同名不同条码由匹配器挡住，不自动改绑。
        Dtos.BindAliasReq alias = new Dtos.BindAliasReq();
        alias.setAliasCode(aliasCode(name));
        alias.setAliasBarcode(StrUtil.nullToEmpty(barcode)); alias.setAliasName(name);
        alias.setProductId(product.getId()); alias.setBindSource("销售自动建档");
        aliasService.bind(alias, operator);
        return new Resolution(product, created, null);
    }
}
