package cn.iocoder.yudao.module.transport.dal.dataobject.shift;

import cn.iocoder.yudao.framework.tenant.core.db.TenantBaseDO;
import com.baomidou.mybatisplus.annotation.KeySequence;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.*;

import java.time.LocalTime;

@TableName("transport_shift")
@KeySequence("transport_shift_seq")
@Data
@EqualsAndHashCode(callSuper = true)
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ShiftDO extends TenantBaseDO {
    @TableId
    private Long id;
    private String shiftCode;
    private Long routeId;
    private LocalTime plannedDepartureTime;
    private Integer plannedDurationMinutes;
    private Integer status;
}
