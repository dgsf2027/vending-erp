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
import top.aole.vend.modules.imports.parser.RawSheet;
import top.aole.vend.modules.purchase.dto.PurchaseWorkbookDtos.*;
import top.aole.vend.modules.purchase.mapper.PurchaseSalesMatchMapper;
import top.aole.vend.modules.purchase.mapper.PurchaseSalesMatchMapper.SaleSummary;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.Normalizer;
import java.time.LocalDate;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/** 原表解析、口味候选、同日同品归集均为只读；保存和确认仍走采购单据。 */
@Service
@RequiredArgsConstructor
public class PurchaseWorkbookService {
    private static final Pattern BANNER_DATE = Pattern.compile("(?<!\\d)(20\\d{2})\\s*[-./年]\\s*(\\d{1,2})\\s*[-./月]\\s*(\\d{1,2})\\s*日?(?!\\d)");
    private static final Pattern SIZE_STRIP = Pattern.compile("\\d+(?:[.\\-~]\\d+)*(?:ml|g)");
    private final ExcelParser parser;
    private final ProductMapper products;
    private final SkuAliasMapper aliases;
    private final PurchaseSalesMatchMapper sales;

    public Preview parse(MultipartFile file) {
        if (file.isEmpty() || file.getSize() > 5 * 1024 * 1024 || file.getOriginalFilename() == null
                || !file.getOriginalFilename().toLowerCase(Locale.ROOT).matches(".*\\.xlsx?$"))
            throw new BizException("请选择不超过 5MB 的 .xls 或 .xlsx 拿货表");
        RawSheet rawSheet;
        try { rawSheet = parser.parseRawFirstSheet(file.getInputStream()); }
        catch (Exception e) { throw new BizException("无法读取拿货表，请选择未加密、未损坏的 Excel 文件"); }

        List<SourceRow> rows = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        Map<String, String> sectionNotes = new LinkedHashMap<>();
        String sheetTitle = null;
        Map<String, Integer> activeColumns = null;
        int sectionSeq = 0;
        String currentSectionKey = null;
        String currentSectionTitle = null;
        String currentSectionDate = "";
        String pendingSectionTitle = null;
        String pendingSectionDate = "";
        int skipped = 0;
        BigDecimal sectionTotal = BigDecimal.ZERO;
        BigDecimal sectionQty = BigDecimal.ZERO;

        for (RawSheet.RawRow input : rawSheet.getRows()) {
            List<String> nonEmpty = nonEmptyCells(input);
            if (nonEmpty.isEmpty()) continue;

            Map<String, Integer> maybeHeader = matchHeaderRow(input);
            if (maybeHeader != null) {
                activeColumns = maybeHeader;
                if (pendingSectionTitle != null) {
                    sectionSeq++;
                    currentSectionKey = "sec-" + sectionSeq;
                    currentSectionTitle = pendingSectionTitle;
                    currentSectionDate = pendingSectionDate;
                    pendingSectionTitle = null;
                    pendingSectionDate = "";
                } else if (currentSectionKey != null && !rows.isEmpty()) {
                    // 同一表格重复表头且上方无分单标题时，开启新分段便于按原表核对
                    sectionSeq++;
                    currentSectionKey = "sec-" + sectionSeq;
                    currentSectionTitle = "送货明细 " + sectionSeq;
                    currentSectionDate = "";
                }
                sectionTotal = BigDecimal.ZERO;
                sectionQty = BigDecimal.ZERO;
                continue;
            }

            if (activeColumns == null) {
                if (nonEmpty.size() == 1) {
                    String banner = nonEmpty.get(0);
                    String foundDate = extractDate(banner);
                    if (!foundDate.isEmpty() || looksLikeSectionBanner(banner)) {
                        if (pendingSectionTitle != null && sheetTitle == null) {
                            sheetTitle = pendingSectionTitle;
                        }
                        pendingSectionTitle = banner;
                        pendingSectionDate = foundDate;
                    } else if (sheetTitle == null) {
                        sheetTitle = banner;
                    }
                }
                continue;
            }

            // 脚注行（如：注：手写金额 46253 元，与明细合计一致。）
            if (isNoteRow(nonEmpty, input, activeColumns)) {
                if (currentSectionKey != null) {
                    String note = String.join(" ", nonEmpty);
                    sectionNotes.merge(currentSectionKey, note, (a, b) -> a + "；" + b);
                }
                continue;
            }

            // 分单横幅行（如：送货单二（No.427946 2026-11-17 收货单位：云仓））
            if (nonEmpty.size() == 1 && isAllNumbersEmpty(input, activeColumns)) {
                String banner = nonEmpty.get(0);
                String foundDate = extractDate(banner);
                if (!foundDate.isEmpty() || looksLikeSectionBanner(banner)) {
                    pendingSectionTitle = banner;
                    pendingSectionDate = foundDate;
                    sectionTotal = BigDecimal.ZERO;
                    sectionQty = BigDecimal.ZERO;
                    continue;
                }
            }

            String name = get(input, activeColumns, "sourceName");
            String code = get(input, activeColumns, "skuCode");
            String qtyText = get(input, activeColumns, "qty");
            String boxCountText = get(input, activeColumns, "boxCount");
            String boxSpecText = get(input, activeColumns, "boxSpec");
            String boxPriceText = get(input, activeColumns, "boxPrice");
            String totalPriceText = get(input, activeColumns, "totalPrice");

            boolean labeledSubtotal = name.matches("^(合计|总计|小计|本单合计|总合计).*$")
                    && code.isEmpty() && boxSpecText.isEmpty() && boxPriceText.isEmpty();
            boolean unlabeledSubtotal = name.isEmpty() && code.isEmpty()
                    && qtyText.isEmpty() && boxCountText.isEmpty() && boxSpecText.isEmpty() && !totalPriceText.isEmpty();
            if (labeledSubtotal || unlabeledSubtotal) {
                skipped++;
                if (!totalPriceText.isEmpty()) {
                    try {
                        if (number(totalPriceText).compareTo(sectionTotal) != 0)
                            errors.add("第 " + input.getRowNo() + " 行小计与上方明细不一致，请核对原表");
                    } catch (RuntimeException e) { errors.add("第 " + input.getRowNo() + " 行小计不是有效金额"); }
                }
                if (!qtyText.isEmpty()) {
                    try {
                        if (number(qtyText).compareTo(sectionQty) != 0)
                            errors.add("第 " + input.getRowNo() + " 行合计数量与上方明细不一致，请核对原表");
                    } catch (RuntimeException e) { errors.add("第 " + input.getRowNo() + " 行合计数量不是有效数字"); }
                }
                sectionTotal = BigDecimal.ZERO;
                sectionQty = BigDecimal.ZERO;
                continue;
            }

            if (pendingSectionTitle != null) {
                sectionSeq++;
                currentSectionKey = "sec-" + sectionSeq;
                currentSectionTitle = pendingSectionTitle;
                currentSectionDate = pendingSectionDate;
                pendingSectionTitle = null;
                pendingSectionDate = "";
                sectionTotal = BigDecimal.ZERO;
                sectionQty = BigDecimal.ZERO;
            }

            if (rows.size() >= 500) throw new BizException("每次最多读取 500 行拿货明细");
            SourceRow row = new SourceRow();
            row.setRowNo(input.getRowNo());
            row.setSourceName(name);
            row.setSkuCode(code);
            String rowDate = get(input, activeColumns, "bizDate");
            row.setBizDate(rowDate.isEmpty() ? currentSectionDate : rowDate);
            row.setSectionKey(currentSectionKey);
            row.setSectionTitle(currentSectionTitle);
            row.setHint(get(input, activeColumns, "hint"));
            try {
                row.setQty(number(qtyText));
                row.setBoxSpec(number(boxSpecText));
                row.setBoxCount(number(boxCountText));
                row.setBoxPrice(number(boxPriceText));
                row.setTotalPrice(number(totalPriceText));
                row.setUnitPrice(number(get(input, activeColumns, "unitPrice")));
                if (row.getQty() == null && row.getBoxSpec() != null && row.getBoxCount() != null)
                    row.setQty(row.getBoxSpec().multiply(row.getBoxCount()));
                if (row.getTotalPrice() == null && row.getBoxPrice() != null && row.getBoxCount() != null)
                    row.setTotalPrice(row.getBoxPrice().multiply(row.getBoxCount()));
                if (row.getTotalPrice() == null && row.getUnitPrice() != null && row.getQty() != null)
                    row.setTotalPrice(row.getQty().multiply(row.getUnitPrice()).setScale(2, RoundingMode.HALF_UP));
                if (row.getTotalPrice() != null) sectionTotal = sectionTotal.add(row.getTotalPrice());
                if (row.getQty() != null) sectionQty = sectionQty.add(row.getQty());
            } catch (RuntimeException e) { errors.add("第 " + row.getRowNo() + " 行含无法识别的数量或价格，请修改原表"); }
            rows.add(row);
        }

        if (activeColumns == null)
            throw new BizException("未找到拿货表表头，需要产品名称或商品编码，以及采购数量（或整件规格、总件数）");
        if (rows.isEmpty())
            throw new BizException("未读取到有效拿货明细行，请检查表格内容");

        Preview result = review(rows, null);
        result.setFileName(file.getOriginalFilename());
        result.setSheetTitle(sheetTitle);
        result.setSkippedSubtotals(skipped);
        for (Section sec : result.getSections()) {
            if (sectionNotes.containsKey(sec.getKey())) sec.setNote(sectionNotes.get(sec.getKey()));
        }
        result.getErrors().addAll(0, errors);
        result.getWarnings().add("同一天、同一商品编码自动合并，保留来源行；不同日期分别带入入库单。");
        result.getWarnings().add("销售数量来自已导入的正常销售及兑换记录，仅供核对口味，不能作为实际进货口味比例。");
        return result;
    }

