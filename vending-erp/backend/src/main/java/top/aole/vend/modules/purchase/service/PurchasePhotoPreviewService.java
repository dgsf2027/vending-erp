package top.aole.vend.modules.purchase.service;

import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import top.aole.vend.common.config.LlmProperties;
import top.aole.vend.common.exception.BizException;
import top.aole.vend.modules.ai.domain.AiScenes;
import top.aole.vend.modules.ai.infrastructure.OpenAiCompatLlmService;
import top.aole.vend.modules.ai.service.AiModelRouteService;
import top.aole.vend.modules.basedata.domain.entity.Product;
import top.aole.vend.modules.basedata.domain.entity.SkuAlias;
import top.aole.vend.modules.basedata.domain.entity.Supplier;
import top.aole.vend.modules.basedata.infrastructure.mapper.ProductMapper;
import top.aole.vend.modules.basedata.infrastructure.mapper.SkuAliasMapper;
import top.aole.vend.modules.basedata.infrastructure.mapper.SupplierMapper;

import java.io.IOException;
import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/** 拿货单照片只生成可修改的预览；建草稿和过账仍由原采购流程处理。 */
@Service
@RequiredArgsConstructor
public class PurchasePhotoPreviewService {
    private static final long MAX_BYTES = 10L * 1024 * 1024;
    private static final BigDecimal CENT = new BigDecimal("0.01");
    static final String PROMPT = "识别这张手写拿货单，只输出 JSON 对象，不要解释或代码围栏。"
            + "格式:{\"documentNo\":null,\"bizDate\":null,\"supplierName\":null,\"total\":null,"
            + "\"items\":[{\"name\":null,\"qty\":null,\"unitPrice\":null,\"amount\":null,"
            + "\"crossedOut\":false,\"uncertain\":false}]}。"
            + "日期用 yyyy-MM-dd，金额和数量只写数字；看不清填 null 并把 uncertain 设为 true。"
            + "按图片从上到下逐行保留商品，划掉/作废的行也保留并标 crossedOut=true。"
            + "不要把左侧打勾当作数量，不要把‘收货单位’当作供应商，不能臆造箱规或商品编码。"
            + "只抄写票面值，不自行补全或修改单价、金额与合计。";

    private final LlmProperties llmProperties;
    private final AiModelRouteService routeService;
    private final OpenAiCompatLlmService vision;
    private final ProductMapper productMapper;
    private final SkuAliasMapper aliasMapper;
    private final SupplierMapper supplierMapper;

    @Data
    public static class Preview {
        private String documentNo;
        private String bizDate;
        private String supplierName;
        private Long supplierId;
        private BigDecimal declaredTotal;
        private BigDecimal calculatedTotal = BigDecimal.ZERO;
        private List<Line> rows = new ArrayList<>();
        private List<String> warnings = new ArrayList<>();
    }

    @Data
    public static class Line {
        private int rowNo;
        private String sourceName;
        private Long productId;
        private String productName;
        private String skuCode;
        private BigDecimal boxSpec;
        private String unit;
        private BigDecimal qty;
        private BigDecimal unitPrice;
        private BigDecimal sourceAmount;
        private BigDecimal calculatedAmount;
        private boolean crossedOut;
        private boolean uncertain;
        private List<String> warnings = new ArrayList<>();
        private List<Candidate> candidates = new ArrayList<>();
    }

    @Data
    public static class Candidate {
        private Long productId;
        private String productName;
        private String skuCode;
        private BigDecimal boxSpec;
        private String unit;

        Candidate(Product product) {
            productId = product.getId();
            productName = product.getProductName();
            skuCode = product.getSkuCode();
            boxSpec = product.getBoxSpec();
            unit = product.getUnit();
        }
    }

    public Preview preview(MultipartFile file) {
        if (file == null || file.isEmpty() || file.getSize() > MAX_BYTES) {
            throw new BizException("请选择不超过 10MB 的非空拿货单照片");
        }
        byte[] bytes;
        try {
            bytes = file.getBytes();
        } catch (IOException e) {
            throw new BizException("照片读取失败，请重新选择文件");
        }
        String mime = imageMime(bytes);
        if (mime == null) {
            throw new BizException("目前支持 JPG、PNG 拿货单照片；Excel 请用按表格导入");
        }
        verifyImage(bytes);
        if (!llmProperties.isConfigured()) {
            throw new BizException("图片识别尚未配置 AI 模型，请先用表格导入或手工录入；不会生成模拟采购数据");
        }
        String reply;
        try {
            reply = vision.chatVision(routeService.resolve(AiScenes.OCR), PROMPT, bytes, mime).getText();
        } catch (BizException ex) {
            throw ex;
        } catch (RuntimeException ex) {
            throw new BizException("图片识别服务暂不可用，请对照照片手工填写");
        }
        return parseReply(reply);
    }

