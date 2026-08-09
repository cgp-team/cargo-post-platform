package cn.iocoder.yudao.module.transport.enums.dispatch;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 派单方式。
 */
@Getter
@AllArgsConstructor
public enum DispatchPlanModeEnum {

    /** 手工派单 */
    MANUAL(0, "手工派单"),
    /** 智能派单 */
    SMART(1, "智能派单");

    private final Integer mode;
    private final String name;

}
