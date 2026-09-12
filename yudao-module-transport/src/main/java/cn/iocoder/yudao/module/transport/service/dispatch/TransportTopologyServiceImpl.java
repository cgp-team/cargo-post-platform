package cn.iocoder.yudao.module.transport.service.dispatch;

import cn.iocoder.yudao.module.transport.controller.admin.transport.topology.vo.OrderTopologyRespVO;
import cn.iocoder.yudao.module.transport.dal.dataobject.dispatch.DispatchPlanDO;
import cn.iocoder.yudao.module.transport.dal.dataobject.dispatch.TransportHandoverDO;
import cn.iocoder.yudao.module.transport.dal.dataobject.dispatch.TransportLegDO;
import cn.iocoder.yudao.module.transport.dal.dataobject.dispatch.TransportOrderEventDO;
import cn.iocoder.yudao.module.transport.dal.dataobject.driver.DriverDO;
import cn.iocoder.yudao.module.transport.dal.dataobject.order.TransportOrderDO;
import cn.iocoder.yudao.module.transport.dal.dataobject.station.StationDO;
import cn.iocoder.yudao.module.transport.dal.dataobject.vehicle.VehicleDO;
import cn.iocoder.yudao.module.transport.dal.mysql.dispatch.DispatchPlanMapper;
import cn.iocoder.yudao.module.transport.dal.mysql.driver.DriverMapper;
import cn.iocoder.yudao.module.transport.dal.mysql.order.TransportOrderMapper;
import cn.iocoder.yudao.module.transport.dal.mysql.station.StationMapper;
import cn.iocoder.yudao.module.transport.dal.mysql.vehicle.VehicleMapper;
import cn.iocoder.yudao.module.transport.enums.dispatch.DispatchPlanningModeEnum;
import cn.iocoder.yudao.module.transport.enums.dispatch.TransportHandoverStatusEnum;
import cn.iocoder.yudao.module.transport.enums.dispatch.TransportLegStatusEnum;
import cn.iocoder.yudao.module.transport.enums.dispatch.TransportOrderStatusEnum;
import cn.iocoder.yudao.module.transport.service.order.OrderEventService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.validation.annotation.Validated;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.module.transport.enums.ErrorCodeConstants.ORDER_NOT_EXISTS;

/**
 * 运输拓扑聚合实现：把 Order / Plan / Legs / Handovers / 候选解释 / 时间线拼成一次响应。
 */
@Service
@Validated
@Slf4j
public class TransportTopologyServiceImpl implements TransportTopologyService {

    @Resource private TransportOrderMapper orderMapper;
    @Resource private DispatchPlanMapper dispatchPlanMapper;
    @Resource private StationMapper stationMapper;
    @Resource private DriverMapper driverMapper;
    @Resource private VehicleMapper vehicleMapper;
    @Resource private MultiLegService multiLegService;
    @Resource private HandoverService handoverService;
    @Resource private OrderEventService orderEventService;

    @Override
    public OrderTopologyRespVO getByOrderId(Long orderId) {
        TransportOrderDO order = orderMapper.selectById(orderId);
        if (order == null) {
            throw exception(ORDER_NOT_EXISTS);
        }
        List<TransportLegDO> legs = multiLegService.getLegsByOrderId(orderId);
        List<TransportHandoverDO> handovers = handoverService.getByOrder(orderId);
        DispatchPlanDO plan = legs.stream().map(TransportLegDO::getPlanId).filter(Objects::nonNull)
                .findFirst().map(dispatchPlanMapper::selectById).orElse(null);
        return build(order, plan, legs, handovers);
    }

    @Override
    public OrderTopologyRespVO getByPlanId(Long planId) {
        DispatchPlanDO plan = dispatchPlanMapper.selectById(planId);
        List<TransportLegDO> legs = multiLegService.getLegsByPlanId(planId);
        List<TransportHandoverDO> handovers = handoverService.getByPlan(planId);
        TransportOrderDO order = legs.stream().map(TransportLegDO::getOrderId).filter(Objects::nonNull)
                .findFirst().map(orderMapper::selectById).orElse(null);
        OrderTopologyRespVO vo = build(order, plan, legs, handovers);
        if (vo != null && vo.getOrderId() == null && plan != null) {
            vo.setPlanNo(plan.getPlanNo());
        }
        return vo;
    }

