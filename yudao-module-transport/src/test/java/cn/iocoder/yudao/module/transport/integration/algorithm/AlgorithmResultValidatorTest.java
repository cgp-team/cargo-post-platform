package cn.iocoder.yudao.module.transport.integration.algorithm;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.module.transport.integration.algorithm.dto.*;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 结果校验：车辆/站点/订单归属原快照、容量与时序不越界、订单只出现一次；
 * PASS 经停合法；Skeleton 顺序正确；配对货运（shipment）完整覆盖。
 */
class AlgorithmResultValidatorTest {

    @Test
    void valid_feasible_passes() {
        assertDoesNotThrow(() -> AlgorithmResultValidator.validate(baseRequest(), feasibleResult(baseRequest())));
    }

    @Test
    void request_id_mismatch_rejected() {
        AlgorithmPlanRespDTO result = feasibleResult(baseRequest());
        result.setRequestId("req-other");
        assertThrows(ServiceException.class, () -> AlgorithmResultValidator.validate(baseRequest(), result));
    }

    @Test
    void unknown_status_rejected() {
        AlgorithmPlanRespDTO result = feasibleResult(baseRequest());
        result.setStatus("computing");
        assertThrows(ServiceException.class, () -> AlgorithmResultValidator.validate(baseRequest(), result));
    }

    @Test
    void unknown_station_rejected() {
        AlgorithmPlanRespDTO result = feasibleResult(baseRequest());
        result.getVehiclePlans().get(0).getStops().get(1).setStationId("S9");
        assertThrows(ServiceException.class, () -> AlgorithmResultValidator.validate(baseRequest(), result));
    }

    @Test
    void unknown_vehicle_rejected() {
        AlgorithmPlanRespDTO result = feasibleResult(baseRequest());
        result.getVehiclePlans().get(0).setVehicleId(9999L);
        assertThrows(ServiceException.class, () -> AlgorithmResultValidator.validate(baseRequest(), result));
    }

    @Test
    void unknown_order_rejected() {
        AlgorithmPlanRespDTO result = feasibleResult(baseRequest());
        result.getVehiclePlans().get(0).getStops().get(1).setOrderId("O-X999");
        assertThrows(ServiceException.class, () -> AlgorithmResultValidator.validate(baseRequest(), result));
    }

    @Test
    void duplicated_order_rejected() {
        AlgorithmPlanReqDTO request = baseRequest();
        AlgorithmPlanRespDTO result = feasibleResult(request);
        // 在方案尾部追加一个重复的派送经停
        List<AlgorithmRouteStopDTO> stops = result.getVehiclePlans().get(0).getStops();
        stops.add(stops.size() - 1, stop("S2", "O-D001", AlgorithmRouteStopDTO.ACTION_DELIVER));
        assertThrows(ServiceException.class, () -> AlgorithmResultValidator.validate(request, result));
    }

    @Test
    void missing_order_rejected() {
        AlgorithmPlanReqDTO request = baseRequest();
        AlgorithmPlanRespDTO result = feasibleResult(request);
        // 移除派送经停，feasible 必须覆盖全部订单
        result.getVehiclePlans().get(0).getStops()
                .removeIf(stop -> "O-D001".equals(stop.getOrderId()));
        assertThrows(ServiceException.class, () -> AlgorithmResultValidator.validate(request, result));
    }

    @Test
    void capacity_overflow_rejected() {
        // 6 个客运订单同时在线，超过单车 5 人上限
        AlgorithmPlanReqDTO request = baseRequest();
        List<AlgorithmOrderDTO> orders = new ArrayList<>();
        for (int i = 1; i <= 6; i++) {
            orders.add(AlgorithmOrderDTO.builder().orderId("O-P00" + i).orderType(AlgorithmOrderDTO.TYPE_PASSENGER)
                    .boardingStationId("S1").alightingStationId("S2").build());
        }
        request.setOrders(orders);

        List<AlgorithmRouteStopDTO> stops = new ArrayList<>();
        stops.add(stop("S0", null, AlgorithmRouteStopDTO.ACTION_DEPART));
        orders.forEach(order -> stops.add(stop("S1", order.getOrderId(), AlgorithmRouteStopDTO.ACTION_BOARD)));
        orders.forEach(order -> stops.add(stop("S2", order.getOrderId(), AlgorithmRouteStopDTO.ACTION_ALIGHT)));
        stops.add(stop("S0", null, AlgorithmRouteStopDTO.ACTION_RETURN));
        AlgorithmPlanRespDTO result = feasibleResult(request);
        result.getVehiclePlans().get(0).setStops(stops);

        assertThrows(ServiceException.class, () -> AlgorithmResultValidator.validate(request, result));
    }

