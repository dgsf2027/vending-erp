package top.aole.vend.modules.basedata.application;

import cn.hutool.core.util.StrUtil;
import top.aole.vend.modules.basedata.domain.entity.Product;
import top.aole.vend.modules.basedata.domain.entity.SkuAlias;
import top.aole.vend.modules.basedata.interfaces.dto.ProductImportDtos.Row;
import java.math.BigDecimal;
import java.text.Normalizer;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/** 建档身份匹配：编号/条码证据有冲突时不允许自动覆盖或新建。 */
public class ProductCatalogMatcher {
    public static final String MATCHED = "已匹配";
    public static final String NEW = "新增商品";
    public static final String REVIEW = "待确认";
    public static final String ERROR = "资料缺失";
    private static final Pattern SIZE = Pattern.compile("(\\d+(?:\\.\\d+)?)(毫升|ml|升|l|千克|kg|克|g)");
    private static final Pattern NORMAL_SIZE = Pattern.compile("\\d+(?:[.\\-~]\\d+)*(?:ml|g)");
    private final List<Product> products;
    private final List<SkuAlias> aliases;
    private final Map<Long, Product> byId;

    public ProductCatalogMatcher(List<Product> products, List<SkuAlias> aliases) {
        this.products = products;
        this.aliases = aliases;
        this.byId = products.stream().collect(Collectors.toMap(Product::getId, p -> p));
    }

    public void resolve(Row row) {
        row.setAction(null);
        row.setErrorMsg(null);
        row.setExistingProductId(null);
        row.setMatchedProductName(null);
        row.setCandidates(new ArrayList<>());
        String code = trim(row.getSourceCode());
        String barcode = trim(row.getBarcode());
        String erp = trim(row.getSkuCode());
        Set<Long> strong = new LinkedHashSet<>();
        for (Product p : products) {
            if ((!erp.isEmpty() && erp.equals(p.getSkuCode()))
                    || (!barcode.isEmpty() && barcode.equals(trim(p.getBarcode())))) strong.add(p.getId());
        }
        List<SkuAlias> codeAliases = aliases.stream().filter(a -> !code.isEmpty()
                && code.equals(trim(a.getAliasCode())) && byId.containsKey(a.getProductId())).collect(Collectors.toList());
        // 一个后台编号可能对应历史拆分商品；有条码时优先使用完整绑定键。
        List<SkuAlias> exact = codeAliases.stream().filter(a -> barcode.equals(trim(a.getAliasBarcode()))).collect(Collectors.toList());
        (exact.isEmpty() ? codeAliases : exact).forEach(a -> strong.add(a.getProductId()));
        for (SkuAlias a : aliases) if (!barcode.isEmpty() && barcode.equals(trim(a.getAliasBarcode()))
                && byId.containsKey(a.getProductId())) strong.add(a.getProductId());
        // 兼容旧账中“后台编号即 SKU”的历史资料，新商品不再使用后台号创建 SKU。
        if (codeAliases.isEmpty()) for (Product p : products) if (!code.isEmpty() && code.equals(p.getSkuCode())) strong.add(p.getId());
        if (!erp.isEmpty() && products.stream().noneMatch(p -> erp.equals(p.getSkuCode()))) {
            reject(row, "ERP 编码不存在；新商品请留空，由系统自动编号", Collections.emptyList());
            return;
        }
        if (strong.size() > 1) {
            reject(row, "后台编号、ERP 编码或条码指向不同商品，请核对原始编号和条码", candidates(strong));
            return;
        }
        Product hit = strong.isEmpty() ? null : byId.get(strong.iterator().next());
        if (hit != null) {
            if (row.isConfirmNew()) {
                reject(row, "编号或条码已有档案，请沿用已有编码", Collections.singletonList(hit));
                return;
            }
            if (row.getSelectedProductId() != null && !row.getSelectedProductId().equals(hit.getId())) {
                reject(row, "所选商品与编号或条码的已有绑定不一致", Collections.singletonList(hit));
                return;
            }
            if (conflictingSize(row.getProductName(), hit.getProductName()) || barcodeConflict(barcode, hit)) {
                reject(row, "名称规格或条码与已有档案不一致，请先核对资料", Collections.singletonList(hit));
                return;
            }
            accept(row, hit, "按条码或已有编号绑定识别");
            return;
        }
        if (row.getSelectedProductId() != null) {
            hit = byId.get(row.getSelectedProductId());
            if (hit == null || conflictingSize(row.getProductName(), hit.getProductName()) || barcodeConflict(barcode, hit)) {
                reject(row, "所选商品不存在，或容量/条码不符，请重新选择", Collections.emptyList());
                return;
            }
            accept(row, hit, "已人工确认对应商品");
            return;
        }
        Set<Long> names = new LinkedHashSet<>();
        String name = normalize(row.getProductName());
        for (Product p : products) if (name.equals(normalize(p.getProductName()))) names.add(p.getId());
        for (SkuAlias a : aliases) if (byId.containsKey(a.getProductId()) && name.equals(normalize(a.getAliasName()))) names.add(a.getProductId());
        if (names.size() == 1 && !row.isConfirmNew()) {
            hit = byId.get(names.iterator().next());
            if (!barcodeConflict(barcode, hit) && !conflictingSize(row.getProductName(), hit.getProductName())) {
                accept(row, hit, "名称与规格唯一一致");
                return;
            }
        }
        List<Product> possible = products.stream().filter(p -> !conflictingSize(row.getProductName(), p.getProductName()))
                .filter(p -> names.contains(p.getId()) || similarity(name, normalize(p.getProductName())) >= 0.65)
                .sorted(Comparator.comparingDouble((Product p) -> similarity(name, normalize(p.getProductName()))).reversed()
                        .thenComparing(Product::getId)).limit(5).collect(Collectors.toList());
        if (!row.isConfirmNew() && !possible.isEmpty()) {
            reject(row, "发现同名或相似商品，请选择已有档案，或确认这是不同的新商品", possible);
            return;
        }
        // 已确认同名不同商品时，要求有可区分的条码或明确补充不同规格，避免误建重复。
        if (row.isConfirmNew() && !names.isEmpty() && barcode.isEmpty()) {
            reject(row, "同名商品已存在，请补充不同的规格名称或条码后新建", candidates(names));
            return;
        }
        row.setSkuCode(null);
        row.setAction(NEW);
        row.setMatchReason("确认入档时自动生成唯一 SKU 编码");
    }

