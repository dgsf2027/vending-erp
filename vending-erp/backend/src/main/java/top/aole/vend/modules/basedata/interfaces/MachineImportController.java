package top.aole.vend.modules.basedata.interfaces;

import lombok.RequiredArgsConstructor;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import top.aole.vend.common.exception.BizException;
import top.aole.vend.common.result.R;
import top.aole.vend.modules.basedata.application.MachineImportService;
import top.aole.vend.modules.basedata.interfaces.dto.MachineImportDtos.*;
import javax.validation.Valid;
import java.io.*;

@RestController
@RequestMapping("/v1/basedata/machines/import")
@RequiredArgsConstructor
public class MachineImportController {
    private final MachineImportService service;

    @PostMapping("/parse")
    public R<Preview> parse(@RequestParam("file") MultipartFile file) throws IOException {
        if (file.isEmpty() || file.getSize() > 20 * 1024 * 1024) throw new BizException("请选择不超过 20MB 的 Excel 机器列表");
        return R.ok(service.parse(file.getOriginalFilename(), file.getBytes()));
    }
    @PostMapping("/review")
    public R<Preview> review(@Valid @RequestBody Request request) { return R.ok(service.review(request.getRows())); }

    @PostMapping("/commit")
    public R<Result> commit(@Valid @RequestBody Request request,
            @RequestHeader(value = Operators.HEADER, required = false) String operator) {
        return R.ok(service.commit(request.getRows(), Operators.resolve(operator)));
    }
    @GetMapping("/template")
    public ResponseEntity<byte[]> template() throws IOException {
        try (Workbook workbook = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = workbook.createSheet("机子编号清单");
            org.apache.poi.ss.usermodel.Row header = sheet.createRow(0);
            header.createCell(0).setCellValue("机子编号（柜子编码）");
            header.createCell(1).setCellValue("点位名称（表内最近记录）");
            CellStyle text = workbook.createCellStyle(); text.setDataFormat(workbook.createDataFormat().getFormat("@"));
            sheet.setDefaultColumnStyle(0, text);
            sheet.setColumnWidth(0, 34 * 256); sheet.setColumnWidth(1, 42 * 256); sheet.createFreezePane(0, 1);
            Sheet help = workbook.createSheet("填写说明");
            help.createRow(0).createCell(0).setCellValue("每台机器一行，机子编号填写厂家后台的柜子编码，按文本保留原值。");
            help.createRow(1).createCell(0).setCellValue("点位名称会填入机器名称和位置；系统内部编号在确认导入时自动生成。");
            help.createRow(2).createCell(0).setCellValue("支持原机子编号清单（自动跳过标题说明）或销售表（按柜子编码去重）。已有机器保留原档案并跳过。");
            help.setColumnWidth(0, 110 * 256); workbook.write(out);
            return ResponseEntity.ok().contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=machine-list-template.xlsx").body(out.toByteArray());
        }
    }
}
