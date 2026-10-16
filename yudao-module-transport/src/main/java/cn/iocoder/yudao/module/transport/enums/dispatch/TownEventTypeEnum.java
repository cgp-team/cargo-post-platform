package cn.iocoder.yudao.module.transport.enums.dispatch;

import java.util.Arrays;

/**
 * 乡镇事件类型（队列 #93）。
 *
 * <p>为什么单独一个枚举而不是各处写字符串：这张表要被"情景提示"读，
 * 一处写 {@code MARKET}、另一处写 {@code market} 或 {@code 集市} 的话，规则匹配会静默漏掉整类事件
 * —— 与后台"自造状态码与后端枚举不同名"（W-1）是同一类病。
 */
public enum TownEventTypeEnum {

    /** 赶集/场期 */
    MARKET,
    /** 农忙/采收季 */
    FARMING,
    /** 法定节假日 */
    HOLIDAY,
    /** 乡镇活动（庙会、赛事、集市以外的临时活动） */
    EVENT;

    public static boolean matches(String value) {
        return value != null && Arrays.stream(values()).anyMatch(t -> t.name().equals(value));
    }
}
