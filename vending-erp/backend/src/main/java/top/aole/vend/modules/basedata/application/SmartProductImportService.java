package top.aole.vend.modules.basedata.application;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import top.aole.vend.common.exception.BizException;
import top.aole.vend.modules.basedata.domain.entity.*;
import top.aole.vend.modules.basedata.infrastructure.mapper.*;
import top.aole.vend.modules.basedata.interfaces.dto.Dtos;
import top.aole.vend.modules.basedata.interfaces.dto.ProductImportDtos;
import top.aole.vend.modules.basedata.interfaces.dto.ProductImportDtos.Row;
import top.aole.vend.modules.imports.domain.entity.ImportBatch;
import top.aole.vend.modules.imports.domain.entity.ImportError;
import top.aole.vend.modules.imports.mapper.ImportBatchMapper;
import top.aole.vend.modules.imports.mapper.ImportErrorMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.*;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/** 预览不建档；确认时逐行原子化地重判身份、分配编码并绑定原始编号/名称。 */
@Service
@RequiredArgsConstructor
public class SmartProductImportService {
    private final ProductImportService fields;
    private final ProductMapper products;
    private final SkuAliasMapper aliases;
    private final AliasPendingMapper pendings;
    private final ProductService productService;
    private final AliasService aliasService;
    private final ProductCodeService codes;
    private final ImportBatchMapper batches;
    private final ImportErrorMapper errors;
    private final OpLogService opLog;
    private final PlatformTransactionManager transactionManager;
    private final Map<String, Upload> uploads = new ConcurrentHashMap<>();

    @Value("${vend.import.storage-dir:../storage/imports}")
    private String storageDir;

    private static class Upload {
        String name;
        Path path;
        long createdAt = System.currentTimeMillis();
    }

    public ProductImportDtos.ParseResp parse(String fileName, byte[] content) {
        if (content == null || content.length == 0 || content.length > 20 * 1024 * 1024) throw new BizException("请选择不超过 20MB 的商品表格");
        ProductImportDtos.ParseResp parsed = fields.parseForSmart(fileName, content);
        ProductImportDtos.ParseResp result = review(parsed.getRows());
        result.setFileName(fileName);
        result.setHeaders(parsed.getHeaders());
        String token = UUID.randomUUID().toString();
        Upload upload = new Upload();
        upload.name = fileName == null ? "商品列表.xlsx" : fileName.replace('\\', '/').replaceAll("\\p{Cntrl}", "");
        upload.name = upload.name.substring(upload.name.lastIndexOf('/') + 1);
        try {
            Path root = Paths.get(storageDir).toAbsolutePath().normalize();
            upload.path = root.resolve("product/" + LocalDate.now() + "/" + token + (upload.name.toLowerCase(Locale.ROOT).endsWith(".xls") ? ".xls" : ".xlsx"));
            Files.createDirectories(upload.path.getParent());
            Files.write(upload.path, content, StandardOpenOption.CREATE_NEW);
        } catch (IOException e) {
            throw new BizException("原始表格归档失败，请稍后重试");
        }
        uploads.entrySet().removeIf(entry -> System.currentTimeMillis() - entry.getValue().createdAt > 4 * 60 * 60 * 1000L);
        uploads.put(token, upload);
        result.setToken(token);
        return result;
    }

    public ProductImportDtos.ParseResp review(List<Row> rows) {
        validateRows(rows);
        ProductImportDtos.ParseResp result = new ProductImportDtos.ParseResp();
        ProductCatalogMatcher matcher = catalog();
        Set<String> seen = new HashSet<>();
        for (Row row : rows) {
            reviewRow(row, matcher);
            String key = identityKey(row);
            if (!key.isEmpty() && !seen.add(key)) markError(row, "本文件中有重复商品，请保留一行；已导入后可再绑定其他后台编号");
            result.getRows().add(row);
            if (ProductCatalogMatcher.NEW.equals(row.getAction())) result.setCreateCount(result.getCreateCount() + 1);
            else if (ProductCatalogMatcher.MATCHED.equals(row.getAction())) result.setUpdateCount(result.getUpdateCount() + 1);
            else result.setErrorCount(result.getErrorCount() + 1);
        }
        result.setRowTotal(rows.size());
        return result;
    }

    private void reviewRow(Row row, ProductCatalogMatcher matcher) {
        row.setSourceCode(StrUtil.trim(row.getSourceCode()));
        row.setBarcode(StrUtil.trim(row.getBarcode()));
        row.setProductName(StrUtil.trim(row.getProductName()));
        matcher.resolve(row);
        String error = fields.validateFields(row);
        if (error == null && (ProductCatalogMatcher.NEW.equals(row.getAction()) || row.isUpdateExisting())) {
            if (StrUtil.isBlank(row.getUnit())) error = "请填写基本单位（瓶、袋、盒等）";
            if (error == null && (row.getUnit().length() > 16)) error = "基本单位最多 16 个字符";
            try {
                BigDecimal pack = new BigDecimal(StrUtil.nullToEmpty(row.getBoxSpec()));
                if (pack.signum() <= 0 || pack.stripTrailingZeros().scale() > 0) error = "每箱数量必须为正整数；单件包装填 1";
            } catch (NumberFormatException e) { if (error == null) error = "请填写每箱数量；单件包装填 1"; }
        }
        if (error != null) markError(row, error);
    }

