package cn.iocoder.yudao.module.transport.enums.dispatch;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 任务段明细状态（司机任务状态，后端为最终来源，前端不用 Boolean 代替状态机）。
 *
 * 对应 transport_dispatch_plan_item.status，随司机执行推进：
 * PENDING   待执行（未到达该经停）
 * EN_ROUTE  行驶中（正前往该经停）
 * ARRIVED   已到站（车辆坐标进入到站阈值）
 * BOARDING  执行上车
 * ALIGHTING 执行下车
 * PICKUP    执行揽收装车
 * DELIVERY  执行派送卸货
 * COMPLETED 已执行完成
 * FAILED    执行失败（如到站后无法交接）
 */
@Getter
@AllArgsConstructor
public enum TaskItemStatusEnum {

    PENDING(0, "待执行"),
    EN_ROUTE(1, "行驶中"),
    ARRIVED(2, "已到站"),
    BOARDING(3, "上车中"),
    ALIGHTING(4, "下车中"),
    PICKUP(5, "揽收中"),
    DELIVERY(6, "派送中"),
    COMPLETED(7, "已完成"),
    FAILED(8, "失败");

    private final Integer status;
    private final String name;

    public static String nameOf(Integer status) {
        if (status == null) {
            return "";
        }
        for (TaskItemStatusEnum item : values()) {
            if (item.getStatus().equals(status)) {
                return item.getName();
            }
        }
        return "";
    }

}