    Preview parseReply(String reply) {
        JSONObject data;
        try {
            int start = reply == null ? -1 : reply.indexOf('{');
            int end = reply == null ? -1 : reply.lastIndexOf('}');
            if (start < 0 || end < start) throw new IllegalArgumentException("无 JSON 对象");
            data = JSONUtil.parseObj(reply.substring(start, end + 1));
        } catch (RuntimeException ex) {
            throw new BizException("图片识别结果无法解析，请重新拍清晰照片");
        }
        JSONArray items = data.getJSONArray("items");
        if (items == null || items.isEmpty() || items.size() > 200) {
            throw new BizException("没有识别出有效商品行，请重新拍清晰照片");
        }

        Preview result = new Preview();
        result.setDocumentNo(trim(data.getStr("documentNo")));
        result.setSupplierName(trim(data.getStr("supplierName")));
        result.setDeclaredTotal(decimal(data.get("total")));
        String date = trim(data.getStr("bizDate"));
        if (date != null) {
            try {
                result.setBizDate(LocalDate.parse(date).toString());
            } catch (DateTimeParseException ex) {
                result.getWarnings().add("日期未识别清楚，请手动选择入库日期");
            }
        } else {
            result.getWarnings().add("日期未识别清楚，请手动选择入库日期");
        }
        if (result.getSupplierName() != null) {
            List<Supplier> suppliers = supplierMapper.selectList(new LambdaQueryWrapper<Supplier>()
                    .eq(Supplier::getSupplierName, result.getSupplierName()));
            if (suppliers.size() == 1) result.setSupplierId(suppliers.get(0).getId());
        }
        if (result.getSupplierId() == null) {
            result.getWarnings().add("请手动选择供应商；单据上的收货单位不等于送货供应商");
        }

        Map<String, Product> byName = uniqueProducts();
        int index = 0;
        for (Object item : items) {
            index++;
            JSONObject raw;
            if (!(item instanceof JSONObject)) {
                throw new BizException("第 " + index + " 行识别格式异常，请重新上传照片");
            }
            raw = (JSONObject) item;
            Line line = new Line();
            line.setRowNo(index);
            line.setSourceName(trim(raw.getStr("name")));
            line.setQty(decimal(raw.get("qty")));
            line.setUnitPrice(decimal(raw.get("unitPrice")));
            line.setSourceAmount(decimal(raw.get("amount")));
            line.setCrossedOut(Boolean.TRUE.equals(raw.getBool("crossedOut")));
            line.setUncertain(Boolean.TRUE.equals(raw.getBool("uncertain")));
            if (line.getSourceName() != null) {
                Product product = byName.get(normalize(line.getSourceName()));
                // A blurred reading must not silently become a confirmed SKU, even if its text happens to match.
                if (product != null && !line.isUncertain()) {
                    line.setProductId(product.getId());
                    line.setProductName(product.getProductName());
                    line.setSkuCode(product.getSkuCode());
                    line.setBoxSpec(product.getBoxSpec());
                    line.setUnit(product.getUnit());
                } else {
                    line.setCandidates(candidates(line.getSourceName(), byName));
                }
            }
            if (line.isCrossedOut()) {
                line.getWarnings().add("票面已划掉，默认不带入入库单");
            } else {
                if (line.getProductId() == null) line.getWarnings().add("商品未能唯一匹配，请手动选择档案");
                if (line.isUncertain()) line.getWarnings().add("手写内容不清楚，请对照照片核对");
                if (line.getQty() == null || line.getQty().signum() <= 0 ||
                        line.getUnitPrice() == null || line.getUnitPrice().signum() <= 0) {
                    line.getWarnings().add("数量或单价缺失/无效，请手动核对");
                } else {
                    line.setCalculatedAmount(line.getQty().multiply(line.getUnitPrice()));
                    result.setCalculatedTotal(result.getCalculatedTotal().add(line.getCalculatedAmount()));
                    if (line.getSourceAmount() != null &&
                            line.getCalculatedAmount().subtract(line.getSourceAmount()).abs().compareTo(CENT) > 0) {
                        line.getWarnings().add("票面金额与数量×单价不一致");
                    }
                }
            }
            result.getRows().add(line);
        }
        if (result.getDeclaredTotal() != null &&
                result.getCalculatedTotal().subtract(result.getDeclaredTotal()).abs().compareTo(CENT) > 0) {
            result.getWarnings().add("票面合计与逐行计算不一致，请检查划掉的行及手写金额");
        }
        return result;
    }

