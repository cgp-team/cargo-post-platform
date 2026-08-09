package cn.iocoder.yudao.module.transport.integration.algorithm;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.module.transport.integration.algorithm.dto.*;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 结果校验：车辆/站点/订单归属原快照、容量与时序不越界、订单只出现一次。
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
    void infeasible_without_reason_rejected() {
        AlgorithmPlanRespDTO result = AlgorithmPlanRespDTO.builder()
                .requestId("req-test-1").status(AlgorithmPlanRespDTO.STATUS_INFEASIBLE).build();
        assertThrows(ServiceException.class, () -> AlgorithmResultValidator.validate(baseRequest(), result));
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
