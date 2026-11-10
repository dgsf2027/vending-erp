package top.aole.vend.modules.purchase.mapper;

import lombok.Data;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;
import java.math.BigDecimal;
import java.util.List;

@Mapper
public interface PurchaseSalesMatchMapper {
    @Data class SaleSummary {
        private Long productId;
        private String sourceName;
        private BigDecimal qty;
        private String firstSale;
        private String lastSale;
    }
    @Select("SELECT product_id,alias_name_raw AS source_name,SUM(qty) AS qty,MIN(biz_time) AS first_sale,MAX(biz_time) AS last_sale "
        + "FROM yc_vend_sale_record WHERE is_deleted=0 AND tenant_id='000000' AND order_type IN ('正常','兑换') "
        + "AND product_id IS NOT NULL GROUP BY product_id,alias_name_raw")
    List<SaleSummary> summaries();
}
