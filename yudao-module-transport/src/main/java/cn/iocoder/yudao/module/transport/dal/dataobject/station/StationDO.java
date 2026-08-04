package cn.iocoder.yudao.module.transport.dal.dataobject.station;

import cn.iocoder.yudao.framework.tenant.core.db.TenantBaseDO;
import com.baomidou.mybatisplus.annotation.KeySequence;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.*;
import java.math.BigDecimal;

@TableName("transport_station")
@KeySequence("transport_station_seq")
@Data
@EqualsAndHashCode(callSuper=true)
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class StationDO extends TenantBaseDO {
    @TableId
    private Long id;
    private String stationCode;

    private String stationName;

    private Integer stationLevel;

    private BigDecimal longitude;

    private BigDecimal latitude;

    private String address;
}
