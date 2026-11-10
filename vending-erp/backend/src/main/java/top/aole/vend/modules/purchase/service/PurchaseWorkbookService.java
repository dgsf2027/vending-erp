package top.aole.vend.modules.purchase.service;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import top.aole.vend.common.exception.BizException;
import top.aole.vend.modules.basedata.application.ProductCatalogMatcher;
import top.aole.vend.modules.basedata.domain.entity.Product;
import top.aole.vend.modules.basedata.domain.entity.SkuAlias;
import top.aole.vend.modules.basedata.infrastructure.mapper.ProductMapper;
import top.aole.vend.modules.basedata.infrastructure.mapper.SkuAliasMapper;
import top.aole.vend.modules.imports.parser.ExcelParser;
import top.aole.vend.modules.imports.parser.ParsedSheet;
import top.aole.vend.modules.purchase.dto.PurchaseWorkbookDtos.*;
import top.aole.vend.modules.purchase.mapper.PurchaseSalesMatchMapper;
import top.aole.vend.modules.purchase.mapper.PurchaseSalesMatchMapper.SaleSummary;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.Normalizer;
import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;

/** 原表解析、口味候选、同日同品归集均为只读；保存和确认仍走采购单据。 */
@Service
@RequiredArgsConstructor
public class PurchaseWorkbookService {
    private final ExcelParser parser;
    private final ProductMapper products;
    private final SkuAliasMapper aliases;
    private final PurchaseSalesMatchMapper sales;

    public Preview parse(MultipartFile file) {
        if (file.isEmpty() || file.getSize() > 5 * 1024 * 1024 || file.getOriginalFilename() == null
                || !file.getOriginalFilename().toLowerCase(Locale.ROOT).matches(".*\\.xlsx?$"))
            throw new BizException("请选择不超过 5MB 的 .xls 或 .xlsx 拿货表");
        ParsedSheet sheet;
        try { sheet = parser.parse(file.getInputStream(), h -> h.stream().anyMatch(s -> "skuCode".equals(column(s)) || "sourceName".equals(column(s)))
            && h.stream().anyMatch(s -> "qty".equals(column(s)) || "boxCount".equals(column(s)))); }
        catch (Exception e) { throw new BizException("无法读取拿货表，请选择未加密、未损坏的 Excel 文件"); }
        if (sheet.getRows().size() > 500) throw new BizException("每次最多读取 500 行拿货明细");
        Map<String, String> columns = new HashMap<>();
        for (String h : sheet.getHeaders()) {
            String field = column(h);
            if (field != null && columns.putIfAbsent(field, h) != null) throw new BizException("重复表头：" + h);
        }
        if ((!columns.containsKey("sourceName") && !columns.containsKey("skuCode"))
            || (!columns.containsKey("qty") && !(columns.containsKey("boxCount") && columns.containsKey("boxSpec"))))
            throw new BizException("未找到拿货表表头，需要产品名称或商品编码，以及采购数量（或整件规格、总件数）");
        List<SourceRow> rows = new ArrayList<>(); List<String> errors = new ArrayList<>();
        int skipped = 0; BigDecimal sectionTotal = BigDecimal.ZERO;
        for (ParsedSheet.Row input : sheet.getRows()) {
            String name = get(input, columns, "sourceName"), code = get(input, columns, "skuCode");
            boolean subtotal = (name.isEmpty() || name.matches("合计|总计|小计")) && code.isEmpty()
                && get(input, columns, "qty").isEmpty() && get(input, columns, "boxCount").isEmpty()
                && get(input, columns, "boxSpec").isEmpty() && !get(input, columns, "totalPrice").isEmpty();
            if (subtotal) {
                skipped++;
                try {
                    if (number(get(input, columns, "totalPrice")).compareTo(sectionTotal) != 0)
                        errors.add("第 " + input.getRowNo() + " 行小计与上方明细不一致，请核对原表");
                } catch (RuntimeException e) { errors.add("第 " + input.getRowNo() + " 行小计不是有效金额"); }
                sectionTotal = BigDecimal.ZERO; continue;
            }
            SourceRow row = new SourceRow(); row.setRowNo(input.getRowNo()); row.setSourceName(name); row.setSkuCode(code);
            row.setBizDate(get(input, columns, "bizDate")); row.setHint(get(input, columns, "hint"));
            try {
                row.setQty(number(get(input, columns, "qty"))); row.setBoxSpec(number(get(input, columns, "boxSpec")));
                row.setBoxCount(number(get(input, columns, "boxCount"))); row.setBoxPrice(number(get(input, columns, "boxPrice")));
                row.setTotalPrice(number(get(input, columns, "totalPrice"))); row.setUnitPrice(number(get(input, columns, "unitPrice")));
                if (row.getQty() == null && row.getBoxSpec() != null && row.getBoxCount() != null) row.setQty(row.getBoxSpec().multiply(row.getBoxCount()));
                if (row.getTotalPrice() == null && row.getBoxPrice() != null && row.getBoxCount() != null) row.setTotalPrice(row.getBoxPrice().multiply(row.getBoxCount()));
                if (row.getTotalPrice() == null && row.getUnitPrice() != null && row.getQty() != null)
                    row.setTotalPrice(row.getQty().multiply(row.getUnitPrice()).setScale(2, RoundingMode.HALF_UP));
                if (row.getTotalPrice() != null) sectionTotal = sectionTotal.add(row.getTotalPrice());
            } catch (RuntimeException e) { errors.add("第 " + row.getRowNo() + " 行含无法识别的数量或价格，请修改原表"); }
            rows.add(row);
        }
        Preview result = review(rows, null);
        result.setFileName(file.getOriginalFilename()); result.setSkippedSubtotals(skipped); result.getErrors().addAll(0, errors);
        result.getWarnings().add("同一天、同一商品编码自动合并，保留来源行；不同日期分别带入入库单。");
        result.getWarnings().add("销售数量来自已导入的正常销售及兑换记录，仅供核对口味，不能作为实际进货口味比例。");
        return result;
    }

