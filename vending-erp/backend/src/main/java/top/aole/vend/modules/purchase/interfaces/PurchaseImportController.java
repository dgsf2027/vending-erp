package top.aole.vend.modules.purchase.interfaces;

import lombok.RequiredArgsConstructor;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import top.aole.vend.common.result.R;
import top.aole.vend.modules.purchase.service.PurchaseImportService;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;

@RestController
@RequestMapping("/v1/purchase/import")
@RequiredArgsConstructor
public class PurchaseImportController {
    private final PurchaseImportService service;

    @PostMapping("/{kind}/preview")
    public R<PurchaseImportService.Preview> preview(@PathVariable String kind, @RequestParam("file") MultipartFile file) {
        return R.ok(service.preview(file, kind));
    }

    @GetMapping("/{kind}/template")
    public void template(@PathVariable String kind, HttpServletResponse response) throws IOException {
        PurchaseImportService.checkKind(kind);
        response.setContentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        response.setHeader("Content-Disposition", "attachment; filename=purchase-" + kind + "-template.xlsx");
        try (Workbook book = new XSSFWorkbook()) {
            Sheet sheet = book.createSheet("商品明细");
            String[] headers = {"商品编码", "receipt".equals(kind) ? "实收数量" : "订购数量", "receipt".equals(kind) ? "进货单价" : "预计单价"};
            Row row = sheet.createRow(0);
            for (int i = 0; i < headers.length; i++) {
                row.createCell(i).setCellValue(headers[i]);
                sheet.setColumnWidth(i, 24 * 256);
            }
            CellStyle textStyle = book.createCellStyle();
            textStyle.setDataFormat(book.createDataFormat().getFormat("@"));
            sheet.setDefaultColumnStyle(0, textStyle);
            sheet.createFreezePane(0, 1);
            book.write(response.getOutputStream());
        }
    }
}
