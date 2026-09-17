package top.aole.vend.modules.money.interfaces;

import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import top.aole.vend.common.result.R;
import top.aole.vend.modules.basedata.interfaces.Operators;
import top.aole.vend.modules.money.service.AccountBillService;
import java.time.LocalDate;

@RestController
@RequestMapping("/v1/money/account-bills")
@RequiredArgsConstructor
public class AccountBillController {
    private final AccountBillService service;

    @PostMapping("/preview")
    public R<AccountBillService.Preview> preview(@RequestParam("file") MultipartFile file, @RequestParam(required = false) Long accountId) {
        return R.ok(service.preview(file, accountId));
    }
    @PostMapping("/import")
    public R<AccountBillService.ImportResult> importFile(@RequestParam("file") MultipartFile file, @RequestParam(required = false) Long accountId,
            @RequestHeader(value = Operators.HEADER, required = false) String operator) {
        return R.ok(service.importFile(file, accountId, Operators.resolve(operator)));
    }
    @GetMapping
    public R<AccountBillService.ListResult> list(@RequestParam(defaultValue = "1") long current, @RequestParam(defaultValue = "20") long size,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) Long accountId, @RequestParam(required = false) String status) {
        return R.ok(service.list(current, size, from, to, accountId, status));
    }
}