    public Preview review(List<SourceRow> input, String defaultDate) {
        if (input == null || input.isEmpty() || input.size() > 500) throw new BizException("请提供 1 至 500 行拿货明细");
        List<Product> catalog = products.selectList(null).stream().filter(p -> p.getStockOwnerId() == null).collect(Collectors.toList());
        List<SkuAlias> aliasList = aliases.selectList(null);
        List<SaleSummary> saleList = sales.summaries();
        Map<Long, Product> byId = catalog.stream().collect(Collectors.toMap(Product::getId, p -> p));
        Preview result = new Preview();
        Map<String, Day> days = new TreeMap<>();
        Map<String, Section> sectionMap = new LinkedHashMap<>();
        Map<String, Boolean> sectionHasAllBoxCount = new HashMap<>();
        Set<Integer> rowNumbers = new HashSet<>();
        for (SourceRow source : input) {
            if (source == null) throw new BizException("明细不能包含空行对象");
            SourceRow r = new SourceRow(); BeanUtils.copyProperties(source, r);
            r.setErrors(new ArrayList<>()); r.setCandidates(new ArrayList<>()); r.setSuggestedProductId(null);
            r.setMatchedProductId(null); r.setMatchedSkuCode(null); r.setMatchedProductName(null); r.setMatchStatus("unmatched");
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
            if (!r.isNeedsConfirmation() && r.getErrors().isEmpty() && r.getAllocations().size() == 1) {
                Product matched = byId.get(r.getAllocations().get(0).getProductId());
                if (matched != null) {
                    r.setMatchStatus("matched");
                    r.setMatchedProductId(matched.getId());
                    r.setMatchedSkuCode(matched.getSkuCode());
                    r.setMatchedProductName(matched.getProductName());
                }
            }
            Day day = days.computeIfAbsent(text(r.getBizDate()), key -> { Day d = new Day(); d.setBizDate(key); return d; });
            day.setSourceCount(day.getSourceCount() + 1);
            if (r.getQty() != null) day.setTotalQty(day.getTotalQty().add(r.getQty()));
            if (r.getTotalPrice() != null) day.setTotalAmount(day.getTotalAmount().add(r.getTotalPrice()));
            for (String error : r.getErrors()) day.getErrors().add("第 " + r.getRowNo() + " 行：" + error);
            if (r.getErrors().isEmpty()) addToDay(day, r, byId);

            String secKey = !text(r.getSectionKey()).isEmpty() ? text(r.getSectionKey()) : ("date:" + text(r.getBizDate()));
            String secTitle = !text(r.getSectionTitle()).isEmpty()
                    ? text(r.getSectionTitle())
                    : (text(r.getBizDate()).isEmpty() ? "采购明细（待补日期）" : "采购日期：" + text(r.getBizDate()));
            Section sec = sectionMap.computeIfAbsent(secKey, k -> {
                Section s = new Section();
                s.setKey(k);
                s.setTitle(secTitle);
                s.setBizDate(text(r.getBizDate()));
                sectionHasAllBoxCount.put(k, Boolean.TRUE);
                return s;
            });
            sec.getRowNos().add(r.getRowNo());
            if (r.getQty() != null) sec.setTotalQty(sec.getTotalQty().add(r.getQty()));
            if (r.getTotalPrice() != null) sec.setTotalAmount(sec.getTotalAmount().add(r.getTotalPrice()));
            if (r.getBoxCount() != null) {
                sec.setTotalBoxCount(sec.getTotalBoxCount() == null ? r.getBoxCount() : sec.getTotalBoxCount().add(r.getBoxCount()));
            } else {
                sectionHasAllBoxCount.put(secKey, Boolean.FALSE);
            }
            result.getRows().add(r);
        }
        for (Section sec : sectionMap.values()) {
            if (!Boolean.TRUE.equals(sectionHasAllBoxCount.get(sec.getKey()))) sec.setTotalBoxCount(null);
        }
        for (Day day : days.values()) for (GroupLine line : day.getLines()) {
            line.setUnitPrice(line.getTotalPrice().divide(line.getQty(), 4, RoundingMode.HALF_UP));
            if (line.getUnitPrice().signum() <= 0 || line.getUnitPrice().precision() - line.getUnitPrice().scale() > 8)
                day.getErrors().add(line.getProductName() + "折算后的进货单价超出系统范围");
        }
        result.setSections(new ArrayList<>(sectionMap.values()));
        result.setDays(new ArrayList<>(days.values()));
        return result;
    }

