package top.aole.vend.modules.report.service;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import top.aole.vend.modules.basedata.domain.entity.Machine;
import top.aole.vend.modules.basedata.domain.entity.Product;
import top.aole.vend.modules.basedata.infrastructure.mapper.MachineMapper;
import top.aole.vend.modules.basedata.infrastructure.mapper.ProductMapper;
import top.aole.vend.modules.claim.dto.ClaimDtos;
import top.aole.vend.modules.claim.service.ClaimService;
import top.aole.vend.modules.money.domain.enums.PlLine;
import top.aole.vend.modules.money.dto.MoneyDtos;
import top.aole.vend.modules.money.service.CashFlowQueryService;
import top.aole.vend.modules.report.dto.DashboardDtos;
import top.aole.vend.modules.report.dto.DashboardDtos.DashboardCategoryRow;
import top.aole.vend.modules.report.dto.DashboardDtos.DashboardMachineRow;
import top.aole.vend.modules.report.dto.DashboardDtos.DashboardPayRow;
import top.aole.vend.modules.report.dto.DashboardDtos.DashboardProductRow;
import top.aole.vend.modules.report.dto.DashboardDtos.DashboardResp;
import top.aole.vend.modules.report.dto.DashboardDtos.DashboardTrendRow;
import top.aole.vend.modules.report.dto.DashboardDtos.PurchaseDocEvent;
import top.aole.vend.modules.report.dto.ReportDtos.SaleEvent;
import top.aole.vend.modules.report.mapper.ReportQueryMapper;
import top.aole.vend.modules.report.service.CostEngine.Pool;
import top.aole.vend.modules.report.service.CostEngine.Replay;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

