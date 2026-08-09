package cn.iocoder.yudao.module.transport.dal.dataobject.dispatch;

import cn.iocoder.yudao.framework.tenant.core.db.TenantBaseDO;
import com.baomidou.mybatisplus.annotation.KeySequence;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.*;

@TableName("transport_departure_check")
@KeySequence("transport_departure_check_seq")
@Data
@EqualsAndHashCode(callSuper = true)
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DepartureCheckDO extends TenantBaseDO {
    @TableId
    private Long id;
    private Long planId;
    private Long vehicleId;
    private Integer result;
    private String remark;
    private String checker;
}