    @Test
    void alight_before_board_rejected() {
        AlgorithmPlanReqDTO request = baseRequest();
        AlgorithmPlanRespDTO result = feasibleResult(request);
        List<AlgorithmRouteStopDTO> stops = result.getVehiclePlans().get(0).getStops();
        // 交换上车与下车顺序
        AlgorithmRouteStopDTO board = stops.get(1);
        stops.set(1, stop("S2", "O-P001", AlgorithmRouteStopDTO.ACTION_ALIGHT));
        stops.set(2, stop("S1", "O-P001", AlgorithmRouteStopDTO.ACTION_BOARD));
        assertThrows(ServiceException.class, () -> AlgorithmResultValidator.validate(request, result));
    }

    @Test
    void not_closed_loop_rejected() {
        AlgorithmPlanReqDTO request = baseRequest();
        AlgorithmPlanRespDTO result = feasibleResult(request);
        List<AlgorithmRouteStopDTO> stops = result.getVehiclePlans().get(0).getStops();
        stops.set(stops.size() - 1, stop("S2", null, AlgorithmRouteStopDTO.ACTION_RETURN));
        assertThrows(ServiceException.class, () -> AlgorithmResultValidator.validate(request, result));
    }

    @Test
    void infeasible_without_reason_is_normalized_to_fallback_instead_of_throwing() {
        // 结果标准化：确实没给原因码 → 兜底 INFEASIBLE，保证业务侧始终有原因码可展示，
        // 不把"无解"升级成接口异常（此前会导致调度直接失败、答辩演示中断）
        AlgorithmPlanRespDTO result = AlgorithmPlanRespDTO.builder()
                .requestId("req-test-1").status(AlgorithmPlanRespDTO.STATUS_INFEASIBLE).build();

        assertDoesNotThrow(() -> AlgorithmResultValidator.validate(baseRequest(), result));
        assertEquals(AlgorithmResultValidator.REASON_INFEASIBLE_FALLBACK, result.getReasonCode());
    }

    @Test
    void infeasible_with_plans_rejected() {
        AlgorithmPlanRespDTO result = feasibleResult(baseRequest());
        result.setStatus(AlgorithmPlanRespDTO.STATUS_INFEASIBLE);
        result.setReasonCode(AlgorithmPlanRespDTO.REASON_PARTIAL_ONLY);
        assertThrows(ServiceException.class, () -> AlgorithmResultValidator.validate(baseRequest(), result));
    }

    @Test
    void infeasible_passes() {
        AlgorithmPlanRespDTO result = AlgorithmPlanRespDTO.builder()
                .requestId("req-test-1").status(AlgorithmPlanRespDTO.STATUS_INFEASIBLE)
                .reasonCode(AlgorithmPlanRespDTO.REASON_TIMING_CONFLICT).build();
        assertDoesNotThrow(() -> AlgorithmResultValidator.validate(baseRequest(), result));
    }

    // ========== PASS 经停验证 ==========

    @Test
    void pass_stop_accepted() {
        // PASS 不关联订单，不参与覆盖统计
        AlgorithmPlanReqDTO request = baseRequest();
        AlgorithmPlanRespDTO result = feasibleResult(request);
        List<AlgorithmRouteStopDTO> stops = result.getVehiclePlans().get(0).getStops();
        // 在 BOARD 之前插入 PASS
        stops.add(1, stop("S1", null, AlgorithmRouteStopDTO.ACTION_PASS));
        assertDoesNotThrow(() -> AlgorithmResultValidator.validate(request, result));
    }

    @Test
    void pass_stop_with_order_rejected() {
        // PASS 不得关联订单
        AlgorithmPlanReqDTO request = baseRequest();
        AlgorithmPlanRespDTO result = feasibleResult(request);
        List<AlgorithmRouteStopDTO> stops = result.getVehiclePlans().get(0).getStops();
        stops.add(1, stop("S1", "O-P001", AlgorithmRouteStopDTO.ACTION_PASS));
        assertThrows(ServiceException.class, () -> AlgorithmResultValidator.validate(request, result));
    }