    /** 手工录入也保留提交时的原表快照，沿用同一匹配、编号与逐行事务管线。 */
    public ProductImportDtos.CommitResp commitManual(List<Row> rows, String operator) {
        validateRows(rows);
        try (org.apache.poi.xssf.usermodel.XSSFWorkbook workbook = new org.apache.poi.xssf.usermodel.XSSFWorkbook();
             java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream()) {
            org.apache.poi.ss.usermodel.Sheet sheet = workbook.createSheet("手工商品录入");
            String[] headers = {"行号", "后台商品编号", "商品名称", "商品条形码", "基本单位", "箱规",
                    "参考成本", "参考售价", "商品分类", "ERP编码", "状态", "备注"};
            org.apache.poi.ss.usermodel.Row head = sheet.createRow(0);
            for (int c = 0; c < headers.length; c++) head.createCell(c).setCellValue(headers[c]);
            for (int i = 0; i < rows.size(); i++) {
                Row row = rows.get(i);
                String[] cells = {String.valueOf(row.getRowNo()), row.getSourceCode(), row.getProductName(), row.getBarcode(),
                    row.getUnit(), row.getBoxSpec(), row.getRefCost(), row.getRefPrice(), row.getCategory(),
                    row.getSkuCode(), row.getProductStatus(), row.getRemark()};
                org.apache.poi.ss.usermodel.Row target = sheet.createRow(i + 1);
                for (int c = 0; c < cells.length; c++) target.createCell(c).setCellValue(StrUtil.nullToEmpty(cells[c]));
            }
            workbook.write(out);
            String token = parse("手工商品录入-" + LocalDate.now() + ".xlsx", out.toByteArray()).getToken();
            return commit(token, rows, operator);
        } catch (IOException e) {
            throw new BizException("手工录入资料归档失败，请稍后重试");
        }
    }

    public ProductImportDtos.CommitResp commit(String token, List<Row> rows, String operator) {
        validateRows(rows);
        Upload upload = uploads.get(token == null ? "" : token);
        if (upload == null || System.currentTimeMillis() - upload.createdAt > 4 * 60 * 60 * 1000L
                || !Files.isRegularFile(upload.path)) throw new BizException("上传凭据已失效，请重新上传商品列表");
        ProductImportDtos.CommitResp result = new ProductImportDtos.CommitResp();
        ImportBatch batch = new ImportBatch();
        batch.setBatchNo("PRODUCT-" + UUID.randomUUID().toString().substring(0, 18));
        batch.setFileName(upload.name);
        batch.setFileType(ImportBatch.TYPE_PRODUCT_LIST);
        batch.setArchivePath(upload.path.toString());
        batch.setColumnMapJson("{\"mode\":\"smart-product\"}");
        batch.setRowTotal(rows.size());
        batch.setRowOk(0); batch.setRowFail(0); batch.setRowDup(0);
        batch.setBatchStatus(ImportBatch.STATUS_PROCESSING);
        batch.setStatus(ImportBatch.HISTORY_VISIBLE);
        batches.insert(batch);
        result.setBatchId(batch.getId());
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        Set<String> seen = new HashSet<>();
        for (Row row : rows) {
            String key = identityKey(row);
            String beforeCode = row.getSkuCode();
            Long beforeId = row.getExistingProductId();
            try {
                if (!key.isEmpty() && !seen.add(key)) throw new BizException("本次提交包含重复商品，请保留一行");
                int[] outcome = transaction.execute(status -> saveRow(row, operator));
                if (outcome[0] == 1) result.setCreated(result.getCreated() + 1);
                else if (outcome[0] == 2) result.setUpdated(result.getUpdated() + 1);
                else result.setMatched(result.getMatched() + 1);
                result.setAliasBound(result.getAliasBound() + outcome[1]);
                result.setPendingCleared(result.getPendingCleared() + outcome[2]);
                result.getSavedRows().add(row);
            } catch (Exception e) {
                row.setSkuCode(beforeCode); row.setExistingProductId(beforeId);
                String message = e instanceof BizException ? e.getMessage() : "保存失败，本行未入档，请重新检查后重试";
                markError(row, message);
                result.setFailed(result.getFailed() + 1);
                result.getFailedRows().add(row);
                result.getErrors().add(new ProductImportDtos.RowError(row.getRowNo(), row.getSkuCode(), message));
                ImportError error = new ImportError();
                error.setBatchId(batch.getId()); error.setRowNo(row.getRowNo());
                error.setRawContent(JSONUtil.toJsonStr(row)); error.setErrorType(ImportError.TYPE_FORMAT);
                error.setErrorMsg(message); error.setResolveStatus("待处理");
                errors.insert(error);
            }
        }
        batch.setRowOk(result.getSavedRows().size()); batch.setRowFail(result.getFailed());
        batch.setBatchStatus(ImportBatch.STATUS_IMPORTED);
        batches.updateById(batch);
        opLog.record(operator, "商品智能建档", "import_batch", batch.getId(), null,
                "新建 " + result.getCreated() + "，匹配 " + result.getMatched() + "，更新 " + result.getUpdated() + "，失败 " + result.getFailed());
        return result;
    }