    public Preview review(List<SourceRow> input, String defaultDate) {
        if (input == null || input.isEmpty() || input.size() > 500) throw new BizException("请提供 1 至 500 行拿货明细");
        List<Product> catalog = products.selectList(null);
        List<SkuAlias> aliasList = aliases.selectList(null);
        List<SaleSummary> saleList = sales.summaries();
        Map<Long, Product> byId = catalog.stream().collect(Collectors.toMap(Product::getId, p -> p));
        Preview result = new Preview(); Map<String, Day> days = new TreeMap<>();
        Set<Integer> rowNumbers = new HashSet<>();
        for (SourceRow source : input) {
            if (source == null) throw new BizException("明细不能包含空行对象");
            SourceRow r = new SourceRow(); BeanUtils.copyProperties(source, r);
            r.setErrors(new ArrayList<>()); r.setCandidates(new ArrayList<>()); r.setSuggestedProductId(null);
            if (r.getRowNo() <= 0 || !rowNumbers.add(r.getRowNo())) throw new BizException("来源行号缺失或重复，请重新上传原表");
            r.setSourceName(text(r.getSourceName())); r.setSkuCode(text(r.getSkuCode())); r.setHint(text(r.getHint()));
            try { r.setBizDate(date(text(r.getBizDate()).isEmpty() ? defaultDate : r.getBizDate())); }
            catch (RuntimeException e) { r.setBizDate(""); r.getErrors().add("请填写有效采购日期"); }
            validateNumbers(r);
            resolve(r, catalog, aliasList, saleList);
            if (r.getAllocations() == null) r.setAllocations(new ArrayList<>());
            if (r.getAllocations().isEmpty()) r.getErrors().add("请选择对应的销售商品；混装可拆分口味");
            else {
                Set<Long> used = new HashSet<>(); BigDecimal assigned = BigDecimal.ZERO;
                for (Allocation a : r.getAllocations()) {
                    if (a == null || a.getProductId() == null || !byId.containsKey(a.getProductId())) { r.getErrors().add("所选商品不存在"); continue; }
                    if (!used.add(a.getProductId())) r.getErrors().add("拆分口味不能重复选择同一商品");
                    if ("清仓中".equals(byId.get(a.getProductId()).getProductStatus())) r.getErrors().add("清仓中商品不能采购");
                    if (a.getQty() == null || a.getQty().signum() <= 0 || a.getQty().stripTrailingZeros().scale() > 3) r.getErrors().add("各口味数量必须大于 0，最多三位小数");
                    else assigned = assigned.add(a.getQty());
                }
                if (r.getQty() != null && assigned.compareTo(r.getQty()) != 0) r.getErrors().add("各口味数量之和必须等于原行采购数量 " + r.getQty().stripTrailingZeros().toPlainString());
            }
            if (r.isNeedsConfirmation() && !r.isConfirmed()) r.getErrors().add("请确认这行的口味与销售商品关联");
            Day day = days.computeIfAbsent(text(r.getBizDate()), key -> { Day d = new Day(); d.setBizDate(key); return d; });
            day.setSourceCount(day.getSourceCount() + 1);
            if (r.getQty() != null) day.setTotalQty(day.getTotalQty().add(r.getQty()));
            if (r.getTotalPrice() != null) day.setTotalAmount(day.getTotalAmount().add(r.getTotalPrice()));
            for (String error : r.getErrors()) day.getErrors().add("第 " + r.getRowNo() + " 行：" + error);
            if (r.getErrors().isEmpty()) addToDay(day, r, byId);
            result.getRows().add(r);
        }
        for (Day day : days.values()) for (GroupLine line : day.getLines()) {
            line.setUnitPrice(line.getTotalPrice().divide(line.getQty(), 4, RoundingMode.HALF_UP));
            if (line.getUnitPrice().signum() <= 0 || line.getUnitPrice().precision() - line.getUnitPrice().scale() > 8)
                day.getErrors().add(line.getProductName() + "折算后的进货单价超出系统范围");
        }
        result.setDays(new ArrayList<>(days.values())); return result;
    }