    @Test
    void multiple_pass_stops_accepted() {
        // 允许多个 PASS
        AlgorithmPlanReqDTO request = baseRequest();
        AlgorithmPlanRespDTO result = feasibleResult(request);
        List<AlgorithmRouteStopDTO> stops = result.getVehiclePlans().get(0).getStops();
        stops.add(1, stop("S1", null, AlgorithmRouteStopDTO.ACTION_PASS));
        stops.add(2, stop("S2", null, AlgorithmRouteStopDTO.ACTION_PASS));
        assertDoesNotThrow(() -> AlgorithmResultValidator.validate(request, result));
    }

    // ========== Skeleton 验证 ==========

    @Test
    void skeleton_order_respected() {
        // 骨架站点按顺序出现
        AlgorithmPlanReqDTO request = baseRequest();
        request.getVehicles().get(0).setSkeleton(List.of("S1", "S2"));
        AlgorithmPlanRespDTO result = feasibleResult(request);
        List<AlgorithmRouteStopDTO> stops = result.getVehiclePlans().get(0).getStops();
        // 在 DEPART 后插入 PASS S1，在 ALIGHT 后插入 PASS S2
        stops.add(1, stop("S1", null, AlgorithmRouteStopDTO.ACTION_PASS));
        stops.add(4, stop("S2", null, AlgorithmRouteStopDTO.ACTION_PASS));
        assertDoesNotThrow(() -> AlgorithmResultValidator.validate(request, result));
    }

    @Test
    void skeleton_order_violation_rejected() {
        // 骨架顺序颠倒
        AlgorithmPlanReqDTO request = baseRequest();
        request.getVehicles().get(0).setSkeleton(List.of("S1", "S2"));
        AlgorithmPlanRespDTO result = feasibleResult(request);
        List<AlgorithmRouteStopDTO> stops = result.getVehiclePlans().get(0).getStops();
        // PASS S2 在 PASS S1 之前（顺序颠倒）
        stops.add(1, stop("S2", null, AlgorithmRouteStopDTO.ACTION_PASS));
        stops.add(2, stop("S1", null, AlgorithmRouteStopDTO.ACTION_PASS));
        assertThrows(ServiceException.class, () -> AlgorithmResultValidator.validate(request, result));
    }

    @Test
    void skeleton_missing_station_rejected() {
        // 骨架站点缺失
        AlgorithmPlanReqDTO request = baseRequest();
        request.getVehicles().get(0).setSkeleton(List.of("S1", "S2"));
        AlgorithmPlanRespDTO result = feasibleResult(request);
        // 没有 PASS 动作，skeleton 未满足
        assertThrows(ServiceException.class, () -> AlgorithmResultValidator.validate(request, result));
    }

    // ========== Shipment 验证 ==========

    @Test
    void shipment_pickup_delivery_accepted() {
        // 完整的 shipment PICKUP + DELIVERY
        AlgorithmPlanReqDTO request = baseRequest();
        request.setShipments(List.of(
                AlgorithmShipmentDTO.builder().shipmentId("TP001").pickupStationId("S1")
                        .deliveryStationId("S2").quantity(2).build()));
        AlgorithmPlanRespDTO result = feasibleResult(request);
        List<AlgorithmRouteStopDTO> stops = result.getVehiclePlans().get(0).getStops();
        stops.add(stops.size() - 1, stop("S1", "TP001", AlgorithmRouteStopDTO.ACTION_PICKUP));
        stops.add(stops.size() - 1, stop("S2", "TP001", AlgorithmRouteStopDTO.ACTION_DELIVER));
        assertDoesNotThrow(() -> AlgorithmResultValidator.validate(request, result));
    }

    @Test
    void shipment_missing_delivery_rejected() {
        // 只有 PICKUP 没有 DELIVERY
        AlgorithmPlanReqDTO request = baseRequest();
        request.setShipments(List.of(
                AlgorithmShipmentDTO.builder().shipmentId("TP001").pickupStationId("S1")
                        .deliveryStationId("S2").quantity(2).build()));
        AlgorithmPlanRespDTO result = feasibleResult(request);
        List<AlgorithmRouteStopDTO> stops = result.getVehiclePlans().get(0).getStops();
        stops.add(stops.size() - 1, stop("S1", "TP001", AlgorithmRouteStopDTO.ACTION_PICKUP));
        assertThrows(ServiceException.class, () -> AlgorithmResultValidator.validate(request, result));
    }

