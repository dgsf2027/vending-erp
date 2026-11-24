package top.aole.vend.modules.report;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import top.aole.vend.modules.basedata.domain.entity.Machine;
import top.aole.vend.modules.basedata.domain.entity.Product;
import top.aole.vend.modules.basedata.infrastructure.mapper.MachineMapper;
import top.aole.vend.modules.basedata.infrastructure.mapper.ProductMapper;
import top.aole.vend.modules.claim.dto.ClaimDtos;
import top.aole.vend.modules.claim.service.ClaimService;
import top.aole.vend.modules.money.domain.enums.PlLine;
import top.aole.vend.modules.money.dto.MoneyDtos;
import top.aole.vend.modules.money.service.CashFlowQueryService;
import top.aole.vend.modules.report.dto.DashboardDtos.DashboardResp;
import top.aole.vend.modules.report.dto.DashboardDtos.PurchaseDocEvent;
import top.aole.vend.modules.report.dto.ReportDtos.LedgerEvent;
import top.aole.vend.modules.report.dto.ReportDtos.SaleEvent;
import top.aole.vend.modules.report.mapper.ReportQueryMapper;
import top.aole.vend.modules.report.service.CostEngine;
import top.aole.vend.modules.report.service.DashboardOverviewService;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * 经营驾驶舱三维度聚合单元测试(纯单测,无外部 DB 依赖):
 * 覆盖月度总览(默认)、年度总览、经营总览、环/同比、费用损耗经营利润、多维明细及空账兜底。
 */
@ExtendWith(MockitoExtension.class)
class DashboardOverviewServiceTest {

    @Mock
    private ReportQueryMapper reportQueryMapper;
    @Mock
    private ProductMapper productMapper;
    @Mock
    private MachineMapper machineMapper;
    @Mock
    private CashFlowQueryService cashFlowQueryService;
    @Mock
    private ClaimService claimService;

    private DashboardOverviewService service;

    @BeforeEach
    void setUp() {
        CostEngine costEngine = new CostEngine(reportQueryMapper);
        service = new DashboardOverviewService(
                costEngine, reportQueryMapper, productMapper, machineMapper,
                cashFlowQueryService, claimService);
    }

