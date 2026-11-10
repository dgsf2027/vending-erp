package top.aole.vend.modules.purchase.interfaces;

import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import top.aole.vend.common.result.R;
import top.aole.vend.modules.basedata.interfaces.Operators;
import top.aole.vend.modules.purchase.dto.PurchaseSimpleDtos.*;
import top.aole.vend.modules.purchase.service.PurchaseSimpleImportService;

@RestController
@RequestMapping("/v1/purchase/import/receipt/workbook")
@RequiredArgsConstructor
public class PurchaseSimpleController {
    private final PurchaseSimpleImportService service;
    @PostMapping("/simple-preview") public R<SimplePreview> preview(@RequestParam("file") MultipartFile file) { return R.ok(service.preview(file)); }
    @PostMapping("/commit") public R<CommitResult> commit(@RequestBody CommitRequest request,
        @RequestHeader(value=Operators.HEADER, required=false) String operator) { return R.ok(service.commit(request, Operators.resolve(operator))); }
}