    private void resolve(SourceRow r, List<Product> catalog, List<SkuAlias> aliasList, List<SaleSummary> saleList) {
        Product coded = catalog.stream().filter(p -> !r.getSkuCode().isEmpty() && r.getSkuCode().equalsIgnoreCase(p.getSkuCode())).findFirst().orElse(null);
        String name = norm(r.getSourceName()), hint = norm(r.getHint());
        Set<Long> exact = catalog.stream().filter(p -> !name.isEmpty() && (name.equals(norm(p.getProductName())) || hint.equals(norm(p.getProductName()))))
            .map(Product::getId).collect(Collectors.toSet());
        aliasList.stream().filter(a -> !name.isEmpty() && name.equals(norm(a.getAliasName()))).forEach(a -> exact.add(a.getProductId()));
        List<Product> possible = catalog.stream().filter(p -> p == coded || exact.contains(p.getId())
            || r.getAllocations() != null && r.getAllocations().stream().anyMatch(a -> a != null && p.getId().equals(a.getProductId()))
            || sameFamily(r.getSourceName(), p.getProductName()) || r.getHint().contains(p.getSkuCode())
            || (!name.isEmpty() && ProductCatalogMatcher.similarity(name, norm(p.getProductName())) >= 0.55))
            .sorted(Comparator.comparingDouble((Product p) -> score(r, p, exact)).reversed()).limit(12).collect(Collectors.toList());
        for (Product p : possible) {
            Candidate c = new Candidate(); c.setProductId(p.getId()); c.setSkuCode(p.getSkuCode()); c.setProductName(p.getProductName());
            c.setUnit(p.getUnit()); c.setBoxSpec(p.getBoxSpec());
            c.setReason(p == coded ? "表中商品编码" : exact.contains(p.getId()) ? "名称或已绑定别名一致" : "相似商品 / 口味候选");
            for (SaleSummary sale : saleList) if (p.getId().equals(sale.getProductId())) {
                c.setSalesQty(c.getSalesQty().add(sale.getQty() == null ? BigDecimal.ZERO : sale.getQty()));
                if (sale.getSourceName() != null && !c.getSalesNames().contains(sale.getSourceName())) c.getSalesNames().add(sale.getSourceName());
                if (sale.getFirstSale() != null && (c.getFirstSale() == null || c.getFirstSale().compareTo(sale.getFirstSale()) > 0)) c.setFirstSale(sale.getFirstSale());
                if (sale.getLastSale() != null && (c.getLastSale() == null || c.getLastSale().compareTo(sale.getLastSale()) < 0)) c.setLastSale(sale.getLastSale());
            }
            r.getCandidates().add(c);
        }
        Product unique = exact.size() == 1 ? catalog.stream().filter(p -> exact.contains(p.getId())).findFirst().orElse(null) : null;
        if (!possible.isEmpty()) {
            Product best = possible.get(0);
            if (exact.contains(best.getId()) || (!flavors(r.getSourceName()).isEmpty() && flavors(best.getProductName()).containsAll(flavors(r.getSourceName()))))
                r.setSuggestedProductId(best.getId());
        }
        boolean multipleFlavors = possible.stream().filter(p -> sameFamily(r.getSourceName(), p.getProductName())).count() > 1;
        boolean flavorConflict = coded != null && !flavors(coded.getProductName()).containsAll(flavors(r.getSourceName()));
        boolean sizeConflict = coded != null && ProductCatalogMatcher.conflictingSize(r.getSourceName(), coded.getProductName());
        boolean genericFlavor = coded != null && multipleFlavors && flavors(r.getSourceName()).isEmpty() && !exact.contains(coded.getId());
        r.setNeedsConfirmation(coded == null && unique == null || flavorConflict || genericFlavor || sizeConflict
            || coded != null && unique != null && !coded.getId().equals(unique.getId()));
        if (!r.getSkuCode().isEmpty() && coded == null) r.setNeedsConfirmation(true);
        r.setMatchMessage(flavorConflict || genericFlavor || sizeConflict ? "表中编码与销售口味或规格需核对：可选择实际口味，混装可拆分；确认只决定本次采购归属。"
            : coded != null ? "按表中商品编码匹配，销售数据供核对" : unique != null ? "按名称或已有别名唯一匹配" : "找到多个销售商品候选，请确认口味与编码");
        if (r.getAllocations() == null || r.getAllocations().isEmpty()) {
            Product selected = genericFlavor ? null : flavorConflict && r.getSuggestedProductId() != null
                ? catalog.stream().filter(p -> p.getId().equals(r.getSuggestedProductId())).findFirst().orElse(null) : coded != null ? coded : unique;
            r.setAllocations(new ArrayList<>());
            if (selected != null) { Allocation a = new Allocation(); a.setProductId(selected.getId()); a.setQty(r.getQty()); r.getAllocations().add(a); }
        }
        // 人工改配及口味拆分必须有明确确认，不能把旧的自动匹配状态沿用到新选择。
        if (r.getAllocations().size() > 1 || r.getAllocations().stream().anyMatch(a -> a == null || a.getProductId() == null
            || (coded != null ? !coded.getId().equals(a.getProductId()) : unique == null || !unique.getId().equals(a.getProductId())))) r.setNeedsConfirmation(true);
    }

