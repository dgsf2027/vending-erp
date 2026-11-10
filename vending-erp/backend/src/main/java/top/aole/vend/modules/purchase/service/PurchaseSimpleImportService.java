package top.aole.vend.modules.purchase.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import top.aole.vend.common.exception.BizException;
import top.aole.vend.modules.basedata.application.ProductCatalogMatcher;
import top.aole.vend.modules.basedata.application.ProductCodeService;
import top.aole.vend.modules.purchase.dto.PurchaseSimpleDtos.*;
import top.aole.vend.modules.purchase.dto.PurchaseWorkbookDtos.*;
import top.aole.vend.modules.purchase.dto.ReceiptCreateReq;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;
import java.util.regex.*;

@Service
@RequiredArgsConstructor
public class PurchaseSimpleImportService {
    private final PurchaseWorkbookService workbook;
    private final PurchaseSharedStockService sharedStock;
    private final PurchaseReceiptService receipts;
    private final ProductCodeService codes;
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    @Transactional(rollbackFor = Exception.class)
    public SimplePreview preview(MultipartFile file) {
        Preview parsed = workbook.parse(file);
        // 只按原始业务字段计算指纹；重命名、重新保存 Excel 不会重复入库。
        List<Object> identity = new ArrayList<>();
        for (SourceRow r : parsed.getRows()) identity.add(Arrays.asList(r.getRowNo(), r.getBizDate(), r.getSourceName(), r.getSkuCode(), decimal(r.getQty()), decimal(r.getTotalPrice()), decimal(r.getBoxSpec()), decimal(r.getBoxCount()), decimal(r.getBoxPrice())));
        identity.add(parsed.getErrors());
        String key = cn.hutool.crypto.digest.DigestUtil.sha256Hex(write(identity));
        try {
            jdbc.update("INSERT IGNORE INTO yc_vend_purchase_workbook (batch_key,file_name,source_json,file_data) VALUES (?,?,?,?)", key, file.getOriginalFilename(), write(parsed), file.getBytes());
        } catch (java.io.IOException e) { throw new BizException("原表保存失败，请重试"); }
        Map<String, Object> stored = jdbc.queryForMap("SELECT result_json FROM yc_vend_purchase_workbook WHERE batch_key=? AND tenant_id='000000'", key);
        SimplePreview result = new SimplePreview(); result.setBatchKey(key); result.setPreview(parsed); result.setGroups(groups(parsed));
        if (stored.get("result_json") != null) result.setReceiptIds(readIds(stored.get("result_json").toString()));
        return result;
    }

    @Transactional(rollbackFor = Exception.class)
    public CommitResult commit(CommitRequest request, String operator) {
        if (request == null || request.getBatchKey() == null || !request.getBatchKey().matches("[0-9a-f]{64}")) throw new BizException("请先上传拿货表");
        codes.lock(); // 与销售建档、商品编辑、别名绑定及单据建账共用锁。
        List<Map<String, Object>> batches = jdbc.queryForList("SELECT source_json,result_json FROM yc_vend_purchase_workbook WHERE batch_key=? AND tenant_id='000000' FOR UPDATE", request.getBatchKey());
        if (batches.isEmpty()) throw new BizException("未找到原表，请重新上传");
        Map<String, Object> batch = batches.get(0); CommitResult result = new CommitResult();
        if (batch.get("result_json") != null) { result.setReceiptIds(readIds(batch.get("result_json").toString())); result.setAlreadyImported(true); return result; }
        if (request.getSupplierId() == null || jdbc.queryForObject("SELECT COUNT(*) FROM yc_vend_supplier WHERE id=? AND tenant_id='000000' AND is_deleted=0", Integer.class, request.getSupplierId()) != 1)
            throw new BizException("请选择有效供应商");
        Preview original = readPreview(batch.get("source_json").toString());
        if (!original.getErrors().isEmpty()) throw new BizException(String.join("；", original.getErrors()));
        // 原始数量/金额只取服务器保存的原表，客户端只能提交关联选择。
        for (SourceRow row : original.getRows()) { row.setAllocations(new ArrayList<>()); row.setConfirmed(false); }
        Preview current = workbook.review(original.getRows(), request.getDefaultDate());
        List<StockGroup> proposed = groups(current);
        Map<String, GroupChoice> choices = new HashMap<>();
        Set<Long> groupedProducts = new HashSet<>();
        if (request.getGroups() == null || request.getRows() == null) throw new BizException("关联选择格式不正确");
        for (GroupChoice choice : request.getGroups()) {
            if (choice == null || choices.put(choice.getKey(), choice) != null) throw new BizException("口味组重复或为空");
            StockGroup group = proposed.stream().filter(g -> g.getKey().equals(choice.getKey())).findFirst().orElseThrow(() -> new BizException("商品档案已变化，请重新上传核对"));
            Set<Long> allowed = group.getMembers().stream().map(Candidate::getProductId).collect(Collectors.toSet());
            if (!choice.isConfirmed() || choice.getMemberIds() == null || choice.getMemberIds().isEmpty()
                || !allowed.containsAll(choice.getMemberIds()) || !choice.getMemberIds().contains(choice.getTargetId())
                || new HashSet<>(choice.getMemberIds()).size() != choice.getMemberIds().size()) throw new BizException("请确认共用库存的口味及保留编码");
            for (Long id : choice.getMemberIds()) if (!groupedProducts.add(id)) throw new BizException("同一商品不能属于两个库存组，请重新核对");
        }
        Map<Integer, Long> rowChoices = new HashMap<>();
        Set<Integer> rowNos = current.getRows().stream().map(SourceRow::getRowNo).collect(Collectors.toSet());
        for (RowChoice row : request.getRows()) if (row == null || !rowNos.contains(row.getRowNo()) || row.getProductId() == null || rowChoices.put(row.getRowNo(), row.getProductId()) != null)
            throw new BizException("商品选择格式不正确");
        for (SourceRow row : current.getRows()) {
            StockGroup group = proposed.stream().filter(g -> g.getSourceRows().contains(row.getRowNo())).findFirst().orElse(null);
            Long chosen = rowChoices.get(row.getRowNo());
            if (group != null) {
                GroupChoice choice = choices.get(group.getKey());
                if (choice == null) throw new BizException("请先确认「" + group.getName() + "」哪些口味共用库存");
                // 指明口味且该口味未勾选时，保留它的独立库存。
                Long suggested = row.getSuggestedProductId();
                if (chosen == null) chosen = suggested != null && !choice.getMemberIds().contains(suggested) ? suggested : choice.getTargetId();
                if (choice.getMemberIds().contains(chosen)) chosen = choice.getTargetId();
            }
            if (chosen != null) {
                Allocation a = new Allocation(); a.setProductId(chosen); a.setQty(row.getQty());
                row.setAllocations(new ArrayList<>(Collections.singletonList(a))); row.setConfirmed(true);
            }
        }
        Preview checked = workbook.review(current.getRows(), request.getDefaultDate());
        List<String> errors = checked.getDays().stream().flatMap(d -> d.getErrors().stream()).collect(Collectors.toList());
        if (!errors.isEmpty()) throw new BizException(String.join("；", errors.subList(0, Math.min(4, errors.size()))));
        for (StockGroup group : proposed) {
            GroupChoice choice = choices.get(group.getKey());
            if (choice != null && choice.getMemberIds().size() > 1) sharedStock.merge(choice.getTargetId(), choice.getMemberIds(), group.getName(), operator);
        }
        for (Day day : checked.getDays()) {
            if (day.getLines().isEmpty()) throw new BizException("采购明细为空");
            ReceiptCreateReq req = new ReceiptCreateReq(); req.setSupplierId(request.getSupplierId()); req.setBizDate(LocalDate.parse(day.getBizDate()));
            req.setRemark("拿货表：" + original.getFileName() + "；按原表日期入库；批次 " + request.getBatchKey().substring(0, 12));
            req.setItems(day.getLines().stream().map(line -> {
                ReceiptCreateReq.Item item = new ReceiptCreateReq.Item(); item.setProductId(line.getProductId()); item.setQty(line.getQty());
                item.setUnitPrice(line.getUnitPrice()); item.setAmount(line.getTotalPrice());
                item.setRemark("原表第 " + line.getSourceRows() + " 行：" + String.join(" / ", line.getSourceNames())); return item;
            }).collect(Collectors.toList()));
            Long id = receipts.createDirect(req, 0L); receipts.confirm(id, 0L); result.getReceiptIds().add(id);
        }
        jdbc.update("UPDATE yc_vend_purchase_workbook SET result_json=?,completed_at=CURRENT_TIMESTAMP WHERE batch_key=? AND tenant_id='000000'", write(result.getReceiptIds()), request.getBatchKey());
        return result;
    }

