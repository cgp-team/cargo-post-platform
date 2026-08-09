package cn.iocoder.yudao.module.transport.enums.dispatch;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 发车核验结果。
 */
@Getter
@AllArgsConstructor
public enum DepartureCheckResultEnum {

    /** 不通过 */
    REJECT(0, "不通过"),
    /** 通过 */
    PASS(1, "通过");

    private final Integer result;
    private final String name;

}
