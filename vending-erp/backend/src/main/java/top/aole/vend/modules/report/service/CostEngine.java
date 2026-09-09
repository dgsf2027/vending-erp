package top.aole.vend.modules.report.service;

import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import top.aole.vend.modules.report.dto.ReportDtos.LedgerEvent;
import top.aole.vend.modules.report.dto.ReportDtos.SaleEvent;
import top.aole.vend.modules.report.mapper.ReportQueryMapper;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * 加权成本引擎 —— 旧版单文件进销存台账的口径搬运(2026-09,替换原「移动加权」实现)。
 *
 * 口径(与旧版 template.html 的 recompute()/ledgerRows() 一致):
 * <ul>
 * <li><b>一本账</b>:库存 = 期初 + 入库 − 出库。销售/盘亏/报损都是出库;
 *     出库上架/退库只是货换了地方,不进不出(其红冲同理);</li>
 * <li><b>按月结转(月末一次加权)</b>:加权单价 = (期初金额 + 本月入库金额) ÷ (期初数量 + 本月入库数量);
 *     本月全部出库按该单价结转;期末金额 = 期末数量 × 加权单价 = 下月期初金额(连续结转);</li>
 * <li><b>累计口径</b>(库存页 / 驾驶舱 / 报表「累计」):加权单价 = Σ入库金额 ÷ Σ入库数量,
 *     出库成本 = 出库数量 × 单价,期末金额 = 期末数量 × 单价 —— 即旧版看板与台账默认视图的算法;</li>
 * <li><b>没有入库史</b>:沿用最近一次加权单价 → 商品档案「参考成本」兜底(旧版的「成本单价」)→ 仍无则成本为空,
 *     毛利显「—」,不进合计;</li>
 * <li>收入口径保留新版三口径:正常全额、退款负、兑换/线下补录收入 0(成本照算)、测试不计;</li>
 * <li>qty=0 的成本调整行只动本月入库金额(±Δ);红冲行按原单类型反向计入(红冲采购 = 入库 −)。</li>
 * </ul>
 * 全程动态重算(数据量小,毫秒级);persist 由 ReportService.recalc 把成本快照回写
 * sale_record.cost_amount 与 ledger 出库/转移行(M3 结算 / M4 BI 直接读快照)。
 */
@Service
@RequiredArgsConstructor
public class CostEngine {

    private static final DateTimeFormatter PERIOD = DateTimeFormatter.ofPattern("yyyy-MM");
    private static final int SCALE = 6;

    /** 转移类单据:一本账无感(货只是换地方) */
    private static final String TYPE_TRANSFER_OUT = "出库上架";
    private static final String TYPE_RETURN_BACK = "退库";
    private static final String TYPE_RED_FLUSH = "红冲";
    /** 入库类单据(数量、金额进「入库」;其红冲反向) */
    private static final Set<String> IN_TYPES = new HashSet<>(Arrays.asList("采购入库", "期初", "盘盈入库"));

    private final ReportQueryMapper reportQueryMapper;

    // ============================== 结果结构 ==============================

    /** 单 SKU 累计成本池(旧版 AGG 一行:Σ入库 / 期末结存 / 累计加权单价) */
    @Data
    public static class Pool {
        /** 期末结存(累计) */
        private BigDecimal qty = BigDecimal.ZERO;
        /** 期末金额 = 期末结存 × 累计加权单价 */
        private BigDecimal val = BigDecimal.ZERO;
        /** 最近一次月度加权单价(入库史为空时沿用) */
        private BigDecimal lastAvg;
        /** 累计入库数量 / 金额(含期初、盘盈、成本调整 Δ;红冲反向) */
        private BigDecimal inbQty = BigDecimal.ZERO;
        private BigDecimal inbAmt = BigDecimal.ZERO;
        /** 累计出库数量(销售 + 盘亏/报损,退款为负) */
        private BigDecimal outQty = BigDecimal.ZERO;
        /** 商品档案参考成本(旧版「成本单价」兜底) */
        private BigDecimal refCost;

