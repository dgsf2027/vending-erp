package top.aole.vend.modules.basedata.infrastructure.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import java.util.List;

@Mapper
public interface ProductCodeMapper {
    @Select("SELECT next_value FROM yc_vend_code_sequence WHERE sequence_key='product_sku' FOR UPDATE")
    Long lockSequence();

    // 包含逻辑删除的历史编码，禁止重新使用已分配的号码。
    @Select("SELECT sku_code FROM yc_vend_product WHERE UPPER(sku_code) LIKE 'SP%'")
    List<String> existingCodes();

    @Update("UPDATE yc_vend_code_sequence SET next_value=#{value} WHERE sequence_key='product_sku'")
    int advance(@Param("value") long value);
}
