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
import java.text.Normalizer;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 拿货单照片只生成可修改的预览；建草稿和过账仍由原采购流程处理。 */
@Service
@RequiredArgsConstructor
public class PurchasePhotoPreviewService {
    private static final long MAX_BYTES = 10L * 1024 * 1024;
    private static final BigDecimal CENT = new BigDecimal("0.01");
    private static final Pattern VOLUME = Pattern.compile("(\\d+(?:\\.\\d+)?)(毫升|ml|升|l)(?![a-z])");
    static final String PROMPT = "识别这张印刷或手写的送货单/拿货单，只输出 JSON 对象，不要解释或代码围栏。"
            + "格式:{\"documentNo\":null,\"bizDate\":null,\"supplierName\":null,\"total\":null,"
            + "\"items\":[{\"name\":null,\"sourceUnit\":null,\"sourceSpec\":null,\"qty\":null,\"unitPrice\":null,\"amount\":null,"
            + "\"crossedOut\":false,\"uncertain\":false}]}。"
            + "日期用 yyyy-MM-dd，金额和数量只写数字；看不清填 null 并把 uncertain 设为 true。"
            + "按图片从上到下逐行保留商品，划掉/作废的行也保留并标 crossedOut=true。"
            + "sourceUnit 只抄录票面明确标注的数量单位（如箱、件、瓶、袋），没有或看不清填 null。"
            + "sourceSpec 只抄录票面规格或商品名中明确写出的容量/规格（如 1L、500毫升），没有或看不清填 null。"
            + "名称、规格、单位分列时分别抄录，name 保留原始商品名，不能省略已有的口味、容量。"
            + "数量和单价保持票面单位口径，不要自动把箱换成瓶，不得根据价格高低或常识猜测单位或箱规。"
            + "不要把左侧打勾当作数量或单位，不要把‘收货单位’当作供应商，不能臆造箱规或商品编码。"
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
        private String sourceUnit;
        private String sourceSpec;
        /** 数量/单价的票面口径；仅由明确单位推断，证据不足时留空。 */
        private String unitMode;
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

