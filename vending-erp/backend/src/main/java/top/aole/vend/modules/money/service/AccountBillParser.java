package top.aole.vend.modules.money.service;

import org.apache.poi.ss.usermodel.*;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;
import top.aole.vend.common.exception.BizException;
import top.aole.vend.modules.money.domain.entity.AccountBill;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.util.*;

/** .xls/.xlsx 账单按原始字段读取，不推断手续费口径，不改变结算状态。 */
@Component
public class AccountBillParser {
    private static final String[] HEADERS = {"账单时间", "收益金额", "支付手续费", "账号", "姓名", "角色", "打款渠道", "渠道商户号", "到账时间", "结算金额", "结算状态", "结算信息"};

    public List<AccountBill> parse(MultipartFile file) {
        String name = file.getOriginalFilename();
        if (name == null || !name.toLowerCase(Locale.ROOT).matches(".*\\.xlsx?")) throw new BizException("请上传 .xls 或 .xlsx 账单文件");
        if (file.isEmpty() || file.getSize() > 5 * 1024 * 1024) throw new BizException("账单文件须为非空且不超过 5MB");
        if (name.length() > 255) throw new BizException("文件名过长，请缩短后上传");
        try (Workbook book = WorkbookFactory.create(file.getInputStream())) {
            if (book.getNumberOfSheets() != 1) throw new BizException("请保留一个账单工作表后上传");
            Sheet sheet = book.getSheetAt(0);
            if (sheet.getLastRowNum() > 1000) throw new BizException("每次最多导入 1000 行账单");
            Row header = sheet.getRow(0);
            if (header == null) throw new BizException("第一行缺少账单表头");
            Map<String, Integer> columns = new HashMap<>();
            for (Cell c : header) {
                String key = text(c);
                if (key.isEmpty()) continue;
                if (columns.put(key, c.getColumnIndex()) != null) throw new BizException("表头重复：" + key);
            }
            for (String key : HEADERS) if (!columns.containsKey(key)) throw new BizException("缺少表头：" + key);
            String fileHash = hash(file.getBytes());
            List<AccountBill> result = new ArrayList<>();
            for (int i = 1; i <= sheet.getLastRowNum(); i++) {
                Row row = sheet.getRow(i);
                if (row == null) continue;
                try {
                    String[] v = new String[HEADERS.length];
                    boolean any = false;
                    for (int c = 0; c < HEADERS.length; c++) {
                        Cell cell = row.getCell(columns.get(HEADERS[c]));
                        v[c] = text(cell);
                        if (cell != null && cell.getCellType() == CellType.NUMERIC && DateUtil.isCellDateFormatted(cell))
                            v[c] = cell.getLocalDateTimeCellValue().toLocalDate().toString();
                        if (!v[c].isEmpty()) any = true;
                    }
                    if (!any) continue;
                    AccountBill b = new AccountBill();
                    b.setBillDate(date(v[0], false)); b.setIncomeAmount(amount(v[1])); b.setFeeAmount(amount(v[2]));
                    b.setSourceAccount(required(v[3], 128)); b.setOwnerName(required(v[4], 128)); b.setSourceRole(required(v[5], 64));
                    b.setChannel(required(v[6], 64)); b.setMerchantNo(required(v[7], 128));
                    Cell merchant = row.getCell(columns.get("渠道商户号"));
                    if (merchant != null && merchant.getCellType() == CellType.NUMERIC && v[7].length() > 15)
                        throw new BizException("渠道商户号超过 15 位，必须使用文本单元格，避免 Excel 丢失精度");
                    b.setArrivalDate(date(v[8], true)); b.setSettlementAmount(amount(v[9]));
                    b.setSettlementStatus(required(v[10], 64));
                    if (v[11].length() > 500) throw new BizException("结算信息不能超过 500 字");
                    b.setSettlementInfo(v[11]);
                    if ("成功".equals(b.getSettlementStatus()) && b.getArrivalDate() == null) throw new BizException("结算成功的账单必须有到账时间");
                    if (b.getArrivalDate() != null && b.getArrivalDate().isBefore(b.getBillDate())) throw new BizException("到账日期早于账单日期");
                    b.setSourceFile(name); b.setSourceRow(i + 1); b.setFileHash(fileHash);
                    b.setIdentityHash(hashParts(Arrays.asList(b.getBillDate().toString(), b.getChannel(), b.getMerchantNo(), b.getSourceAccount())));
                    b.setContentHash(hashParts(Arrays.asList(b.getBillDate().toString(), b.getIncomeAmount().toPlainString(), b.getFeeAmount().toPlainString(), b.getSourceAccount(), b.getOwnerName(), b.getSourceRole(), b.getChannel(), b.getMerchantNo(), b.getArrivalDate() == null ? "" : b.getArrivalDate().toString(), b.getSettlementAmount().toPlainString(), b.getSettlementStatus(), b.getSettlementInfo())));
                    result.add(b);
                } catch (BizException e) { throw new BizException("第 " + (i + 1) + " 行：" + e.getMessage()); }
            }
            if (result.isEmpty()) throw new BizException("没有可导入的账单明细");
            return result;
        } catch (BizException e) { throw e; }
        catch (IOException | RuntimeException e) { throw new BizException("账单文件无法读取，请使用未加密、未损坏的 Excel 文件"); }
    }
    private String required(String value, int length) {
        if (value.isEmpty() || value.length() > length) throw new BizException("账号、姓名、角色、渠道、商户号、结算状态必填，且不能超长");
        return value;
    }
    private LocalDate date(String value, boolean optional) {
        if (value.isEmpty() && optional) return null;
        try { return LocalDate.parse(value); } catch (RuntimeException e) { throw new BizException("日期必须为 YYYY-MM-DD"); }
    }
    private BigDecimal amount(String value) {
        if (!value.matches("[0-9]+(\\.[0-9]+)?")) throw new BizException("金额必须为非负数字");
        BigDecimal n = new BigDecimal(value).stripTrailingZeros();
        if (n.scale() > 2 || n.precision() - n.scale() > 12) throw new BizException("金额最多 12 位整数、2 位小数");
        return n.setScale(2);
    }
    private String text(Cell c) {
        if (c == null) return "";
        if (c.getCellType() == CellType.FORMULA || c.getCellType() == CellType.ERROR) throw new BizException("请将公式或错误单元格转换为实际值");
        if (c.getCellType() == CellType.NUMERIC) return BigDecimal.valueOf(c.getNumericCellValue()).stripTrailingZeros().toPlainString();
        return new DataFormatter(Locale.ROOT).formatCellValue(c).trim();
    }
    private String hashParts(List<String> parts) {
        StringBuilder s = new StringBuilder();
        for (String p : parts) s.append(p.length()).append(':').append(p);
        return hash(s.toString().getBytes(StandardCharsets.UTF_8));
    }
    private static String hash(byte[] bytes) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
            StringBuilder s = new StringBuilder();
            for (byte b : digest) s.append(String.format("%02x", b & 255));
            return s.toString();
        } catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
}
