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
    /** 揽收点联系人；空串 = 没填（界面要念"没填"，不许显示成"无联系人"这种像已核对过的话） */
    private String contactName;
    /** 揽收点联系电话 */
    private String contactMobile;
    /** 营业时间，自由文本（如 08:00-18:00）；空 = 未公布，不拿默认值冒充 24 小时 */
    private String businessHours;

    /** 站点状态：0=启用 1=停用（与车辆状态口径一致；默认 0） */
    private Integer status;

    // ========== 站点可达性三维模型（StationAccessUtil）==========
    /** 数据来源：REAL 现实公交站 / PROJECT 项目自建站 / SIMULATION 模拟站（StationSourceTypeEnum） */
    private String sourceType;
    /** 站点类型：BUS_STOP 公交站 / CARGO_STATION 货运站 / MIXED 混合（StationTypeEnum） */
    private String stationType;
    /** 用户可达：能否推荐给用户作为送站/取货点（null 视为可达） */
    private Boolean userAccess;
    /** 车辆可达：车辆能否进入装卸货（null 视为可达；校园禁行区应显式置 false） */
    private Boolean vehicleAccess;
    /** 是否可用于调度（作场站/换乘站；null 视为可用） */
    private Boolean dispatchEnabled;
    /** 是否电商站点。`stationType` 只有 BUS_STOP/CARGO_STATION/MIXED，表达不了《标记说明》九点的"电商站点"这一类 */
    private Boolean ecommerceFlag;
    /** 排序（列表展示） */
    private Integer sort;
    /** 备注 */
    private String remark;
}
