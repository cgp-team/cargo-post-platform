package cn.iocoder.yudao.module.transport.integration.algorithm;

import cn.iocoder.yudao.module.transport.integration.algorithm.dto.*;
import cn.hutool.core.util.StrUtil;
import org.springframework.util.CollectionUtils;

import java.util.*;

import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.module.transport.enums.ErrorCodeConstants.ALGORITHM_RESULT_INVALID;

/**
 * 算法结果合法性校验（适配层责任第 5 条）：
 * 车辆、站点、订单均属于原快照；容量与时序不越界；所有订单只出现一次；
 * PASS 经停合法；Skeleton 顺序正确；配对货运（shipment）完整覆盖。
 * 校验不通过一律抛 {@link cn.iocoder.yudao.framework.common.exception.ServiceException}，不落库。
 */
public final class AlgorithmResultValidator {

    private AlgorithmResultValidator() {
    }

    public static void validate(AlgorithmPlanReqDTO request, AlgorithmPlanRespDTO response) {
        if (response == null) {
            throw exception(ALGORITHM_RESULT_INVALID, "响应为空");
        }
        if (!Objects.equals(request.getRequestId(), response.getRequestId())) {
            throw exception(ALGORITHM_RESULT_INVALID, "requestId 与请求不一致");
        }
        if (AlgorithmPlanRespDTO.STATUS_INFEASIBLE.equals(response.getStatus())) {
            validateInfeasible(response);
            return;
        }
        if (!AlgorithmPlanRespDTO.STATUS_FEASIBLE.equals(response.getStatus())) {
            throw exception(ALGORITHM_RESULT_INVALID, "未知状态 " + response.getStatus());
        }
        validateFeasible(request, response);
    }

    private static void validateInfeasible(AlgorithmPlanRespDTO response) {
        // 结果标准化（不删校验）：
        // 1) 旧接口可能返回 reason_code（JSON 别名已接入 DTO）；
        // 2) 若确实没给原因码，兜底 INFEASIBLE，保证业务侧始终能拿到原因码做展示与统计，
        //    而不是把"无解"变成一次接口异常（此前会导致调度直接失败、演示中断）。
        if (StrUtil.isBlank(response.getReasonCode())) {
            response.setReasonCode(REASON_INFEASIBLE_FALLBACK);
        }
        // PARTIAL_ONLY 语义待澄清，当前按"只给状态不给方案"处理：无解不得携带方案
        if (!CollectionUtils.isEmpty(response.getVehiclePlans())) {
            throw exception(ALGORITHM_RESULT_INVALID, "无解结果不得携带车辆方案");
        }
    }

    /** 无解兜底原因码（算法未给原因时的最后防线） */
    public static final String REASON_INFEASIBLE_FALLBACK = "INFEASIBLE";

    private static void validateFeasible(AlgorithmPlanReqDTO request, AlgorithmPlanRespDTO response) {
        if (CollectionUtils.isEmpty(response.getVehiclePlans()) && !CollectionUtils.isEmpty(request.getOrders())) {
            throw exception(ALGORITHM_RESULT_INVALID, "feasible 结果缺少车辆方案");
        }

        Set<String> stationIds = new HashSet<>();
        stationIds.add(request.getDepot().getStationId());
        request.getStations().forEach(station -> stationIds.add(station.getStationId()));
        Map<Long, AlgorithmVehicleDTO> vehicleMap = new HashMap<>();
        request.getVehicles().forEach(vehicle -> vehicleMap.put(vehicle.getVehicleId(), vehicle));
        Map<String, AlgorithmOrderDTO> orderMap = new HashMap<>();
        request.getOrders().forEach(order -> orderMap.put(order.getOrderId(), order));

        // 构建 shipment 映射
        Map<String, AlgorithmShipmentDTO> shipmentMap = new HashMap<>();
        if (!CollectionUtils.isEmpty(request.getShipments())) {
            request.getShipments().forEach(s -> shipmentMap.put(s.getShipmentId(), s));
        }

        Map<String, Integer> orderStopCount = new HashMap<>();
        Map<String, Integer> shipmentPickupCount = new HashMap<>();
        Map<String, Integer> shipmentDeliveryCount = new HashMap<>();
        Set<Long> usedVehicleIds = new HashSet<>();
        for (AlgorithmVehiclePlanDTO plan : nullSafe(response.getVehiclePlans())) {
            validateVehiclePlan(request, plan, stationIds, vehicleMap, orderMap, shipmentMap,
                    orderStopCount, shipmentPickupCount, shipmentDeliveryCount);
            if (!usedVehicleIds.add(plan.getVehicleId())) {
                throw exception(ALGORITHM_RESULT_INVALID, "车辆 " + plan.getVehicleId() + " 出现多份方案");
            }
        }

        // 所有订单必须被覆盖且只出现一次；客运订单为 BOARD+ALIGHT 两站
        for (AlgorithmOrderDTO order : request.getOrders()) {
            int expected = AlgorithmOrderDTO.TYPE_PASSENGER.equals(order.getOrderType()) ? 2 : 1;
            int actual = orderStopCount.getOrDefault(order.getOrderId(), 0);
            if (actual != expected) {
                throw exception(ALGORITHM_RESULT_INVALID,
                        "订单 " + order.getOrderId() + " 出现 " + actual + " 次，期望 " + expected + " 次");
            }
        }

        // 所有 shipment 必须被完整覆盖：PICKUP + DELIVERY 各一次
        for (AlgorithmShipmentDTO shipment : nullSafe(request.getShipments())) {
            int pickups = shipmentPickupCount.getOrDefault(shipment.getShipmentId(), 0);
            int deliveries = shipmentDeliveryCount.getOrDefault(shipment.getShipmentId(), 0);
            if (pickups != 1 || deliveries != 1) {
                throw exception(ALGORITHM_RESULT_INVALID,
                        "shipment " + shipment.getShipmentId() + " 覆盖不完整：PICKUP=" + pickups + " DELIVERY=" + deliveries);
            }
        }

        // 距离校验
        if (response.getTotalDistance() != null && response.getTotalDistance() < 0) {
            throw exception(ALGORITHM_RESULT_INVALID, "总里程为负值：" + response.getTotalDistance());
        }
    }

