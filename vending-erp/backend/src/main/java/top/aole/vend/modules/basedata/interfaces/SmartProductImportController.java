package top.aole.vend.modules.basedata.interfaces;

import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.http.*;
import top.aole.vend.common.exception.BizException;
import top.aole.vend.common.result.R;
import top.aole.vend.modules.basedata.application.SmartProductImportService;
import top.aole.vend.modules.basedata.interfaces.dto.ProductImportDtos;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.ss.usermodel.*;
import javax.validation.Valid;
import java.io.*;

@RestController
@RequestMapping("/v1/basedata/products/smart-import")
@RequiredArgsConstructor
public class SmartProductImportController {
    private final SmartProductImportService service;

    @PostMapping("/parse")
    public R<ProductImportDtos.ParseResp> parse(@RequestParam("file") MultipartFile file) throws IOException {
        if (file == null || file.isEmpty() || file.getSize() > 20 * 1024 * 1024) throw new BizException("请选择不超过 20MB 的 Excel 商品列表");
        return R.ok(service.parse(file.getOriginalFilename(), file.getBytes()));
    }

    @PostMapping("/review")
    public R<ProductImportDtos.ParseResp> review(@Valid @RequestBody ProductImportDtos.CommitReq request) {
        return R.ok(service.review(request.getRows()));
    }

    @PostMapping("/commit")
    public R<ProductImportDtos.CommitResp> commit(@Valid @RequestBody ProductImportDtos.CommitReq request,
            @RequestHeader(value = Operators.HEADER, required = false) String operator) {
        if (request.isManualEntry()) {
            if (cn.hutool.core.util.StrUtil.isNotBlank(request.getToken())) throw new BizException("手工录入不能同时使用上传凭据");
            return R.ok(service.commitManual(request.getRows(), Operators.resolve(operator)));
        }
        return R.ok(service.commit(request.getToken(), request.getRows(), Operators.resolve(operator)));
    }

    @GetMapping("/template")
    public ResponseEntity<byte[]> template() throws IOException {
        try (Workbook workbook = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = workbook.createSheet("商品列表");
            String[] columns = {"商品编码", "商品名称", "基本单位", "箱规"};
            Row header = sheet.createRow(0);
            CellStyle text = workbook.createCellStyle(); text.setDataFormat(workbook.createDataFormat().getFormat("@"));
            for (int i = 0; i < columns.length; i++) {
                header.createCell(i).setCellValue(columns[i]); sheet.setColumnWidth(i, (i == 1 ? 36 : 20) * 256);
            }
            sheet.setDefaultColumnStyle(0, text);
            sheet.createFreezePane(2, 1);
            Sheet help = workbook.createSheet("填写说明");
            String[] notes = {"已有自己的商品编码就直接填写，系统原样使用；没有编码可以留空，确认入档时自动分配。",
                    "编码请按文本填写，保留前导零。已有档案的编码不会自动改动；编码与名称冲突时请先核对。",
                    "商品名建议包含品牌、口味和容量。不同商品请使用不同的编码。",
                    "新商品须确认基本单位和每箱数量；按单件采购的商品每箱数量填 1。",
                    "条码、分类、价格等可在页面的「更多资料」中填写。已有商品默认只建立对应关系。",
                    "也兼容原后台导出表；明确标为「后台商品编号」「平台商品编号」的列作为来源编号保存。"};
            for (int i = 0; i < notes.length; i++) help.createRow(i).createCell(0).setCellValue(notes[i]);
            help.setColumnWidth(0, 110 * 256); workbook.write(out);
            return ResponseEntity.ok().contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=product-auto-code-template.xlsx").body(out.toByteArray());
        }
    }
}
