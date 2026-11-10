package top.aole.vend.modules.purchase.interfaces;

import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import top.aole.vend.common.result.R;
import top.aole.vend.modules.purchase.dto.PurchaseWorkbookDtos.*;
import top.aole.vend.modules.purchase.service.PurchaseWorkbookService;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.ss.usermodel.*;
import org.springframework.http.*;
import java.io.*;

@RestController
@RequestMapping("/v1/purchase/import/receipt/workbook")
@RequiredArgsConstructor
public class PurchaseWorkbookController {
    private final PurchaseWorkbookService service;
    @PostMapping("/preview") public R<Preview> preview(@RequestParam("file") MultipartFile file) { return R.ok(service.parse(file)); }
    @PostMapping("/review") public R<Preview> review(@RequestBody ReviewRequest request) { return R.ok(service.review(request.getRows(), request.getDefaultDate())); }
    @GetMapping("/template") public ResponseEntity<byte[]> template() throws IOException {
        try (Workbook book = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = book.createSheet("拿货明细");
            String[] headers = {"采购日期", "产品名称", "商品编码", "整件规格", "总件数", "采购数量", "整件价格", "总价格", "匹配商品 / 待核对"};
            Row header = sheet.createRow(0);
            for (int c = 0; c < headers.length; c++) { header.createCell(c).setCellValue(headers[c]); sheet.setColumnWidth(c, (c == 1 || c == 8 ? 32 : 18) * 256); }
            CellStyle text = book.createCellStyle(); text.setDataFormat(book.createDataFormat().getFormat("@"));
            sheet.setDefaultColumnStyle(2, text); sheet.createFreezePane(3, 1); book.write(out);
            return ResponseEntity.ok().contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=purchase-workbook-template.xlsx").body(out.toByteArray());
        }
    }
}