        /** 是否能算成本:有入库史 / 有沿用单价 / 有参考成本 */
        public boolean hasCost() {
            return currentAvg() != null;
        }

        /** 累计加权单价:Σ入库金额 ÷ Σ入库数量;无入库史 → 最近月度单价 → 参考成本 → null */
        public BigDecimal currentAvg() {
            if (inbQty.signum() > 0) {
                return inbAmt.divide(inbQty, SCALE, RoundingMode.HALF_UP);
            }
            if (lastAvg != null) {
                return lastAvg;
            }
            return refCost == null || refCost.signum() <= 0 ? null : refCost;
        }
    }

    /** 按 (key, 月) 的销售聚合 */
    @Data
    public static class MonthAgg {
        private BigDecimal salesQty = BigDecimal.ZERO;
        /** 正常 + 退款(负);兑换/线下补录收入 0 */
        private BigDecimal salesAmt = BigDecimal.ZERO;
        private BigDecimal costAmt = BigDecimal.ZERO;
        /** 出现过无成本销售(SKU 维=整行无成本;机器维=部分无成本) */
        private boolean noCost;
        private int noCostSkuCount;
    }

    /** 按 (productId, 月) 的进销存聚合(一本账口径:仓库+机器合计) */
    @Data
    public static class InvAgg {
        private BigDecimal inQty = BigDecimal.ZERO;
        private BigDecimal inAmt = BigDecimal.ZERO;
        private BigDecimal outQty = BigDecimal.ZERO;
        /** 出库成本 = 出库数量 × 本月加权单价 */
        private BigDecimal outAmt = BigDecimal.ZERO;
        /** 期初(上月期末结转) */
        private BigDecimal openingQty = BigDecimal.ZERO;
        private BigDecimal openingVal = BigDecimal.ZERO;
        /** 期末 = 期初 + 入库 − 出库;期末金额 = 期末数量 × 本月加权单价 */
        private BigDecimal closingQty = BigDecimal.ZERO;
        private BigDecimal closingVal = BigDecimal.ZERO;
        /** 本月加权单价(无成本 = null) */
        private BigDecimal unitCost;
        private boolean snapshotTaken;
    }

    @Data
    public static class Replay {
        /** productId+月 → 销售聚合(未绑定行 productId 用 UNBOUND_KEY) */
        private Map<String, MonthAgg> skuMonth = new LinkedHashMap<>();
        /** machineId+月 → 销售聚合 */
        private Map<String, MonthAgg> machineMonth = new LinkedHashMap<>();
        /** productId+月 → 进销存聚合 */
        private Map<String, InvAgg> invMonth = new LinkedHashMap<>();
        /** 各 SKU 累计成本池 */
        private Map<Long, Pool> pools = new HashMap<>();
        /** saleId → 结转成本(退款为负;无成本 SKU 不在内) */
        private Map<Long, BigDecimal> saleCost = new HashMap<>();
        /** 无成本 SKU 的销售行(cost_amount 应回写 NULL) */
        private List<Long> noCostSaleIds = new ArrayList<>();
        /** ledgerId → 单位成本快照(出库/转移行;无成本 SKU 不在内) */
        private Map<Long, BigDecimal> ledgerUnitCost = new HashMap<>();
        /** 事件流出现过的全部月份(升序,无空洞——中间月补齐) */
        private List<String> months = new ArrayList<>();
        /** machineId → productId → 累计销量(带符号);报表「累计」机器维用 */
        private Map<Long, Map<Long, BigDecimal>> machineSkuQty = new LinkedHashMap<>();
        /** machineId → 累计无成本销售行数 */
        private Map<Long, Integer> machineNoCostRows = new HashMap<>();

        public static final long UNBOUND_KEY = -1L;
        /** 聚合键分隔符(id + SEP + 月份) */
        public static final String KEY_SEP = "\u0001";

