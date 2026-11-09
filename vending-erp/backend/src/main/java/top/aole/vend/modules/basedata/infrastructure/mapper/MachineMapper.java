package top.aole.vend.modules.basedata.infrastructure.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;
import java.util.List;
import top.aole.vend.modules.basedata.domain.entity.Machine;

/** Machine 表 Mapper(MyBatis-Plus 通用 CRUD)。 */
@Mapper
public interface MachineMapper extends BaseMapper<Machine> {
    // 当前单租户的历史标识也参与查重，逻辑删除不释放数据库唯一键。
    @Select("SELECT id,machine_code,machine_name,device_id,location,is_deleted FROM yc_vend_machine WHERE tenant_id='000000'")
    List<Machine> importIdentities();
}
