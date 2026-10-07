package top.aole.vend.modules.stock.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import top.aole.vend.modules.stock.domain.entity.MachineStockSnapshot;
import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface MachineStockSnapshotMapper extends BaseMapper<MachineStockSnapshot> {

    /** 最近一张快照(按快照业务时间,同刻取后写入的) */
    @Select("SELECT * FROM yc_vend_machine_stock_snapshot " +
            "WHERE machine_id=#{machineId} AND product_id=#{productId} AND is_deleted=0 " +
            "ORDER BY snapshot_time DESC, id DESC LIMIT 1")
    MachineStockSnapshot latest(@Param("machineId") Long machineId,
                                @Param("productId") Long productId);

    /** 指定时刻各货道最近一次盘点/补货库存，用于汇总商品锚点。 */
    @Select("SELECT s.* FROM yc_vend_machine_stock_snapshot s WHERE " +
            "s.machine_id=#{machineId} AND s.product_id=#{productId} AND s.slot_no IS NOT NULL " +
            "AND s.snapshot_time<=#{at} AND s.is_deleted=0 AND NOT EXISTS (" +
            "SELECT 1 FROM yc_vend_machine_stock_snapshot n WHERE n.machine_id=s.machine_id " +
            "AND n.product_id=s.product_id AND n.slot_no=s.slot_no AND n.is_deleted=0 " +
            "AND n.snapshot_time<=#{at} AND (n.snapshot_time>s.snapshot_time " +
            "OR (n.snapshot_time=s.snapshot_time AND n.id>s.id)))")
    List<MachineStockSnapshot> latestSlotsAt(@Param("machineId") Long machineId,
                                              @Param("productId") Long productId,
                                              @Param("at") LocalDateTime at);
}
