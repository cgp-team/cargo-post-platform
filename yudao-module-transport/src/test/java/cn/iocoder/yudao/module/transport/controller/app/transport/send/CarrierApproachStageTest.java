package cn.iocoder.yudao.module.transport.controller.app.transport.send;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * P1-G 回归：车辆接近目标站点的距离分级（NEAR_2KM / NEAR_1KM / ARRIVING）。
 *
 * 约定阈值：2km / 1km / 0.5km（可用 yudao.transport.approach.* 覆盖）。超过 2km 返回 null（不提醒）。
 * 前端据此换文案/图标；服务端据此（订单+档位）幂等触发"即将送达"通知（P1-F）。
 */
class CarrierApproachStageTest {

    @Test
    void beyond_2km_returns_null() {
        assertNull(AppSendController.approachStage(2.1, 2.0, 1.0, 0.5));
        assertNull(AppSendController.approachStage(5.0, 2.0, 1.0, 0.5));
    }

    @Test
    void distance_tiers_map_to_stages() {
        assertEquals("NEAR_2KM", AppSendController.approachStage(1.9, 2.0, 1.0, 0.5));
        assertEquals("NEAR_1KM", AppSendController.approachStage(0.8, 2.0, 1.0, 0.5));
        assertEquals("ARRIVING", AppSendController.approachStage(0.4, 2.0, 1.0, 0.5));
        assertEquals("ARRIVING", AppSendController.approachStage(0.1, 2.0, 1.0, 0.5));
    }

    @Test
    void boundary_values_are_inclusive() {
        assertEquals("ARRIVING", AppSendController.approachStage(0.5, 2.0, 1.0, 0.5));
        assertEquals("NEAR_1KM", AppSendController.approachStage(1.0, 2.0, 1.0, 0.5));
        assertEquals("NEAR_2KM", AppSendController.approachStage(2.0, 2.0, 1.0, 0.5));
    }
}
