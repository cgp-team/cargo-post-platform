package cn.iocoder.yudao.module.transport.enums.order;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 承运审核原因码（reasonCode）统一枚举。后端返回原因码，前端负责映射文案，
 * 禁止在前后端各页面硬编码不同错误文本。
 */
@Getter
@AllArgsConstructor
public enum ReviewReasonCodeEnum {

    PROHIBITED_GOODS("PROHIBITED_GOODS", "禁运品"),
    DANGEROUS_GOODS("DANGEROUS_GOODS", "危险品"),
    OVER_WEIGHT("OVER_WEIGHT", "超重"),
    OVER_SIZE("OVER_SIZE", "超尺寸"),
    ROAD_UNREACHABLE("ROAD_UNREACHABLE", "道路不可达"),
    DETOUR_TOO_LARGE("DETOUR_TOO_LARGE", "绕行代价过大"),
    PASSENGER_SERVICE_CONFLICT("PASSENGER_SERVICE_CONFLICT", "与客运服务冲突"),
    NO_SAFE_HANDOFF_POINT("NO_SAFE_HANDOFF_POINT", "无安全交接点"),
    /** 用户当前位置不适合车辆直接进入（校园/步行区/无道路），需送最近站点交接 */
    USER_LOCATION_UNREACHABLE("USER_LOCATION_UNREACHABLE", "当前位置车辆无法进入"),
    CUSTOMER_ACTION_REQUIRED("CUSTOMER_ACTION_REQUIRED", "需客户操作"),
    MANUAL_REVIEW_REQUIRED("MANUAL_REVIEW_REQUIRED", "需人工审核");

    private final String code;
    private final String name;

    public static String nameOf(String code) {
        if (code == null) {
            return "";
        }
        for (ReviewReasonCodeEnum item : values()) {
            if (item.getCode().equals(code)) {
                return item.getName();
            }
        }
        return "";
    }

}