    static List<StockGroup> groups(Preview preview) {
        Map<String, StockGroup> result = new LinkedHashMap<>();
        for (SourceRow row : preview.getRows()) {
            List<Candidate> members = row.getCandidates().stream().filter(c -> PurchaseWorkbookService.sameFamily(row.getSourceName(), c.getProductName())).sorted(Comparator.comparing(Candidate::getSkuCode)).collect(Collectors.toList());
            if (members.size() < 2) continue;
            Candidate first = members.get(0);
            // 不把不同容量/箱规的商品提议为同一库存组。
            if (members.stream().anyMatch(c -> !Objects.equals(first.getUnit(), c.getUnit()) || first.getBoxSpec() == null || c.getBoxSpec() == null || first.getBoxSpec().compareTo(c.getBoxSpec()) != 0 || ProductCatalogMatcher.conflictingSize(first.getProductName(), c.getProductName()))) continue;
            String key = members.stream().map(c -> c.getProductId().toString()).collect(Collectors.joining("-"));
            StockGroup group = result.computeIfAbsent(key, k -> { StockGroup g = new StockGroup(); g.setKey(k); g.setTargetId(first.getProductId()); g.setMembers(members); g.setName(groupName(first.getProductName())); return g; });
            group.getSourceRows().add(row.getRowNo());
        }
        return new ArrayList<>(result.values());
    }

    private static String groupName(String raw) {
        String name = raw.replace("维C", "维c");
        for (String family : new String[]{"电解质水", "气泡水", "维c水"}) if (name.contains(family)) {
            String base = name.substring(0, name.indexOf(family) + family.length()).replace("维c", "维C");
            Matcher size = Pattern.compile("\\d+(?:\\.\\d+)?(?:ml|毫升|升|L)", Pattern.CASE_INSENSITIVE).matcher(raw);
            return base + (size.find() ? size.group() : "") + "（共用库存）";
        }
        return raw;
    }
    private static String decimal(BigDecimal n) { return n == null ? "" : n.stripTrailingZeros().toPlainString(); }
    private String write(Object value) { try { return json.writeValueAsString(value); } catch (Exception e) { throw new BizException("原表归档失败"); } }
    private Preview readPreview(String value) { try { return json.readValue(value, Preview.class); } catch (Exception e) { throw new BizException("原表归档读取失败，请联系管理员"); } }
    private List<Long> readIds(String value) { try { return json.readValue(value, new TypeReference<List<Long>>() {}); } catch (Exception e) { throw new BizException("导入结果读取失败，请勿重复建单"); } }
}