    @Test
    void testThreeScopesMonthYearOverall() {
        Product p1 = product(1L, "SP001", "可乐", "饮料");
        Product p2 = product(2L, "SP002", "泡面", "速食");
        Machine m1 = machine(10L, "M01", "1号机");
        Machine m2 = machine(20L, "M02", "2号机");

        when(productMapper.selectList(any())).thenReturn(Arrays.asList(p1, p2));
        when(machineMapper.selectList(any())).thenReturn(Arrays.asList(m1, m2));
        when(reportQueryMapper.productRefCosts()).thenReturn(Collections.emptyList());
        when(reportQueryMapper.dataAsOf()).thenReturn(LocalDateTime.of(2026, 2, 15, 18, 0));

        // 入库流水:2025-12 可乐 100 瓶 @2元; 2026-01 可乐 100 瓶 @2元, 泡面 50 桶 @3元; 2026-02 可乐 100 瓶 @4元
        List<LedgerEvent> ledgers = Arrays.asList(
                inLedger(1L, 1L, "采购入库", "100", "200", LocalDateTime.of(2025, 12, 1, 9, 0)),
                inLedger(2L, 1L, "采购入库", "100", "200", LocalDateTime.of(2026, 1, 2, 9, 0)),
                inLedger(3L, 2L, "采购入库", "50", "150", LocalDateTime.of(2026, 1, 3, 9, 0)),
                inLedger(4L, 1L, "采购入库", "100", "400", LocalDateTime.of(2026, 2, 1, 9, 0))
        );
        when(reportQueryMapper.ledgerEvents()).thenReturn(ledgers);

        // 销售记录:
        // 2025-12: 50 瓶可乐 实收 200 (1单)
        // 2026-01: 40 瓶可乐 实收 160 (订单 A1), 10 桶泡面 实收 80 (同订单 A1#2 -> 合并算 1 单!)
        // 2026-02: 50 瓶可乐 实收 250 (订单 B1, 微信), 10 瓶可乐退款 -50 (订单 B2, 微信),
        //          2 瓶可乐兑换 0 (订单 B3), 1 瓶测试单 5 (应跳过), 20 桶泡面 实收 160 (订单 B4, 支付宝)
        List<SaleEvent> sales = Arrays.asList(
                sale(101L, "Z1", 1L, 10L, "50", "200", "微信", "正常", LocalDateTime.of(2025, 12, 10, 10, 0)),
                sale(102L, "A1", 1L, 10L, "40", "160", "微信", "正常", LocalDateTime.of(2026, 1, 5, 10, 0)),
                sale(103L, "A1#2", 2L, 10L, "10", "80", "微信", "正常", LocalDateTime.of(2026, 1, 5, 10, 0)),
                sale(104L, "B1", 1L, 10L, "50", "250", "微信", "正常", LocalDateTime.of(2026, 2, 10, 12, 0)),
                sale(105L, "B2", 1L, 10L, "10", "-50", "微信", "退款", LocalDateTime.of(2026, 2, 10, 12, 30)),
                sale(106L, "B3", 1L, 10L, "2", "0", "微信", "兑换", LocalDateTime.of(2026, 2, 11, 14, 0)),
                sale(107L, "T1", 1L, 10L, "1", "5", "微信", "测试", LocalDateTime.of(2026, 2, 11, 15, 0)),
                sale(108L, "B4", 2L, 20L, "20", "160", "支付宝", "正常", LocalDateTime.of(2026, 2, 12, 16, 0))
        );
        when(reportQueryMapper.saleEvents()).thenReturn(sales);

        List<PurchaseDocEvent> purchases = Arrays.asList(
                purchase(LocalDate.of(2025, 12, 1), "100", "200"),
                purchase(LocalDate.of(2026, 1, 2), "100", "200"),
                purchase(LocalDate.of(2026, 1, 3), "50", "150"),
                purchase(LocalDate.of(2026, 2, 1), "100", "400")
        );
        when(reportQueryMapper.dashboardPurchaseDocs()).thenReturn(purchases);

        // 费用/损耗/补贴 mock
        MoneyDtos.PlSummaryRow feeFeb = plRow("2026-02", PlLine.PLATFORM_FEE.getLabel(), "-10.00");
        MoneyDtos.PlSummaryRow miscFeb = plRow("2026-02", PlLine.MISC_EXPENSE.getLabel(), "-20.00");
        MoneyDtos.PlSummaryRow claimFeb = plRow("2026-02", PlLine.OTHER_INCOME_CLAIM.getLabel(), "5.00");
        when(cashFlowQueryService.plMonthlySummary("2026-02", "2026-02"))
                .thenReturn(Arrays.asList(feeFeb, miscFeb, claimFeb));
        when(cashFlowQueryService.plMonthlySummary("2026-01", "2026-12"))
                .thenReturn(Arrays.asList(feeFeb, miscFeb, claimFeb));
        when(cashFlowQueryService.plMonthlySummary(null, null))
                .thenReturn(Arrays.asList(feeFeb, miscFeb, claimFeb));

        ClaimDtos.NetShrinkageResp shrink = new ClaimDtos.NetShrinkageResp();
        shrink.setLossAmount(new BigDecimal("15.00"));
        shrink.setCompensatedAmount(new BigDecimal("5.00"));
        shrink.setNetAmount(new BigDecimal("10.00"));
        when(claimService.netShrinkage(any(), any())).thenReturn(shrink);

        Map<String, Object> subRow = new HashMap<>();
        subRow.put("period", "2026-02");
        subRow.put("amount", new BigDecimal("6.00"));
        when(reportQueryMapper.subsidyUsedAllPeriods()).thenReturn(Collections.singletonList(subRow));

        // 1) 默认月度总览(传 null 自动落到 2026-02)
        DashboardResp monthResp = service.overview(null, null);
        assertEquals("month", monthResp.getScope());
        assertEquals("2026-02", monthResp.getPeriod());
        assertEquals("2026年2月", monthResp.getPeriodLabel());
        // 销售额 = 250 - 50 + 0 + 160 = 360 (测试单跳过)
        assertEquals(0, new BigDecimal("360.00").compareTo(monthResp.getSalesAmt()));
        // 销量 = 50 - 10 + 2 + 20 = 62
        assertEquals(0, new BigDecimal("62").compareTo(monthResp.getSalesQty()));
        // 正常订单数 = B1 + B4 = 2 单; 退款 1 单 ¥50; 兑换 1 单
        assertEquals(2, monthResp.getOrderCount());
        assertEquals(1, monthResp.getRefundCount());
        assertEquals(0, new BigDecimal("50.00").compareTo(monthResp.getRefundAmt()));
        assertEquals(1, monthResp.getExchangeCount());
        // 采购 = 400 (1单, 100件)
        assertEquals(0, new BigDecimal("400.00").compareTo(monthResp.getPurchaseAmt()));
        assertEquals(1, monthResp.getPurchaseDocCount());
        // 较上月(2026-01 销售额 240): (360-240)/240 = +50.0%
        assertEquals("较上月", monthResp.getCompareLabel());
        assertEquals(0, new BigDecimal("240.00").compareTo(monthResp.getPrevSalesAmt()));
        assertEquals(0, new BigDecimal("50.0").compareTo(monthResp.getSalesGrowthPct()));
        assertEquals(1, monthResp.getPrevOrderCount().intValue()); // A1 + A1#2 合并为 1 单
        // 费用与经营利润: 毛利 - 10(手续费) - 20(杂费) - 15(损耗) + (5赔付 + 6补贴) = 毛利 - 34
        assertNotNull(monthResp.getGrossProfit());
        assertEquals(0, monthResp.getGrossProfit().subtract(new BigDecimal("34.00"))
                .compareTo(monthResp.getOperatingProfit()));
        assertEquals(0, new BigDecimal("10.00").compareTo(monthResp.getNetLossAmt()));
        // 2026-02 28天日走势, 2台活跃机器, 2个品类, 2个支付渠道
        assertEquals(28, monthResp.getTrend().size());
        assertEquals(2, monthResp.getMachines().size());
        assertEquals(2, monthResp.getCategories().size());
        assertEquals(2, monthResp.getPayMethods().size());

        // 2) 年度总览(2026)
        DashboardResp yearResp = service.overview("year", "2026");
        assertEquals("year", yearResp.getScope());
        assertEquals("2026", yearResp.getPeriod());
        assertEquals("2026 年度", yearResp.getPeriodLabel());
        // 2026 销售 = 240 + 360 = 600; 2025 销售 = 200 -> 同比 +200.0%
        assertEquals(0, new BigDecimal("600.00").compareTo(yearResp.getSalesAmt()));
        assertEquals("较上年", yearResp.getCompareLabel());
        assertEquals(0, new BigDecimal("200.00").compareTo(yearResp.getPrevSalesAmt()));
        assertEquals(0, new BigDecimal("200.0").compareTo(yearResp.getSalesGrowthPct()));
        // 2026 采购 = 200 + 150 + 400 = 750
        assertEquals(0, new BigDecimal("750.00").compareTo(yearResp.getPurchaseAmt()));
        assertEquals(12, yearResp.getTrend().size());
        assertEquals("01月", yearResp.getTrend().get(0).getLabel());

        // 3) 经营总览(开账至今累计)
        DashboardResp overallResp = service.overview("overall", null);
        assertEquals("overall", overallResp.getScope());
        assertEquals("累计", overallResp.getPeriod());
        assertEquals("开账至今累计", overallResp.getPeriodLabel());
        assertNull(overallResp.getCompareLabel());
        // 全期销售 = 200 + 240 + 360 = 800; 全期采购 = 200 + 200 + 150 + 400 = 950
        assertEquals(0, new BigDecimal("800.00").compareTo(overallResp.getSalesAmt()));
        assertEquals(0, new BigDecimal("950.00").compareTo(overallResp.getPurchaseAmt()));
        // 可乐累计加权单价 = (200+200+400)/(100+100+100) = 800/300 = 2.666667; 累计销量 = 50+40+50-10+2 = 132
        // 泡面累计加权单价 = 150/50 = 3; 累计销量 = 10+20 = 30 -> 成本 = 90
        // 总成本 = 132 * 2.666667 + 90 = 352.00 + 90.00 = 442.00; 总毛利 = 800 - 442 = 358.00
        assertEquals(0, new BigDecimal("442.00").compareTo(overallResp.getCostAmt()));
        assertEquals(0, new BigDecimal("358.00").compareTo(overallResp.getGrossProfit()));
        assertEquals(3, overallResp.getTrend().size()); // 2025-12, 2026-01, 2026-02
    }

