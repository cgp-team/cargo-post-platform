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

}
