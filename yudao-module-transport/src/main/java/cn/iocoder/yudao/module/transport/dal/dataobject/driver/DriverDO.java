package cn.iocoder.yudao.module.transport.dal.dataobject.driver;

import cn.iocoder.yudao.framework.tenant.core.db.TenantBaseDO;
import com.baomidou.mybatisplus.annotation.KeySequence;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.*;
import java.time.LocalDate;

@TableName("transport_driver")
@KeySequence("transport_driver_seq")
@Data
@EqualsAndHashCode(callSuper=true)
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DriverDO extends TenantBaseDO {
    @TableId
    private Long id;
    private String name;

    private String mobile;

    private String licenseNo;

    private LocalDate licenseExpireDate;
}