    private static void validateVehiclePlan(AlgorithmPlanReqDTO request, AlgorithmVehiclePlanDTO plan,
                                            Set<String> stationIds, Map<Long, AlgorithmVehicleDTO> vehicleMap,
                                            Map<String, AlgorithmOrderDTO> orderMap,
                                            Map<String, AlgorithmShipmentDTO> shipmentMap,
                                            Map<String, Integer> orderStopCount,
                                            Map<String, Integer> shipmentPickupCount,
                                            Map<String, Integer> shipmentDeliveryCount) {
        AlgorithmVehicleDTO vehicle = vehicleMap.get(plan.getVehicleId());
        if (vehicle == null) {
            throw exception(ALGORITHM_RESULT_INVALID, "方案引用了快照外的车辆 " + plan.getVehicleId());
        }
        List<AlgorithmRouteStopDTO> stops = plan.getStops();
        if (CollectionUtils.isEmpty(stops)) {
            throw exception(ALGORITHM_RESULT_INVALID, "车辆 " + plan.getVehicleId() + " 方案经停序列为空");
        }
        AlgorithmRouteStopDTO first = stops.get(0);
        if (!AlgorithmRouteStopDTO.ACTION_DEPART.equals(first.getAction())
                || !Objects.equals(request.getDepot().getStationId(), first.getStationId())) {
            throw exception(ALGORITHM_RESULT_INVALID, "车辆 " + plan.getVehicleId() + " 未从场站出发");
        }
        AlgorithmRouteStopDTO last = stops.get(stops.size() - 1);
        if (!AlgorithmRouteStopDTO.ACTION_RETURN.equals(last.getAction())
                || !Objects.equals(request.getDepot().getStationId(), last.getStationId())) {
            throw exception(ALGORITHM_RESULT_INVALID, "车辆 " + plan.getVehicleId() + " 未返回场站");
        }

        // 初始载荷
        int passengers = vehicle.getInitialPassengerLoad() != null ? vehicle.getInitialPassengerLoad() : 0;
        int cargo = vehicle.getInitialCargoLoad() != null ? vehicle.getInitialCargoLoad() : 0;

        // 静态闭环：派送件在场站装车，出发时即占用货仓容量
        for (AlgorithmRouteStopDTO stop : stops) {
            if (AlgorithmOrderDTO.TYPE_DELIVERY.equals(orderTypeOf(stop, orderMap))) {
                cargo += itemCountOf(stop, orderMap);
            }
        }
        Map<String, Integer> passengerActionIndex = new HashMap<>();

        // Skeleton 验证：收集 PASS 站点，检查顺序
        List<String> passStops = new ArrayList<>();

        for (int i = 0; i < stops.size(); i++) {
            AlgorithmRouteStopDTO stop = stops.get(i);
            if (!stationIds.contains(stop.getStationId())) {
                throw exception(ALGORITHM_RESULT_INVALID, "经停点引用了快照外的站点 " + stop.getStationId());
            }
            String action = stop.getAction();
            if (AlgorithmRouteStopDTO.ACTION_DEPART.equals(action) || AlgorithmRouteStopDTO.ACTION_RETURN.equals(action)) {
                if (stop.getOrderId() != null) {
                    throw exception(ALGORITHM_RESULT_INVALID, "场站起止点不得关联订单");
                }
                if (AlgorithmRouteStopDTO.ACTION_DEPART.equals(action) && i != 0) {
                    throw exception(ALGORITHM_RESULT_INVALID, "车辆 " + plan.getVehicleId() + " 出现序列中途出发动作");
                }
                if (AlgorithmRouteStopDTO.ACTION_RETURN.equals(action) && i != stops.size() - 1) {
                    throw exception(ALGORITHM_RESULT_INVALID, "车辆 " + plan.getVehicleId() + " 出现序列中途返回动作");
                }
                continue;
            }
            // PASS 动作：不关联订单，不参与覆盖统计，不增减载荷
            if (AlgorithmRouteStopDTO.ACTION_PASS.equals(action)) {
                if (stop.getOrderId() != null) {
                    throw exception(ALGORITHM_RESULT_INVALID, "PASS 经停不得关联订单");
                }
                passStops.add(stop.getStationId());
                continue;
            }
            // 非 PASS 动作需要关联订单
            AlgorithmOrderDTO order = orderMap.get(stop.getOrderId());
            if (order == null) {
                // 检查是否为 shipment 的 orderId
                if (!shipmentMap.containsKey(stop.getOrderId())) {
                    throw exception(ALGORITHM_RESULT_INVALID, "经停点引用了快照外的订单 " + stop.getOrderId());
                }
            }
            if (order != null) {
                orderStopCount.merge(order.getOrderId(), 1, Integer::sum);
            }
            switch (action) {
                case AlgorithmRouteStopDTO.ACTION_BOARD -> {
                    requirePassengerStop(order, stop, order.getBoardingStationId(), plan);
                    passengers += 1;
                    if (passengerActionIndex.put(order.getOrderId() + "#BOARD", i) != null) {
                        throw exception(ALGORITHM_RESULT_INVALID, "客运订单 " + order.getOrderId() + " 重复上车");
                    }
                }
                case AlgorithmRouteStopDTO.ACTION_ALIGHT -> {
                    requirePassengerStop(order, stop, order.getAlightingStationId(), plan);
                    passengers -= 1;
                    if (passengerActionIndex.putIfAbsent(order.getOrderId() + "#ALIGHT", i) != null) {
                        throw exception(ALGORITHM_RESULT_INVALID, "客运订单 " + order.getOrderId() + " 重复下车");
                    }
                    Integer boardIndex = passengerActionIndex.get(order.getOrderId() + "#BOARD");
                    if (boardIndex == null || boardIndex >= i) {
                        throw exception(ALGORITHM_RESULT_INVALID, "客运订单 " + order.getOrderId() + " 下车先于上车");
                    }
                }
                case AlgorithmRouteStopDTO.ACTION_DELIVER -> {
                    if (order != null) {
                        requireCargoStop(order, stop, AlgorithmOrderDTO.TYPE_DELIVERY, plan);
                        cargo -= itemCountOf(stop, orderMap);
                    } else {
                        // Shipment DELIVERY
                        String sid = stop.getOrderId();
                        AlgorithmShipmentDTO shipment = shipmentMap.get(sid);
                        if (shipment == null) {
                            throw exception(ALGORITHM_RESULT_INVALID, "经停点引用了快照外的订单/shipment " + sid);
                        }
                        if (!Objects.equals(shipment.getDeliveryStationId(), stop.getStationId())) {
                            throw exception(ALGORITHM_RESULT_INVALID,
                                    "shipment " + sid + " DELIVERY 站点不匹配：期望 " + shipment.getDeliveryStationId());
                        }
                        cargo -= shipment.getQuantity() != null ? shipment.getQuantity() : 1;
                        shipmentDeliveryCount.merge(sid, 1, Integer::sum);
                    }
                }
                case AlgorithmRouteStopDTO.ACTION_PICKUP -> {
                    if (order != null) {
                        requireCargoStop(order, stop, AlgorithmOrderDTO.TYPE_PICKUP, plan);
                        cargo += itemCountOf(stop, orderMap);
                    } else {
                        // Shipment PICKUP
                        String sid = stop.getOrderId();
                        AlgorithmShipmentDTO shipment = shipmentMap.get(sid);
                        if (shipment == null) {
                            throw exception(ALGORITHM_RESULT_INVALID, "经停点引用了快照外的订单/shipment " + sid);
                        }
                        if (!Objects.equals(shipment.getPickupStationId(), stop.getStationId())) {
                            throw exception(ALGORITHM_RESULT_INVALID,
                                    "shipment " + sid + " PICKUP 站点不匹配：期望 " + shipment.getPickupStationId());
                        }
                        cargo += shipment.getQuantity() != null ? shipment.getQuantity() : 1;
                        shipmentPickupCount.merge(sid, 1, Integer::sum);
                    }
                }
                default -> throw exception(ALGORITHM_RESULT_INVALID, "未知经停动作 " + action);
            }
            int passengerCapacity = vehicle.getPassengerCapacity() != null
                    ? vehicle.getPassengerCapacity() : AlgorithmVehicleDTO.DEFAULT_PASSENGER_CAPACITY;
            int cargoCapacity = vehicle.getCargoCapacity() != null
                    ? vehicle.getCargoCapacity() : AlgorithmVehicleDTO.DEFAULT_CARGO_CAPACITY;
            if (passengers < 0 || passengers > passengerCapacity || cargo < 0 || cargo > cargoCapacity) {
                throw exception(ALGORITHM_RESULT_INVALID,
                        "车辆 " + plan.getVehicleId() + " 容量越界（载客 " + passengers + "/" + passengerCapacity
                                + "，载货 " + cargo + "/" + cargoCapacity + "）");
            }
            // 段距离校验
            if (stop.getSegmentDistance() != null && stop.getSegmentDistance() < 0) {
                throw exception(ALGORITHM_RESULT_INVALID,
                        "车辆 " + plan.getVehicleId() + " 段距离为负值：" + stop.getSegmentDistance());
            }
        }

        // Skeleton 顺序校验
        validateSkeleton(vehicle, passStops, plan);
    }

