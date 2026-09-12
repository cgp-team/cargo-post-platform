package cn.iocoder.yudao.module.transport.service.dispatch;

import cn.iocoder.yudao.module.transport.dal.dataobject.dispatch.TransportLegDO;
import cn.iocoder.yudao.module.transport.dal.dataobject.driver.DriverVehicleDO;
import cn.iocoder.yudao.module.transport.dal.dataobject.order.TransportOrderDO;
import cn.iocoder.yudao.module.transport.dal.dataobject.route.RouteStationDO;
import cn.iocoder.yudao.module.transport.dal.dataobject.station.StationDO;
import cn.iocoder.yudao.module.transport.dal.dataobject.vehicle.VehicleDO;
import cn.iocoder.yudao.module.transport.dal.mysql.dispatch.TransportLegMapper;
import cn.iocoder.yudao.module.transport.dal.mysql.driver.DriverVehicleMapper;
import cn.iocoder.yudao.module.transport.dal.mysql.driver.TransportDriverStatusMapper;
import cn.iocoder.yudao.module.transport.dal.mysql.order.TransportOrderMapper;
import cn.iocoder.yudao.module.transport.dal.mysql.route.RouteStationMapper;
import cn.iocoder.yudao.module.transport.dal.mysql.route.RouteMapper;
import cn.iocoder.yudao.module.transport.dal.mysql.shift.ShiftExecutionMapper;
import cn.iocoder.yudao.module.transport.dal.mysql.dispatch.DispatchPlanMapper;
import cn.iocoder.yudao.module.transport.dal.mysql.dispatch.DispatchPlanItemMapper;
import cn.iocoder.yudao.module.transport.dal.dataobject.dispatch.DispatchPlanDO;
import cn.iocoder.yudao.module.transport.dal.dataobject.shift.ShiftExecutionDO;
import cn.iocoder.yudao.module.transport.enums.dispatch.DispatchPlanStatusEnum;
import cn.iocoder.yudao.module.transport.dal.mysql.station.StationMapper;
import cn.iocoder.yudao.module.transport.dal.mysql.vehicle.VehicleMapper;
import cn.iocoder.yudao.module.transport.enums.dispatch.TransportLegStatusEnum;
import cn.iocoder.yudao.module.transport.enums.dispatch.TransportOrderEventTypeEnum;
import cn.iocoder.yudao.module.transport.integration.algorithm.AlgorithmClient;
import cn.iocoder.yudao.module.transport.integration.algorithm.dto.AlgorithmRouteReqDTO;
import cn.iocoder.yudao.module.transport.integration.algorithm.dto.AlgorithmRouteRespDTO;
import cn.iocoder.yudao.module.transport.service.notification.UserNotificationService;
import cn.iocoder.yudao.module.transport.service.order.OrderEventService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.annotation.Validated;

import java.time.LocalDateTime;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.module.transport.enums.ErrorCodeConstants.*;

/**
 * 澶氭�佃仈杩愭湇鍔″疄鐜帮細瑙勫垝锛堝�旀墭 {@link MultiLegPlanner}锛夆啋 钀藉簱杩愯緭娈?鈫?鐘舵�佹満鎺ㄨ繘 + 璧勬簮鐘舵�佸悓姝ャ�?
 */
@Service
@Validated
@Slf4j
public class MultiLegServiceImpl implements MultiLegService {

    @Resource private TransportLegMapper legMapper;
    @Resource private TransportOrderMapper orderMapper;
    @Resource private StationMapper stationMapper;
    @Resource private RouteStationMapper routeStationMapper;
    @Resource private RouteMapper routeMapper;
    @Resource private DriverVehicleMapper driverVehicleMapper;
    @Resource private VehicleMapper vehicleMapper;
    @Resource private TransportDriverStatusMapper driverStatusMapper;
    @Resource private MultiLegPlanner multiLegPlanner;
    @Resource private LegConflictService legConflictService;
    @Resource private ShiftExecutionMapper shiftExecutionMapper;
    @Resource private DispatchPlanMapper dispatchPlanMapper;
    @Resource private DispatchPlanItemMapper dispatchPlanItemMapper;
    @Resource private AlgorithmClient algorithmClient;
    @Resource private OrderEventService orderEventService;
    @Resource private UserNotificationService userNotificationService;
    /** 杞﹁締褰撳墠浣嶇疆锛圧EAL > 妯℃嫙寮曟搸 > 鐝�娆℃彃鍊硷級锛氬�氭�佃仈杩愭寜"璋佺�绘湰娈佃捣鐐硅�?鏀规淳锛岄伩鍏嶄竴鍙拌溅璺ㄥ煄寰�杩?*/
    @Resource private cn.iocoder.yudao.module.transport.service.monitoring.VehicleLocationProvider vehicleLocationProvider;

    @Override
    public List<TransportLegDO> planLegs(Long orderId) {
        return planLegs(orderId, null, null, null);
    }

    @Override
    public List<TransportLegDO> planLegs(Long orderId, Long planId) {
        return planLegs(orderId, planId, null, null);
    }