        public static String key(long id, String period) {
            return id + KEY_SEP + period;
        }

        /** 从聚合键取回 id */
        public static long keyId(String key) {
            return Long.parseLong(key.substring(0, key.indexOf(KEY_SEP)));
        }
    }

    // ============================== 事件分桶 ==============================

    /** 单 SKU 单月的原始事件桶 */
    private static class Bucket {
        BigDecimal inQty = BigDecimal.ZERO;
        BigDecimal inAmt = BigDecimal.ZERO;
        /** 无金额入库(如无价盘盈):按期初单价估值,不改变单价 */
        BigDecimal inNoAmtQty = BigDecimal.ZERO;
        /** 成本调整 Δ(qty=0 行) */
        BigDecimal adjAmt = BigDecimal.ZERO;
        /** 单据出库数量(盘亏/报损为正,其红冲为负) */
        BigDecimal outQtyLedger = BigDecimal.ZERO;
        final List<LedgerEvent> costRows = new ArrayList<>();
        final List<SaleEvent> sales = new ArrayList<>();
    }

    // ============================== 重放 ==============================

    public Replay replay() {
        List<LedgerEvent> ledgers = reportQueryMapper.ledgerEvents();
        List<SaleEvent> sales = reportQueryMapper.saleEvents();
        Map<Long, BigDecimal> refCosts = loadRefCosts();
        Replay r = new Replay();
        TreeSet<String> monthSet = new TreeSet<>();
        Map<Long, TreeMap<String, Bucket>> byProduct = new LinkedHashMap<>();

        for (LedgerEvent e : ledgers) {
            String period = e.getBizTime().format(PERIOD);
            monthSet.add(period);
            String effective = e.getOriginDocType() != null ? e.getOriginDocType() : e.getDocType();
            Bucket b = bucket(byProduct, e.getProductId(), period);
            if (TYPE_TRANSFER_OUT.equals(effective) || TYPE_RETURN_BACK.equals(effective)) {
                b.costRows.add(e); // 转移:一本账无感,只记单位成本快照
                continue;
            }
            int sign = e.getChangeQty().signum();
            if (sign == 0) {
                b.adjAmt = b.adjAmt.add(nvl(e.getAmount())); // 成本调整:只动金额(附录C)
                continue;
            }
            boolean inbound = IN_TYPES.contains(effective)
                    || (sign > 0 && !TYPE_RED_FLUSH.equals(e.getDocType()));
            if (inbound) {
                // 入库(采购/期初/盘盈);红冲采购 = 负数量负金额,自然反向
                if (e.getAmount() != null) {
                    b.inQty = b.inQty.add(e.getChangeQty());
                    b.inAmt = b.inAmt.add(e.getAmount());
                } else {
                    b.inQty = b.inQty.add(e.getChangeQty());
                    b.inNoAmtQty = b.inNoAmtQty.add(e.getChangeQty());
                }
            } else {
                // 出库(盘亏/报损/其它负行):按本月加权单价结转;盘亏红冲(+)= 出库负
                b.outQtyLedger = b.outQtyLedger.add(e.getChangeQty().negate());
                b.costRows.add(e);
            }
        }

        for (SaleEvent s : sales) {
            String type = s.getOrderType() == null ? "正常" : s.getOrderType();
            if ("测试".equals(type)) {
                continue; // 三口径:测试不计(§13.2-3)
            }
            String period = s.getBizPeriod() != null ? s.getBizPeriod() : s.getBizTime().format(PERIOD);
            monthSet.add(period);
            if (s.getProductId() == null) {
                // 未绑定行:只进销售额聚合,无成本
                MonthAgg sku = r.getSkuMonth().computeIfAbsent(Replay.key(Replay.UNBOUND_KEY, period), k -> new MonthAgg());
                addSale(sku, s, null);
                if (s.getMachineId() != null) {
                    MonthAgg mac = r.getMachineMonth().computeIfAbsent(Replay.key(s.getMachineId(), period), k -> new MonthAgg());
                    addSale(mac, s, null);
                    mac.setNoCostSkuCount(mac.getNoCostSkuCount() + 1);
                    r.getMachineNoCostRows().merge(s.getMachineId(), 1, Integer::sum);
                }
                continue;
            }
            bucket(byProduct, s.getProductId(), period).sales.add(s);
        }

        // 月份序列补齐空洞(进销存期初期末要连续结转)
        if (!monthSet.isEmpty()) {
            YearMonth m = YearMonth.parse(monthSet.first());
            YearMonth last = YearMonth.parse(monthSet.last());
            while (!m.isAfter(last)) {
                r.getMonths().add(m.toString());
                m = m.plusMonths(1);
            }
        }

        for (Map.Entry<Long, TreeMap<String, Bucket>> e : byProduct.entrySet()) {
            replayProduct(r, e.getKey(), e.getValue(), refCosts.get(e.getKey()));
        }
        return r;
    }

