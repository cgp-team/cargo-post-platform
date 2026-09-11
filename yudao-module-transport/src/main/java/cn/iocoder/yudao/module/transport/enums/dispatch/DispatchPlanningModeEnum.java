package cn.iocoder.yudao.module.transport.enums.dispatch;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 运输组织方式（直达 or 多段联运）。
 */
@Getter
@AllArgsConstructor
public enum DispatchPlanningModeEnum {

    /** 一段直达 */
    DIRECT("DIRECT", "一段直达"),
    /** 多段联运（2~3 段，含换乘交接） */
    MULTI_LEG("MULTI_LEG", "多段联运");

    private final String mode;
    private final String name;

    public static String nameOf(String mode) {
        if (mode == null) {
            return "";
        }
        for (DispatchPlanningModeEnum item : values()) {
            if (item.getMode().equalsIgnoreCase(mode)) {
                return item.getName();
            }
        }
        return mode;
    }

}
