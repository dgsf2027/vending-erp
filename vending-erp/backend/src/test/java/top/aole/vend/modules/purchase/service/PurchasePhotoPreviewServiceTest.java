package top.aole.vend.modules.purchase.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;
import top.aole.vend.common.config.LlmProperties;
import top.aole.vend.common.exception.BizException;
import top.aole.vend.modules.ai.infrastructure.OpenAiCompatLlmService;
import top.aole.vend.modules.ai.service.AiModelRouteService;
import top.aole.vend.modules.basedata.domain.entity.Product;
import top.aole.vend.modules.basedata.domain.entity.SkuAlias;
import top.aole.vend.modules.basedata.infrastructure.mapper.ProductMapper;
import top.aole.vend.modules.basedata.infrastructure.mapper.SkuAliasMapper;
import top.aole.vend.modules.basedata.infrastructure.mapper.SupplierMapper;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PurchasePhotoPreviewServiceTest {
    @Mock AiModelRouteService route;
    @Mock OpenAiCompatLlmService vision;
    @Mock ProductMapper products;
    @Mock SkuAliasMapper aliases;
    @Mock SupplierMapper suppliers;

    private PurchasePhotoPreviewService service(LlmProperties properties) {
        return new PurchasePhotoPreviewService(properties, route, vision, products, aliases, suppliers);
    }

    @Test
    void crossedOutRowsStayVisibleButDoNotEnterCalculatedTotal() {
        Product product = new Product();
        product.setId(7L);
        product.setSkuCode("SP007");
        product.setProductName("康师傅绿茶1L");
        product.setBoxSpec(new BigDecimal("12"));
        when(products.selectList(any())).thenReturn(Collections.singletonList(product));
        when(aliases.selectList(any())).thenReturn(Collections.emptyList());
        PurchasePhotoPreviewService.Preview preview = service(new LlmProperties()).parseReply(
                "```json\n{\"documentNo\":\"4763644\",\"bizDate\":\"2026-10-13\",\"supplierName\":null,"
                        + "\"total\":2845,\"items\":["
                        + "{\"name\":\"康师傅绿茶1L\",\"qty\":5,\"unitPrice\":38,\"amount\":190,\"crossedOut\":false,\"uncertain\":false},"
                        + "{\"name\":\"看不清的商品\",\"qty\":2,\"unitPrice\":80,\"amount\":160,\"crossedOut\":true,\"uncertain\":true}]}\n```");
        assertEquals(2, preview.getRows().size());
        assertEquals(7L, preview.getRows().get(0).getProductId());
        assertEquals(new BigDecimal("12"), preview.getRows().get(0).getBoxSpec());
        assertEquals(new BigDecimal("190"), preview.getCalculatedTotal());
        assertTrue(preview.getRows().get(1).isCrossedOut());
        assertTrue(preview.getWarnings().stream().anyMatch(w -> w.contains("合计")));
    }

    @Test
    void amountMismatchIsFlaggedAndUnknownProductIsNotInvented() {
        when(products.selectList(any())).thenReturn(Collections.emptyList());
        when(aliases.selectList(any())).thenReturn(Collections.emptyList());
        PurchasePhotoPreviewService.Preview preview = service(new LlmProperties()).parseReply(
                "{\"items\":[{\"name\":\"手写新品\",\"qty\":10,\"unitPrice\":38,\"amount\":330,"
                        + "\"crossedOut\":false,\"uncertain\":true}]}");
        assertNull(preview.getRows().get(0).getProductId());
        assertTrue(preview.getRows().get(0).getWarnings().stream().anyMatch(w -> w.contains("不一致")));
        assertEquals(new BigDecimal("380"), preview.getCalculatedTotal());
    }

    @Test
    void printedNameAndSeparateVolumeMatchTheRightCapacityAndKeepOriginalText() {
        catalog(product(7L, "康师傅绿茶1L", "瓶", "12"), product(8L, "康师傅绿茶500ml", "瓶", "24"));

        PurchasePhotoPreviewService.Preview preview = parse("{\"name\":\"康师傅绿茶\","
                + "\"sourceSpec\":\"１升\",\"sourceUnit\":\"箱\",\"qty\":5,\"unitPrice\":38,\"amount\":190}");

        PurchasePhotoPreviewService.Line line = preview.getRows().get(0);
        assertEquals(7L, line.getProductId());
        assertEquals("康师傅绿茶", line.getSourceName());
        assertEquals("１升", line.getSourceSpec());
        assertEquals("箱", line.getSourceUnit());
        assertEquals("box", line.getUnitMode());
        assertEquals(new BigDecimal("12"), line.getBoxSpec());
        assertEquals(new BigDecimal("5"), line.getQty());
        assertEquals(new BigDecimal("38"), line.getUnitPrice());
        assertEquals(new BigDecimal("190"), preview.getCalculatedTotal());
    }

    @Test
    void fullWidthMillilitresAndDecimalLitresDoNotMixDifferentCapacities() {
        catalog(product(7L, "果汁1.5L", "瓶", "6"), product(8L, "果汁15L", "桶", "1"));

        PurchasePhotoPreviewService.Preview preview = parse("{\"name\":\"果汁１５００ｍＬ\","
                + "\"sourceSpec\":\"1.5升\",\"sourceUnit\":\"瓶\",\"qty\":2,\"unitPrice\":5}");

        assertEquals(7L, preview.getRows().get(0).getProductId());
        assertEquals("base", preview.getRows().get(0).getUnitMode());
    }

    @Test
    void specificationInsideACompleteNameIsRecognizedWithoutReorderingTheName() {
        catalog(product(7L, "康师傅1L绿茶", "瓶", "12"));

        PurchasePhotoPreviewService.Line line = parse("{\"name\":\"康师傅1升绿茶\","
                + "\"sourceSpec\":\"1000ml\",\"sourceUnit\":\"瓶\",\"qty\":2,\"unitPrice\":4}").getRows().get(0);

        assertEquals(7L, line.getProductId());
        assertEquals("base", line.getUnitMode());
    }

    @Test
    void rangeAndDecimalSpecificationsAreNotCollapsedIntoOtherProductNames() {
        catalog(product(7L, "果汁15L", "桶", "1"), product(8L, "饼干15kg", "袋", "1"));

        PurchasePhotoPreviewService.Preview preview = parse(
                "{\"name\":\"果汁1-5L\",\"qty\":2,\"unitPrice\":50},"
                + "{\"name\":\"饼干1.5kg\",\"qty\":2,\"unitPrice\":50}");

        assertTrue(preview.getRows().stream().allMatch(line -> line.getProductId() == null));
    }

    @Test
    void conflictingCapacityDoesNotFallBackToAnExactNameOrGenericAlias() {
        Product small = product(7L, "果汁500ml", "瓶", "24");
        when(products.selectList(any())).thenReturn(Collections.singletonList(small));
        when(aliases.selectList(any())).thenReturn(Collections.singletonList(alias("果汁", 7L)));

        PurchasePhotoPreviewService.Preview preview = parse(
                "{\"name\":\"果汁\",\"sourceSpec\":\"1L\",\"sourceUnit\":\"箱\",\"qty\":2,\"unitPrice\":50},"
                + "{\"name\":\"果汁500ml\",\"sourceSpec\":\"1L\",\"sourceUnit\":\"箱\",\"qty\":2,\"unitPrice\":50}");

        assertTrue(preview.getRows().stream().allMatch(line -> line.getProductId() == null));
        assertTrue(preview.getRows().stream().allMatch(line -> line.getBoxSpec() == null));
    }

    @Test
    void capacitySuffixCannotPartiallyMatchLargerCapacity() {
        catalog(product(7L, "果汁1500ml", "瓶", "6"));

        PurchasePhotoPreviewService.Line line = parse("{\"name\":\"果汁1500ml\","
                + "\"sourceSpec\":\"500ml\",\"sourceUnit\":\"瓶\",\"qty\":2,\"unitPrice\":5}").getRows().get(0);

        assertNull(line.getProductId());
        assertNull(line.getUnitMode());
    }

    @Test
    void ambiguousNamesStayUnmatchedButExplicitNameAndSpecCanResolveThem() {
        when(products.selectList(any())).thenReturn(Arrays.asList(
                product(7L, "康师傅绿茶1L", "瓶", "12"), product(8L, "康师傅绿茶500ml", "瓶", "24")));
        when(aliases.selectList(any())).thenReturn(Arrays.asList(
                alias("绿茶", 7L), alias("绿茶", 8L), alias("绿茶1L", 7L), alias("绿茶500ml", 8L)));

        PurchasePhotoPreviewService.Preview preview = parse(
                "{\"name\":\"绿茶\",\"sourceUnit\":\"箱\",\"qty\":2,\"unitPrice\":38},"
                + "{\"name\":\"绿茶\",\"sourceSpec\":\"1升\",\"sourceUnit\":\"件\",\"qty\":2,\"unitPrice\":38}");

        assertNull(preview.getRows().get(0).getProductId());
        assertEquals(7L, preview.getRows().get(1).getProductId());
        assertEquals("box", preview.getRows().get(1).getUnitMode());
    }

    @Test
    void missingOrUnclearUnitIsNotGuessedFromPriceModelHintOrCheckmark() {
        catalog(product(7L, "果汁500ml", "瓶", "24"));

        PurchasePhotoPreviewService.Preview preview = parse(
                "{\"name\":\"果汁500ml\",\"unitMode\":\"box\",\"boxSpec\":12,\"qty\":2,\"unitPrice\":60},"
                + "{\"name\":\"果汁500ml\",\"sourceUnit\":\"✓\",\"qty\":2,\"unitPrice\":60},"
                + "{\"name\":\"果汁500ml\",\"sourceUnit\":\"箱/瓶\",\"qty\":2,\"unitPrice\":60},"
                + "{\"name\":\"果汁500ml\",\"sourceUnit\":\"箱？\",\"qty\":2,\"unitPrice\":60},"
                + "{\"name\":\"果汁500ml\",\"sourceUnit\":\"袋\",\"qty\":2,\"unitPrice\":60}");

        for (PurchasePhotoPreviewService.Line line : preview.getRows()) {
            assertEquals(7L, line.getProductId());
            assertEquals(new BigDecimal("24"), line.getBoxSpec());
            assertNull(line.getUnitMode());
            assertTrue(line.getWarnings().stream().anyMatch(w -> w.contains("单位未能确定")));
        }
    }

    @Test
    void missingBoxSpecIsFlaggedWithoutUsingTicketSpecAsAConversionFactor() {
        catalog(product(7L, "果汁500ml", "瓶", null));

        PurchasePhotoPreviewService.Line line = parse("{\"name\":\"果汁500ml\","
                + "\"sourceUnit\":\"整箱\",\"boxSpec\":24,\"qty\":2,\"unitPrice\":60}").getRows().get(0);

        assertEquals("box", line.getUnitMode());
        assertNull(line.getBoxSpec());
        assertTrue(line.getWarnings().stream().anyMatch(w -> w.contains("箱规")));
    }

    private PurchasePhotoPreviewService.Preview parse(String rows) {
        return service(new LlmProperties()).parseReply("{\"items\":[" + rows + "]}");
    }

    private void catalog(Product... entries) {
        when(products.selectList(any())).thenReturn(Arrays.asList(entries));
        when(aliases.selectList(any())).thenReturn(Collections.emptyList());
    }

    private Product product(long id, String name, String unit, String boxSpec) {
        Product product = new Product();
        product.setId(id);
        product.setSkuCode("SP" + id);
        product.setProductName(name);
        product.setUnit(unit);
        product.setBoxSpec(boxSpec == null ? null : new BigDecimal(boxSpec));
        return product;
    }

    private SkuAlias alias(String name, long productId) {
        SkuAlias alias = new SkuAlias();
        alias.setAliasName(name);
        alias.setProductId(productId);
        return alias;
    }

    @Test
    void missingModelFailsClosedWithoutCallingVision() throws Exception {
        BufferedImage image = new BufferedImage(500, 500, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "png", out);
        MockMultipartFile file = new MockMultipartFile("file", "单据.png", "image/png", out.toByteArray());
        BizException error = assertThrows(BizException.class, () -> service(new LlmProperties()).preview(file));
        assertTrue(error.getMessage().contains("尚未配置"));
        verifyNoInteractions(vision);
    }
}