    @Test
    void shipment_wrong_station_rejected() {
        // PICKUP 站点不匹配
        AlgorithmPlanReqDTO request = baseRequest();
        request.setShipments(List.of(
                AlgorithmShipmentDTO.builder().shipmentId("TP001").pickupStationId("S1")
                        .deliveryStationId("S2").quantity(2).build()));
        AlgorithmPlanRespDTO result = feasibleResult(request);
        List<AlgorithmRouteStopDTO> stops = result.getVehiclePlans().get(0).getStops();
        stops.add(stops.size() - 1, stop("S2", "TP001", AlgorithmRouteStopDTO.ACTION_PICKUP)); // 错误：应该是 S1
        stops.add(stops.size() - 1, stop("S2", "TP001", AlgorithmRouteStopDTO.ACTION_DELIVER));
        assertThrows(ServiceException.class, () -> AlgorithmResultValidator.validate(request, result));
    }

    // ========== 部分解（unassignedOrderIds）验证 ==========

    @Test
    void declared_unassigned_cargo_makes_a_partial_solution_valid() {
        // 算法这一趟带不下的货运单写进 unassignedOrderIds 是正常出口（后端转多段联运接力），
        // 不是接口异常 —— 此前这里抛 ALGORITHM_RESULT_INVALID，接力分支永远进不去
        AlgorithmPlanReqDTO request = baseRequest();
        AlgorithmPlanRespDTO result = feasibleResult(request);
        result.getVehiclePlans().get(0).getStops()
                .removeIf(stop -> "O-D001".equals(stop.getOrderId()));
        result.setUnassignedOrderIds(new ArrayList<>(List.of("O-D001")));
        assertDoesNotThrow(() -> AlgorithmResultValidator.validate(request, result));
    }

    @Test
    void unassigned_cargo_without_the_declaration_is_still_a_coverage_failure() {
        // 与上一例成对：同样少派一个货运单，不声明就是漏派。宽松判据只放行"明说的"部分解。
        AlgorithmPlanReqDTO request = baseRequest();
        AlgorithmPlanRespDTO result = feasibleResult(request);
        result.getVehiclePlans().get(0).getStops()
                .removeIf(stop -> "O-D001".equals(stop.getOrderId()));
        assertThrows(ServiceException.class, () -> AlgorithmResultValidator.validate(request, result));
    }

    @Test
    void shed_passenger_order_is_rejected_by_the_bottom_line_not_by_coverage() {
        // 乘客不可舍弃（每站必停拉客）在 Java 侧同样钉住：断言报错原因，
        // 避免"靠覆盖率检查顺带拦住"被当成这条底线已经生效
        AlgorithmPlanReqDTO request = baseRequest();
        AlgorithmPlanRespDTO result = feasibleResult(request);
        result.getVehiclePlans().get(0).getStops()
                .removeIf(stop -> "O-P001".equals(stop.getOrderId()));
        result.setUnassignedOrderIds(new ArrayList<>(List.of("O-P001")));
        ServiceException ex = assertThrows(ServiceException.class,
                () -> AlgorithmResultValidator.validate(request, result));
        assertTrue(ex.getMessage().contains("乘客不可舍弃"), ex.getMessage());
    }

    @Test
    void shed_id_outside_the_snapshot_is_rejected() {
        AlgorithmPlanReqDTO request = baseRequest();
        AlgorithmPlanRespDTO result = feasibleResult(request);
        result.setUnassignedOrderIds(new ArrayList<>(List.of("O-NOPE")));
        ServiceException ex = assertThrows(ServiceException.class,
                () -> AlgorithmResultValidator.validate(request, result));
        assertTrue(ex.getMessage().contains("不属于本次请求快照"), ex.getMessage());
    }

    @Test
    void shed_order_must_not_also_appear_in_the_plan() {
        // "既报未分配又出现在方案里"是口径自相矛盾，不能让它蒙过落库
        AlgorithmPlanReqDTO request = baseRequest();
        AlgorithmPlanRespDTO result = feasibleResult(request);
        result.setUnassignedOrderIds(new ArrayList<>(List.of("O-D001")));
        ServiceException ex = assertThrows(ServiceException.class,
                () -> AlgorithmResultValidator.validate(request, result));
        assertTrue(ex.getMessage().contains("未分配"), ex.getMessage());
    }

    @Test
    void declared_unassigned_shipment_needs_no_legs() {
        AlgorithmPlanReqDTO request = baseRequest();
        request.setShipments(List.of(
                AlgorithmShipmentDTO.builder().shipmentId("TP001").pickupStationId("S1")
                        .deliveryStationId("S2").quantity(2).build()));
        AlgorithmPlanRespDTO result = feasibleResult(request);
        result.setUnassignedOrderIds(new ArrayList<>(List.of("TP001")));
        assertDoesNotThrow(() -> AlgorithmResultValidator.validate(request, result));
    }