    /** 单 SKU 按月结转:期初 → 加权单价 → 出库结转 → 期末 = 下月期初 */
    private void replayProduct(Replay r, Long productId, TreeMap<String, Bucket> buckets, BigDecimal refCost) {
        Pool pool = new Pool();
        pool.setRefCost(refCost);
        BigDecimal carryQty = BigDecimal.ZERO;
        BigDecimal carryAmt = BigDecimal.ZERO;
        BigDecimal lastAvg = null;
        for (String month : r.getMonths()) {
            Bucket b = buckets.get(month);
            InvAgg inv = r.getInvMonth().computeIfAbsent(Replay.key(productId, month), k -> new InvAgg());
            inv.setOpeningQty(carryQty);
            inv.setOpeningVal(carryAmt);
            inv.setSnapshotTaken(true);
            if (b == null) {
                // 无事件月:照抄期初
                inv.setClosingQty(carryQty);
                inv.setClosingVal(carryAmt);
                inv.setUnitCost(lastAvg != null ? lastAvg : (refCost != null && refCost.signum() > 0 ? refCost : null));
                continue;
            }
            // 无价入库按期初单价估值(不改变单价);没有任何单价依据时按 0 计
            BigDecimal avgBefore = carryQty.signum() > 0
                    ? carryAmt.divide(carryQty, SCALE, RoundingMode.HALF_UP)
                    : (lastAvg != null ? lastAvg : (refCost != null && refCost.signum() > 0 ? refCost : null));
            BigDecimal inAmt = b.inAmt.add(b.adjAmt);
            if (b.inNoAmtQty.signum() != 0 && avgBefore != null) {
                inAmt = inAmt.add(b.inNoAmtQty.multiply(avgBefore));
            }
            BigDecimal baseQty = carryQty.add(b.inQty);
            BigDecimal baseAmt = carryAmt.add(inAmt);
            // 旧版公式:加权单价 = (期初金额 + 入库金额) ÷ (期初数量 + 入库数量);分母 ≤0 → 沿用/参考成本
            BigDecimal wAvg = baseQty.signum() > 0
                    ? baseAmt.divide(baseQty, SCALE, RoundingMode.HALF_UP)
                    : (lastAvg != null ? lastAvg : (refCost != null && refCost.signum() > 0 ? refCost : null));

            // 销售:按本月加权单价结转
            BigDecimal outQty = b.outQtyLedger;
            for (SaleEvent s : b.sales) {
                String type = s.getOrderType() == null ? "正常" : s.getOrderType();
                boolean refund = "退款".equals(type);
                BigDecimal signedQty = refund ? nvl(s.getQty()).negate() : nvl(s.getQty());
                outQty = outQty.add(signedQty);
                BigDecimal cost = wAvg == null ? null : signedQty.multiply(wAvg);
                MonthAgg sku = r.getSkuMonth().computeIfAbsent(Replay.key(productId, month), k -> new MonthAgg());
                addSale(sku, s, cost);
                if (s.getMachineId() != null) {
                    MonthAgg mac = r.getMachineMonth().computeIfAbsent(Replay.key(s.getMachineId(), month), k -> new MonthAgg());
                    addSale(mac, s, cost);
                    if (cost == null) {
                        mac.setNoCostSkuCount(mac.getNoCostSkuCount() + 1);
                        r.getMachineNoCostRows().merge(s.getMachineId(), 1, Integer::sum);
                    }
                    r.getMachineSkuQty().computeIfAbsent(s.getMachineId(), k -> new LinkedHashMap<>())
                            .merge(productId, signedQty, BigDecimal::add);
                }
                if (cost == null) {
                    r.getNoCostSaleIds().add(s.getId());
                } else {
                    r.getSaleCost().put(s.getId(), cost.setScale(4, RoundingMode.HALF_UP));
                }
            }
            for (LedgerEvent row : b.costRows) {
                if (wAvg != null) {
                    r.getLedgerUnitCost().put(row.getId(), wAvg);
                }
            }
            BigDecimal outAmt = wAvg == null ? BigDecimal.ZERO : outQty.multiply(wAvg);
            BigDecimal closingQty = baseQty.subtract(outQty);
            BigDecimal closingAmt = wAvg == null ? BigDecimal.ZERO : closingQty.multiply(wAvg);
            inv.setInQty(b.inQty);
            inv.setInAmt(inAmt);
            inv.setOutQty(outQty);
            inv.setOutAmt(outAmt);
            inv.setClosingQty(closingQty);
            inv.setClosingVal(closingAmt);
            inv.setUnitCost(wAvg);

            pool.setInbQty(pool.getInbQty().add(b.inQty));
            pool.setInbAmt(pool.getInbAmt().add(inAmt));
            pool.setOutQty(pool.getOutQty().add(outQty));
            carryQty = closingQty;
            carryAmt = closingAmt;
            if (wAvg != null) {
                lastAvg = wAvg;
            }
        }
        pool.setQty(carryQty);
        pool.setLastAvg(lastAvg);
        BigDecimal avg = pool.currentAvg();
        pool.setVal(avg == null ? BigDecimal.ZERO : carryQty.multiply(avg));
        r.getPools().put(productId, pool);
    }