    private OrderTopologyRespVO build(TransportOrderDO order, DispatchPlanDO plan,
                                      List<TransportLegDO> legs, List<TransportHandoverDO> handovers) {
        OrderTopologyRespVO vo = new OrderTopologyRespVO();
        Map<Long, StationDO> stations = loadStations(legs, order);
        Map<Long, String> stationNames = stations.entrySet().stream()
                .collect(Collectors.toMap(Map.Entry::getKey,
                        e -> e.getValue().getStationName() != null ? e.getValue().getStationName() : "", (a, b) -> a));
        if (order != null) {
            vo.setOrderId(order.getId());
            vo.setOrderNo(order.getOrderNo());
            vo.setOrderStatus(order.getStatus());
            vo.setOrderStatusName(TransportOrderStatusEnum.nameOf(order.getStatus()));
            vo.setOriginStationName(stationNames.get(order.getPickupStationId()));
            vo.setDestinationStationName(stationNames.get(order.getDeliveryStationId()));
        }
        if (plan != null) {
            vo.setPlanNo(plan.getPlanNo());
            vo.setPlanningMode(plan.getPlanningMode());
            vo.setPlanningModeName(DispatchPlanningModeEnum.nameOf(plan.getPlanningMode()));
            vo.setTotalLegs(plan.getTotalLegCount());
            vo.setTransferCount(plan.getTransferCount());
            vo.setTotalDistanceKm(plan.getTotalDistance());
            vo.setTotalDurationMinutes(plan.getEstDurationMinutes());
            vo.setPlanReason(plan.getPlanReason());
        }
        vo.setLegs(legs.stream().map(l -> toLeg(l, stationNames, stations)).toList());
        // P2-N：totalLegs/transferCount 以「实际运输段」为准（前端曾因方案字段为 0 而靠 legs.length 兜底）。
        // 单订单视角：换乘次数 = 段数 - 1；方案级多订单：段数 - 订单数。
        if (!legs.isEmpty()) {
            long distinctOrders = legs.stream().map(TransportLegDO::getOrderId)
                    .filter(Objects::nonNull).distinct().count();
            vo.setTotalLegs(legs.size());
            vo.setTransferCount((int) Math.max(0, legs.size() - Math.max(1, distinctOrders)));
        }
        // 总里程统一口径：有运输段时按"各段真实路网里程之和"展示（与 Leg/地图一致），无段时回退方案侧口径
        java.math.BigDecimal legDistanceSum = legs.stream().map(TransportLegDO::getDistanceKm)
                .filter(Objects::nonNull).reduce(java.math.BigDecimal.ZERO, java.math.BigDecimal::add);
        if (legDistanceSum.compareTo(java.math.BigDecimal.ZERO) > 0) {
            vo.setTotalDistanceKm(legDistanceSum);
        }
        // 总时长同理：各段时长 + 换乘停留，避免"方案侧估算时长"与分段时长不一致
        int legDurationSum = legs.stream().map(TransportLegDO::getDurationMinutes)
                .filter(Objects::nonNull).mapToInt(Integer::intValue).sum();
        if (legDurationSum > 0) {
            vo.setTotalDurationMinutes(legDurationSum + Math.max(0, legs.size() - 1) * 20);
        }
        vo.setHandovers(handovers.stream().map(h -> toHandover(h, stationNames)).toList());
        // 候选方案解释（直达/两段/三段 + 为什么选它）
        if (order != null) {
            try {
                MultiLegPlanner.PlanResult preview = multiLegService.preview(order.getId());
                boolean chosenMulti = preview.isMultiLeg();
                List<OrderTopologyRespVO.Candidate> candidates = new ArrayList<>();
                for (MultiLegPlanner.Candidate c : preview.candidates()) {
                    OrderTopologyRespVO.Candidate cv = new OrderTopologyRespVO.Candidate();
                    cv.setMode(c.mode());
                    cv.setModeName(c.modeName());
                    cv.setLegCount(c.legCount());
                    cv.setTransferCount(c.transferCount());
                    cv.setDistanceKm(c.distanceKm());
                    cv.setDurationMinutes(c.durationMinutes());
                    cv.setScore(Math.round(c.score() * 100) / 100.0);
                    cv.setReason(c.reason());
                    cv.setChosen(Objects.equals(c.mode(), preview.mode())
                            && c.legCount() == preview.legCount()
                            && Objects.equals(c.transferStationId(), preview.transferStationId()));
                    candidates.add(cv);
                }
                vo.setCandidates(candidates);
                if (vo.getPlanReason() == null) {
                    vo.setPlanReason(preview.reason());
                }
                if (vo.getPlanningMode() == null) {
                    vo.setPlanningMode(preview.mode());
                    vo.setPlanningModeName(DispatchPlanningModeEnum.nameOf(preview.mode()));
                    vo.setTotalLegs(preview.legCount());
                    vo.setTransferCount(preview.transferCount());
                }
                // 兜底：多段但方案字段未回写时，用规划结果修正
                if (chosenMulti && vo.getTransferCount() == null) {
                    vo.setTransferCount(preview.transferCount());
                }
            } catch (Exception ex) {
                log.debug("[topology] 订单 {} 候选方案解释不可用：{}", order.getId(), ex.getMessage());
            }
        }
        if (order != null) {
            List<TransportOrderEventDO> events = orderEventService.getTimeline(order.getId());
            vo.setTimeline(events.stream().map(e -> {
                OrderTopologyRespVO.Timeline t = new OrderTopologyRespVO.Timeline();
                t.setEventType(e.getEventType());
                t.setEventTypeName(orderEventService.typeName(e.getEventType()));
                t.setEventTime(e.getEventTime());
                t.setOperator(e.getOperator());
                t.setDetail(e.getDetail());
                return t;
            }).toList());
        }
        return vo;
    }

