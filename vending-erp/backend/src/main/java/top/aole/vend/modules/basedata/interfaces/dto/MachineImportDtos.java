package top.aole.vend.modules.basedata.interfaces.dto;

import lombok.Data;
import javax.validation.constraints.NotEmpty;
import javax.validation.constraints.Size;
import java.util.ArrayList;
import java.util.List;

public final class MachineImportDtos {
    private MachineImportDtos() {}

    @Data
    public static class Row {
        private int rowNo;
        private String deviceId;
        private String machineName;
        private String location;
        private String machineCode;
        private String action;
        private String message;
        private String warning;
        private int sourceRowCount = 1;
    }

    @Data
    public static class Preview {
        private String fileName;
        private int headerRowNo;
        private int sourceRows;
        private int createCount;
        private int existingCount;
        private int errorCount;
        private List<Row> rows = new ArrayList<>();
    }

    @Data
    public static class Request {
        @NotEmpty @Size(max = 1000)
        private List<Row> rows;
    }

    @Data
    public static class Result {
        private int created;
        private int skipped;
        private List<Row> rows = new ArrayList<>();
    }
}
