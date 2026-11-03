package top.aole.vend.modules.imports.service;

import top.aole.vend.modules.imports.parser.ParsedSheet;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;

/** 元气销售表与出货明细使用同一导入管线，不读取品类汇总页。 */
final class SalesSheetNormalizer {
    static boolean isYuanqi(ParsedSheet sheet) {
        return sheet.getHeaders().contains("柜子编码") && sheet.getHeaders().contains("订单编号")
                && sheet.getHeaders().contains("销售数量") && sheet.getHeaders().contains("支付金额")
                && sheet.getHeaders().contains("订单时间");
    }

    static void normalize(ParsedSheet sheet) {
        if (!isYuanqi(sheet)) return;
        Map<String, String> names = new LinkedHashMap<>();
        names.put("柜子编码", "设备ID"); names.put("订单编号", "订单号");
        names.put("销售数量", "出货数量"); names.put("支付金额", "商品金额(元)");
        names.put("订单时间", "出货时间"); names.put("点位名称", "设备名称");
        names.put("支付渠道（微信/支付宝）", "支付方式"); names.put("品类", "商品分类");
        for (Map.Entry<String, String> entry : names.entrySet()) {
            int index = sheet.getHeaders().indexOf(entry.getKey());
            if (index < 0 || sheet.getHeaders().contains(entry.getValue())) continue;
            sheet.getHeaders().set(index, entry.getValue());
            for (ParsedSheet.Row row : sheet.getRows()) row.getCells().put(entry.getValue(), row.getCells().remove(entry.getKey()));
        }
        if (!sheet.getHeaders().contains("订单类型")) {
            sheet.getHeaders().add("订单类型");
            for (ParsedSheet.Row row : sheet.getRows()) {
                // 未提供类型的负额、零/负数量记录不能猜成正常销售，留空交给行校验。
                try {
                    if (new BigDecimal(row.get("出货数量")).signum() > 0 && new BigDecimal(row.get("商品金额(元)")).signum() >= 0) {
                        row.getCells().put("订单类型", "正常订单");
                    }
                } catch (RuntimeException ignored) { /* 行校验返回缺项或格式错误 */ }
            }
        }
    }
}
