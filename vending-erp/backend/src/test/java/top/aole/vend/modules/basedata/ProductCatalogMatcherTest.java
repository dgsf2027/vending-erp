package top.aole.vend.modules.basedata;

import org.junit.jupiter.api.Test;
import top.aole.vend.modules.basedata.application.ProductCatalogMatcher;
import top.aole.vend.modules.basedata.domain.entity.*;
import top.aole.vend.modules.basedata.interfaces.dto.ProductImportDtos.Row;
import java.math.BigDecimal;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ProductCatalogMatcherTest {
    static Product product(long id, String name, String barcode) {
        Product p = new Product(); p.setId(id); p.setSkuCode("SP00000" + id); p.setProductName(name);
        p.setBarcode(barcode); p.setUnit("瓶"); p.setBoxSpec(BigDecimal.valueOf(12)); p.setProductStatus("停售"); return p;
    }
    static Row row(String name, String barcode) {
        Row r = new Row(); r.setRowNo(2); r.setProductName(name); r.setBarcode(barcode); return r;
    }
    static SkuAlias alias(long product, String code, String barcode, String name) {
        SkuAlias a = new SkuAlias(); a.setProductId(product); a.setAliasCode(code); a.setAliasBarcode(barcode); a.setAliasName(name); return a;
    }
    private final Product tea = product(1, "茉莉茶500ml", "00123");
    private final Product water = product(2, "纯净水1L", "00456");
    private ProductCatalogMatcher matcher() {
        return new ProductCatalogMatcher(Arrays.asList(tea, water), Arrays.asList(alias(1, "100", "00123", "茉莉清茶")));
    }
    @Test void newProductNeedsNoCodeAndKeepsExternalCode() {
        Row r = row("全新面包80g", "00789"); r.setSourceCode("00009"); matcher().resolve(r);
        assertEquals("新增商品", r.getAction()); assertNull(r.getSkuCode()); assertEquals("00009", r.getSourceCode());
    }
    @Test void barcodeKeepsExistingCodeAndPackagingWithoutChangingStatus() {
        Row r = row("茉莉清茶", "00123"); matcher().resolve(r);
        assertEquals("已匹配", r.getAction()); assertEquals(1L, r.getExistingProductId());
        assertEquals("SP000001", r.getSkuCode()); assertEquals("12", r.getBoxSpec()); assertEquals("停售", tea.getProductStatus());
    }
    @Test void conflictingSourceCodeAndBarcodeDoNotChooseEither() {
        Row r = row("纯净水1L", "00456"); r.setSourceCode("100"); matcher().resolve(r);
        assertEquals("待确认", r.getAction()); assertNull(r.getExistingProductId()); assertEquals(2, r.getCandidates().size());
    }
    @Test void equivalentCapacityMatchesButDifferentSizeDoesNotMerge() {
        Row r = row("纯净水1000毫升", null); matcher().resolve(r); assertEquals(2L, r.getExistingProductId());
        Row other = row("纯净水500ml", null); matcher().resolve(other); assertEquals("新增商品", other.getAction());
        other.setBarcode("00456"); matcher().resolve(other); assertEquals("待确认", other.getAction());
    }
    @Test void ambiguousNamesRequireChoiceAndFuzzyDoesNotAutobind() {
        Product twin = product(3, "茉莉茶500ml", "00999");
        ProductCatalogMatcher m = new ProductCatalogMatcher(Arrays.asList(tea, twin), Collections.emptyList());
        Row r = row("茉莉茶500ml", null); m.resolve(r); assertEquals("待确认", r.getAction());
        r.setSelectedProductId(3L); m.resolve(r); assertEquals(3L, r.getExistingProductId());
        Row fuzzy = row("茉莉绿茶500ml", null); matcher().resolve(fuzzy); assertEquals("待确认", fuzzy.getAction());
    }
    @Test void explicitNewCannotDuplicateKnownBarcodeOrUndifferentiatedName() {
        Row r = row("茉莉茶500ml", "00123"); r.setConfirmNew(true); matcher().resolve(r); assertEquals("待确认", r.getAction());
        r.setBarcode(null); matcher().resolve(r); assertEquals("待确认", r.getAction());
        r.setBarcode("00999"); matcher().resolve(r); assertEquals("新增商品", r.getAction());
    }
    @Test void missingExplicitErpDoesNotSilentlyCreateAndWrongSelectionDoesNotOverrideIdentity() {
        Row r = row("新面包", null); r.setSkuCode("SP999999"); matcher().resolve(r); assertEquals("待确认", r.getAction());
        Row wrong = row("茉莉清茶", "00123"); wrong.setSelectedProductId(2L); matcher().resolve(wrong); assertEquals("待确认", wrong.getAction());
    }
    @Test void historicalAliasRecognizesNameButNotDifferentCapacity() {
        Row r = row("茉莉清茶", null); matcher().resolve(r); assertEquals(1L, r.getExistingProductId());
        Row conflict = row("茉莉茶1L", "00123"); matcher().resolve(conflict); assertEquals("待确认", conflict.getAction());
    }
}