    /**
     * Skeleton 顺序校验：骨架站点必须在 PASS 动作中按原顺序出现，允许货运站点插入其间。
     */
    private static void validateSkeleton(AlgorithmVehicleDTO vehicle, List<String> passStops,
                                         AlgorithmVehiclePlanDTO plan) {
        List<String> skeleton = vehicle.getSkeleton();
        if (CollectionUtils.isEmpty(skeleton)) {
            return;
        }
        int skeletonIndex = 0;
        for (String passStationId : passStops) {
            if (skeletonIndex < skeleton.size() && Objects.equals(passStationId, skeleton.get(skeletonIndex))) {
                skeletonIndex++;
            }
        }
        if (skeletonIndex < skeleton.size()) {
            throw exception(ALGORITHM_RESULT_INVALID,
                    "车辆 " + plan.getVehicleId() + " 未按骨架顺序经停：缺少 " + skeleton.get(skeletonIndex)
                            + "（已匹配 " + skeletonIndex + "/" + skeleton.size() + "）");
        }
    }

    private static void requirePassengerStop(AlgorithmOrderDTO order, AlgorithmRouteStopDTO stop,
                                             String expectedStationId, AlgorithmVehiclePlanDTO plan) {
        if (order == null || !AlgorithmOrderDTO.TYPE_PASSENGER.equals(order.getOrderType())
                || !Objects.equals(expectedStationId, stop.getStationId())) {
            throw exception(ALGORITHM_RESULT_INVALID,
                    "车辆 " + plan.getVehicleId() + " 中订单 " + stop.getOrderId() + " 的上下车站点与快照不符");
        }
    }