    @Test
    void testEmptyDatabaseReturnsSafeZeroes() {
        when(productMapper.selectList(any())).thenReturn(Collections.emptyList());
        when(machineMapper.selectList(any())).thenReturn(Collections.emptyList());
        when(reportQueryMapper.ledgerEvents()).thenReturn(Collections.emptyList());
        when(reportQueryMapper.saleEvents()).thenReturn(Collections.emptyList());
        when(reportQueryMapper.productRefCosts()).thenReturn(Collections.emptyList());
        when(reportQueryMapper.dashboardPurchaseDocs()).thenReturn(Collections.emptyList());
        when(reportQueryMapper.subsidyUsedAllPeriods()).thenReturn(Collections.emptyList());
        when(cashFlowQueryService.plMonthlySummary(any(), any())).thenReturn(Collections.emptyList());
        when(claimService.netShrinkage(any(), any())).thenReturn(new ClaimDtos.NetShrinkageResp());

        DashboardResp resp = service.overview("month", null);
        assertEquals("month", resp.getScope());
        assertEquals(0, BigDecimal.ZERO.compareTo(resp.getSalesAmt()));
        assertEquals(0, BigDecimal.ZERO.compareTo(resp.getPurchaseAmt()));
        assertNull(resp.getMarginPct());
        assertNull(resp.getDailyAvgSales());
        assertNull(resp.getAvgOrderAmt());
    }