    @Test
    void half_a_shipment_is_never_a_valid_shed() {
        // 配对货运整单未分配才叫"带不下"；只落揽收不落实派仍是非法
        AlgorithmPlanReqDTO request = baseRequest();
        request.setShipments(List.of(
                AlgorithmShipmentDTO.builder().shipmentId("TP001").pickupStationId("S1")
                        .deliveryStationId("S2").quantity(2).build()));
        AlgorithmPlanRespDTO result = feasibleResult(request);
        List<AlgorithmRouteStopDTO> stops = result.getVehiclePlans().get(0).getStops();
        stops.add(stops.size() - 1, stop("S1", "TP001", AlgorithmRouteStopDTO.ACTION_PICKUP));
        result.setUnassignedOrderIds(new ArrayList<>(List.of("TP001")));
        ServiceException ex = assertThrows(ServiceException.class,
                () -> AlgorithmResultValidator.validate(request, result));
        assertTrue(ex.getMessage().contains("经停点"), ex.getMessage());
    }

    @Test
    void empty_plan_is_valid_only_when_everything_is_shed() {
        AlgorithmPlanReqDTO request = baseRequest();
        request.setOrders(List.of(
                AlgorithmOrderDTO.builder().orderId("O-D001").orderType(AlgorithmOrderDTO.TYPE_DELIVERY)
                        .stationId("S2").itemCount(1).build()));
        AlgorithmPlanRespDTO allShed = AlgorithmPlanRespDTO.builder()
                .requestId(request.getRequestId())
                .status(AlgorithmPlanRespDTO.STATUS_FEASIBLE)
                .vehiclePlans(new ArrayList<>())
                .unassignedOrderIds(new ArrayList<>(List.of("O-D001")))
                .computedAt(OffsetDateTime.parse("2026-08-09T08:00:05+08:00"))
                .build();
        assertDoesNotThrow(() -> AlgorithmResultValidator.validate(request, allShed));

        // 对照：同样空方案但不声明 → 依旧非法，不能把空方案说成部分解
        AlgorithmPlanRespDTO silent = AlgorithmPlanRespDTO.builder()
                .requestId(request.getRequestId())
                .status(AlgorithmPlanRespDTO.STATUS_FEASIBLE)
                .vehiclePlans(new ArrayList<>())
                .computedAt(OffsetDateTime.parse("2026-08-09T08:00:05+08:00"))
                .build();
        assertThrows(ServiceException.class, () -> AlgorithmResultValidator.validate(request, silent));
    }

    // ========== 初始载荷验证 ==========

    @Test
    void initial_passenger_load_respected() {
        // 初始载客 3 人，再上 2 人（共 5 人，未超容量）
        AlgorithmPlanReqDTO request = baseRequest();
        request.getVehicles().get(0).setInitialPassengerLoad(3);
        request.getVehicles().get(0).setPassengerCapacity(5);
        // 只保留 2 个客运订单
        request.setOrders(List.of(
                AlgorithmOrderDTO.builder().orderId("O-P001").orderType(AlgorithmOrderDTO.TYPE_PASSENGER)
                        .boardingStationId("S1").alightingStationId("S2").build(),
                AlgorithmOrderDTO.builder().orderId("O-P002").orderType(AlgorithmOrderDTO.TYPE_PASSENGER)
                        .boardingStationId("S1").alightingStationId("S2").build()));
        AlgorithmPlanRespDTO result = AlgorithmPlanRespDTO.builder()
                .requestId(request.getRequestId())
                .status(AlgorithmPlanRespDTO.STATUS_FEASIBLE)
                .algorithmVersion("test-v1")
                .parameterVersion("p-v1")
                .totalDistance(1.0)
                .vehiclePlans(new ArrayList<>(List.of(AlgorithmVehiclePlanDTO.builder()
                        .vehicleId(1001L).stops(new ArrayList<>(List.of(
                                stop("S0", null, AlgorithmRouteStopDTO.ACTION_DEPART),
                                stop("S1", "O-P001", AlgorithmRouteStopDTO.ACTION_BOARD),
                                stop("S1", "O-P002", AlgorithmRouteStopDTO.ACTION_BOARD),
                                stop("S2", "O-P001", AlgorithmRouteStopDTO.ACTION_ALIGHT),
                                stop("S2", "O-P002", AlgorithmRouteStopDTO.ACTION_ALIGHT),
                                stop("S0", null, AlgorithmRouteStopDTO.ACTION_RETURN))))
                        .totalDistance(1.0).build())))
                .computedAt(OffsetDateTime.parse("2026-08-09T08:00:05+08:00"))
                .build();
        assertDoesNotThrow(() -> AlgorithmResultValidator.validate(request, result));
    }

