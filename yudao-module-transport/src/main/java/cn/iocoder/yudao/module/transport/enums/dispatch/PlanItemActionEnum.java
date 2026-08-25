package cn.iocoder.yudao.module.transport.enums.dispatch;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 调度方案经停动作，与算法契约字符串一一对应。
 */
@Getter
@AllArgsConstructor
public enum PlanItemActionEnum {

    DEPART(0, "DEPART"),
    BOARD(1, "BOARD"),
    ALIGHT(2, "ALIGHT"),
    DELIVER(3, "DELIVER"),
    PICKUP(4, "PICKUP"),
    RETURN(5, "RETURN"),
    PASS(6, "PASS");

    private final Integer action;
    /** 契约字符串，见 docs/api/algorithm-api.yaml */
    private final String code;

    /** 契约字符串转枚举；未知字符串返回 null */
    public static PlanItemActionEnum fromCode(String code) {
        for (PlanItemActionEnum value : values()) {
            if (value.code.equals(code)) {
                return value;
            }
        }
        return null;
    }

    /** 枚举转契约字符串 */
    public static String toCode(Integer action) {
        if (action == null) {
            return null;
        }
        for (PlanItemActionEnum value : values()) {
            if (value.action.equals(action)) {
                return value.code;
            }
        }
        return null;
    }

}