    private static Product product(Long id, String code, String name, String category) {
        Product p = new Product();
        p.setId(id);
        p.setSkuCode(code);
        p.setProductName(name);
        p.setCategory(category);
        return p;
    }

    private static Machine machine(Long id, String code, String name) {
        Machine m = new Machine();
        m.setId(id);
        m.setMachineCode(code);
        m.setMachineName(name);
        return m;
    }

    private static LedgerEvent inLedger(Long id, Long pid, String docType, String qty, String amt, LocalDateTime t) {
        LedgerEvent e = new LedgerEvent();
        e.setId(id);
        e.setProductId(pid);
        e.setDocType(docType);
        e.setChangeQty(new BigDecimal(qty));
        e.setAmount(new BigDecimal(amt));
        e.setBizTime(t);
        return e;
    }

    private static SaleEvent sale(Long id, String orderNo, Long pid, Long mid, String qty, String amt,
                                  String payMethod, String orderType, LocalDateTime t) {
        SaleEvent s = new SaleEvent();
        s.setId(id);
        s.setOrderNo(orderNo);
        s.setProductId(pid);
        s.setMachineId(mid);
        s.setQty(new BigDecimal(qty));
        s.setAmountReceived(new BigDecimal(amt));
        s.setPayMethod(payMethod);
        s.setOrderType(orderType);
        s.setBizTime(t);
        s.setBizPeriod(String.format("%04d-%02d", t.getYear(), t.getMonthValue()));
        return s;
    }

    private static PurchaseDocEvent purchase(LocalDate date, String qty, String amt) {
        PurchaseDocEvent p = new PurchaseDocEvent();
        p.setBizDate(date);
        p.setBookPeriod(String.format("%04d-%02d", date.getYear(), date.getMonthValue()));
        p.setDocType("采购入库");
        p.setTotalQty(new BigDecimal(qty));
        p.setTotalAmount(new BigDecimal(amt));
        return p;
    }

    private static MoneyDtos.PlSummaryRow plRow(String period, String line, String net) {
        MoneyDtos.PlSummaryRow r = new MoneyDtos.PlSummaryRow();
        r.setPeriod(period);
        r.setPlLine(line);
        r.setNet(new BigDecimal(net));
        return r;
    }
}