    @Test
    void initial_passenger_load_overflow_rejected() {
        // 初始载客 4 人，再上 2 人（共 6 人，超容量 5）
        AlgorithmPlanReqDTO request = baseRequest();
        request.getVehicles().get(0).setInitialPassengerLoad(4);
        request.getVehicles().get(0).setPassengerCapacity(5);
        request.setOrders(List.of(
                AlgorithmOrderDTO.builder().orderId("O-P001").orderType(AlgorithmOrderDTO.TYPE_PASSENGER)
                        .boardingStationId("S1").alightingStationId("S2").build(),
                AlgorithmOrderDTO.builder().orderId("O-P002").orderType(AlgorithmOrderDTO.TYPE_PASSENGER)
                        .boardingStationId("S1").alightingStationId("S2").build()));
        AlgorithmPlanRespDTO result = AlgorithmPlanRespDTO.builder()
                .requestId(request.getRequestId())
                .status(AlgorithmPlanRespDTO.STATUS_FEASIBLE)
                .algorithmVersion("test-v1")
                .parameterVersion("p-v1")
                .totalDistance(1.0)
                .vehiclePlans(new ArrayList<>(List.of(AlgorithmVehiclePlanDTO.builder()
                        .vehicleId(1001L).stops(new ArrayList<>(List.of(
                                stop("S0", null, AlgorithmRouteStopDTO.ACTION_DEPART),
                                stop("S1", "O-P001", AlgorithmRouteStopDTO.ACTION_BOARD),
                                stop("S1", "O-P002", AlgorithmRouteStopDTO.ACTION_BOARD),
                                stop("S2", "O-P001", AlgorithmRouteStopDTO.ACTION_ALIGHT),
                                stop("S2", "O-P002", AlgorithmRouteStopDTO.ACTION_ALIGHT),
                                stop("S0", null, AlgorithmRouteStopDTO.ACTION_RETURN))))
                        .totalDistance(1.0).build())))
                .computedAt(OffsetDateTime.parse("2026-08-09T08:00:05+08:00"))
                .build();
        assertThrows(ServiceException.class, () -> AlgorithmResultValidator.validate(request, result));
    }

    @Test
    void initial_cargo_load_respected() {
        // 初始载货 3 件，再派送 1 件（共 4 件，未超容量）
        AlgorithmPlanReqDTO request = baseRequest();
        request.getVehicles().get(0).setInitialCargoLoad(3);
        request.getVehicles().get(0).setCargoCapacity(4);
        // 只保留 1 个派送订单
        request.setOrders(List.of(
                AlgorithmOrderDTO.builder().orderId("O-D001").orderType(AlgorithmOrderDTO.TYPE_DELIVERY)
                        .stationId("S2").itemCount(1).build()));
        AlgorithmPlanRespDTO result = AlgorithmPlanRespDTO.builder()
                .requestId(request.getRequestId())
                .status(AlgorithmPlanRespDTO.STATUS_FEASIBLE)
                .algorithmVersion("test-v1")
                .parameterVersion("p-v1")
                .totalDistance(1.0)
                .vehiclePlans(new ArrayList<>(List.of(AlgorithmVehiclePlanDTO.builder()
                        .vehicleId(1001L).stops(new ArrayList<>(List.of(
                                stop("S0", null, AlgorithmRouteStopDTO.ACTION_DEPART),
                                stop("S2", "O-D001", AlgorithmRouteStopDTO.ACTION_DELIVER),
                                stop("S0", null, AlgorithmRouteStopDTO.ACTION_RETURN))))
                        .totalDistance(1.0).build())))
                .computedAt(OffsetDateTime.parse("2026-08-09T08:00:05+08:00"))
                .build();
        assertDoesNotThrow(() -> AlgorithmResultValidator.validate(request, result));
    }

    // ========== 距离验证 ==========

    @Test
    void negative_total_distance_rejected() {
        AlgorithmPlanReqDTO request = baseRequest();
        AlgorithmPlanRespDTO result = feasibleResult(request);
        result.setTotalDistance(-1.0);
        assertThrows(ServiceException.class, () -> AlgorithmResultValidator.validate(request, result));
    }