    private OrderTopologyRespVO.Leg toLeg(TransportLegDO leg, Map<Long, String> stationNames,
                                          Map<Long, StationDO> stations) {
        OrderTopologyRespVO.Leg vo = new OrderTopologyRespVO.Leg();
        vo.setId(leg.getId());
        vo.setLegSequence(leg.getLegSequence());
        vo.setFromStationName(stationNames.get(leg.getFromStationId()));
        vo.setToStationName(stationNames.get(leg.getToStationId()));
        StationDO from = stations.get(leg.getFromStationId());
        StationDO to = stations.get(leg.getToStationId());
        if (from != null && from.getLongitude() != null && from.getLatitude() != null) {
            vo.setFromLongitude(from.getLongitude().doubleValue());
            vo.setFromLatitude(from.getLatitude().doubleValue());
        }
        if (to != null && to.getLongitude() != null && to.getLatitude() != null) {
            vo.setToLongitude(to.getLongitude().doubleValue());
            vo.setToLatitude(to.getLatitude().doubleValue());
        }
        vo.setDriverName(driverName(leg.getDriverId()));
        vo.setPlateNo(plateNo(leg.getVehicleId()));
        vo.setStatus(leg.getStatus());
        vo.setStatusName(TransportLegStatusEnum.nameOf(leg.getStatus()));
        vo.setDistanceKm(leg.getDistanceKm());
        vo.setDurationMinutes(leg.getDurationMinutes());
        vo.setNavigationSource(leg.getNavigationSource());
        vo.setNavigationPolyline(parsePolyline(leg.getNavigationPolyline()));
        vo.setEstimatedDeparture(leg.getEstimatedDeparture());
        vo.setEstimatedArrival(leg.getEstimatedArrival());
        vo.setActualArrival(leg.getActualArrival());
        vo.setHandoverRequired(leg.getHandoverRequired());
        return vo;
    }

    /** 解析 Leg 上存储的紧凑 polyline（"lon,lat;lon,lat;..."）→ 道路点列表（空串/非法返回 null） */
    private static List<OrderTopologyRespVO.RoadPoint> parsePolyline(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        List<OrderTopologyRespVO.RoadPoint> points = new java.util.ArrayList<>();
        for (String segment : raw.split(";")) {
            int comma = segment.indexOf(',');
            if (comma <= 0) {
                continue;
            }
            try {
                OrderTopologyRespVO.RoadPoint p = new OrderTopologyRespVO.RoadPoint();
                p.setLongitude(Double.valueOf(segment.substring(0, comma).trim()));
                p.setLatitude(Double.valueOf(segment.substring(comma + 1).trim()));
                points.add(p);
            } catch (NumberFormatException ignored) {
                // 单点解析失败不影响整条轨迹
            }
        }
        return points.isEmpty() ? null : points;
    }

    private OrderTopologyRespVO.Handover toHandover(TransportHandoverDO h, Map<Long, String> stationNames) {
        OrderTopologyRespVO.Handover vo = new OrderTopologyRespVO.Handover();
        vo.setId(h.getId());
        vo.setStationName(stationNames.get(h.getStationId()));
        vo.setFromDriverName(driverName(h.getFromDriverId()));
        vo.setToDriverName(driverName(h.getToDriverId()));
        vo.setFromPlateNo(plateNo(h.getFromVehicleId()));
        vo.setToPlateNo(plateNo(h.getToVehicleId()));
        vo.setItemCount(h.getItemCount());
        vo.setStatus(h.getStatus());
        vo.setStatusName(TransportHandoverStatusEnum.nameOf(h.getStatus()));
        vo.setArrivedAt(h.getArrivedAt());
        vo.setHandoverStartedAt(h.getHandoverStartedAt());
        vo.setHandoverCompletedAt(h.getHandoverCompletedAt());
        vo.setExceptionReason(h.getExceptionReason());
        return vo;
    }

    private Map<Long, StationDO> loadStations(List<TransportLegDO> legs, TransportOrderDO order) {
        Set<Long> ids = legs.stream()
                .flatMap(l -> java.util.stream.Stream.of(l.getFromStationId(), l.getToStationId()))
                .filter(Objects::nonNull).collect(Collectors.toSet());
        if (order != null) {
            if (order.getPickupStationId() != null) {
                ids.add(order.getPickupStationId());
            }
            if (order.getDeliveryStationId() != null) {
                ids.add(order.getDeliveryStationId());
            }
        }
        if (ids.isEmpty()) {
            return Map.of();
        }
        return stationMapper.selectBatchIds(ids).stream()
                .collect(Collectors.toMap(StationDO::getId, s -> s, (a, b) -> a));
    }

    private String driverName(Long driverId) {
        if (driverId == null) {
            return null;
        }
        DriverDO d = driverMapper.selectById(driverId);
        return d != null ? d.getName() : null;
    }

    private String plateNo(Long vehicleId) {
        if (vehicleId == null) {
            return null;
        }
        VehicleDO v = vehicleMapper.selectById(vehicleId);
        return v != null ? v.getPlateNo() : null;
    }

}
