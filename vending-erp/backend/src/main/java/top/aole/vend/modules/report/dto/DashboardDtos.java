package top.aole.vend.modules.report.dto;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 经营驾驶舱三维度聚合 DTO:月度总览(month) / 年度总览(year) / 经营总览(overall)。
 */
public final class DashboardDtos {

    private DashboardDtos() {
    }

    /** 采购/期初单据只读投影 */
    @Data
    public static class PurchaseDocEvent {
        private LocalDate bizDate;
        private String bookPeriod;
        private String docType;
        private BigDecimal totalQty;
        private BigDecimal totalAmount;
    }

    /** 走势节点(月度=按日,年度=按月01~12,经营总览=开账至今按月) */
    @Data
    public static class DashboardTrendRow {
        private String key;
        private String label;
        private BigDecimal salesAmt;
        private BigDecimal salesQty;
        private BigDecimal grossProfit;
        private BigDecimal marginPct;
        private BigDecimal purchaseAmt;
        private int orderCount;
    }

    /** 机器排行行 */
    @Data
    public static class DashboardMachineRow {
        private Long machineId;
        private String code;
        private String name;
        private BigDecimal salesAmt;
        private BigDecimal salesQty;
        private int orderCount;
        private BigDecimal costAmt;
        private BigDecimal grossProfit;
        private BigDecimal marginPct;
        private BigDecimal sharePct;
        private BigDecimal dailyAvgAmt;
        private int noCostSkuCount;
    }

    /** 品类结构行 */
    @Data
    public static class DashboardCategoryRow {
        private String category;
        private BigDecimal salesAmt;
        private BigDecimal salesQty;
        private BigDecimal grossProfit;
        private BigDecimal marginPct;
        private BigDecimal sharePct;
        private int skuCount;
    }

    /** 热销商品行(TOP 10) */
    @Data
    public static class DashboardProductRow {
        private Long productId;
        private String code;
        private String name;
        private String category;
        private BigDecimal salesQty;
        private BigDecimal salesAmt;
        private BigDecimal costAmt;
        private BigDecimal grossProfit;
        private BigDecimal marginPct;
        private BigDecimal sharePct;
        private boolean hasCost = true;
    }

    /** 支付方式占比行 */
    @Data
    public static class DashboardPayRow {
        private String method;
        private int orderCount;
        private BigDecimal salesAmt;
        private BigDecimal sharePct;
    }

    /** 三维度经营驾驶舱响应 */
    @Data
    public static class DashboardResp {
        /** month / year / overall */
        private String scope;
        /** YYYY-MM / YYYY / 累计 */
        private String period;
        /** 中文标题,如 2026年11月 / 2026年度 / 开账至今累计 */
        private String periodLabel;
        /** 可选月份(倒序) */
        private List<String> months = new ArrayList<>();
        /** 可选年份(倒序) */
        private List<String> years = new ArrayList<>();
        private LocalDateTime dataAsOf;
        private String firstBizDate;
        private String lastBizDate;
        /** 统计区间内有销售发生的天数 */
        private int activeDays;

        // ---- 销售与订单 ----
        private BigDecimal salesAmt;
        private BigDecimal salesQty;
        private int orderCount;
        private BigDecimal avgOrderAmt;
        private BigDecimal dailyAvgSales;
        private BigDecimal refundAmt;
        private int refundCount;
        private int exchangeCount;

        // ---- 成本与毛利 ----
        private BigDecimal costAmt;
        private BigDecimal grossProfit;
        private BigDecimal marginPct;
        private BigDecimal costedSalesAmt;
        private int noCostSkuCount;

        // ---- 采购投入 ----
        private BigDecimal purchaseAmt;
        private BigDecimal purchaseQty;
        private int purchaseDocCount;

        // ---- 费用、损耗与经营利润 ----
        private BigDecimal platformFeeAmt;
        private BigDecimal expenseAmt;
        private BigDecimal lossAmt;
        private BigDecimal compensatedAmt;
        private BigDecimal netLossAmt;
        private BigDecimal costAdjustAmt;
        private BigDecimal otherIncomeAmt;
        private BigDecimal operatingProfit;
        private BigDecimal operatingMarginPct;

        // ---- 覆盖规模 ----
        private int activeMachineCount;
        private int totalMachineCount;
        private int soldSkuCount;

        // ---- 环/同比(月度=较上月,年度=较上年,总览=null) ----
        private String compareLabel;
        private BigDecimal prevSalesAmt;
        private BigDecimal salesGrowthPct;
        private BigDecimal prevGrossProfit;
        private BigDecimal profitGrowthPct;
        private BigDecimal prevPurchaseAmt;
        private BigDecimal purchaseGrowthPct;
        private Integer prevOrderCount;
        private BigDecimal orderGrowthPct;

        // ---- 多维明细 ----
        private List<DashboardTrendRow> trend = new ArrayList<>();
        private List<DashboardMachineRow> machines = new ArrayList<>();
        private List<DashboardCategoryRow> categories = new ArrayList<>();
        private List<DashboardProductRow> topProducts = new ArrayList<>();
        private List<DashboardPayRow> payMethods = new ArrayList<>();
    }
}