    @Test
    void negative_segment_distance_rejected() {
        AlgorithmPlanReqDTO request = baseRequest();
        AlgorithmPlanRespDTO result = feasibleResult(request);
        result.getVehiclePlans().get(0).getStops().get(1).setSegmentDistance(-0.5);
        assertThrows(ServiceException.class, () -> AlgorithmResultValidator.validate(request, result));
    }

    // ========== 就近站交接（停靠站 ≠ 订单原始点位）==========

    @Test
    void cargo_stop_moved_without_handover_markers_is_rejected() {
        // 对照组：只把停靠站挪走、不声明交接 → 仍然是"与快照不符"，
        // 不能让"停靠别的站"这件事靠改个 stationId 就蒙过去
        AlgorithmPlanReqDTO request = baseRequest();
        AlgorithmPlanRespDTO result = feasibleResult(request);
        List<AlgorithmRouteStopDTO> stops = result.getVehiclePlans().get(0).getStops();
        stops.set(indexOfOrder(stops, "O-D001"), stop("S1", "O-D001", AlgorithmRouteStopDTO.ACTION_DELIVER));
        assertThrows(ServiceException.class, () -> AlgorithmResultValidator.validate(request, result));
    }

    @Test
    void cargo_order_handover_at_nearest_station_is_valid() {
        // 第②级处置的既定形态：车不停在村里那个点，改停最近站点，客户到站取送。
        // 停靠站与订单原始点位**本来就该不同**；此前这里一刀切拒绝，
        // 等于算法每出一份交接方案都被后端判成非法路单（客户地址还在订单记录里，不用重复出现在经停上）。
        AlgorithmPlanReqDTO request = baseRequest();
        AlgorithmPlanRespDTO result = feasibleResult(request);
        List<AlgorithmRouteStopDTO> stops = result.getVehiclePlans().get(0).getStops();
        stops.set(indexOfOrder(stops, "O-D001"), handover("S1", "O-D001", AlgorithmRouteStopDTO.ACTION_DELIVER));
        assertDoesNotThrow(() -> AlgorithmResultValidator.validate(request, result));
    }

    @Test
    void shipment_leg_handover_at_skeleton_station_is_valid() {
        // 配对货运单的揽收点在村里（S3，公交进不去/绕行超阈值），车改停骨架站 S1 揽收
        AlgorithmPlanReqDTO request = baseRequest();
        List<AlgorithmStationDTO> stations = new ArrayList<>(request.getStations());
        stations.add(AlgorithmClientTest.station("S3"));
        request.setStations(stations);
        request.getVehicles().get(0).setSkeleton(List.of("S1"));
        request.setShipments(List.of(
                AlgorithmShipmentDTO.builder().shipmentId("TP001").pickupStationId("S3")
                        .deliveryStationId("S2").quantity(1).build()));
        List<AlgorithmRouteStopDTO> stops = new ArrayList<>(List.of(
                stop("S0", null, AlgorithmRouteStopDTO.ACTION_DEPART),
                stop("S1", null, AlgorithmRouteStopDTO.ACTION_PASS),
                stop("S1", "O-P001", AlgorithmRouteStopDTO.ACTION_BOARD),
                handover("S1", "TP001", AlgorithmRouteStopDTO.ACTION_PICKUP),
                stop("S2", "TP001", AlgorithmRouteStopDTO.ACTION_DELIVER),
                stop("S2", "O-D001", AlgorithmRouteStopDTO.ACTION_DELIVER),
                stop("S2", "O-P001", AlgorithmRouteStopDTO.ACTION_ALIGHT),
                stop("S0", null, AlgorithmRouteStopDTO.ACTION_RETURN)));
        AlgorithmPlanRespDTO result = feasibleResult(request);
        result.getVehiclePlans().get(0).setStops(stops);
        assertDoesNotThrow(() -> AlgorithmResultValidator.validate(request, result));
    }

    @Test
    void fake_handover_off_the_skeleton_is_rejected() {
        // 只贴标签不算交接：停靠点必须真的落在本车的公交骨架上
        // （与度量台 check.py 的 HANDOVER_NOT_AT_SKELETON 同一条判据）
        AlgorithmPlanReqDTO request = baseRequest();
        request.getVehicles().get(0).setSkeleton(List.of("S2"));
        AlgorithmPlanRespDTO result = feasibleResult(request);
        List<AlgorithmRouteStopDTO> stops = result.getVehiclePlans().get(0).getStops();
        stops.set(indexOfOrder(stops, "O-D001"), handover("S1", "O-D001", AlgorithmRouteStopDTO.ACTION_DELIVER));
        ServiceException ex = assertThrows(ServiceException.class,
                () -> AlgorithmResultValidator.validate(request, result));
        assertTrue(ex.getMessage().contains("作业站点或动作与快照不符"), ex.getMessage());
    }

