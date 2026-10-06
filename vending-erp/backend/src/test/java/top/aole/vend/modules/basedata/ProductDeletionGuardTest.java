package top.aole.vend.modules.basedata;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.JdbcTemplate;
import top.aole.vend.modules.basedata.application.*;
import top.aole.vend.modules.basedata.domain.entity.Product;
import top.aole.vend.common.exception.BizException;
import java.util.Collections;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
class ProductDeletionGuardTest {
 @ParameterizedTest
 @ValueSource(strings={"sale_record","stock_ledger","doc_item","purchase_order_item","machine_stock_snapshot","stocktake_item","prekit_ticket_item","replenish_plan","slot","replenish_config"})
 void each_reference_blocks_deletion_before_any_mutation(String table) {
  JdbcTemplate jdbc=mock(JdbcTemplate.class);
  ProductService products=mock(ProductService.class);
  OpLogService logs=mock(OpLogService.class);
  when(jdbc.queryForList(anyString(),eq(Long.class),eq(42L))).thenReturn(Collections.singletonList(42L));
  when(jdbc.queryForObject(anyString(),eq(Integer.class),eq(42L))).thenReturn(0);
  when(jdbc.queryForObject(eq("SELECT COUNT(*) FROM yc_vend_"+table+" WHERE product_id=?"),eq(Integer.class),eq(42L))).thenReturn(1);
  Product product=new Product(); product.setProductName("受保护商品");
  when(products.getById(42L)).thenReturn(product);
  assertThrows(BizException.class,()->new ProductDeletionService(products,jdbc,logs).delete(42L,"测试"));
  verify(jdbc,never()).update(anyString(),any(Object[].class));
  verifyNoInteractions(logs);
 }
}