    /** 销售行进聚合:销售额口径 正常全额 / 退款负 / 兑换、线下补录 0;销量带符号 */
    private static void addSale(MonthAgg agg, SaleEvent s, BigDecimal cost) {
        String type = s.getOrderType() == null ? "正常" : s.getOrderType();
        boolean refund = "退款".equals(type);
        boolean zeroRevenue = "兑换".equals(type) || "线下补录".equals(type);
        BigDecimal amt = zeroRevenue ? BigDecimal.ZERO : nvl(s.getAmountReceived());
        BigDecimal qty = nvl(s.getQty());
        agg.setSalesAmt(agg.getSalesAmt().add(amt));
        agg.setSalesQty(agg.getSalesQty().add(refund ? qty.negate() : qty));
        if (cost == null) {
            agg.setNoCost(true);
        } else {
            agg.setCostAmt(agg.getCostAmt().add(cost));
        }
    }

    private static Bucket bucket(Map<Long, TreeMap<String, Bucket>> byProduct, Long productId, String period) {
        return byProduct.computeIfAbsent(productId, k -> new TreeMap<>())
                .computeIfAbsent(period, k -> new Bucket());
    }

    private Map<Long, BigDecimal> loadRefCosts() {
        Map<Long, BigDecimal> map = new HashMap<>();
        for (Map<String, Object> row : reportQueryMapper.productRefCosts()) {
            Object cost = row.get("refCost");
            if (cost != null) {
                map.put(((Number) row.get("id")).longValue(), new BigDecimal(cost.toString()));
            }
        }
        return map;
    }

    private static BigDecimal nvl(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }
}