    private static void requireCargoStop(AlgorithmOrderDTO order, AlgorithmRouteStopDTO stop,
                                         String expectedType, AlgorithmVehiclePlanDTO plan) {
        if (!expectedType.equals(order.getOrderType()) || !Objects.equals(order.getStationId(), stop.getStationId())) {
            throw exception(ALGORITHM_RESULT_INVALID,
                    "车辆 " + plan.getVehicleId() + " 中订单 " + order.getOrderId() + " 的作业站点或动作与快照不符");
        }
    }

    private static String orderTypeOf(AlgorithmRouteStopDTO stop, Map<String, AlgorithmOrderDTO> orderMap) {
        AlgorithmOrderDTO order = stop.getOrderId() == null ? null : orderMap.get(stop.getOrderId());
        return order == null ? null : order.getOrderType();
    }

    private static int itemCountOf(AlgorithmRouteStopDTO stop, Map<String, AlgorithmOrderDTO> orderMap) {
        AlgorithmOrderDTO order = orderMap.get(stop.getOrderId());
        if (order == null) {
            return 0;
        }
        return order.getItemCount() != null ? order.getItemCount() : 1;
    }

    private static <T> List<T> nullSafe(List<T> list) {
        return list == null ? Collections.emptyList() : list;
    }

}
