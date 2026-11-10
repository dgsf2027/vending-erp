package top.aole.vend.modules.purchase.dto;

import lombok.Data;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

public final class PurchaseWorkbookDtos {
    private PurchaseWorkbookDtos() {}
    @Data public static class Allocation {
        private Long productId;
        /** 基本单位数，拆分口味时相加必须等于原行采购数量。 */
        private BigDecimal qty;
    }
    @Data public static class Candidate {
        private Long productId;
        private String skuCode;
        private String productName;
        private String unit;
        private BigDecimal boxSpec;
        private BigDecimal salesQty = BigDecimal.ZERO;
        private String firstSale;
        private String lastSale;
        private List<String> salesNames = new ArrayList<>();
        private String reason;
    }
    @Data public static class SourceRow {
        private int rowNo;
        private String bizDate;
        private String sourceName;
        private String skuCode;
        private String hint;
        private BigDecimal boxSpec;
        private BigDecimal boxCount;
        private BigDecimal qty;
        private BigDecimal boxPrice;
        private BigDecimal totalPrice;
        private BigDecimal unitPrice;
        private boolean confirmed;
        private List<Allocation> allocations = new ArrayList<>();
        private List<Candidate> candidates = new ArrayList<>();
        private Long suggestedProductId;
        private String matchMessage;
        private boolean needsConfirmation;
        private List<String> errors = new ArrayList<>();
    }
    @Data public static class GroupLine {
        private Long productId;
        private String skuCode;
        private String productName;
        private String unit;
        private BigDecimal qty = BigDecimal.ZERO;
        private BigDecimal totalPrice = BigDecimal.ZERO;
        private BigDecimal unitPrice;
        private List<Integer> sourceRows = new ArrayList<>();
        private List<String> sourceNames = new ArrayList<>();
    }
    @Data public static class Day {
        private String bizDate;
        private int sourceCount;
        private BigDecimal totalQty = BigDecimal.ZERO;
        private BigDecimal totalAmount = BigDecimal.ZERO;
        private List<GroupLine> lines = new ArrayList<>();
        private List<String> errors = new ArrayList<>();
    }
    @Data public static class Preview {
        private String fileName;
        private int skippedSubtotals;
        private List<SourceRow> rows = new ArrayList<>();
        private List<Day> days = new ArrayList<>();
        private List<String> errors = new ArrayList<>();
        private List<String> warnings = new ArrayList<>();
    }
    @Data public static class ReviewRequest {
        private List<SourceRow> rows;
        private String defaultDate;
    }
}