    @Test
    void handover_must_stop_where_it_says_it_serves() {
        // 服务点与停靠站不一致 = 方案自相矛盾（check.py 的 SERVICE_POINT_MISMATCH）
        AlgorithmPlanReqDTO request = baseRequest();
        AlgorithmPlanRespDTO result = feasibleResult(request);
        List<AlgorithmRouteStopDTO> stops = result.getVehiclePlans().get(0).getStops();
        AlgorithmRouteStopDTO moved = handover("S1", "O-D001", AlgorithmRouteStopDTO.ACTION_DELIVER);
        moved.setServicePoint("S2");
        stops.set(indexOfOrder(stops, "O-D001"), moved);
        assertThrows(ServiceException.class, () -> AlgorithmResultValidator.validate(request, result));
    }

    @Test
    void passenger_stops_are_never_rewritten_to_a_handover() {
        // 业务底线：每站必停拉客，乘客的上落点由不得算法改派 —— 这条与上面几条例外**故意不共用**，
        // 所以它带着交接标签也必须红（红因是"上下车站点与快照不符"）
        AlgorithmPlanReqDTO request = baseRequest();
        AlgorithmPlanRespDTO result = feasibleResult(request);
        List<AlgorithmRouteStopDTO> stops = result.getVehiclePlans().get(0).getStops();
        stops.set(indexOfOrder(stops, "O-P001"), handover("S2", "O-P001", AlgorithmRouteStopDTO.ACTION_BOARD));
        ServiceException ex = assertThrows(ServiceException.class,
                () -> AlgorithmResultValidator.validate(request, result));
        assertTrue(ex.getMessage().contains("上下车站点与快照不符"), ex.getMessage());
    }

    // ========== 测试数据 ==========

    static AlgorithmPlanReqDTO baseRequest() {
        return AlgorithmClientTest.request();
    }

    static AlgorithmPlanRespDTO feasibleResult(AlgorithmPlanReqDTO request) {
        List<AlgorithmRouteStopDTO> stops = new ArrayList<>(List.of(
                stop("S0", null, AlgorithmRouteStopDTO.ACTION_DEPART),
                stop("S1", "O-P001", AlgorithmRouteStopDTO.ACTION_BOARD),
                stop("S2", "O-P001", AlgorithmRouteStopDTO.ACTION_ALIGHT),
                stop("S2", "O-D001", AlgorithmRouteStopDTO.ACTION_DELIVER),
                stop("S0", null, AlgorithmRouteStopDTO.ACTION_RETURN)));
        return AlgorithmPlanRespDTO.builder()
                .requestId(request.getRequestId())
                .status(AlgorithmPlanRespDTO.STATUS_FEASIBLE)
                .cached(false)
                .algorithmVersion("mock-2.0.0")
                .parameterVersion("default-v1")
                .totalDistance(1.0)
                .vehiclePlans(new ArrayList<>(List.of(AlgorithmVehiclePlanDTO.builder()
                        .vehicleId(1001L).stops(stops).totalDistance(1.0).build())))
                .computedAt(OffsetDateTime.parse("2026-08-09T08:00:05+08:00"))
                .build();
    }

    static AlgorithmRouteStopDTO stop(String stationId, String orderId, String action) {
        return AlgorithmRouteStopDTO.builder()
                .stationId(stationId).orderId(orderId).action(action).segmentDistance(0.1).build();
    }

    /** 就近站交接经停：车停在 stationId，服务点就是这一站，客户到站取送 */
    static AlgorithmRouteStopDTO handover(String stationId, String orderId, String action) {
        return AlgorithmRouteStopDTO.builder()
                .stationId(stationId).orderId(orderId).action(action).segmentDistance(0.1)
                .accepted(true).serviceMode("NEAREST_STATION").servicePoint(stationId).build();
    }

    static int indexOfOrder(List<AlgorithmRouteStopDTO> stops, String orderId) {
        for (int i = 0; i < stops.size(); i++) {
            if (orderId.equals(stops.get(i).getOrderId())) {
                return i;
            }
        }
        throw new AssertionError("夹具里没有 " + orderId + " 的经停（用例失去内容）");
    }

}