    private boolean barcodeConflict(String barcode, Product product) {
        if (barcode.isEmpty()) return false;
        Set<String> known = new HashSet<>();
        if (!trim(product.getBarcode()).isEmpty()) known.add(trim(product.getBarcode()));
        for (SkuAlias a : aliases) if (product.getId().equals(a.getProductId()) && !trim(a.getAliasBarcode()).isEmpty()) known.add(trim(a.getAliasBarcode()));
        return !known.isEmpty() && !known.contains(barcode);
    }

    private List<Product> candidates(Set<Long> ids) {
        return ids.stream().map(byId::get).filter(Objects::nonNull).limit(5).collect(Collectors.toList());
    }

    private static void accept(Row row, Product hit, String reason) {
        row.setExistingProductId(hit.getId());
        row.setSkuCode(hit.getSkuCode());
        row.setMatchedProductName(hit.getProductName());
        if (StrUtil.isBlank(row.getUnit())) row.setUnit(hit.getUnit());
        if (StrUtil.isBlank(row.getBoxSpec()) && hit.getBoxSpec() != null) row.setBoxSpec(hit.getBoxSpec().stripTrailingZeros().toPlainString());
        row.setAction(MATCHED);
        row.setMatchReason(reason);
    }

    private static void reject(Row row, String reason, List<Product> candidates) {
        row.setAction(REVIEW);
        row.setErrorMsg(reason);
        row.setMatchReason(reason);
        row.setCandidates(candidates);
    }

    public static String normalize(String value) {
        String text = Normalizer.normalize(trim(value), Normalizer.Form.NFKC).toLowerCase(Locale.ROOT).replaceAll("\\s+", "");
        Matcher matcher = SIZE.matcher(text);
        StringBuffer result = new StringBuffer();
        while (matcher.find()) {
            String unit = matcher.group(2);
            BigDecimal number = new BigDecimal(matcher.group(1));
            if (unit.equals("升") || unit.equals("l") || unit.equals("kg") || unit.equals("千克")) number = number.multiply(BigDecimal.valueOf(1000));
            matcher.appendReplacement(result, Matcher.quoteReplacement(number.stripTrailingZeros().toPlainString()
                    + (Arrays.asList("毫升", "ml", "升", "l").contains(unit) ? "ml" : "g")));
        }
        matcher.appendTail(result);
        return result.toString().replaceAll("[（）()【】\\[\\]，,]", "");
    }

    private static Set<String> sizes(String name) {
        Set<String> values = new HashSet<>();
        Matcher matcher = NORMAL_SIZE.matcher(normalize(name));
        while (matcher.find()) values.add(matcher.group());
        return values;
    }

    private static boolean conflictingSize(String a, String b) {
        Set<String> x = sizes(a), y = sizes(b);
        return !x.isEmpty() && !y.isEmpty() && !x.equals(y);
    }

    private static double similarity(String a, String b) {
        a = NORMAL_SIZE.matcher(a).replaceAll("");
        b = NORMAL_SIZE.matcher(b).replaceAll("");
        if (a.equals(b)) return 1;
        if (Math.min(a.length(), b.length()) < 3) return 0;
        Set<Character> x = new HashSet<>(), y = new HashSet<>();
        for (char c : a.toCharArray()) x.add(c);
        for (char c : b.toCharArray()) y.add(c);
        Set<Character> common = new HashSet<>(x);
        common.retainAll(y);
        return 2.0 * common.size() / (x.size() + y.size());
    }

    private static String trim(String value) { return StrUtil.nullToEmpty(StrUtil.trim(value)); }
}
