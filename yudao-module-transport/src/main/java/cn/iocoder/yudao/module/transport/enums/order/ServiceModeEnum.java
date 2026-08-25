package cn.iocoder.yudao.module.transport.enums.order;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 货运服务方式（ServiceMode）。取货与送达分别判定，允许 pickupMode != deliveryMode。
 *
 * - DOOR_PICKUP         上门取货/送货上门（客户地址）；
 * - NEAREST_STATION     最近可服务站点交接（客户到最近站点寄/取）；
 * - SAFE_ROADSIDE       安全路边交接点（司机在安全点交接，客户就近）；
 * - CUSTOMER_TO_STATION 客户送站（客户把货送到指定站点）；
 * - STATION_TO_STATION  站到站（两端均为客运站点，本系统寄货默认）。
 *
 * 司机只执行「已审批 + 已调度」的服务方式，不自行改服务点。
 */
@Getter
@AllArgsConstructor
public enum ServiceModeEnum {

    DOOR_PICKUP("DOOR_PICKUP", "上门交接"),
    NEAREST_STATION("NEAREST_STATION", "最近站点交接"),
    SAFE_ROADSIDE("SAFE_ROADSIDE", "安全点交接"),
    CUSTOMER_TO_STATION("CUSTOMER_TO_STATION", "客户送站"),
    STATION_TO_STATION("STATION_TO_STATION", "站到站");

    private final String code;
    private final String name;

    public static String nameOf(String code) {
        if (code == null) {
            return "";
        }
        for (ServiceModeEnum item : values()) {
            if (item.getCode().equals(code)) {
                return item.getName();
            }
        }
        return "";
    }

}
