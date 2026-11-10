package top.aole.vend.modules.purchase.dto;

import lombok.Data;
import java.util.*;
import top.aole.vend.modules.purchase.dto.PurchaseWorkbookDtos.*;

public final class PurchaseSimpleDtos {
    private PurchaseSimpleDtos() {}
    @Data public static class StockGroup {
        private String key;
        private String name;
        private Long targetId;
        private List<Candidate> members = new ArrayList<>();
        private List<Integer> sourceRows = new ArrayList<>();
    }
    @Data public static class GroupChoice {
        private String key;
        private Long targetId;
        private List<Long> memberIds = new ArrayList<>();
        private boolean confirmed;
    }
    @Data public static class RowChoice {
        private int rowNo;
        private Long productId;
    }
    @Data public static class SimplePreview {
        private String batchKey;
        private Preview preview;
        private List<StockGroup> groups = new ArrayList<>();
        private List<Long> receiptIds = new ArrayList<>();
    }
    @Data public static class CommitRequest {
        private String batchKey;
        private Long supplierId;
        private String defaultDate;
        private List<GroupChoice> groups = new ArrayList<>();
        private List<RowChoice> rows = new ArrayList<>();
    }
    @Data public static class CommitResult {
        private List<Long> receiptIds = new ArrayList<>();
        private boolean alreadyImported;
    }
}