    private void resolve(SourceRow r, List<Product> catalog, List<SkuAlias> aliasList, List<SaleSummary> saleList) {
        Set<Long> codeAliases = aliasList.stream().filter(a -> !r.getSkuCode().isEmpty() && r.getSkuCode().equalsIgnoreCase(a.getAliasCode())).map(SkuAlias::getProductId).collect(Collectors.toSet());
        Product coded = catalog.stream().filter(p -> !r.getSkuCode().isEmpty() && (r.getSkuCode().equalsIgnoreCase(p.getSkuCode()) || Boolean.TRUE.equals(p.getSharedStock()) && codeAliases.size() == 1 && codeAliases.contains(p.getId()))).findFirst().orElse(null);
        String name = norm(r.getSourceName()), hint = norm(r.getHint());
        Set<Long> exact = catalog.stream().filter(p -> !name.isEmpty() && (name.equals(norm(p.getProductName())) || hint.equals(norm(p.getProductName()))))
            .map(Product::getId).collect(Collectors.toSet());
        aliasList.stream().filter(a -> !name.isEmpty() && name.equals(norm(a.getAliasName()))).forEach(a -> exact.add(a.getProductId()));
        // 已确认的通用拿货名称沿用共用库存；新的明确口味仍须确认。
        if (flavors(r.getSourceName()).isEmpty()) catalog.stream().filter(p -> Boolean.TRUE.equals(p.getSharedStock()) && sameFamily(r.getSourceName(), p.getProductName())).forEach(p -> exact.add(p.getId()));

        // 无编码且未精确命中时：若去掉容量后缀后唯一命中单个非多口味系列商品（且箱规不冲突），自动匹配为绿色。
        if (coded == null && exact.isEmpty() && !name.isEmpty() && name.equals(stripSize(name))) {
            long familyCount = catalog.stream().filter(p -> sameFamily(r.getSourceName(), p.getProductName())).count();
            if (familyCount <= 1) {
                List<Product> sizeStrippedHits = catalog.stream()
                    .filter(p -> !"停售".equals(p.getProductStatus()) && !"清仓中".equals(p.getProductStatus()))
                    .filter(p -> name.equals(stripSize(norm(p.getProductName()))))
                    .filter(p -> r.getBoxSpec() == null || p.getBoxSpec() == null || p.getBoxSpec().signum() <= 0 || r.getBoxSpec().compareTo(p.getBoxSpec()) == 0)
                    .collect(Collectors.toList());
                if (sizeStrippedHits.size() == 1) exact.add(sizeStrippedHits.get(0).getId());
            }
        }

        Map<Long, List<String>> aliasNamesByProduct = new HashMap<>();
        for (SkuAlias a : aliasList) {
            if (a.getProductId() != null && a.getAliasName() != null) {
                aliasNamesByProduct.computeIfAbsent(a.getProductId(), k -> new ArrayList<>()).add(norm(a.getAliasName()));
            }
        }

        List<Product> possible = catalog.stream().filter(p -> p == coded || exact.contains(p.getId())
            || r.getAllocations() != null && r.getAllocations().stream().anyMatch(a -> a != null && p.getId().equals(a.getProductId()))
            || sameFamily(r.getSourceName(), p.getProductName()) || r.getHint().contains(p.getSkuCode())
            || matchesCandidateName(r.getSourceName(), name, p, aliasNamesByProduct.get(p.getId())))
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
        boolean flavorConflict = coded != null && !(Boolean.TRUE.equals(coded.getSharedStock()) && exact.contains(coded.getId())) && !flavors(coded.getProductName()).containsAll(flavors(r.getSourceName()));
        boolean sizeConflict = coded != null && ProductCatalogMatcher.conflictingSize(r.getSourceName(), coded.getProductName());
        boolean genericFlavor = coded != null && multipleFlavors && flavors(r.getSourceName()).isEmpty() && !exact.contains(coded.getId());
        r.setNeedsConfirmation(coded == null && unique == null || flavorConflict || genericFlavor || sizeConflict
            || coded != null && unique != null && !coded.getId().equals(unique.getId()));
        if (!r.getSkuCode().isEmpty() && coded == null) r.setNeedsConfirmation(true);
        r.setMatchMessage(flavorConflict || genericFlavor || sizeConflict ? "表中编码与销售口味或规格需核对：可选择实际口味，混装可拆分；确认只决定本次采购归属。"
            : coded != null ? "按表中商品编码匹配，销售数据供核对" : unique != null ? "按名称或已有别名唯一匹配" : "未精确匹配，请手动绑定系统商品");
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

    private static boolean matchesCandidateName(String rawSource, String normSource, Product p, List<String> aliasNorms) {
        if (normSource.isEmpty() || ProductCatalogMatcher.conflictingSize(rawSource, p.getProductName())) return false;
        String normProduct = norm(p.getProductName());
        if (ProductCatalogMatcher.similarity(normSource, normProduct) >= 0.55) return true;
        String baseSource = stripSize(normSource);
        String baseProduct = stripSize(normProduct);
        if (baseSource.length() >= 2 && (baseProduct.contains(baseSource) || baseSource.contains(baseProduct) || containsAllChars(baseProduct, baseSource))) {
            return true;
        }
        if (aliasNorms != null) {
            for (String an : aliasNorms) {
                String baseAlias = stripSize(an);
                if (baseSource.length() >= 2 && (baseAlias.contains(baseSource) || baseSource.contains(baseAlias))) return true;
            }
        }
        return false;
    }

    private static boolean containsAllChars(String haystack, String needle) {
        if (needle.length() < 2 || haystack.length() < needle.length()) return false;
        for (int i = 0; i < needle.length(); i++) {
            if (haystack.indexOf(needle.charAt(i)) < 0) return false;
        }
        return true;
    }

    private static String stripSize(String normalized) {
        return SIZE_STRIP.matcher(normalized).replaceAll("");
    }

    private Map<String, Integer> matchHeaderRow(RawSheet.RawRow row) {
        Map<String, Integer> map = new HashMap<>();
        List<String> cells = row.getCells();
        for (int c = 0; c < cells.size(); c++) {
            String cell = text(cells.get(c));
            if (cell.isEmpty()) continue;
            String field = column(cell);
            if (field != null && map.putIfAbsent(field, c) != null) {
                if (hasRequiredColumns(map)) throw new BizException("重复表头：" + cell);
                return null;
            }
        }
        if (!hasRequiredColumns(map)) return null;
        return map;
    }

    private static boolean hasRequiredColumns(Map<String, Integer> columns) {
        return (columns.containsKey("sourceName") || columns.containsKey("skuCode"))
                && (columns.containsKey("qty") || (columns.containsKey("boxCount") && columns.containsKey("boxSpec")));
    }

    private static List<String> nonEmptyCells(RawSheet.RawRow row) {
        List<String> list = new ArrayList<>();
        for (String cell : row.getCells()) {
            String t = text(cell);
            if (!t.isEmpty()) list.add(t);
        }
        return list;
    }

    private static boolean isAllNumbersEmpty(RawSheet.RawRow row, Map<String, Integer> columns) {
        return get(row, columns, "qty").isEmpty()
                && get(row, columns, "boxCount").isEmpty()
                && get(row, columns, "boxSpec").isEmpty()
                && get(row, columns, "boxPrice").isEmpty()
                && get(row, columns, "totalPrice").isEmpty()
                && get(row, columns, "unitPrice").isEmpty();
    }

    private static boolean isNoteRow(List<String> nonEmpty, RawSheet.RawRow row, Map<String, Integer> columns) {
        if (nonEmpty.isEmpty() || !isAllNumbersEmpty(row, columns)) return false;
        String first = nonEmpty.get(0);
        return first.startsWith("注：") || first.startsWith("注:")
                || first.startsWith("备注：") || first.startsWith("备注:")
                || first.startsWith("说明：") || first.startsWith("说明:");
    }

    private static boolean looksLikeSectionBanner(String text) {
        return text.matches(".*(送货单|采购单|拿货单|入库单|收货单位|No\\.|单号).*");
    }

    static String extractDate(String banner) {
        if (banner == null || banner.isEmpty()) return "";
        Matcher m = BANNER_DATE.matcher(banner);
        if (!m.find()) return "";
        try {
            int y = Integer.parseInt(m.group(1));
            int mo = Integer.parseInt(m.group(2));
            int d = Integer.parseInt(m.group(3));
            return LocalDate.of(y, mo, d).toString();
        } catch (RuntimeException ignored) {
            return "";
        }
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
    private static String get(RawSheet.RawRow row, Map<String, Integer> columns, String field) {
        Integer idx = columns.get(field);
        return idx == null ? "" : text(row.get(idx));
    }
    private static String column(String raw) {
        String h = Normalizer.normalize(text(raw), Normalizer.Form.NFKC)
                .replaceAll("\\s+", "")
                .replaceAll("\\((?:元|元/箱|元/件|元/瓶|箱|件|瓶|个|袋)\\)$", "");
        switch (h) {
            case "采购日期": case "入库日期": case "送货日期": return "bizDate";
            case "产品名称": case "商品名称": case "品名": return "sourceName";
            case "商品编码": case "ERP编码": return "skuCode";
            case "整件规格": case "每箱数量": case "箱规": return "boxSpec";
            case "总件数": case "箱数": return "boxCount";
            case "采购数量": case "实收数量": return "qty";
            case "整件价格": case "箱价": case "整件单价": return "boxPrice";
            case "总价格": case "总金额": case "小计金额": return "totalPrice";
            case "进货单价": case "单价": return "unitPrice";
            case "匹配商品/待核对": return "hint";
            default: return null;
        }
    }
    static boolean sameFamily(String a, String b) {
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
        for (String flavor : new String[]{"青柠", "西柚", "荔枝", "椰子", "葡萄", "车厘子", "蔓越莓", "茉莉", "原味", "柠檬", "白桃", "柚子"}) if (value.contains(flavor)) found.add(flavor);
        if (value.contains("橙")) found.add("橙"); if (value.contains("专业版") || value.contains("pro")) found.add("专业版");
        return found;
    }
    private static double score(SourceRow row, Product p, Set<Long> exact) {
        String normSource = norm(row.getSourceName());
        String normProduct = norm(p.getProductName());
        double score = ProductCatalogMatcher.similarity(normSource, normProduct);
        if (exact.contains(p.getId())) score += 3;
        String baseSource = stripSize(normSource);
        String baseProduct = stripSize(normProduct);
        if (!baseSource.isEmpty() && baseProduct.contains(baseSource)) score += 1.2;
        else if (!baseSource.isEmpty() && containsAllChars(baseProduct, baseSource)) score += 0.6;
        Set<String> flavors = flavors(row.getSourceName());
        if (!flavors.isEmpty() && flavors(p.getProductName()).containsAll(flavors)) score += 2;
        if (row.getSkuCode().equalsIgnoreCase(p.getSkuCode())) score += 0.3;
        return score;
    }
}
