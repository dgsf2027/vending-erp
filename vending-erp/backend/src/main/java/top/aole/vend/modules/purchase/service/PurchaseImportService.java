package top.aole.vend.modules.purchase.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import top.aole.vend.common.exception.BizException;
import top.aole.vend.modules.basedata.domain.entity.Product;
import top.aole.vend.modules.basedata.infrastructure.mapper.ProductMapper;

import java.io.IOException;
import java.math.BigDecimal;
import java.util.*;
import java.util.stream.Collectors;

/** 仅校验和预览明细；保存、下单、确认仍走原采购流程。 */
@Service
@RequiredArgsConstructor
public class PurchaseImportService {
    private final ProductMapper productMapper;

    @Data
    public static class Preview {
        private List<Line> rows = new ArrayList<>();
        private List<String> errors = new ArrayList<>();
    }
    @Data
    public static class Line {
        private int rowNo;
        private String skuCode;
        private Long productId;
        private String productName;
        private BigDecimal qty;
        private BigDecimal unitPrice;
    }

    public static void checkKind(String kind) {
        if (!"receipt".equals(kind) && !"order".equals(kind)) throw new BizException("不支持的采购导入类型");
    }

    public Preview preview(MultipartFile file, String kind) {
        checkKind(kind);
        if (file.isEmpty() || file.getSize() > 5 * 1024 * 1024) throw new BizException("请选择不超过 5MB 的 Excel 文件");
        if (file.getOriginalFilename() == null || !file.getOriginalFilename().toLowerCase(Locale.ROOT).endsWith(".xlsx"))
            throw new BizException("仅支持 .xlsx 文件，请先下载模板");
        Preview result = new Preview();
        try (Workbook book = new XSSFWorkbook(file.getInputStream())) {
            if (book.getNumberOfSheets() != 1) throw new BizException("请仅保留一个明细工作表，避免遗漏数据");
            Sheet sheet = book.getSheetAt(0);
            if (sheet.getLastRowNum() > 500) throw new BizException("每次最多导入 500 行，请拆分文件");
            Row header = sheet.getRow(0);
            if (header == null) throw new BizException("第一行必须是模板表头");
            Map<String, Integer> columns = new HashMap<>();
            for (Cell c : header) {
                String name = text(c);
                if (name.isEmpty()) continue;
                if (columns.put(name, c.getColumnIndex()) != null) throw new BizException("表头重复：" + name);
            }
            String quantity = "receipt".equals(kind) ? "实收数量" : "订购数量";
            String price = "receipt".equals(kind) ? "进货单价" : "预计单价";
            for (String name : Arrays.asList("商品编码", quantity, price)) {
                if (!columns.containsKey(name)) throw new BizException("缺少表头：" + name + "，请使用对应模板");
            }
            if (columns.size() != 3) throw new BizException("请使用模板的三列明细，供应商和日期在页面填写");
            Set<String> seen = new HashSet<>();
            for (int i = 1; i <= sheet.getLastRowNum(); i++) {
                Row row = sheet.getRow(i);
                if (row == null) continue;
                try {
                    boolean blank = true;
                    for (Cell cell : row) if (!text(cell).isEmpty()) blank = false;
                    if (blank) continue;
                    Line line = new Line();
                    line.setRowNo(i + 1);
                    line.setSkuCode(text(row.getCell(columns.get("商品编码"))));
                    if (line.getSkuCode().isEmpty()) throw new BizException("商品编码不能为空");
                    if (!seen.add(line.getSkuCode())) throw new BizException("商品编码重复，请合并数量后导入：" + line.getSkuCode());
                    line.setQty(decimal(text(row.getCell(columns.get(quantity))), quantity, 3, false, false));
                    line.setUnitPrice(decimal(text(row.getCell(columns.get(price))), price, 4, "order".equals(kind), "order".equals(kind)));
                    result.getRows().add(line);
                } catch (BizException e) {
                    result.getErrors().add("第 " + (i + 1) + " 行：" + e.getMessage());
                }
            }
        } catch (BizException e) {
            throw e;
        } catch (IOException | RuntimeException e) {
            throw new BizException("文件无法读取，请上传未加密、未损坏的 .xlsx 文件");
        }
        if (result.getRows().isEmpty() && result.getErrors().isEmpty()) throw new BizException("文件没有商品明细，请填写模板后上传");
        if (!result.getRows().isEmpty()) {
            List<String> codes = result.getRows().stream().map(Line::getSkuCode).collect(Collectors.toList());
            List<Product> products = productMapper.selectList(new LambdaQueryWrapper<Product>().in(Product::getSkuCode, codes));
            for (Line line : result.getRows()) {
                List<Product> matches = products.stream().filter(p -> line.getSkuCode().equals(p.getSkuCode())).collect(Collectors.toList());
                if (matches.size() != 1) {
                    result.getErrors().add("第 " + line.getRowNo() + " 行：商品编码不存在或不唯一：" + line.getSkuCode());
                } else {
                    Product p = matches.get(0);
                    line.setProductId(p.getId());
                    line.setProductName(p.getProductName());
                    if ("清仓中".equals(p.getProductStatus())) result.getErrors().add("第 " + line.getRowNo() + " 行：清仓中商品禁止采购：" + line.getSkuCode());
                }
            }
        }
        return result;
    }

    private static BigDecimal decimal(String value, String name, int scale, boolean optional, boolean zeroAllowed) {
        if (value.isEmpty() && optional) return null;
        if (!value.matches("[0-9]+(\\.[0-9]+)?")) throw new BizException(name + "必须填写有效数字");
        BigDecimal number = new BigDecimal(value).stripTrailingZeros();
        if (number.signum() < 0 || (!zeroAllowed && number.signum() == 0)) throw new BizException(name + "必须大于 0");
        if (number.scale() > scale || number.precision() - number.scale() > 8) throw new BizException(name + "最多 8 位整数、" + scale + " 位小数");
        return number;
    }

    private static String text(Cell cell) {
        if (cell == null) return "";
        if (cell.getCellType() == CellType.FORMULA || cell.getCellType() == CellType.ERROR)
            throw new BizException("请将公式或错误单元格转换为实际数值");
        if (cell.getCellType() == CellType.NUMERIC) return BigDecimal.valueOf(cell.getNumericCellValue()).stripTrailingZeros().toPlainString();
        return new DataFormatter(Locale.ROOT).formatCellValue(cell).trim();
    }
}