/**
 * 经营驾驶舱三维度聚合服务:
 * <ul>
 *   <li><b>month(默认)</b>:月度总览 —— 每日走势、较上月环比、当月机器/品类/商品TOP10/支付结构、月度费用与经营利润;</li>
 *   <li><b>year</b>:年度总览 —— 1~12 月走势、较上年同比、全年采购投入、年度毛利与经营利润;</li>
 *   <li><b>overall</b>:经营总览 —— 开账至今全期累计(成本按 CostEngine 累计加权单价,与 /reports「累计」一致)、历月走势与全期经营利润。</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
public class DashboardOverviewService {

    public static final String SCOPE_MONTH = "month";
    public static final String SCOPE_YEAR = "year";
    public static final String SCOPE_OVERALL = "overall";

    private static final DateTimeFormatter PERIOD_FMT = DateTimeFormatter.ofPattern("yyyy-MM");
    private static final DateTimeFormatter DAY_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final Pattern MONTH_PATTERN = Pattern.compile("^\\d{4}-\\d{2}$");
    private static final Pattern YEAR_PATTERN = Pattern.compile("^\\d{4}$");
    private static final Pattern ORDER_SUFFIX = Pattern.compile("#\\d+$");
    private static final BigDecimal HUNDRED = new BigDecimal("100");
    private static final BigDecimal DEFAULT_MONTH_MIN_SALES = new BigDecimal("100");

    private final CostEngine costEngine;
    private final ReportQueryMapper reportQueryMapper;
    private final ProductMapper productMapper;
    private final MachineMapper machineMapper;
    private final CashFlowQueryService cashFlowQueryService;
    private final ClaimService claimService;

    public DashboardResp overview(String rawScope, String rawPeriod) {
        String scope = normalizeScope(rawScope);
        Replay replay = costEngine.replay();
        List<SaleEvent> allSales = safeList(reportQueryMapper.saleEvents());
        List<PurchaseDocEvent> allPurchases = safeList(reportQueryMapper.dashboardPurchaseDocs());
        Map<Long, Product> products = loadProducts();
        Map<Long, Machine> machines = loadMachines();

        // 可选月份 & 年份(倒序)
        TreeSet<String> monthSet = new TreeSet<>();
        if (replay.getMonths() != null) {
            monthSet.addAll(replay.getMonths());
        }
        for (PurchaseDocEvent d : allPurchases) {
            String p = docPeriod(d);
            if (isValidMonth(p)) {
                monthSet.add(p);
            }
        }
        Map<String, BigDecimal> monthSalesMap = computeMonthSalesMap(allSales);
        for (String m : monthSalesMap.keySet()) {
            if (isValidMonth(m)) {
                monthSet.add(m);
            }
        }

        List<String> monthsAsc = new ArrayList<>(monthSet);
        List<String> monthsDesc = new ArrayList<>(monthsAsc);
        Collections.reverse(monthsDesc);

        TreeSet<String> yearSet = new TreeSet<>();
        for (String m : monthsAsc) {
            yearSet.add(m.substring(0, 4));
        }
        List<String> yearsDesc = new ArrayList<>(yearSet);
        Collections.reverse(yearsDesc);

        // 解析选中区间
        String period = resolvePeriod(scope, rawPeriod, monthsDesc, yearsDesc, monthSalesMap);

        DashboardResp resp = new DashboardResp();
        resp.setScope(scope);
        resp.setPeriod(period);
        resp.setPeriodLabel(buildPeriodLabel(scope, period));
        resp.setMonths(monthsDesc);
        resp.setYears(yearsDesc);
        resp.setDataAsOf(reportQueryMapper.dataAsOf());
        resp.setTotalMachineCount(machines.size());

        boolean useCumulativeCost = SCOPE_OVERALL.equals(scope);

        // ---- 聚合当前区间 ----
        ScopeStats curr = aggregateScope(scope, period, allSales, allPurchases, replay, products, machines,
                useCumulativeCost, true, monthsAsc);

        resp.setFirstBizDate(curr.firstDate == null ? null : curr.firstDate.format(DAY_FMT));
        resp.setLastBizDate(curr.lastDate == null ? null : curr.lastDate.format(DAY_FMT));
        resp.setActiveDays(curr.activeDays.size());

        resp.setSalesAmt(scale2(curr.salesAmt));
        resp.setSalesQty(strip(curr.salesQty));
        resp.setOrderCount(curr.orderNos.size());
        resp.setAvgOrderAmt(curr.orderNos.isEmpty() ? null
                : curr.normalSalesAmt.divide(BigDecimal.valueOf(curr.orderNos.size()), 2, RoundingMode.HALF_UP));
        resp.setDailyAvgSales(curr.activeDays.isEmpty() ? null
                : curr.salesAmt.divide(BigDecimal.valueOf(curr.activeDays.size()), 2, RoundingMode.HALF_UP));
        resp.setRefundAmt(scale2(curr.refundAmt));
        resp.setRefundCount(curr.refundCount);
        resp.setExchangeCount(curr.exchangeCount);

        resp.setCostedSalesAmt(scale2(curr.costedSalesAmt));
        resp.setNoCostSkuCount(curr.noCostSkus.size());
        if (curr.hasCostData) {
            BigDecimal gross = curr.costedSalesAmt.subtract(curr.costAmt);
            resp.setCostAmt(scale2(curr.costAmt));
            resp.setGrossProfit(scale2(gross));
            resp.setMarginPct(curr.costedSalesAmt.signum() > 0 ? pct(gross, curr.costedSalesAmt) : null);
        } else if (curr.totalNonTestRows == 0) {
            resp.setCostAmt(scale2(BigDecimal.ZERO));
            resp.setGrossProfit(scale2(BigDecimal.ZERO));
            resp.setMarginPct(null);
        }

        resp.setPurchaseAmt(scale2(curr.purchaseAmt));
        resp.setPurchaseQty(strip(curr.purchaseQty));
        resp.setPurchaseDocCount(curr.purchaseDocCount);

        resp.setActiveMachineCount(curr.activeMachineIds.size());
        resp.setSoldSkuCount(curr.soldProductIds.size());

        // ---- 费用 / 损耗 / 补贴 / 经营利润 ----
        String fromPeriod = rangeStart(scope, period);
        String toPeriod = rangeEnd(scope, period);
        fillProfitLines(resp, scope, period, fromPeriod, toPeriod, curr.hasCostData ? curr.costedSalesAmt.subtract(curr.costAmt) : (curr.totalNonTestRows == 0 ? BigDecimal.ZERO : null), curr.costedSalesAmt);

        // ---- 环/同比 ----
        if (SCOPE_MONTH.equals(scope)) {
            String prevPeriod = YearMonth.parse(period, PERIOD_FMT).minusMonths(1).format(PERIOD_FMT);
            ScopeStats prev = aggregateScope(SCOPE_MONTH, prevPeriod, allSales, allPurchases, replay,
                    products, machines, false, false, monthsAsc);
            fillCompare(resp, "较上月", curr, prev);
        } else if (SCOPE_YEAR.equals(scope)) {
            String prevYear = String.valueOf(Integer.parseInt(period) - 1);
            ScopeStats prev = aggregateScope(SCOPE_YEAR, prevYear, allSales, allPurchases, replay,
                    products, machines, false, false, monthsAsc);
            fillCompare(resp, "较上年", curr, prev);
        }

        // ---- 走势 & 多维明细 ----
        resp.setTrend(curr.trend);
        resp.setMachines(curr.machineRows);
        resp.setCategories(curr.categoryRows);
        resp.setTopProducts(curr.topProductRows);
        resp.setPayMethods(curr.payRows);
        return resp;
    }

    // ============================== 核心聚合 ==============================

    private ScopeStats aggregateScope(String scope, String period,
                                      List<SaleEvent> allSales,
                                      List<PurchaseDocEvent> allPurchases,
                                      Replay replay,
                                      Map<Long, Product> products,
                                      Map<Long, Machine> machines,
                                      boolean useCumulativeCost,
                                      boolean buildBreakdowns,
                                      List<String> monthsAsc) {
        ScopeStats st = new ScopeStats();

        Map<Long, MachineAcc> machineMap = new LinkedHashMap<>();
        Map<String, CategoryAcc> categoryMap = new LinkedHashMap<>();
        Map<Long, ProductAcc> productMap = new LinkedHashMap<>();
        Map<String, PayAcc> payMap = new LinkedHashMap<>();
        Map<String, TrendAcc> trendMap = buildBreakdowns ? initTrendBuckets(scope, period, allSales, allPurchases, monthsAsc) : Collections.emptyMap();

        for (SaleEvent s : allSales) {
            String type = StrUtil.isBlank(s.getOrderType()) ? "正常" : s.getOrderType();
            if ("测试".equals(type)) {
                continue;
            }
            String sp = salePeriod(s);
            if (!matchesScope(sp, scope, period)) {
                continue;
            }

            st.totalNonTestRows++;
            LocalDate bizDate = s.getBizTime() != null ? s.getBizTime().toLocalDate() : null;
            if (bizDate != null) {
                st.recordDate(bizDate);
                st.activeDays.add(bizDate);
            }

            boolean refund = "退款".equals(type);
            boolean exchange = "兑换".equals(type);
            boolean zeroRev = exchange || "线下补录".equals(type);

            BigDecimal rawAmt = nz(s.getAmountReceived());
            BigDecimal amt = zeroRev ? BigDecimal.ZERO : rawAmt;
            BigDecimal qty = nz(s.getQty());
            BigDecimal signedQty = refund ? qty.negate() : qty;

            st.salesAmt = st.salesAmt.add(amt);
            st.salesQty = st.salesQty.add(signedQty);
            if (refund) {
                st.refundCount++;
                st.refundAmt = st.refundAmt.add(rawAmt.abs());
            } else if (exchange) {
                st.exchangeCount++;
            } else if ("正常".equals(type)) {
                st.normalSalesAmt = st.normalSalesAmt.add(amt);
                st.orderNos.add(baseOrderNo(s));
            }

            // 成本结转:月/年按 CostEngine 当月结转;总览按累计加权单价
            BigDecimal cost = resolveSaleCost(s, signedQty, replay, useCumulativeCost);
            BigDecimal monthlyCost = replay.getSaleCost().get(s.getId());
            if (cost != null) {
                st.hasCostData = true;
                st.costAmt = st.costAmt.add(cost);
                st.costedSalesAmt = st.costedSalesAmt.add(amt);
            } else {
                st.noCostSkus.add(s.getProductId() == null ? Replay.UNBOUND_KEY : s.getProductId());
            }

            if (s.getMachineId() != null) {
                st.activeMachineIds.add(s.getMachineId());
            }
            if (s.getProductId() != null) {
                st.soldProductIds.add(s.getProductId());
            }

            if (!buildBreakdowns) {
                continue;
            }

            // 走势桶(总览按月展示时,每根柱子用当月结转成本与月度口径一致)
            String tKey = trendKeyForSale(scope, sp, bizDate);
            if (tKey != null) {
                TrendAcc ta = trendMap.get(tKey);
                if (ta != null) {
                    ta.salesAmt = ta.salesAmt.add(amt);
                    ta.salesQty = ta.salesQty.add(signedQty);
                    BigDecimal tCost = SCOPE_OVERALL.equals(scope) ? monthlyCost : cost;
                    if (tCost != null) {
                        ta.hasCost = true;
                        ta.costAmt = ta.costAmt.add(tCost);
                        ta.costedSalesAmt = ta.costedSalesAmt.add(amt);
                    }
                    if ("正常".equals(type)) {
                        ta.orderNos.add(baseOrderNo(s));
                    }
                }
            }

            // 机器维
            if (s.getMachineId() != null) {
                MachineAcc ma = machineMap.computeIfAbsent(s.getMachineId(), k -> new MachineAcc());
                ma.salesAmt = ma.salesAmt.add(amt);
                ma.salesQty = ma.salesQty.add(signedQty);
                if ("正常".equals(type)) {
                    ma.orderNos.add(baseOrderNo(s));
                }
                if (cost != null) {
                    ma.hasCost = true;
                    ma.costAmt = ma.costAmt.add(cost);
                    ma.costedSalesAmt = ma.costedSalesAmt.add(amt);
                } else {
                    ma.noCostSkus.add(s.getProductId() == null ? Replay.UNBOUND_KEY : s.getProductId());
                }
            }

            // 商品维
            long pid = s.getProductId() == null ? Replay.UNBOUND_KEY : s.getProductId();
            ProductAcc pa = productMap.computeIfAbsent(pid, k -> new ProductAcc());
            pa.salesAmt = pa.salesAmt.add(amt);
            pa.salesQty = pa.salesQty.add(signedQty);
            if (cost != null) {
                pa.hasCost = true;
                pa.costAmt = pa.costAmt.add(cost);
                pa.costedSalesAmt = pa.costedSalesAmt.add(amt);
            } else {
                pa.hasMissingCost = true;
            }

            // 品类维
            Product prod = s.getProductId() == null ? null : products.get(s.getProductId());
            String cat = (prod == null || StrUtil.isBlank(prod.getCategory())) ? "未分类" : prod.getCategory().trim();
            CategoryAcc ca = categoryMap.computeIfAbsent(cat, k -> new CategoryAcc());
            ca.salesAmt = ca.salesAmt.add(amt);
            ca.salesQty = ca.salesQty.add(signedQty);
            if (s.getProductId() != null) {
                ca.productIds.add(s.getProductId());
            }
            if (cost != null) {
                ca.hasCost = true;
                ca.costAmt = ca.costAmt.add(cost);
                ca.costedSalesAmt = ca.costedSalesAmt.add(amt);
            }

            // 支付渠道维(统计实收金额,仅正常与退款)
            if (!zeroRev) {
                String pm = StrUtil.isBlank(s.getPayMethod()) ? "其他/未注明" : s.getPayMethod().trim();
                PayAcc pay = payMap.computeIfAbsent(pm, k -> new PayAcc());
                pay.salesAmt = pay.salesAmt.add(amt);
                if ("正常".equals(type)) {
                    pay.orderNos.add(baseOrderNo(s));
                }
            }
        }

        // 采购单据聚合
        for (PurchaseDocEvent d : allPurchases) {
            String dp = docPeriod(d);
            if (!matchesScope(dp, scope, period)) {
                continue;
            }
            if (d.getBizDate() != null) {
                st.recordDate(d.getBizDate());
            }
            BigDecimal dAmt = nz(d.getTotalAmount());
            BigDecimal dQty = nz(d.getTotalQty());
            st.purchaseAmt = st.purchaseAmt.add(dAmt);
            st.purchaseQty = st.purchaseQty.add(dQty);
            st.purchaseDocCount++;

            if (buildBreakdowns) {
                String tKey = trendKeyForDoc(scope, dp, d.getBizDate());
                if (tKey != null) {
                    TrendAcc ta = trendMap.get(tKey);
                    if (ta != null) {
                        ta.purchaseAmt = ta.purchaseAmt.add(dAmt);
                    }
                }
            }
        }

        if (buildBreakdowns) {
            buildTrendRows(st, trendMap);
            buildMachineRows(st, machineMap, machines);
            buildCategoryRows(st, categoryMap);
            buildTopProductRows(st, productMap, products);
            buildPayRows(st, payMap);
        }
        return st;
    }

    private void fillProfitLines(DashboardResp resp, String scope, String period,
                                 String fromPeriod, String toPeriod,
                                 BigDecimal grossProfit, BigDecimal costedSalesAmt) {
        Map<String, BigDecimal> lineNet = new HashMap<>();
        List<MoneyDtos.PlSummaryRow> plRows = cashFlowQueryService.plMonthlySummary(fromPeriod, toPeriod);
        if (plRows != null) {
            for (MoneyDtos.PlSummaryRow row : plRows) {
                lineNet.merge(row.getPlLine(), nz(row.getNet()), BigDecimal::add);
            }
        }
        BigDecimal feeNet = lineNet.getOrDefault(PlLine.PLATFORM_FEE.getLabel(), BigDecimal.ZERO);
        BigDecimal miscNet = lineNet.getOrDefault(PlLine.MISC_EXPENSE.getLabel(), BigDecimal.ZERO);
        BigDecimal costAdjustNet = lineNet.getOrDefault(PlLine.COST_ADJUST.getLabel(), BigDecimal.ZERO);
        BigDecimal claimIncomeNet = lineNet.getOrDefault(PlLine.OTHER_INCOME_CLAIM.getLabel(), BigDecimal.ZERO);
        BigDecimal offlineIncomeNet = lineNet.getOrDefault(PlLine.OTHER_INCOME_OFFLINE.getLabel(), BigDecimal.ZERO);
        BigDecimal subsidyFlowNet = lineNet.getOrDefault(PlLine.OTHER_INCOME_SUBSIDY.getLabel(), BigDecimal.ZERO);
        BigDecimal shrinkFlowNet = lineNet.getOrDefault(PlLine.SHRINKAGE.getLabel(), BigDecimal.ZERO);

        BigDecimal subsidyDeduction = BigDecimal.ZERO;
        List<Map<String, Object>> subsidies = reportQueryMapper.subsidyUsedAllPeriods();
        if (subsidies != null) {
            for (Map<String, Object> row : subsidies) {
                String p = row.get("period") == null ? null : String.valueOf(row.get("period"));
                if (matchesScope(p, scope, period)) {
                    subsidyDeduction = subsidyDeduction.add(toDecimal(row.get("amount")));
                }
            }
        }

        ClaimDtos.NetShrinkageResp shrink = claimService.netShrinkage(fromPeriod, toPeriod);
        BigDecimal lossDocAmt = shrink == null ? BigDecimal.ZERO : nz(shrink.getLossAmount());
        BigDecimal compensatedAmt = shrink == null ? BigDecimal.ZERO : nz(shrink.getCompensatedAmount());

        BigDecimal platformFeeAmt = feeNet.negate();
        BigDecimal expenseAmt = miscNet.negate();
        BigDecimal lossAmt = lossDocAmt.subtract(shrinkFlowNet);
        BigDecimal netLossAmt = lossAmt.subtract(compensatedAmt);
        BigDecimal otherIncomeAmt = claimIncomeNet.add(offlineIncomeNet).add(subsidyFlowNet).add(subsidyDeduction);

        resp.setPlatformFeeAmt(scale2(platformFeeAmt));
        resp.setExpenseAmt(scale2(expenseAmt));
        resp.setLossAmt(scale2(lossAmt));
        resp.setCompensatedAmt(scale2(compensatedAmt));
        resp.setNetLossAmt(scale2(netLossAmt));
        resp.setCostAdjustAmt(scale2(costAdjustNet));
        resp.setOtherIncomeAmt(scale2(otherIncomeAmt));

        if (grossProfit != null) {
            BigDecimal operating = grossProfit
                    .subtract(platformFeeAmt)
                    .subtract(expenseAmt)
                    .subtract(lossAmt)
                    .add(costAdjustNet)
                    .add(otherIncomeAmt);
            resp.setOperatingProfit(scale2(operating));
            resp.setOperatingMarginPct(costedSalesAmt.signum() > 0 ? pct(operating, costedSalesAmt) : null);
        }
    }

    private void fillCompare(DashboardResp resp, String label, ScopeStats curr, ScopeStats prev) {
        resp.setCompareLabel(label);
        resp.setPrevSalesAmt(scale2(prev.salesAmt));
        resp.setSalesGrowthPct(growthPct(curr.salesAmt, prev.salesAmt));

        BigDecimal currGross = curr.hasCostData ? curr.costedSalesAmt.subtract(curr.costAmt) : null;
        BigDecimal prevGross = prev.hasCostData ? prev.costedSalesAmt.subtract(prev.costAmt) : null;
        resp.setPrevGrossProfit(scale2(prevGross));
        resp.setProfitGrowthPct(currGross != null && prevGross != null ? growthPct(currGross, prevGross) : null);

        resp.setPrevPurchaseAmt(scale2(prev.purchaseAmt));
        resp.setPurchaseGrowthPct(growthPct(curr.purchaseAmt, prev.purchaseAmt));

        int prevOrders = prev.orderNos.size();
        resp.setPrevOrderCount(prevOrders);
        resp.setOrderGrowthPct(prevOrders > 0
                ? growthPct(BigDecimal.valueOf(curr.orderNos.size()), BigDecimal.valueOf(prevOrders))
                : null);
    }

    // ============================== 走势与明细装配 ==============================

    private Map<String, TrendAcc> initTrendBuckets(String scope, String period,
                                                   List<SaleEvent> allSales,
                                                   List<PurchaseDocEvent> allPurchases,
                                                   List<String> monthsAsc) {
        Map<String, TrendAcc> map = new LinkedHashMap<>();
        if (SCOPE_MONTH.equals(scope)) {
            YearMonth ym = YearMonth.parse(period, PERIOD_FMT);
            int maxDay = ym.lengthOfMonth();
            YearMonth nowYm = YearMonth.now();
            if (ym.equals(nowYm)) {
                int lastDataDay = LocalDate.now().getDayOfMonth();
                for (SaleEvent s : allSales) {
                    if (period.equals(salePeriod(s)) && s.getBizTime() != null) {
                        lastDataDay = Math.max(lastDataDay, s.getBizTime().getDayOfMonth());
                    }
                }
                for (PurchaseDocEvent d : allPurchases) {
                    if (period.equals(docPeriod(d)) && d.getBizDate() != null) {
                        lastDataDay = Math.max(lastDataDay, d.getBizDate().getDayOfMonth());
                    }
                }
                maxDay = Math.min(ym.lengthOfMonth(), Math.max(1, lastDataDay));
            }
            for (int day = 1; day <= maxDay; day++) {
                LocalDate date = ym.atDay(day);
                String key = date.format(DAY_FMT);
                String label = String.format("%02d-%02d", ym.getMonthValue(), day);
                map.put(key, new TrendAcc(key, label));
            }
        } else if (SCOPE_YEAR.equals(scope)) {
            for (int m = 1; m <= 12; m++) {
                String key = String.format("%s-%02d", period, m);
                String label = String.format("%02d月", m);
                map.put(key, new TrendAcc(key, label));
            }
        } else {
            for (String m : monthsAsc) {
                map.put(m, new TrendAcc(m, m));
            }
        }
        return map;
    }

    private void buildTrendRows(ScopeStats st, Map<String, TrendAcc> trendMap) {
        for (TrendAcc ta : trendMap.values()) {
            DashboardTrendRow row = new DashboardTrendRow();
            row.setKey(ta.key);
            row.setLabel(ta.label);
            row.setSalesAmt(scale2(ta.salesAmt));
            row.setSalesQty(strip(ta.salesQty));
            if (ta.hasCost) {
                BigDecimal gp = ta.costedSalesAmt.subtract(ta.costAmt);
                row.setGrossProfit(scale2(gp));
                row.setMarginPct(ta.costedSalesAmt.signum() > 0 ? pct(gp, ta.costedSalesAmt) : null);
            } else if (ta.salesAmt.signum() == 0 && ta.salesQty.signum() == 0) {
                row.setGrossProfit(scale2(BigDecimal.ZERO));
            }
            row.setPurchaseAmt(scale2(ta.purchaseAmt));
            row.setOrderCount(ta.orderNos.size());
            st.trend.add(row);
        }
    }

    private void buildMachineRows(ScopeStats st, Map<Long, MachineAcc> machineMap, Map<Long, Machine> machines) {
        int days = st.activeDays.size();
        for (Map.Entry<Long, MachineAcc> e : machineMap.entrySet()) {
            Long id = e.getKey();
            MachineAcc ma = e.getValue();
            Machine m = machines.get(id);
            DashboardMachineRow row = new DashboardMachineRow();
            row.setMachineId(id);
            row.setCode(m == null ? "" : m.getMachineCode());
            row.setName(m == null ? "机器#" + id : m.getMachineName());
            row.setSalesAmt(scale2(ma.salesAmt));
            row.setSalesQty(strip(ma.salesQty));
            row.setOrderCount(ma.orderNos.size());
            row.setNoCostSkuCount(ma.noCostSkus.size());
            if (ma.hasCost) {
                BigDecimal gp = ma.costedSalesAmt.subtract(ma.costAmt);
                row.setCostAmt(scale2(ma.costAmt));
                row.setGrossProfit(scale2(gp));
                row.setMarginPct(ma.costedSalesAmt.signum() > 0 ? pct(gp, ma.costedSalesAmt) : null);
            }
            row.setSharePct(st.salesAmt.signum() > 0 ? pct(ma.salesAmt, st.salesAmt) : null);
            row.setDailyAvgAmt(days > 0
                    ? ma.salesAmt.divide(BigDecimal.valueOf(days), 2, RoundingMode.HALF_UP) : null);
            st.machineRows.add(row);
        }
        st.machineRows.sort(Comparator.comparing(DashboardMachineRow::getSalesAmt,
                Comparator.nullsLast(Comparator.reverseOrder())));
    }

    private void buildCategoryRows(ScopeStats st, Map<String, CategoryAcc> categoryMap) {
        for (Map.Entry<String, CategoryAcc> e : categoryMap.entrySet()) {
            CategoryAcc ca = e.getValue();
            DashboardCategoryRow row = new DashboardCategoryRow();
            row.setCategory(e.getKey());
            row.setSalesAmt(scale2(ca.salesAmt));
            row.setSalesQty(strip(ca.salesQty));
            row.setSkuCount(ca.productIds.size());
            if (ca.hasCost) {
                BigDecimal gp = ca.costedSalesAmt.subtract(ca.costAmt);
                row.setGrossProfit(scale2(gp));
                row.setMarginPct(ca.costedSalesAmt.signum() > 0 ? pct(gp, ca.costedSalesAmt) : null);
            }
            row.setSharePct(st.salesAmt.signum() > 0 ? pct(ca.salesAmt, st.salesAmt) : null);
            st.categoryRows.add(row);
        }
        st.categoryRows.sort(Comparator.comparing(DashboardCategoryRow::getSalesAmt,
                Comparator.nullsLast(Comparator.reverseOrder())));
    }

    private void buildTopProductRows(ScopeStats st, Map<Long, ProductAcc> productMap, Map<Long, Product> products) {
        List<DashboardProductRow> rows = new ArrayList<>();
        for (Map.Entry<Long, ProductAcc> e : productMap.entrySet()) {
            long pid = e.getKey();
            ProductAcc pa = e.getValue();
            DashboardProductRow row = new DashboardProductRow();
            if (pid == Replay.UNBOUND_KEY) {
                row.setProductId(null);
                row.setCode("—");
                row.setName("(未绑定商品)");
                row.setCategory("未分类");
                row.setHasCost(false);
            } else {
                Product p = products.get(pid);
                row.setProductId(pid);
                row.setCode(p == null ? "" : p.getSkuCode());
                row.setName(p == null ? "SKU#" + pid : p.getProductName());
                row.setCategory(p == null || StrUtil.isBlank(p.getCategory()) ? "未分类" : p.getCategory());
                row.setHasCost(pa.hasCost && !pa.hasMissingCost);
            }
            row.setSalesQty(strip(pa.salesQty));
            row.setSalesAmt(scale2(pa.salesAmt));
            row.setSharePct(st.salesAmt.signum() > 0 ? pct(pa.salesAmt, st.salesAmt) : null);
            if (row.isHasCost()) {
                BigDecimal gp = pa.costedSalesAmt.subtract(pa.costAmt);
                row.setCostAmt(scale2(pa.costAmt));
                row.setGrossProfit(scale2(gp));
                row.setMarginPct(pa.costedSalesAmt.signum() > 0 ? pct(gp, pa.costedSalesAmt) : null);
            }
            rows.add(row);
        }
        rows.sort(Comparator.comparing(DashboardProductRow::getSalesAmt, Comparator.nullsLast(Comparator.reverseOrder()))
                .thenComparing(DashboardProductRow::getSalesQty, Comparator.nullsLast(Comparator.reverseOrder())));
        if (rows.size() > 10) {
            st.topProductRows.addAll(rows.subList(0, 10));
        } else {
            st.topProductRows.addAll(rows);
        }
    }

    private void buildPayRows(ScopeStats st, Map<String, PayAcc> payMap) {
        BigDecimal totalPayAmt = BigDecimal.ZERO;
        for (PayAcc acc : payMap.values()) {
            totalPayAmt = totalPayAmt.add(acc.salesAmt);
        }
        for (Map.Entry<String, PayAcc> e : payMap.entrySet()) {
            PayAcc acc = e.getValue();
            DashboardPayRow row = new DashboardPayRow();
            row.setMethod(e.getKey());
            row.setOrderCount(acc.orderNos.size());
            row.setSalesAmt(scale2(acc.salesAmt));
            row.setSharePct(totalPayAmt.signum() > 0 ? pct(acc.salesAmt, totalPayAmt) : null);
            st.payRows.add(row);
        }
        st.payRows.sort(Comparator.comparing(DashboardPayRow::getSalesAmt,
                Comparator.nullsLast(Comparator.reverseOrder())));
    }

    // ============================== 工具方法 ==============================

    private BigDecimal resolveSaleCost(SaleEvent s, BigDecimal signedQty, Replay replay, boolean useCumulativeCost) {
        if (s.getProductId() == null) {
            return null;
        }
        if (useCumulativeCost) {
            Pool pool = replay.getPools().get(s.getProductId());
            BigDecimal avg = pool == null ? null : pool.currentAvg();
            return avg == null ? null : signedQty.multiply(avg);
        }
        return replay.getSaleCost().get(s.getId());
    }

    private Map<String, BigDecimal> computeMonthSalesMap(List<SaleEvent> allSales) {
        Map<String, BigDecimal> map = new HashMap<>();
        for (SaleEvent s : allSales) {
            String type = StrUtil.isBlank(s.getOrderType()) ? "正常" : s.getOrderType();
            if ("测试".equals(type) || "兑换".equals(type) || "线下补录".equals(type)) {
                continue;
            }
            String sp = salePeriod(s);
            if (sp != null) {
                map.merge(sp, nz(s.getAmountReceived()), BigDecimal::add);
            }
        }
        return map;
    }

    private String resolvePeriod(String scope, String rawPeriod,
                                 List<String> monthsDesc, List<String> yearsDesc,
                                 Map<String, BigDecimal> monthSalesMap) {
        if (SCOPE_OVERALL.equals(scope)) {
            return ReportService.PERIOD_ALL;
        }
        if (SCOPE_YEAR.equals(scope)) {
            if (StrUtil.isNotBlank(rawPeriod) && YEAR_PATTERN.matcher(rawPeriod.trim()).matches()) {
                return rawPeriod.trim();
            }
            return yearsDesc.isEmpty() ? String.valueOf(LocalDate.now().getYear()) : yearsDesc.get(0);
        }
        // SCOPE_MONTH
        if (StrUtil.isNotBlank(rawPeriod) && isValidMonth(rawPeriod.trim())) {
            return rawPeriod.trim();
        }
        // 自动选最近一个有实质销售(≥100元)的月份,避免月初1单测试或空月占据首屏
        for (String m : monthsDesc) {
            BigDecimal amt = monthSalesMap.get(m);
            if (amt != null && amt.compareTo(DEFAULT_MONTH_MIN_SALES) >= 0) {
                return m;
            }
        }
        return monthsDesc.isEmpty() ? YearMonth.now().format(PERIOD_FMT) : monthsDesc.get(0);
    }

    private static String normalizeScope(String rawScope) {
        if (SCOPE_YEAR.equalsIgnoreCase(rawScope)) {
            return SCOPE_YEAR;
        }
        if (SCOPE_OVERALL.equalsIgnoreCase(rawScope) || "all".equalsIgnoreCase(rawScope)) {
            return SCOPE_OVERALL;
        }
        return SCOPE_MONTH;
    }

    private static String buildPeriodLabel(String scope, String period) {
        if (SCOPE_OVERALL.equals(scope)) {
            return "开账至今累计";
        }
        if (SCOPE_YEAR.equals(scope)) {
            return period + " 年度";
        }
        if (isValidMonth(period)) {
            return period.substring(0, 4) + "年" + Integer.parseInt(period.substring(5, 7)) + "月";
        }
        return period;
    }

    private static String rangeStart(String scope, String period) {
        if (SCOPE_MONTH.equals(scope)) {
            return period;
        }
        if (SCOPE_YEAR.equals(scope)) {
            return period + "-01";
        }
        return null;
    }

    private static String rangeEnd(String scope, String period) {
        if (SCOPE_MONTH.equals(scope)) {
            return period;
        }
        if (SCOPE_YEAR.equals(scope)) {
            return period + "-12";
        }
        return null;
    }

    private static boolean matchesScope(String itemPeriod, String scope, String period) {
        if (SCOPE_OVERALL.equals(scope)) {
            return true;
        }
        if (itemPeriod == null) {
            return false;
        }
        if (SCOPE_YEAR.equals(scope)) {
            return itemPeriod.startsWith(period + "-");
        }
        return period.equals(itemPeriod);
    }

    private static String trendKeyForSale(String scope, String salePeriod, LocalDate bizDate) {
        if (SCOPE_MONTH.equals(scope)) {
            return bizDate == null ? null : bizDate.format(DAY_FMT);
        }
        return salePeriod;
    }

    private static String trendKeyForDoc(String scope, String docPeriod, LocalDate bizDate) {
        if (SCOPE_MONTH.equals(scope)) {
            return bizDate == null ? null : bizDate.format(DAY_FMT);
        }
        return docPeriod;
    }

    private static String salePeriod(SaleEvent s) {
        if (StrUtil.isNotBlank(s.getBizPeriod())) {
            return s.getBizPeriod();
        }
        return s.getBizTime() == null ? null : s.getBizTime().format(PERIOD_FMT);
    }

    private static String docPeriod(PurchaseDocEvent d) {
        if (d.getBizDate() != null) {
            return d.getBizDate().format(PERIOD_FMT);
        }
        return d.getBookPeriod();
    }

    private static String baseOrderNo(SaleEvent s) {
        if (StrUtil.isBlank(s.getOrderNo())) {
            return "ID#" + s.getId();
        }
        return ORDER_SUFFIX.matcher(s.getOrderNo().trim()).replaceFirst("");
    }

    private static boolean isValidMonth(String p) {
        if (p == null || !MONTH_PATTERN.matcher(p).matches()) {
            return false;
        }
        try {
            YearMonth.parse(p, PERIOD_FMT);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private Map<Long, Product> loadProducts() {
        Map<Long, Product> map = new HashMap<>();
        List<Product> list = productMapper.selectList(new LambdaQueryWrapper<>());
        if (list != null) {
            for (Product p : list) {
                map.put(p.getId(), p);
            }
        }
        return map;
    }

    private Map<Long, Machine> loadMachines() {
        Map<Long, Machine> map = new HashMap<>();
        List<Machine> list = machineMapper.selectList(new LambdaQueryWrapper<>());
        if (list != null) {
            for (Machine m : list) {
                map.put(m.getId(), m);
            }
        }
        return map;
    }

    private static BigDecimal pct(BigDecimal part, BigDecimal total) {
        if (part == null || total == null || total.signum() == 0) {
            return null;
        }
        return part.multiply(HUNDRED).divide(total, 1, RoundingMode.HALF_UP);
    }

    private static BigDecimal growthPct(BigDecimal curr, BigDecimal prev) {
        if (curr == null || prev == null || prev.signum() == 0) {
            return null;
        }
        return curr.subtract(prev).multiply(HUNDRED).divide(prev.abs(), 1, RoundingMode.HALF_UP);
    }

    private static BigDecimal scale2(BigDecimal v) {
        return v == null ? null : v.setScale(2, RoundingMode.HALF_UP);
    }

    private static BigDecimal strip(BigDecimal v) {
        if (v == null) {
            return BigDecimal.ZERO;
        }
        BigDecimal s = v.stripTrailingZeros();
        return s.scale() < 0 ? s.setScale(0, RoundingMode.HALF_UP) : s;
    }

    private static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }

    private static BigDecimal toDecimal(Object v) {
        if (v == null) {
            return BigDecimal.ZERO;
        }
        if (v instanceof BigDecimal) {
            return (BigDecimal) v;
        }
        return new BigDecimal(v.toString());
    }

    private static <T> List<T> safeList(List<T> list) {
        return list == null ? Collections.emptyList() : list;
    }

    // ============================== 内部累加器 ==============================

    private static class ScopeStats {
        int totalNonTestRows;
        LocalDate firstDate;
        LocalDate lastDate;
        final Set<LocalDate> activeDays = new HashSet<>();

        BigDecimal salesAmt = BigDecimal.ZERO;
        BigDecimal normalSalesAmt = BigDecimal.ZERO;
        BigDecimal salesQty = BigDecimal.ZERO;
        final Set<String> orderNos = new HashSet<>();
        BigDecimal refundAmt = BigDecimal.ZERO;
        int refundCount;
        int exchangeCount;

        boolean hasCostData;
        BigDecimal costAmt = BigDecimal.ZERO;
        BigDecimal costedSalesAmt = BigDecimal.ZERO;
        final Set<Long> noCostSkus = new HashSet<>();

        BigDecimal purchaseAmt = BigDecimal.ZERO;
        BigDecimal purchaseQty = BigDecimal.ZERO;
        int purchaseDocCount;

        final Set<Long> activeMachineIds = new HashSet<>();
        final Set<Long> soldProductIds = new HashSet<>();

        final List<DashboardTrendRow> trend = new ArrayList<>();
        final List<DashboardMachineRow> machineRows = new ArrayList<>();
        final List<DashboardCategoryRow> categoryRows = new ArrayList<>();
        final List<DashboardProductRow> topProductRows = new ArrayList<>();
        final List<DashboardPayRow> payRows = new ArrayList<>();

        void recordDate(LocalDate d) {
            if (firstDate == null || d.isBefore(firstDate)) {
                firstDate = d;
            }
            if (lastDate == null || d.isAfter(lastDate)) {
                lastDate = d;
            }
        }
    }

    private static class TrendAcc {
        final String key;
        final String label;
        BigDecimal salesAmt = BigDecimal.ZERO;
        BigDecimal salesQty = BigDecimal.ZERO;
        boolean hasCost;
        BigDecimal costAmt = BigDecimal.ZERO;
        BigDecimal costedSalesAmt = BigDecimal.ZERO;
        BigDecimal purchaseAmt = BigDecimal.ZERO;
        final Set<String> orderNos = new HashSet<>();

        TrendAcc(String key, String label) {
            this.key = key;
            this.label = label;
        }
    }

    private static class MachineAcc {
        BigDecimal salesAmt = BigDecimal.ZERO;
        BigDecimal salesQty = BigDecimal.ZERO;
        boolean hasCost;
        BigDecimal costAmt = BigDecimal.ZERO;
        BigDecimal costedSalesAmt = BigDecimal.ZERO;
        final Set<String> orderNos = new HashSet<>();
        final Set<Long> noCostSkus = new HashSet<>();
    }

    private static class CategoryAcc {
        BigDecimal salesAmt = BigDecimal.ZERO;
        BigDecimal salesQty = BigDecimal.ZERO;
        boolean hasCost;
        BigDecimal costAmt = BigDecimal.ZERO;
        BigDecimal costedSalesAmt = BigDecimal.ZERO;
        final Set<Long> productIds = new HashSet<>();
    }

    private static class ProductAcc {
        BigDecimal salesAmt = BigDecimal.ZERO;
        BigDecimal salesQty = BigDecimal.ZERO;
        boolean hasCost;
        boolean hasMissingCost;
        BigDecimal costAmt = BigDecimal.ZERO;
        BigDecimal costedSalesAmt = BigDecimal.ZERO;
    }

    private static class PayAcc {
        BigDecimal salesAmt = BigDecimal.ZERO;
        final Set<String> orderNos = new HashSet<>();
    }
}