    @Override
    // REQUIRES_NEW锛氳皟鐢ㄦ柟 DispatchServiceImpl.createSmartPlan 浼氬悶鎺夋�佃�勫垝寮傚父锛堝�氭�垫槸澧炲己鑳藉姏锛?
    // 涓嶈�ヨ�╂暣鍗曡皟搴﹀け璐ワ級銆傝嫢鍙備笌澶栧眰浜嬪姟锛屽紓甯镐細鎶婂叡浜�浜嬪姟鏍囪�?rollback-only锛屽�艰嚧澶栧眰鎻愪氦鏃�
    // 鎶?UnexpectedRollbackException锛涚嫭绔嬩簨鍔″彲淇濊瘉"娈佃�勫垝澶辫触鍙�鍥炴粴鑷�宸憋紝涓嶅奖鍝嶅凡鐢熸垚鐨勭洿杈炬柟妗�"銆?
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public List<TransportLegDO> planLegs(Long orderId, Long planId, Long planVehicleId, Long planDriverId) {
        TransportOrderDO order = orderMapper.selectById(orderId);
        if (order == null) {
            throw exception(ORDER_NOT_EXISTS);
        }
        List<TransportLegDO> existing = legMapper.selectListByOrderId(orderId);
        if (!existing.isEmpty()) {
            return existing; // 骞傜瓑锛氶噸澶嶈皟搴︿笉閲嶅�嶆媶娈�
        }
        StationDO pickup = stationMapper.selectById(order.getPickupStationId());
        StationDO delivery = stationMapper.selectById(order.getDeliveryStationId());
        if (pickup == null || delivery == null) {
            throw exception(STATION_NOT_EXISTS);
        }
        MultiLegPlanner.PlanResult result = multiLegPlanner.plan(order, pickup, delivery,
                stationMapper.selectList(), routeStationsForPlanning());

        List<TransportLegDO> legs = new ArrayList<>();
        for (MultiLegPlanner.LegDraft draft : result.legs()) {
            TransportLegDO leg = TransportLegDO.builder()
                    .orderId(order.getId())
                    .planId(planId)
                    .legSequence(draft.sequence())
                    .fromStationId(draft.fromStationId())
                    .toStationId(draft.toStationId())
                    .distanceKm(BigDecimal.valueOf(draft.distanceKm()))
                    .durationMinutes(draft.durationMinutes())
                    .navigationSource("ESTIMATED")
                    .status(TransportLegStatusEnum.PLANNED.getStatus())
                    .handoverRequired(draft.handoverRequired())
                    .build();
            legs.add(leg);
        }
        // 鐪熷疄閬撹矾锛氶�愭�靛彇楂樺痉璺�缃戯紙璺濈��/鏃堕暱/polyline锛夛紝澶辫触淇濇寔 ESTIMATED锛堜笉浼�瑁呯湡瀹為亾璺�锛岄渶姹?搂73/搂141锛?
        enrichWithRoadRoute(legs);
        // 棰勮�℃椂闂村熀浜庢渶缁堟椂闀匡紙鍙�鑳芥槸璺�缃戞椂闀匡級椤哄簭鎺ㄨ繘
        LocalDateTime cursor = LocalDateTime.now().plusMinutes(MultiLegPlanner.PREPARE_MINUTES);
        for (TransportLegDO leg : legs) {
            int minutes = leg.getDurationMinutes() != null ? leg.getDurationMinutes()
                    : MultiLegPlanner.travelMinutes(leg.getDistanceKm() == null ? 0 : leg.getDistanceKm().doubleValue());
            leg.setEstimatedDeparture(cursor);
            leg.setEstimatedArrival(cursor.plusMinutes(minutes));
            cursor = leg.getEstimatedArrival().plusMinutes(MultiLegPlanner.HANDOVER_DWELL_MINUTES);
        }
        assignVehicles(legs, orderId, planId, planVehicleId, planDriverId);
        relayFarLegsToNearbyVehicles(legs);
        for (TransportLegDO leg : legs) {
            legMapper.insert(leg);
        }
        orderEventService.record(orderId, TransportOrderEventTypeEnum.PLAN_CREATED,
                result.reason(), "{\"legCount\":" + result.legCount() + ",\"transferCount\":"
                        + result.transferCount() + ",\"mode\":\"" + result.mode() + "\"}");
        userNotificationService.sendToOrderUser(orderId, TransportOrderEventTypeEnum.PLAN_CREATED,
                "宸茬敓鎴愯繍杈撴柟妗?, result.reason());
        return legs;
    }

    /**
     * 閫愭�佃ˉ鐪熷疄閬撹矾杞ㄨ抗锛堥渶姹?搂73/搂74/搂141锛夛細
     * 楂樺痉璺�缃戝彲鐢� 鈫?navigationSource=AMAP + 瀛樺偍 polyline锛?lon,lat;..." 绱у噾涓诧級+ 鐢ㄧ湡瀹炶窛绂?鏃堕暱瑕嗙洊浼扮畻锛?
     * 澶辫触/涓嶅彲鐢?鈫?淇濇寔 ESTIMATED锛岀晫闈㈡寜"浼扮畻鍊?灞曠ず锛岀粷涓嶄吉瑁呮垚瀹炴椂閬撹矾瀵艰埅銆?
     * 娈垫暟 鈮?锛屼笖绠楁硶渚ф湁 24h 璺�缃戠紦瀛橈紝鎴愭湰鍙�鎺с�?
     */
    private void enrichWithRoadRoute(List<TransportLegDO> legs) {
        if (algorithmClient == null || legs.isEmpty()) {
            return;
        }
        java.util.Map<Long, StationDO> stationMap = legs.stream()
                .flatMap(l -> java.util.stream.Stream.of(l.getFromStationId(), l.getToStationId()))
                .filter(Objects::nonNull).distinct()
                .map(stationMapper::selectById).filter(Objects::nonNull)
                .collect(java.util.stream.Collectors.toMap(StationDO::getId, s -> s, (a, b) -> a));
        for (TransportLegDO leg : legs) {
            StationDO from = stationMap.get(leg.getFromStationId());
            StationDO to = stationMap.get(leg.getToStationId());
            if (from == null || to == null || from.getLongitude() == null || from.getLatitude() == null
                    || to.getLongitude() == null || to.getLatitude() == null) {
                continue;
            }
            try {
                AlgorithmRouteRespDTO route = algorithmClient.route(AlgorithmRouteReqDTO.builder()
                        .origin(AlgorithmRouteReqDTO.RoutePoint.builder()
                                .latitude(from.getLatitude().doubleValue())
                                .longitude(from.getLongitude().doubleValue()).build())
                        .destination(AlgorithmRouteReqDTO.RoutePoint.builder()
                                .latitude(to.getLatitude().doubleValue())
                                .longitude(to.getLongitude().doubleValue()).build())
                        .build());
                if (route == null || route.getPolyline() == null || route.getPolyline().isEmpty()) {
                    continue;
                }
                leg.setNavigationPolyline(route.getPolyline().stream()
                        .map(p -> p.getLongitude() + "," + p.getLatitude())
                        .collect(java.util.stream.Collectors.joining(";")));
                leg.setNavigationSource("amap".equalsIgnoreCase(route.getProvider()) ? "AMAP" : "ESTIMATED");
                if (route.getDistanceKm() != null) {
                    leg.setDistanceKm(BigDecimal.valueOf(Math.round(route.getDistanceKm() * 100) / 100.0));
                }
                if (route.getDurationSeconds() != null) {
                    leg.setDurationMinutes(Math.max(1, (int) Math.round(route.getDurationSeconds() / 60)));
                }
            } catch (Exception ex) {
                log.debug("[multi-leg] 璁㈠崟 {} 绗?{} 娈电湡瀹為亾璺�涓嶅彲鐢�锛屼繚鐣欎及绠楋細{}",
                        leg.getOrderId(), leg.getLegSequence(), ex.getMessage());
            }
        }
    }

    @Override
    public MultiLegPlanner.PlanResult preview(Long orderId) {
        TransportOrderDO order = orderMapper.selectById(orderId);
        if (order == null) {
            throw exception(ORDER_NOT_EXISTS);
        }
        StationDO pickup = stationMapper.selectById(order.getPickupStationId());
        StationDO delivery = stationMapper.selectById(order.getDeliveryStationId());
        if (pickup == null || delivery == null) {
            throw exception(STATION_NOT_EXISTS);
        }
        return multiLegPlanner.plan(order, pickup, delivery,
                stationMapper.selectList(), routeStationsForPlanning());
    }

    /**
     * 渚涜�勫垝浣跨敤鐨勭嚎璺�绔欑偣锛氬彧淇濈暀"鍚�鐢ㄤ笖鍙�鐢ㄤ簬璋冨害"鐨勭嚎璺�锛堥渶姹?搂38锛?
     * 鍋滅敤绾胯矾涓嶅緱鐢ㄤ簬璋冨害/瀵艰埅锛夛紝閬垮厤鎷垮仠鐢ㄧ嚎璺�褰撴崲涔橀�氶亾銆?
     */
    private List<RouteStationDO> routeStationsForPlanning() {
        List<RouteStationDO> all = routeStationMapper.selectList();
        if (routeMapper == null) {
            return all;
        }
        java.util.Set<Long> enabledRouteIds = new java.util.HashSet<>(routeMapper.selectEnabledDispatchRouteIds());
        return all.stream().filter(rs -> enabledRouteIds.contains(rs.getRouteId())).toList();
    }

    @Override
    public List<TransportLegDO> getLegsByOrderId(Long orderId) {
        return orderId == null ? List.of() : legMapper.selectListByOrderId(orderId);
    }

    @Override
    public List<TransportLegDO> getLegsByPlanId(Long planId) {
        return planId == null ? List.of() : legMapper.selectListByPlanId(planId);
    }

    @Override
    public TransportLegDO getLeg(Long legId) {
        TransportLegDO leg = legMapper.selectById(legId);
        if (leg == null) {
            throw exception(LEG_NOT_EXISTS);
        }
        return leg;
    }

    @Override
    @Transactional
    public void advanceLegStatus(Long legId, TransportLegStatusEnum targetStatus) {
        TransportLegDO leg = getLeg(legId);
        TransportLegStatusEnum current = TransportLegStatusEnum.of(leg.getStatus());
        if (current == targetStatus) {
            return; // 骞傜瓑
        }
        // 鐘舵�佹満瀹堝崼锛氱�佹�㈣烦绾э紙闇�姹?搂6/搂115锛屽��"杩愯緭涓?涓嶈兘鐩存帴"宸插畬鎴?锛?
        if (!TransportLegStatusEnum.canTransit(current, targetStatus)) {
            throw exception(LEG_TRANSITION_ILLEGAL,
                    (current == null ? "鏈�鐭�" : current.getName()) + " 鈫?" + targetStatus.getName());
        }
        applyLegStatus(leg, targetStatus, null);
    }

    @Override
    @Transactional
    public void forceLegStatus(Long legId, TransportLegStatusEnum targetStatus, String reason) {
        TransportLegDO leg = getLeg(legId);
        if (Objects.equals(leg.getStatus(), targetStatus.getStatus())) {
            return;
        }
        applyLegStatus(leg, targetStatus, reason);
    }

    @Override
    public boolean needMultiLeg(Long orderId) {
        return preview(orderId).isMultiLeg();
    }

    @Override
    @Transactional
    public TransportLegDO replanLeg(Long legId, String reason) {
        TransportLegDO leg = getLeg(legId);
        List<DriverVehicleDO> bindings = driverVehicleMapper == null
                ? List.of() : driverVehicleMapper.selectActiveBindings();
        DriverVehicleDO chosen = null;
        for (DriverVehicleDO binding : bindings) {
            // 鎺掗櫎褰撳墠锛堟晠闅?寮傚父锛夎祫婧愶紝骞跺湪鍚屼竴鏃舵�垫棤鍐茬�?
            if (Objects.equals(binding.getVehicleId(), leg.getVehicleId())
                    && Objects.equals(binding.getDriverId(), leg.getDriverId())) {
                continue;
            }
            boolean conflict = legConflictService != null && (legConflictService.vehicleConflicts(
                    binding.getVehicleId(), leg.getEstimatedDeparture(), leg.getEstimatedArrival(), List.of(), legId)
                    || legConflictService.driverConflicts(binding.getDriverId(), leg.getEstimatedDeparture(),
                    leg.getEstimatedArrival(), List.of(), legId));
            if (!conflict) {
                chosen = binding;
                break;
            }
        }
        if (chosen == null) {
            // 鏂规�堢疆寮傚父锛岀瓑浜哄伐浠嬪叆锛堥渶姹?搂108锛?
            if (dispatchPlanMapper != null && leg.getPlanId() != null) {
                DispatchPlanDO planUpdate = new DispatchPlanDO();
                planUpdate.setId(leg.getPlanId());
                planUpdate.setStatus(DispatchPlanStatusEnum.EXCEPTION.getStatus());
                dispatchPlanMapper.updateById(planUpdate);
            }
            orderEventService.record(leg.getOrderId(), TransportOrderEventTypeEnum.ORDER_EXCEPTION,
                    "绗?" + leg.getLegSequence() + " 娈甸噸璋冨害澶辫触锛氬綋鍓嶆棤鍙�璋冨害杞﹁�?鍙告満");
            userNotificationService.sendToAdmin(TransportOrderEventTypeEnum.ORDER_EXCEPTION,
                    cn.iocoder.yudao.module.transport.enums.notification.NotificationLevelEnum.EXCEPTION,
                    "閲嶈皟搴﹀け璐?, "璁㈠崟 " + leg.getOrderId() + " 绗?" + leg.getLegSequence() + " 娈垫棤鍙�璋冨害璧勬�?,
                    leg.getOrderId(), legId);
            throw exception(NO_AVAILABLE_RESOURCE);
        }
        Long fromVehicle = leg.getVehicleId();
        Long fromDriver = leg.getDriverId();
        TransportLegDO update = new TransportLegDO();
        update.setId(legId);
        update.setVehicleId(chosen.getVehicleId());
        update.setDriverId(chosen.getDriverId());
        update.setStatus(TransportLegStatusEnum.ASSIGNED.getStatus());
        legMapper.updateById(update);
        // 寮傚父璧勬簮閲婃斁
        releaseVehicle(fromVehicle);
        // 鍒嗛厤鏂拌祫婧愬悗鍐欎簨浠?+ 澶氭柟閫氱煡锛堢敤鎴?鏂板徃鏈?鍚庡彴锛?
        orderEventService.record(leg.getOrderId(), TransportOrderEventTypeEnum.PLAN_REPLANNED,
                "绗?" + leg.getLegSequence() + " 娈靛凡閲嶆柊璋冨害锛堣溅杈?"
                        + (fromVehicle == null ? "鏃? : fromVehicle) + " 鈫?" + chosen.getVehicleId()
                        + (reason != null ? "锛屽師鍥狅細" + reason : "") + "锛?);
        userNotificationService.sendToOrderUser(leg.getOrderId(), TransportOrderEventTypeEnum.PLAN_REPLANNED,
                cn.iocoder.yudao.module.transport.enums.notification.NotificationLevelEnum.WARNING, false,
                "杩愯緭鏂规�堝凡璋冩�?, "鎮ㄧ殑璁㈠崟绗?" + leg.getLegSequence() + " 娈靛凡閲嶆柊瀹夋帓杞﹁締锛岄�勮�℃椂闂村彲鑳界暐鏈夊彉鍖?);
        userNotificationService.sendToDriver(chosen.getDriverId(), TransportOrderEventTypeEnum.LEG_ASSIGNED,
                cn.iocoder.yudao.module.transport.enums.notification.NotificationLevelEnum.ACTION_REQUIRED, true,
                "鏂扮殑杩愯緭浠诲姟", "璁㈠崟 " + leg.getOrderId() + " 绗?" + leg.getLegSequence() + " 娈靛凡鍒嗛厤缁欐偍锛岃�锋帴鍗�",
                leg.getOrderId(), leg.getPlanId(), legId);
        userNotificationService.sendToAdmin(TransportOrderEventTypeEnum.PLAN_REPLANNED,
                cn.iocoder.yudao.module.transport.enums.notification.NotificationLevelEnum.WARNING,
                "宸插畬鎴愰噸璋冨害", "璁㈠崟 " + leg.getOrderId() + " 绗?" + leg.getLegSequence() + " 娈靛凡鎹㈣溅",
                leg.getOrderId(), legId);
        return legMapper.selectById(legId);
    }

    private void releaseVehicle(Long vehicleId) {
        if (vehicleId == null || vehicleMapper == null) {
            return;
        }
        VehicleDO upd = new VehicleDO();
        upd.setId(vehicleId);
        upd.setRealtimeStatus(0); // 閲婃斁鍥炵┖闂?
        vehicleMapper.updateById(upd);
    }

    // ==================== 鍐呴儴 ====================

    private void applyLegStatus(TransportLegDO leg, TransportLegStatusEnum target, String reason) {
        LocalDateTime now = LocalDateTime.now();
        TransportLegDO update = new TransportLegDO();
        update.setId(leg.getId());
        update.setStatus(target.getStatus());
        if (target == TransportLegStatusEnum.IN_TRANSIT && leg.getActualDeparture() == null) {
            update.setActualDeparture(now);
        }
        if (target == TransportLegStatusEnum.ARRIVED_DESTINATION || target == TransportLegStatusEnum.COMPLETED) {
            if (leg.getActualArrival() == null) {
                update.setActualArrival(now);
            }
        }
        legMapper.updateById(update);
        syncResourceStatus(leg, target);
        syncShiftExecution(leg, target);
        recordLegEvent(leg, target, reason);
    }

    /**
     * 娈典笌鐝�娆℃墽琛屽悓姝ワ紙闇�姹?搂125锛夛細娈靛紑濮?鈫?鍙告満褰撳ぉ鎵ц�岃�板綍缃�鍦ㄩ�旓紱
     * 娈靛畬鎴愪笖璇ュ徃鏈哄綋澶╁凡鏃犲叾浠栬繘琛屼腑娈?鈫?鎵ц�岃�板綍缃�宸插畬鎴愩�?
     * 閬垮厤"Leg=宸插畬鎴?鑰?ShiftExecution 浠嶅湪閫?鐨勭郴缁熷垎瑁傘�?
     */
    private void syncShiftExecution(TransportLegDO leg, TransportLegStatusEnum target) {
        if (shiftExecutionMapper == null || leg.getDriverId() == null) {
            return;
        }
        List<ShiftExecutionDO> executions = shiftExecutionMapper.selectListByDriverAndDate(
                leg.getDriverId(), java.time.LocalDate.now());
        if (executions.isEmpty()) {
            return;
        }
        ShiftExecutionDO execution = executions.get(0);
        if (target == TransportLegStatusEnum.IN_TRANSIT) {
            ShiftExecutionDO upd = new ShiftExecutionDO();
            upd.setId(execution.getId());
            upd.setStatus(0); // 鍦ㄩ�?
            if (execution.getDepartTime() == null) {
                upd.setDepartTime(LocalDateTime.now());
            }
            shiftExecutionMapper.updateById(upd);
        } else if (target == TransportLegStatusEnum.COMPLETED) {
            boolean otherActive = legMapper.selectActiveByDriverId(leg.getDriverId()).stream()
                    .anyMatch(l -> !Objects.equals(l.getId(), leg.getId()));
            if (otherActive) {
                return;
            }
            ShiftExecutionDO upd = new ShiftExecutionDO();
            upd.setId(execution.getId());
            upd.setStatus(1); // 宸插畬鎴?
            if (execution.getArriveTime() == null) {
                upd.setArriveTime(LocalDateTime.now());
            }
            shiftExecutionMapper.updateById(upd);
        }
    }

    /** 杞﹁締/鍙告満鐘舵�佷笌杩愯緭娈靛悓姝ワ紙闇�姹?搂123/搂124锛夛細鍦ㄩ�?鈫?杞﹁締鍦ㄩ�?鍙告満蹇欑�岋紱瀹屾�?鈫?绌洪棽/鍦ㄧ嚎 */
    private void syncResourceStatus(TransportLegDO leg, TransportLegStatusEnum target) {
        boolean busy = target == TransportLegStatusEnum.IN_TRANSIT
                || target == TransportLegStatusEnum.NAVIGATING
                || target == TransportLegStatusEnum.LOADING
                || target == TransportLegStatusEnum.HANDOVER
                || target == TransportLegStatusEnum.DELIVERING;
        if (leg.getVehicleId() != null && vehicleMapper != null) {
            VehicleDO upd = new VehicleDO();
            upd.setId(leg.getVehicleId());
            upd.setRealtimeStatus(target == TransportLegStatusEnum.COMPLETED ? 0 : (busy ? 1 : 0));
            vehicleMapper.updateById(upd);
        }
        if (leg.getDriverId() != null && driverStatusMapper != null) {
            var status = driverStatusMapper.selectByDriverId(leg.getDriverId());
            if (status == null) {
                driverStatusMapper.insert(cn.iocoder.yudao.module.transport.dal.dataobject.driver.TransportDriverStatusDO
                        .builder()
                        .driverId(leg.getDriverId())
                        .onlineStatus(busy ? 2 : 1)
                        .currentVehicleId(leg.getVehicleId())
                        .lastHeartbeat(nowOrNull())
                        .build());
            } else {
                var upd = new cn.iocoder.yudao.module.transport.dal.dataobject.driver.TransportDriverStatusDO();
                upd.setId(status.getId());
                upd.setOnlineStatus(busy ? 2 : 1);
                upd.setCurrentVehicleId(leg.getVehicleId());
                upd.setLastHeartbeat(nowOrNull());
                driverStatusMapper.updateById(upd);
            }
        }
    }

    private static LocalDateTime nowOrNull() {
        return LocalDateTime.now();
    }

    private void recordLegEvent(TransportLegDO leg, TransportLegStatusEnum target, String reason) {
        String prefix = "绗?" + leg.getLegSequence() + " 娈?;
        switch (target) {
            case DRIVER_ACCEPTED -> orderEventService.record(leg.getOrderId(),
                    TransportOrderEventTypeEnum.LEG_ACCEPTED, prefix + "鍙告満宸叉帴鍗?);
            case IN_TRANSIT -> orderEventService.record(leg.getOrderId(),
                    TransportOrderEventTypeEnum.LEG_STARTED, prefix + "宸插紑濮嬭繍杈?);
            case ARRIVED_DESTINATION -> orderEventService.record(leg.getOrderId(),
                    TransportOrderEventTypeEnum.LEG_ARRIVED, prefix + "宸插埌杈? + (Boolean.TRUE.equals(leg.getHandoverRequired()) ? "鎹�涔樼�? : "鐩�鐨勭�?));
            case COMPLETED -> orderEventService.record(leg.getOrderId(),
                    TransportOrderEventTypeEnum.LEG_COMPLETED, prefix + "宸插畬鎴? + (reason != null ? "锛? + reason : ""));
            case EXCEPTION -> orderEventService.record(leg.getOrderId(),
                    TransportOrderEventTypeEnum.ORDER_EXCEPTION, prefix + "寮傚父" + (reason != null ? "锛? + reason : ""));
            default -> { /* 鍏朵綑鐘舵�佹棤闇�鍗曠嫭璁颁簨浠?*/ }
        }
    }

    /**
     * 缁欏緟鍒嗛厤娈电粦瀹氳溅杈?鍙告満锛堥渶姹?搂48/搂49/搂113锛夛細
     * 1. 鍙栧綋鍓嶆湁鏁堜汉杞︾粦瀹氾紝灏介噺璁╃浉閭绘�电敤涓嶅悓杞﹁締锛堟崲涔樼殑鎰忎箟锛夛�?
     * 2. **蹇呴』鏃犳椂闂村啿绐?*锛氳溅杈?鍙告満鍦ㄨ�ユ椂娈靛凡琚�鍏朵粬娈靛崰鐢ㄥ垯璺宠繃璇ョ粦瀹氾紱
     * 3. 鍏ㄩ儴缁戝畾閮藉啿绐?鈫?淇濇寔"宸茶�勫�?鏈�鍒嗛�?锛岀敱浜哄伐鏀规淳锛堝苟鍦ㄦ棩蹇椾腑鏄庣‘鎻愮ず锛岀粷涓嶇‖濉炲啿绐佽溅杈嗭級銆?
     */
    private void assignVehicles(List<TransportLegDO> legs, Long orderId, Long planId,
                                Long planVehicleId, Long planDriverId) {
        // 浼樺厛閲囩敤**璋冨害绠楁硶缁欏嚭鐨勮溅杈?鍙告満鍒嗛厤**锛坱ransport_dispatch_plan_item锛夛細
        // 绠楁硶浼氭妸鍚屼竴鐗囧尯鐨勫�氬紶璁㈠崟鎷煎埌鍚屼竴杈嗚溅涓婏紙鎷煎崟/鍏辫浇锛夛紝杩欓噷蹇呴』娌跨敤瀹冪殑鍒嗛厤锛?
        // 鍚﹀垯浼氬嚭鐜?涓烘煇涓�鍗曞崟鐙�娲句竴杈嗚溅"鐨勫亣璞★紝涓庣湡瀹炶繍钀ワ紙涓�杞﹀�氬崟锛変笉绗︺�?
        java.util.Map<Long, cn.iocoder.yudao.module.transport.dal.dataobject.dispatch.DispatchPlanItemDO> byStation =
                new java.util.HashMap<>();
        // 璇ヨ�㈠崟鍦ㄧ畻娉曟柟妗堥噷鎵�灞炵殑杞﹁締锛堝彇娲鹃�佹槑缁嗕腑鏈�闈犲墠鐨勪竴鏉★級锛氭嫾鍗曞叡杞芥椂鐢ㄥ畠缁欏彇璐ф�垫淳杞�
        cn.iocoder.yudao.module.transport.dal.dataobject.dispatch.DispatchPlanItemDO orderVehicleItem = null;
        if (dispatchPlanItemMapper != null && orderId != null) {
            List<cn.iocoder.yudao.module.transport.dal.dataobject.dispatch.DispatchPlanItemDO> items =
                    dispatchPlanItemMapper.selectList(
                            new cn.iocoder.yudao.framework.mybatis.core.query.LambdaQueryWrapperX
                                    <cn.iocoder.yudao.module.transport.dal.dataobject.dispatch.DispatchPlanItemDO>()
                                    .eq(cn.iocoder.yudao.module.transport.dal.dataobject.dispatch.DispatchPlanItemDO::getOrderId, orderId)
                                    .eqIfPresent(cn.iocoder.yudao.module.transport.dal.dataobject.dispatch.DispatchPlanItemDO::getPlanId, planId));
            items.stream().filter(i -> i.getStationId() != null).forEach(i -> byStation.putIfAbsent(i.getStationId(), i));
            orderVehicleItem = items.stream()
                    .filter(i -> i.getVehicleId() != null)
                    .min(java.util.Comparator.comparing(
                            cn.iocoder.yudao.module.transport.dal.dataobject.dispatch.DispatchPlanItemDO::getVisitSequence,
                            java.util.Comparator.nullsLast(Integer::compareTo)))
                    .orElse(null);
        }
        // 璋冪敤鏂癸紙璋冨害锛夌洿鎺ヤ紶鍏ョ殑绠楁硶鍒嗛厤锛氫紭鍏堜簬搴撳唴鏌ヨ��锛堢嫭绔嬩簨鍔￠噷鏌ヤ笉鍒版湭鎻愪氦鐨勬槑缁嗭級
        if (orderVehicleItem == null && planVehicleId != null) {
            orderVehicleItem = cn.iocoder.yudao.module.transport.dal.dataobject.dispatch.DispatchPlanItemDO.builder()
                    .orderId(orderId).planId(planId).vehicleId(planVehicleId).driverId(planDriverId).build();
        }
        if (driverVehicleMapper == null) {
            return;
        }
        List<DriverVehicleDO> bindings = driverVehicleMapper.selectActiveBindings();
        if (bindings == null || bindings.isEmpty()) {
            return;
        }
        List<TransportLegDO> assigned = new ArrayList<>();
        for (int i = 0; i < legs.size(); i++) {
            TransportLegDO leg = legs.get(i);
            // 1) 绠楁硶宸插垎閰嶏紙璇ヨ�㈠崟鍦ㄨ�ョ粡鍋滀笂鐨勮溅杈?鍙告満锛夆啋 鐩存帴娌跨敤锛堝疄鐜?涓�杞﹀�氬�?锛?
            var planned = byStation.get(leg.getFromStationId());
            if (planned == null && !Boolean.TRUE.equals(leg.getHandoverRequired())) {
                // 鏈�鍚庝竴娈碉細鍙?绠楁硶鍒嗛厤缁欒�ヨ�㈠崟閫佽揪绔?鐨勮溅杈嗭紙涓�杞﹀�氬崟缁х画娌跨敤鍚屼竴杈嗚溅锛?
                planned = byStation.get(leg.getToStationId());
            }
            if (planned == null && i == 0) {
                // 鍙栬揣娈碉細绠楁硶鎶婂彇璐ц�嗕�?鍦虹珯棰勮��"锛堟槑缁嗛噷鍙�鏈夋淳閫佺珯锛夆啋 鐢ㄨ�ヨ�㈠崟鎵�灞炶溅杈嗭紝
                // 杩欐牱鍚屼竴杈嗚溅涓婄殑澶氬紶璁㈠崟鍦ㄥ彇璐ф�靛氨钀藉湪鍚屼竴杈嗚溅涓婏紙鐪熷疄鎷煎崟锛屼笉鏄�涓撹溅锛�
                planned = orderVehicleItem;
            }
            // 娉ㄦ剰锛氳繖閲?*涓嶅仛鏃舵�靛啿绐佸垽鏂�**鈥斺�旂畻娉曞湪娲惧崟鏃跺凡鎸夊�归�?鏃堕棿绐楁牎楠岃繃锛?
            // 鑰?鍚屼竴杈嗚溅鍦ㄥ悓涓�鏃舵�垫壙杩愬�氬紶璁㈠崟"姝ｆ槸鎷煎崟/鍏辫浇鐨勬�ｅ父褰㈡�侊紝鍐嶅垽鍐茬獊浼氭妸鍏辫浇鎸℃帀銆?
            if (planned != null && planned.getVehicleId() != null) {
                leg.setVehicleId(planned.getVehicleId());
                leg.setDriverId(planned.getDriverId());
                leg.setShiftId(planned.getShiftId());
                leg.setPlanItemId(planned.getId());
                leg.setStatus(TransportLegStatusEnum.ASSIGNED.getStatus());
                assigned.add(leg);
                continue;
            }
            // 2) 绠楁硶鏈�瑕嗙洊锛堝�氭�典腑杞�绔欐病鏈夋槑缁嗭級鈫?鎸変汉杞︾粦瀹氬厹搴曪紝骞堕伩璁╂椂娈靛啿绐?
            // 鐩搁偦娈典紭鍏堢敤**涓嶅悓**杞﹁締锛堟崲涔樼殑鎰忎箟锛岄渶姹?搂111锛歀eg1.vehicle != Leg2.vehicle锛夛細
            // 绗�涓�杞�鍙�鎸?鏈�鏂规�堣繕娌＄敤杩囦笖鏃犳椂娈靛啿绐?鐨勭粦瀹氾紱娌℃湁鎵嶉��鑰屾眰鍏舵�″厑璁稿�嶇敤銆?
            java.util.Set<Long> usedVehicles = assigned.stream().map(TransportLegDO::getVehicleId)
                    .filter(Objects::nonNull).collect(java.util.stream.Collectors.toSet());
            DriverVehicleDO chosen = pickBinding(bindings, leg, assigned, usedVehicles, true);
            if (chosen == null) {
                chosen = pickBinding(bindings, leg, assigned, usedVehicles, false);
            }
            if (chosen == null) {
                log.warn("[multi-leg] 璁㈠崟 {} 绗?{} 娈靛湪 {}~{} 鏃犲彲鐢ㄨ溅杈?鍙告満锛堟椂娈靛啿绐侊級锛屼繚鎸佹湭鍒嗛厤",
                        leg.getOrderId(), leg.getLegSequence(), leg.getEstimatedDeparture(), leg.getEstimatedArrival());
                continue;
            }
            leg.setVehicleId(chosen.getVehicleId());
            leg.setDriverId(chosen.getDriverId());
            leg.setStatus(TransportLegStatusEnum.ASSIGNED.getStatus());
            assigned.add(leg);
        }
    }

    /** 閫変竴涓�鍙�鐢ㄧ粦瀹氾紱strictUnused=true 鏃惰烦杩囨湰鏂规�堝凡鐢ㄨ溅杈嗭紙淇濊瘉鐩搁偦娈典笉鍚岃溅锛� */
    private DriverVehicleDO pickBinding(List<DriverVehicleDO> bindings, TransportLegDO leg,
                                        List<TransportLegDO> assigned, java.util.Set<Long> usedVehicles,
                                        boolean strictUnused) {
        for (DriverVehicleDO binding : bindings) {
            if (strictUnused && usedVehicles.contains(binding.getVehicleId())) {
                continue;
            }
            boolean conflict = legConflictService != null && (legConflictService.vehicleConflicts(
                    binding.getVehicleId(), leg.getEstimatedDeparture(), leg.getEstimatedArrival(), assigned, null)
                    || legConflictService.driverConflicts(binding.getDriverId(), leg.getEstimatedDeparture(),
                    leg.getEstimatedArrival(), assigned, null));
            if (!conflict) {
                return binding;
            }
        }
        return null;
    }

    /** 瑙﹀彂鏀规淳鐨勬渶灏?褰撳墠杞︾�绘湰娈佃捣鐐�"璺濈�伙紙km锛夛細浣庝簬瀹冭�存槑杞﹀氨鍦ㄩ檮杩戯紝娌″繀瑕佹崲杞� */
    private static final double RELAY_MIN_CURRENT_KM = 6.0;
    /** 鏀规淳鏀剁泭涓嬮檺锛坘m锛夛細鎹㈣溅鍚庤嚦灏戣繎杩欎箞澶氭墠鍊煎緱澶氫竴娆′氦鎺?*/
    private static final double RELAY_MIN_GAIN_KM = 3.0;

    /**
     * 澶氭�佃仈杩� 路 鎸?璋佺�绘湰娈佃捣鐐硅�?鏀规淳鍚庣画娈点�?
     *
     * <p>鑳屾櫙锛氳皟搴︾畻娉曞彲鑳芥妸涓�寮犺法鐗囧尯璁㈠崟锛堝��"閲嶉偖 鈫?宸村崡榫欐床婀?锛夋暣娈典氦缁欏悓涓�鍙拌溅锛?
     * 浜庢槸鍑虹幇"涓�鍙板叕浜よ溅璺戝埌宸村崡鍐嶇┖杞︾粫鍥炴潵"鐨勪笉鍚堢悊璋冨害銆傜湡瀹炶繍钀ラ噷姣忓彴杞︽湁鑷�宸辩殑浣滀笟鐗囧尯锛�
     * 璺ㄧ墖鍖哄簲鐢?*鍙︿竴鍙拌溅/鍙︿竴浣嶅徃鏈哄湪鎹�涔樼珯鎺ラ�?*銆?/p>
     *
     * <p>鍋氭硶锛氬�圭�?2 娈佃捣鐨勬瘡涓�娈碉紝姣旇緝"褰撳墠娲捐溅"涓?鏈�鍗曡繕娌＄敤杩囥�佷笖褰撳墠灏卞湪鏈�娈佃捣鐐归檮杩�"鐨勮溅锛?
     * 鑻ュ綋鍓嶈溅绂绘湰娈佃捣鐐硅緝杩滐紙>{@value #RELAY_MIN_CURRENT_KM}km锛変笖鎹㈣溅鍚庤兘鏄庢樉鏇磋繎
     * 锛?{@value #RELAY_MIN_GAIN_KM}km锛夛紝灏辨敼娲捐�ヨ�?鍙告満锛屽苟鎶婂墠涓�娈垫爣璁颁负闇�瑕佹崲涔樹氦鎺ャ�?/p>
     */
    private void relayFarLegsToNearbyVehicles(List<TransportLegDO> legs) {
        if (vehicleLocationProvider == null || driverVehicleMapper == null || legs == null || legs.size() < 2) {
            return;
        }
        List<DriverVehicleDO> bindings = driverVehicleMapper.selectActiveBindings();
        if (bindings == null || bindings.isEmpty()) {
            return;
        }
        java.util.Set<Long> vehicleIds = bindings.stream().map(DriverVehicleDO::getVehicleId)
                .filter(Objects::nonNull).collect(java.util.stream.Collectors.toSet());
        if (vehicleIds.isEmpty()) {
            return;
        }
        java.util.Map<Long, cn.iocoder.yudao.module.transport.service.monitoring.VehicleLocationSnapshot> locs;
        try {
            locs = vehicleLocationProvider.getLocations(vehicleIds, true);
        } catch (Exception ex) {
            log.debug("[multi-leg] 杞﹁締浣嶇疆涓嶅彲鐢�锛岃烦杩囨寜鐗囧尯鏀规淳锛歿}", ex.getMessage());
            return;
        }
        java.util.Set<Long> used = legs.stream().map(TransportLegDO::getVehicleId)
                .filter(Objects::nonNull).collect(java.util.stream.Collectors.toSet());
        // 鏀规淳鍚庨渶瑕佺粰鏂拌溅鐣?浠庡綋鍓嶄綅缃�寮�鍒颁氦鎺ョ珯"鐨勬椂闂达細璁颁笅姣忔�电殑璋冨姩鍒嗛挓锛屾渶鍚庣粺涓�閲嶆帓鏃堕棿
        java.util.Map<Integer, Integer> repositionMinutes = new java.util.HashMap<>();
        for (int i = 1; i < legs.size(); i++) {
            TransportLegDO leg = legs.get(i);
            StationDO from = leg.getFromStationId() == null ? null : stationMapper.selectById(leg.getFromStationId());
            if (from == null || from.getLongitude() == null || from.getLatitude() == null) {
                continue;
            }
            double currentKm = distanceToStation(locs.get(leg.getVehicleId()), from);
            if (currentKm < RELAY_MIN_CURRENT_KM) {
                continue; // 褰撳墠杞︽湰鏉ュ氨鍦ㄦ湰娈佃捣鐐归檮杩戯紝涓嶉渶瑕佹崲杞?
            }
            DriverVehicleDO best = null;
            double bestKm = Double.MAX_VALUE;
            for (DriverVehicleDO binding : bindings) {
                if (binding.getVehicleId() == null || used.contains(binding.getVehicleId())) {
                    continue; // 璺宠繃鏈�鍗曞凡鐢ㄨ溅杈嗭細鎹�涔樼殑鎰忎箟灏辨槸"鎹�涓�鍙拌溅"
                }
                double km = distanceToStation(locs.get(binding.getVehicleId()), from);
                if (km < bestKm) {
                    bestKm = km;
                    best = binding;
                }
            }
            if (best == null || currentKm - bestKm < RELAY_MIN_GAIN_KM) {
                continue;
            }
            log.info("[multi-leg] 璁㈠崟 {} 绗?{} 娈垫寜鐗囧尯鏀规淳锛氳溅杈?{}锛堣窛璧风偣 {}km锛夆啋 杞﹁締 {}锛坽}km锛?,
                    leg.getOrderId(), leg.getLegSequence(), leg.getVehicleId(), round1(currentKm),
                    best.getVehicleId(), round1(bestKm));
            legs.get(i - 1).setHandoverRequired(true); // 涓婁竴娈电粨鏉熼渶瑕佷氦鎺ョ粰鏂板徃鏈?
            leg.setVehicleId(best.getVehicleId());
            leg.setDriverId(best.getDriverId());
            leg.setShiftId(null);
            leg.setPlanItemId(null);
            leg.setHandoverRequired(true);
            leg.setStatus(TransportLegStatusEnum.ASSIGNED.getStatus());
            used.add(best.getVehicleId());
            // 鏂拌溅浠庡綋鍓嶄綅缃�寮�鍒版湰娈佃捣鐐归渶瑕佺殑鍒嗛挓锛堟寜 20km/h 鍩庡尯鍧囬�熶及绠楋紝鍚戜笂鍙栨暣锛?            repositionMinutes.put(i, (int) Math.ceil(bestKm / 20.0 * 60));
        }
        retimeWithReposition(legs, repositionMinutes);
    }

    /**
     * 鏀规淳鍚庨噸鎺掑悇娈垫椂闂达細鎶?鏂拌溅寮�鍒颁氦鎺ョ珯"鐨勮皟鍔ㄦ椂闂寸畻杩涘幓锛屼氦鎺ョ珯瓒婅繙鐣欑殑鏃堕棿瓒婅冻銆?     *
     * <p>瑙勫垯鏉ヨ嚜涓氬姟绾︽潫锛氳仈杩愪竴瀹氳�佺粺绛瑰ソ鍑犱釜鍙告満杞﹁締鐨勬椂闂达紝鎹�涔樼珯鐐硅繃杩滆�佺暀瓒充氦鎺ユ椂闂淬�?/p>
     */
    private void retimeWithReposition(List<TransportLegDO> legs, java.util.Map<Integer, Integer> repositionMinutes) {
        if (legs == null || legs.isEmpty()) {
            return;
        }
        LocalDateTime cursor = legs.get(0).getEstimatedDeparture();
        if (cursor == null) {
            return;
        }
        for (int i = 0; i < legs.size(); i++) {
            TransportLegDO leg = legs.get(i);
            int travel = leg.getDurationMinutes() != null ? leg.getDurationMinutes()
                    : MultiLegPlanner.travelMinutes(leg.getDistanceKm() == null ? 0 : leg.getDistanceKm().doubleValue());
            if (i > 0) {
                // 涓婁竴娈靛埌杈?+ 浜ゆ帴鍋滅暀锛堟崲涔樻�垫墠闇�瑕侊級+ 鏂拌溅璋冨姩鍒颁氦鎺ョ珯鐨勮�岄┒鏃堕�?                cursor = legs.get(i - 1).getEstimatedArrival();
                if (cursor == null) {
                    return;
                }
                if (Boolean.TRUE.equals(leg.getHandoverRequired())
                        || Boolean.TRUE.equals(legs.get(i - 1).getHandoverRequired())) {
                    cursor = cursor.plusMinutes(MultiLegPlanner.HANDOVER_DWELL_MINUTES);
                }
                Integer reposition = repositionMinutes.get(i);
                if (reposition != null && reposition > 0) {
                    cursor = cursor.plusMinutes(reposition);
                }
            }
            leg.setEstimatedDeparture(cursor);
            leg.setEstimatedArrival(cursor.plusMinutes(travel));
            cursor = leg.getEstimatedArrival();
        }
    }

    /** 杞﹁締褰撳墠浣嶇疆鍒扮洰鏍囩珯鐐圭殑鐩寸嚎璺濈�伙紙km锛夛紱鏃犱綅缃�杩斿洖涓�涓�澶ф�?*/
    private static double distanceToStation(cn.iocoder.yudao.module.transport.service.monitoring.VehicleLocationSnapshot loc,
                                            StationDO station) {
        if (loc == null || loc.getLongitude() == null || loc.getLatitude() == null
                || station.getLongitude() == null || station.getLatitude() == null) {
            return Double.MAX_VALUE;
        }
        return cn.iocoder.yudao.module.transport.util.GeoDistanceUtil.haversineKm(
                loc.getLongitude(), loc.getLatitude(),
                station.getLongitude().doubleValue(), station.getLatitude().doubleValue());
    }

    private static double round1(double value) {
        return Math.round(value * 10) / 10.0;
    }

}


