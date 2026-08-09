package cn.iocoder.yudao.module.transport.enums.algorithm;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 算法请求留痕状态。
 */
@Getter
@AllArgsConstructor
public enum AlgorithmRequestStatusEnum {

    /** 已提交，等待算法结果 */
    PROCESSING(0, "处理中"),
    /** 算法返回完整解并通过结果校验 */
    FEASIBLE(1, "可行"),
    /** 算法判定无解 */
    INFEASIBLE(2, "无解"),
    /** 调用失败或结果校验不通过 */
    FAILED(3, "失败");

    private final Integer status;
    private final String name;

}
