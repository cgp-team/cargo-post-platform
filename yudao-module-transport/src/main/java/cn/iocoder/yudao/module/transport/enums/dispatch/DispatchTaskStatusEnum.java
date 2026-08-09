package cn.iocoder.yudao.module.transport.enums.dispatch;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 调度任务状态。
 */
@Getter
@AllArgsConstructor
public enum DispatchTaskStatusEnum {

    /** 已提交，规划中 */
    PLANNING(0, "规划中"),
    /** 规划成功 */
    SUCCESS(1, "规划成功"),
    /** 算法判定无可行解 */
    INFEASIBLE(2, "无可行解"),
    /** 规划失败 */
    FAILED(3, "规划失败");

    private final Integer status;
    private final String name;

}