    private Map<String, Product> uniqueProducts() {
        Map<String, Product> unique = new HashMap<>();
        Set<String> ambiguous = new HashSet<>();
        Map<Long, Product> products = new HashMap<>();
        for (Product product : productMapper.selectList(null)) {
            if ("停售".equals(product.getProductStatus())) continue;
            products.put(product.getId(), product);
            putUnique(unique, ambiguous, product.getProductName(), product);
        }
        for (SkuAlias alias : aliasMapper.selectList(null)) {
            putUnique(unique, ambiguous, alias.getAliasName(), products.get(alias.getProductId()));
        }
        ambiguous.forEach(unique::remove);
        return unique;
    }

    private static List<Candidate> candidates(String reading, Map<String, Product> names) {
        String target = normalize(reading);
        if (target.length() < 2) return new ArrayList<>();
        Map<Long, Integer> scores = new HashMap<>();
        Map<Long, Product> products = new HashMap<>();
        for (Map.Entry<String, Product> entry : names.entrySet()) {
            String name = entry.getKey();
            int score = similarity(target, name);
            if (score < 50) continue;
            Product product = entry.getValue();
            products.put(product.getId(), product);
            scores.merge(product.getId(), score, Math::max);
        }
        return scores.entrySet().stream()
                .sorted(Comparator.<Map.Entry<Long, Integer>>comparingInt(Map.Entry::getValue).reversed()
                        .thenComparing(Map.Entry::getKey))
                .limit(3)
                .map(entry -> new Candidate(products.get(entry.getKey())))
                .collect(Collectors.toList());
    }

    private static int similarity(String a, String b) {
        if (a.equals(b)) return 100;
        if (a.length() >= 3 && b.length() >= 3 && (a.contains(b) || b.contains(a))) {
            return 80 * Math.min(a.length(), b.length()) / Math.max(a.length(), b.length());
        }
        int[] previous = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) previous[j] = j;
        for (int i = 1; i <= a.length(); i++) {
            int[] current = new int[b.length() + 1];
            current[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                current[j] = Math.min(Math.min(current[j - 1] + 1, previous[j] + 1),
                        previous[j - 1] + (a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1));
            }
            previous = current;
        }
        return 100 * (Math.max(a.length(), b.length()) - previous[b.length()]) / Math.max(a.length(), b.length());
    }

    private static void putUnique(Map<String, Product> unique, Set<String> ambiguous, String name, Product product) {
        if (name == null || product == null) return;
        String key = normalize(name);
        if (key.isEmpty()) return;
        Product previous = unique.putIfAbsent(key, product);
        if (previous != null && !previous.getId().equals(product.getId())) ambiguous.add(key);
    }

    private static String normalize(String name) {
        return name.replaceAll("[\\s\\p{Punct}，。；：（）【】]+", "").toLowerCase(java.util.Locale.ROOT);
    }

    private static String trim(String text) {
        return text == null || text.trim().isEmpty() || "null".equalsIgnoreCase(text.trim()) ? null : text.trim();
    }

    private static BigDecimal decimal(Object value) {
        if (value == null) return null;
        try {
            return new BigDecimal(value.toString().trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String imageMime(byte[] bytes) {
        if (bytes.length >= 8 && (bytes[0] & 0xff) == 0x89 && bytes[1] == 'P' && bytes[2] == 'N'
                && bytes[3] == 'G' && bytes[4] == '\r' && bytes[5] == '\n') return "image/png";
        if (bytes.length >= 3 && (bytes[0] & 0xff) == 0xff && (bytes[1] & 0xff) == 0xd8
                && (bytes[2] & 0xff) == 0xff) return "image/jpeg";
        return null;
    }

    private static void verifyImage(byte[] bytes) {
        try (javax.imageio.stream.ImageInputStream stream = javax.imageio.ImageIO.createImageInputStream(
                new ByteArrayInputStream(bytes))) {
            java.util.Iterator<javax.imageio.ImageReader> readers = javax.imageio.ImageIO.getImageReaders(stream);
            if (!readers.hasNext()) throw new BizException("照片无法读取，请上传清晰的 JPG 或 PNG");
            javax.imageio.ImageReader reader = readers.next();
            try {
                reader.setInput(stream);
                int width = reader.getWidth(0);
                int height = reader.getHeight(0);
                if (width < 400 || height < 400 || (long) width * height > 40_000_000L) {
                    throw new BizException("照片分辨率过低或过大，请使用清晰的单据照片");
                }
            } finally {
                reader.dispose();
            }
        } catch (IOException e) {
            throw new BizException("照片无法读取，请上传清晰的 JPG 或 PNG");
        }
    }
}
