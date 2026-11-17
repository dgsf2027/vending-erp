package top.aole.vend.modules.basedata.interfaces;

import io.swagger.annotations.Api;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import top.aole.vend.common.result.R;
import top.aole.vend.modules.basedata.application.ProductLinkService;
import top.aole.vend.modules.basedata.domain.entity.Product;
import javax.validation.Valid;
import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;
import java.util.List;

@Api(tags="商品 · 关联已有商品")
@RestController @RequiredArgsConstructor
@RequestMapping("/v1/basedata/products/{id}/link")
public class ProductLinkController {
    private final ProductLinkService service;
    @Data public static class Request {
        @NotNull private Long targetId;
        @NotBlank private String token;
    }
    @GetMapping("/candidates") public R<List<Product>> candidates(@PathVariable Long id){return R.ok(service.candidates(id));}
    @GetMapping("/preview") public R<ProductLinkService.Preview> preview(@PathVariable Long id,@RequestParam Long targetId){return R.ok(service.preview(id,targetId));}
    @PostMapping public R<Void> link(@PathVariable Long id,@Valid @RequestBody Request req,
            @RequestHeader(value=Operators.HEADER,required=false) String userName){
        service.link(id,req.getTargetId(),req.getToken(),Operators.resolve(userName));return R.ok();
    }
}