    private int[] saveRow(Row row, String operator) {
        // 锁定后再读档案，防止两个导入同时把同一条码建成两个新商品。
        codes.lock();
        reviewRow(row, catalog());
        if (!ProductCatalogMatcher.NEW.equals(row.getAction()) && !ProductCatalogMatcher.MATCHED.equals(row.getAction())) throw new BizException(row.getErrorMsg());
        Product product;
        int action;
        if (ProductCatalogMatcher.NEW.equals(row.getAction())) {
            Product draft = fields.newProductOf(row);
            draft.setSkuCode(null);
            product = productService.create(draft, operator);
            fields.applyStatus(row, product.getId(), product.getProductStatus(), operator);
            action = 1;
        } else {
            product = productService.getById(row.getExistingProductId());
            action = 0;
            if (row.isUpdateExisting()) {
                // 用户明确选择更新后，才覆盖表格中提供的资料。
                productService.update(product.getId(), fields.patchOf(row), operator);
                row.setMatchedProductName(row.getProductName());
                fields.applyStatus(row, product.getId(), product.getProductStatus(), operator);
                action = 2;
            }
        }
        String originalName = StrUtil.blankToDefault(row.getSourceName(), row.getProductName());
        if (originalName.length() > 128) throw new BizException("原始商品名称最多 128 个字符");
        Dtos.BindAliasReq alias = new Dtos.BindAliasReq();
        alias.setAliasCode(StrUtil.nullToEmpty(row.getSourceCode()));
        alias.setAliasBarcode(StrUtil.nullToEmpty(row.getBarcode()));
        alias.setAliasName(originalName);
        alias.setProductId(product.getId()); alias.setBindSource("商品智能建档");
        if (StrUtil.isNotBlank(alias.getAliasCode()) || StrUtil.isNotBlank(alias.getAliasBarcode())) {
            aliasService.bind(alias, operator);
        }
        // 同一个后台键再次导入新名称时，保留旧别名并记住本次确认的叫法。
        Dtos.BindAliasReq nameAlias = new Dtos.BindAliasReq();
        nameAlias.setAliasCode("A/" + product.getId() + "/" + cn.hutool.crypto.digest.DigestUtil.md5Hex(originalName));
        nameAlias.setAliasBarcode("");
        nameAlias.setAliasName(originalName);
        nameAlias.setProductId(product.getId());
        nameAlias.setBindSource("商品智能建档");
        aliasService.bind(nameAlias, operator);
        int cleared = 0;
        for (AliasPending pending : pendings.selectList(new LambdaQueryWrapper<AliasPending>().eq(AliasPending::getPendingStatus, "待绑定"))) {
            // 只消化完整绑定键一致的项；不再按“编号相等 OR 条码相等”批量跨商品绑定。
            if (StrUtil.nullToEmpty(pending.getAliasCode()).equals(StrUtil.nullToEmpty(row.getSourceCode()))
                    && StrUtil.nullToEmpty(pending.getAliasBarcode()).equals(StrUtil.nullToEmpty(row.getBarcode()))
                    && (StrUtil.isNotBlank(row.getSourceCode()) || StrUtil.isNotBlank(row.getBarcode()))) {
                aliasService.confirmPending(pending.getId(), product.getId(), operator);
                cleared++;
            }
        }
        row.setSkuCode(product.getSkuCode()); row.setExistingProductId(product.getId());
        row.setAction(action == 1 ? "已新增" : action == 2 ? "已更新" : "已匹配");
        row.setErrorMsg(null);
        return new int[]{action, 1, cleared};
    }

    private ProductCatalogMatcher catalog() {
        return new ProductCatalogMatcher(products.selectList(null), aliases.selectList(null));
    }

    private static void markError(Row row, String error) {
        row.setAction(ProductCatalogMatcher.ERROR); row.setErrorMsg(error); row.setMatchReason(error);
    }

    private static String identityKey(Row row) {
        if (StrUtil.isNotBlank(row.getBarcode())) return "B/" + row.getBarcode().trim();
        if (StrUtil.isNotBlank(row.getSourceCode())) return "C/" + row.getSourceCode().trim();
        return "N/" + ProductCatalogMatcher.normalize(row.getProductName());
    }

    private static void validateRows(List<Row> rows) {
        if (rows == null || rows.isEmpty() || rows.size() > 5000) throw new BizException("请提供 1 至 5000 行商品资料");
        Set<Integer> rowNumbers = new HashSet<>();
        for (Row row : rows) if (row == null || row.getRowNo() == null || !rowNumbers.add(row.getRowNo())) throw new BizException("商品行号无效或重复，请重新上传");
    }
}
