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
import top.aole.vend.modules.basedata.infrastructure.mapper.ProductMapper;
import top.aole.vend.modules.basedata.infrastructure.mapper.SkuAliasMapper;
import top.aole.vend.modules.basedata.infrastructure.mapper.SupplierMapper;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
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
    void uncertainHandwritingOffersCandidatesWithoutAutoBinding() {
        Product product = new Product();
        product.setId(7L);
        product.setSkuCode("SP007");
        product.setProductName("康师傅绿茶1L");
        product.setBoxSpec(new BigDecimal("12"));
        product.setUnit("瓶");
        when(products.selectList(any())).thenReturn(Collections.singletonList(product));
        when(aliases.selectList(any())).thenReturn(Collections.emptyList());
        PurchasePhotoPreviewService.Preview preview = service(new LlmProperties()).parseReply(
                "{\"items\":[{\"name\":\"康师傅绿荼1L\",\"qty\":5,\"unitPrice\":38,"
                        + "\"amount\":190,\"uncertain\":true}]}");
        PurchasePhotoPreviewService.Line line = preview.getRows().get(0);
        assertNull(line.getProductId());
        assertEquals(1, line.getCandidates().size());
        assertEquals(7L, line.getCandidates().get(0).getProductId());
        assertEquals(new BigDecimal("12"), line.getCandidates().get(0).getBoxSpec());
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
