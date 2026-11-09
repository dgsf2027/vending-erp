package top.aole.vend.modules.basedata.application;

import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import top.aole.vend.common.exception.BizException;
import top.aole.vend.modules.basedata.domain.entity.Machine;
import top.aole.vend.modules.basedata.infrastructure.mapper.MachineMapper;
import top.aole.vend.modules.basedata.interfaces.dto.MachineImportDtos.*;
import top.aole.vend.modules.imports.parser.ExcelParser;
import top.aole.vend.modules.imports.parser.ParsedSheet;

import java.io.ByteArrayInputStream;
import java.text.Normalizer;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class MachineImportService {
    private final ExcelParser excelParser;
    private final MachineMapper machines;
    private final MachineService machineService;
    private final PlatformTransactionManager transactionManager;

    public Preview parse(String fileName, byte[] content) {
        if (fileName == null || !fileName.toLowerCase(Locale.ROOT).matches(".*\\.xlsx?$") || content.length == 0
                || content.length > 20 * 1024 * 1024) throw new BizException("请选择不超过 20MB 的 .xls 或 .xlsx 机器列表");
        ParsedSheet sheet;
        try {
            sheet = excelParser.parse(new ByteArrayInputStream(content), headers ->
                headers.stream().anyMatch(h -> "deviceId".equals(field(h)))
                    && headers.stream().anyMatch(h -> "machineName".equals(field(h))));
        } catch (RuntimeException e) {
            if (e instanceof BizException) throw e;
            throw new BizException("无法读取机器列表，请选择未加密、未损坏的 Excel 文件");
        }
        Map<String, String> columns = new HashMap<>();
        for (String header : sheet.getHeaders()) {
            String field = field(header);
            if (field != null && columns.putIfAbsent(field, header) != null)
                throw new BizException("表格有重复的" + header + "列，请保留一列后上传");
        }
        if (!columns.containsKey("deviceId") || !columns.containsKey("machineName"))
            throw new BizException("未找到机器列表表头：需要「机子编号（柜子编码）」和「点位名称（表内最近记录）」，也支持「柜子编码 / 点位名称」。表头请放在前50行。");
        if (sheet.getRows().size() > 50000) throw new BizException("一次最多读取 50000 行，请拆分文件");
        LinkedHashMap<String, Row> unique = new LinkedHashMap<>();
        Map<String, LocalDateTime> latest = new HashMap<>();
        for (ParsedSheet.Row input : sheet.getRows()) {
            Row row = new Row();
            row.setRowNo(input.getRowNo());
            row.setDeviceId(value(input, columns, "deviceId"));
            row.setMachineName(value(input, columns, "machineName"));
            row.setMachineCode(value(input, columns, "machineCode"));
            String location = value(input, columns, "location");
            row.setLocation(location.isEmpty() ? row.getMachineName() : location);
            String key = row.getDeviceId().isEmpty() ? "missing:" + row.getRowNo() : "id:" + key(row.getDeviceId());
            LocalDateTime time = date(value(input, columns, "time"));
            Row prior = unique.get(key);
            if (prior == null) {
                unique.put(key, row);
                if (time != null) latest.put(key, time);
            } else {
                prior.setSourceRowCount(prior.getSourceRowCount() + 1);
                if (!prior.getMachineName().equals(row.getMachineName()) || !prior.getLocation().equals(row.getLocation()))
                    prior.setWarning("同一设备出现不同点位名称，已优先采用最近订单的名称；无订单时间时保留首条，请核对。");
                if (!row.getMachineCode().isEmpty() && !prior.getMachineCode().equals(row.getMachineCode()))
                    throw new BizException("第" + row.getRowNo() + "行：同一设备填写了不同内部编号，请核对");
                if (time != null && (latest.get(key) == null || time.isAfter(latest.get(key)))) {
                    prior.setMachineName(row.getMachineName());
                    prior.setLocation(row.getLocation());
                    latest.put(key, time);
                }
            }
        }
        Preview result = review(new ArrayList<>(unique.values()));
        // 仅解析阶段提供来源提示，不把客户端提示作为校验依据。
        int index = 0;
        for (Row source : unique.values()) {
            result.getRows().get(index).setSourceRowCount(source.getSourceRowCount());
            result.getRows().get(index++).setWarning(source.getWarning());
        }
        result.setFileName(fileName);
        result.setHeaderRowNo(sheet.getHeaderRowNo());
        result.setSourceRows(sheet.getRows().size());
        return result;
    }

    public Preview review(List<Row> input) {
        return review(input, machines.importIdentities());
    }

    private Preview review(List<Row> input, List<Machine> catalog) {
        if (input == null || input.isEmpty()) throw new BizException("没有可导入的机器，请先填写机器列表");
        if (input.size() > 1000) throw new BizException("一次最多导入 1000 台机器");
        Map<String, Machine> devices = new HashMap<>();
        Set<String> codes = new HashSet<>();
        for (Machine m : catalog) {
            devices.put(key(m.getDeviceId()), m);
            codes.add(key(m.getMachineCode()));
        }
        Map<String, Long> deviceCounts = input.stream().filter(Objects::nonNull)
            .collect(Collectors.groupingBy(r -> key(r.getDeviceId()), Collectors.counting()));
        Map<String, Long> codeCounts = input.stream().filter(Objects::nonNull)
            .filter(r -> !text(r.getMachineCode()).isEmpty())
            .collect(Collectors.groupingBy(r -> key(r.getMachineCode()), Collectors.counting()));
        Preview result = new Preview();
        for (int i = 0; i < input.size(); i++) {
            Row source = input.get(i);
            if (source == null) throw new BizException("机器列表不能包含空行对象");
            Row r = new Row();
            r.setRowNo(source.getRowNo() > 0 ? source.getRowNo() : i + 1);
            r.setDeviceId(text(source.getDeviceId())); r.setMachineName(text(source.getMachineName()));
            r.setMachineCode(text(source.getMachineCode())); r.setLocation(text(source.getLocation()));
            List<String> errors = new ArrayList<>();
            validate(errors, r.getDeviceId(), "后台设备ID", 64, true);
            validate(errors, r.getMachineName(), "机器名称", 64, true);
            validate(errors, r.getMachineCode(), "内部编号", 32, false);
            validate(errors, r.getLocation(), "点位", 128, false);
            if (deviceCounts.getOrDefault(key(r.getDeviceId()), 0L) > 1) errors.add("后台设备ID重复，请只保留一行");
            Machine existing = devices.get(key(r.getDeviceId()));
            if (existing != null) {
                if (Integer.valueOf(1).equals(existing.getIsDeleted())) errors.add("设备ID已被历史档案占用，请先核对历史机器");
                else if (errors.isEmpty()) {
                    r.setMachineCode(existing.getMachineCode());
                    r.setAction("已存在"); r.setMessage("保留现有档案：" + existing.getMachineName() + "，本次跳过");
                    result.setExistingCount(result.getExistingCount() + 1);
                }
            } else if (!r.getMachineCode().isEmpty()) {
                if (codes.contains(key(r.getMachineCode()))) errors.add("内部编号已被其他机器占用，可留空自动编号");
                if (codeCounts.getOrDefault(key(r.getMachineCode()), 0L) > 1) errors.add("内部编号在列表内重复");
            }
            if (!errors.isEmpty()) {
                r.setAction("待检查"); r.setMessage(String.join("；", errors));
                result.setErrorCount(result.getErrorCount() + 1);
            } else if (r.getAction() == null) {
                r.setAction("待新增"); r.setMessage(r.getMachineCode().isEmpty() ? "确认时自动分配内部编号" : "使用填写的内部编号");
                result.setCreateCount(result.getCreateCount() + 1);
            }
            result.getRows().add(r);
        }
        return result;
    }

    public Result commit(List<Row> input, String operator) {
        try {
            return new TransactionTemplate(transactionManager).execute(tx -> {
                List<Machine> catalog = machines.importIdentities();
                Preview checked = review(input, catalog);
                if (checked.getErrorCount() > 0) {
                    Row bad = checked.getRows().stream().filter(r -> "待检查".equals(r.getAction())).findFirst().get();
                    throw new BizException("第" + bad.getRowNo() + "行：" + bad.getMessage() + "。本次未导入，请修改后重试。");
                }
                Set<String> reserved = catalog.stream().map(m -> key(m.getMachineCode())).collect(Collectors.toSet());
                checked.getRows().stream().filter(r -> !r.getMachineCode().isEmpty()).forEach(r -> reserved.add(key(r.getMachineCode())));
                long next = 1;
                for (String code : reserved) if (code.matches("M[0-9]{1,12}")) next = Math.max(next, Long.parseLong(code.substring(1)) + 1);
                Result result = new Result();
                for (Row row : checked.getRows()) {
                    if ("已存在".equals(row.getAction())) { result.setSkipped(result.getSkipped() + 1); }
                    else {
                        if (row.getMachineCode().isEmpty()) {
                            String generated;
                            do { generated = String.format(Locale.ROOT, "M%06d", next++); } while (!reserved.add(key(generated)));
                            row.setMachineCode(generated);
                        }
                        Machine machine = new Machine();
                        machine.setMachineCode(row.getMachineCode()); machine.setMachineName(row.getMachineName());
                        machine.setDeviceId(row.getDeviceId()); machine.setLocation(row.getLocation());
                        machineService.create(machine, operator);
                        row.setAction("已新增"); row.setMessage("机器档案已建立，货道可在列表中配置");
                        result.setCreated(result.getCreated() + 1);
                    }
                    result.getRows().add(row);
                }
                return result;
            });
        } catch (DuplicateKeyException e) {
            throw new BizException("有机器刚被其他操作建档，本次已全部撤回，请重新检查后导入");
        }
    }

    private static void validate(List<String> errors, String value, String label, int max, boolean required) {
        if (required && value.isEmpty()) errors.add("请填写" + label);
        if (value.length() > max) errors.add(label + "最多" + max + "字");
        if (value.chars().anyMatch(Character::isISOControl)) errors.add(label + "不能包含换行或控制字符");
    }
    private static String text(String value) { return value == null ? "" : value.trim(); }
    private static String key(String value) { return text(value).toUpperCase(Locale.ROOT); }
    private static String value(ParsedSheet.Row row, Map<String, String> columns, String field) {
        return columns.containsKey(field) ? text(row.get(columns.get(field))) : "";
    }
    private static String field(String header) {
        String name = Normalizer.normalize(text(header), Normalizer.Form.NFKC).replaceAll("\\s+", "").toLowerCase(Locale.ROOT);
        switch (name) {
            case "机子编号(柜子编码)": case "柜子编码": case "后台设备id": case "设备id":
            case "机子编号": case "设备编号": case "设备编码": return "deviceId";
            case "点位名称(表内最近记录)": case "点位名称": case "机器名称": case "设备名称": return "machineName";
            case "机器编号": case "内部编号": return "machineCode";
            case "点位": case "位置": return "location";
            case "订单时间": case "出货时间": return "time";
            default: return null;
        }
    }
    private static LocalDateTime date(String value) {
        if (value.isEmpty()) return null;
        try { return LocalDateTime.parse(value.replace('/', '-').replace('T', ' '), DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")); }
        catch (RuntimeException e) { return null; }
    }
}