    private static void addToDay(Day day, SourceRow r, Map<Long, Product> catalog) {
        BigDecimal allocated = BigDecimal.ZERO;
        for (int i = 0; i < r.getAllocations().size(); i++) {
            Allocation a = r.getAllocations().get(i); Product p = catalog.get(a.getProductId());
            BigDecimal amount = i == r.getAllocations().size() - 1 ? r.getTotalPrice().subtract(allocated)
                : r.getTotalPrice().multiply(a.getQty()).divide(r.getQty(), 2, RoundingMode.HALF_UP);
            if (amount.signum() <= 0) { day.getErrors().add("第 " + r.getRowNo() + " 行口味拆分金额不足一分，请调整数量"); return; }
            allocated = allocated.add(amount);
            GroupLine group = day.getLines().stream().filter(g -> g.getProductId().equals(p.getId())).findFirst().orElse(null);
            if (group == null) { group = new GroupLine(); group.setProductId(p.getId()); group.setSkuCode(p.getSkuCode()); group.setProductName(p.getProductName()); group.setUnit(p.getUnit()); day.getLines().add(group); }
            group.setQty(group.getQty().add(a.getQty())); group.setTotalPrice(group.getTotalPrice().add(amount));
            group.getSourceRows().add(r.getRowNo()); if (!group.getSourceNames().contains(r.getSourceName())) group.getSourceNames().add(r.getSourceName());
        }
    }
    private static void validateNumbers(SourceRow r) {
        try {
            positive(r.getQty(), "采购数量", 3); positive(r.getTotalPrice(), "总价格", 2);
            if (r.getBoxSpec() != null || r.getBoxCount() != null || r.getBoxPrice() != null) {
                positive(r.getBoxSpec(), "整件规格", 3); positive(r.getBoxCount(), "总件数", 3); positive(r.getBoxPrice(), "整件价格", 2);
                if (r.getBoxSpec().multiply(r.getBoxCount()).compareTo(r.getQty()) != 0) throw new BizException("采购数量应等于整件规格 × 总件数");
                if (r.getBoxPrice().multiply(r.getBoxCount()).compareTo(r.getTotalPrice()) != 0) throw new BizException("总价格应等于整件价格 × 总件数");
            }
            r.setUnitPrice(r.getTotalPrice().divide(r.getQty(), 4, RoundingMode.HALF_UP));
            if (r.getSourceName().isEmpty() && r.getSkuCode().isEmpty()) throw new BizException("产品名称和商品编码至少填一项");
        } catch (BizException e) { r.getErrors().add(e.getMessage()); }
    }
    private static void positive(BigDecimal n, String field, int scale) {
        if (n == null || n.signum() <= 0 || n.stripTrailingZeros().scale() > scale || n.precision() - n.scale() > 8)
            throw new BizException(field + "须大于0，最多8位整数、" + scale + "位小数");
    }
    private static BigDecimal number(String value) { return value.isEmpty() ? null : new BigDecimal(value.replace(",", "").replace("￥", "").replace("¥", "")); }
    private static String date(String value) {
        String s = text(value).replace('.', '-').replace('/', '-');
        if (s.length() > 10) s = s.substring(0, 10);
        return LocalDate.parse(s, java.time.format.DateTimeFormatter.ofPattern("uuuu-M-d").withResolverStyle(java.time.format.ResolverStyle.STRICT)).toString();
    }
    private static String text(String s) { return s == null ? "" : s.trim(); }
    private static String norm(String s) { return ProductCatalogMatcher.normalize(text(s)); }
    private static String get(ParsedSheet.Row row, Map<String, String> columns, String field) { return columns.containsKey(field) ? text(row.get(columns.get(field))) : ""; }
    private static String column(String raw) {
        String h = Normalizer.normalize(text(raw), Normalizer.Form.NFKC).replaceAll("\\s+", "");
        switch (h) {
            case "采购日期": case "入库日期": return "bizDate";
            case "产品名称": case "商品名称": return "sourceName";
            case "商品编码": case "ERP编码": return "skuCode";
            case "整件规格": case "每箱数量": case "箱规": return "boxSpec";
            case "总件数": case "箱数": return "boxCount";
            case "采购数量": case "实收数量": return "qty";
            case "整件价格": case "箱价": return "boxPrice";
            case "总价格": case "总金额": return "totalPrice";
            case "进货单价": return "unitPrice";
            case "匹配商品/待核对": return "hint";
            default: return null;
        }
    }
    private static boolean sameFamily(String a, String b) {
        String x = norm(a), y = norm(b);
        for (String family : new String[]{"电解质水", "气泡水", "维c水"}) {
            String source = x.replace("气泡橙水", "气泡水");
            if (source.contains(family) && y.contains(family)) {
                String brand = source.substring(0, source.indexOf(family));
                return (brand.isEmpty() || y.contains(brand)) && !ProductCatalogMatcher.conflictingSize(a, b);
            }
        }
        return false;
    }
    private static Set<String> flavors(String raw) {
        String value = norm(raw); Set<String> found = new HashSet<>();
        for (String flavor : new String[]{"青柠", "西柚", "荔枝", "椰子", "葡萄", "车厘子", "蔓越莓", "茉莉", "原味"}) if (value.contains(flavor)) found.add(flavor);
        if (value.contains("橙")) found.add("橙"); if (value.contains("专业版") || value.contains("pro")) found.add("专业版");
        return found;
    }
    private static double score(SourceRow row, Product p, Set<Long> exact) {
        double score = ProductCatalogMatcher.similarity(norm(row.getSourceName()), norm(p.getProductName()));
        if (exact.contains(p.getId())) score += 3;
        Set<String> flavors = flavors(row.getSourceName());
        if (!flavors.isEmpty() && flavors(p.getProductName()).containsAll(flavors)) score += 2;
        if (row.getSkuCode().equalsIgnoreCase(p.getSkuCode())) score += 0.3;
        return score;
    }
}