        ProductNames byName = uniqueProducts();
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
            line.setSourceUnit(trim(raw.getStr("sourceUnit")));
            line.setSourceSpec(trim(raw.getStr("sourceSpec")));
            line.setQty(decimal(raw.get("qty")));
            line.setUnitPrice(decimal(raw.get("unitPrice")));
            line.setSourceAmount(decimal(raw.get("amount")));
            line.setCrossedOut(Boolean.TRUE.equals(raw.getBool("crossedOut")));
            line.setUncertain(Boolean.TRUE.equals(raw.getBool("uncertain")));
            if (line.getSourceName() != null) {
                Product product = byName.match(line.getSourceName(), line.getSourceSpec());
                if (product != null) {
                    line.setProductId(product.getId());
                    line.setProductName(product.getProductName());
                    line.setSkuCode(product.getSkuCode());
                    line.setBoxSpec(product.getBoxSpec());
                    line.setUnit(product.getUnit());
                }
            }
            line.setUnitMode(unitMode(line.getSourceUnit(), line.getUnit()));
            if (line.isCrossedOut()) {
                line.getWarnings().add("票面已划掉，默认不带入入库单");
            } else {
                if (line.getProductId() == null) line.getWarnings().add("商品未能唯一匹配，请手动选择档案");
                if (line.getUnitMode() == null) line.getWarnings().add("票面单位未能确定，请核对数量和单价按箱还是基本单位填写");
                if ("box".equals(line.getUnitMode()) && (line.getBoxSpec() == null || line.getBoxSpec().signum() <= 0)) {
                    line.getWarnings().add("商品档案缺少有效箱规，请核对每箱数量");
                }
                if (line.isUncertain()) line.getWarnings().add("票面内容不清楚，请对照照片核对");
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

    private ProductNames uniqueProducts() {
        ProductNames names = new ProductNames();
        Map<Long, Product> products = new HashMap<>();
        for (Product product : productMapper.selectList(null)) {
            if ("停售".equals(product.getProductStatus())) continue;
            products.put(product.getId(), product);
            names.add(product.getProductName(), product);
        }
        for (SkuAlias alias : aliasMapper.selectList(null)) {
            names.add(alias.getAliasName(), products.get(alias.getProductId()));
        }
        names.ambiguous.forEach(names.unique::remove);
        return names;
    }

    /** 只做规范化后的精确名称/别名匹配，不根据相似度跨容量猜商品。 */
    private static class ProductNames {
        private final Map<String, Product> unique = new HashMap<>();
        private final Set<String> ambiguous = new HashSet<>();
        private final Map<Long, Set<String>> namesByProduct = new HashMap<>();

        void add(String name, Product product) {
            if (name == null || product == null) return;
            String key = normalize(name);
            if (key.isEmpty()) return;
            namesByProduct.computeIfAbsent(product.getId(), id -> new HashSet<>()).add(key);
            Product previous = unique.putIfAbsent(key, product);
            if (previous != null && !previous.getId().equals(product.getId())) ambiguous.add(key);
        }

        Product match(String sourceName, String sourceSpec) {
            String name = normalize(sourceName);
            if (sourceSpec == null) return unique.get(name);
            String spec = normalize(sourceSpec);
            if (spec.isEmpty()) return null;
            Product combined = unique.get(normalize(sourceName + sourceSpec));
            if (combined != null) return combined;
            Product exact = unique.get(name);
            if (exact == null) return null;
            // 已带规格的名称不需要重复追加；票面另列的规格必须有档案名称/别名佐证。
            for (String knownName : namesByProduct.get(exact.getId())) {
                int start = knownName.indexOf(spec);
                while (start >= 0) {
                    int end = start + spec.length();
                    boolean leftBoundary = start == 0 || (!Character.isDigit(knownName.charAt(start - 1))
                            && knownName.charAt(start - 1) != '.');
                    boolean rightBoundary = end == knownName.length() || (!Character.isDigit(knownName.charAt(end))
                            && knownName.charAt(end) != '.');
                    if (leftBoundary && rightBoundary) return exact;
                    start = knownName.indexOf(spec, start + 1);
                }
            }
            return null;
        }
    }

    private static String normalize(String name) {
        String normalized = Normalizer.normalize(name, Normalizer.Form.NFKC)
                .toLowerCase(java.util.Locale.ROOT).replaceAll("\\s+", "");
        Matcher volumes = VOLUME.matcher(normalized);
        StringBuffer result = new StringBuffer();
        while (volumes.find()) {
            BigDecimal value = new BigDecimal(volumes.group(1));
            if ("升".equals(volumes.group(2)) || "l".equals(volumes.group(2))) {
                value = value.multiply(new BigDecimal("1000"));
            }
            volumes.appendReplacement(result, Matcher.quoteReplacement(value.stripTrailingZeros().toPlainString() + "ml"));
        }
        volumes.appendTail(result);
        // 小数点及规格连接符不可删除，防止 1.5/15、1-5L/15L 等被合并。
        return result.toString().replaceAll("[\\p{Punct}&&[^.\\-+/*]]|[，。；：（）【】]", "");
    }

    private static String unitMode(String sourceUnit, String productUnit) {
        if (sourceUnit == null) return null;
        // 单位保留问号、斜杠等不确定标记，不能把“箱?”清洗成明确的“箱”。
        String unit = Normalizer.normalize(sourceUnit, Normalizer.Form.NFKC).replaceAll("\\s+", "");
        if (unit.isEmpty()) return null;
        if (productUnit != null && unit.equals(Normalizer.normalize(productUnit, Normalizer.Form.NFKC)
                .replaceAll("\\s+", ""))) return "base";
        if ("箱".equals(unit) || "件".equals(unit) || "整箱".equals(unit) || "整件".equals(unit)) return "box";
        return null;
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
