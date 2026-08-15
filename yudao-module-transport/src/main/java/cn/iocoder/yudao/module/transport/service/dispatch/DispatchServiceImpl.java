package cn.iocoder.yudao.module.transport.service.dispatch;

import cn.hutool.core.util.IdUtil;
import cn.hutool.core.util.StrUtil;
import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.framework.common.util.object.BeanUtils;
import cn.iocoder.yudao.framework.mybatis.core.query.LambdaQueryWrapperX;
import cn.iocoder.yudao.framework.security.core.util.SecurityFrameworkUtils;
import cn.iocoder.yudao.module.transport.controller.admin.dispatch.vo.*;
import cn.iocoder.yudao.module.transport.dal.dataobject.dispatch.*;
import cn.iocoder.yudao.module.transport.dal.dataobject.driver.DriverVehicleDO;
import cn.iocoder.yudao.module.transport.dal.dataobject.order.CargoOrderDO;
import cn.iocoder.yudao.module.transport.dal.dataobject.order.PassengerOrderDO;
import cn.iocoder.yudao.module.transport.dal.dataobject.order.PostalOrderDO;
import cn.iocoder.yudao.module.transport.dal.dataobject.order.TransportOrderDO;
import cn.iocoder.yudao.module.transport.dal.dataobject.station.StationDO;
import cn.iocoder.yudao.module.transport.dal.dataobject.vehicle.VehicleDO;
import cn.iocoder.yudao.module.transport.dal.mysql.dispatch.*;
import cn.iocoder.yudao.module.transport.dal.mysql.driver.DriverVehicleMapper;
import cn.iocoder.yudao.module.transport.dal.mysql.order.CargoOrderMapper;
import cn.iocoder.yudao.module.transport.dal.mysql.order.PassengerOrderMapper;
import cn.iocoder.yudao.module.transport.dal.mysql.order.PostalOrderMapper;
import cn.iocoder.yudao.module.transport.dal.mysql.order.TransportOrderMapper;
import cn.iocoder.yudao.module.transport.dal.mysql.station.StationMapper;
import cn.iocoder.yudao.module.transport.dal.mysql.vehicle.VehicleMapper;
import cn.iocoder.yudao.module.transport.enums.dispatch.*;
import cn.iocoder.yudao.module.transport.integration.algorithm.AlgorithmAdapter;
import cn.iocoder.yudao.module.transport.integration.algorithm.AlgorithmResultValidator;
import cn.iocoder.yudao.module.transport.integration.algorithm.dto.*;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.annotation.Validated;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

import static cn.iocoder.yudao.framework.common.exception.enums.GlobalErrorCodeConstants.BAD_REQUEST;
import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.module.transport.enums.ErrorCodeConstants.*;

@Service
@Validated
public class DispatchServiceImpl implements DispatchService {

    /** 算法契约时区固定 +08:00，见 docs/api/algorithm-api.yaml */
    private static final ZoneOffset BATCH_ZONE_OFFSET = ZoneOffset.ofHours(8);
    /** 手工派单场景标记 */
    private static final String SCENARIO_MANUAL = "MANUAL";

    @Resource private TransportOrderMapper orderMapper;
    @Resource private CargoOrderMapper cargoOrderMapper;
    @Resource private PostalOrderMapper postalOrderMapper;
    @Resource private PassengerOrderMapper passengerOrderMapper;
    @Resource private StationMapper stationMapper;
    @Resource private VehicleMapper vehicleMapper;
    @Resource private DriverVehicleMapper driverVehicleMapper;
    @Resource private TransportDispatchTaskMapper dispatchTaskMapper;
    @Resource private DispatchPlanMapper dispatchPlanMapper;
    @Resource private DispatchPlanItemMapper dispatchPlanItemMapper;
    @Resource private DispatchPlanLogMapper dispatchPlanLogMapper;
    @Resource private DepartureCheckMapper departureCheckMapper;
    @Resource private AlgorithmAdapter algorithmAdapter;

    @Override
    public PageResult<TransportOrderDO> getOrderPoolPage(DispatchPoolPageReqVO reqVO) {
        return orderMapper.selectPage(reqVO, new LambdaQueryWrapperX<TransportOrderDO>()
                .inIfPresent(TransportOrderDO::getStatus, TransportOrderStatusEnum.CREATED.getStatus(),
                        TransportOrderStatusEnum.POOLED.getStatus())
                .eqIfPresent(TransportOrderDO::getStatus, reqVO.getStatus())
                .eqIfPresent(TransportOrderDO::getOrderType, reqVO.getOrderType())
                .betweenIfPresent(TransportOrderDO::getCreateTime, reqVO.getCreateTime())
                .orderByDesc(TransportOrderDO::getId));
    }

    @Override
    @Transactional
    public int collectOrders(DispatchCollectReqVO reqVO) {
        // 查区间内待归集订单
        List<TransportOrderDO> orders = orderMapper.selectList(new LambdaQueryWrapperX<TransportOrderDO>()
                .eq(TransportOrderDO::getStatus, TransportOrderStatusEnum.CREATED.getStatus())
                .between(TransportOrderDO::getCreateTime, reqVO.getBatchStart(), reqVO.getBatchEnd()));
        // 过滤：货运需管理端审核通过（危险品/违禁品拒绝运输）；客运/邮快件直接可归集
        List<Long> ids = orders.stream()
                .filter(o -> !Objects.equals(o.getOrderType(), 2) || isCargoAudited(o.getId()))
                .map(TransportOrderDO::getId)
                .toList();
        if (ids.isEmpty()) {
            return 0;
        }
        TransportOrderDO updateObj = new TransportOrderDO();
        updateObj.setStatus(TransportOrderStatusEnum.POOLED.getStatus());
        return orderMapper.update(updateObj, new LambdaQueryWrapperX<TransportOrderDO>()
                .in(TransportOrderDO::getId, ids)
                .eq(TransportOrderDO::getStatus, TransportOrderStatusEnum.CREATED.getStatus()));
    }

    /** 货运是否已管理端审核通过（audit_status=1） */
    private boolean isCargoAudited(Long orderId) {
        CargoOrderDO cargo = cargoOrderMapper.selectOne(CargoOrderDO::getOrderId, orderId);
        return cargo != null && Objects.equals(cargo.getAuditStatus(), 1);
    }

    @Override
    @Transactional
    public Long createManualPlan(DispatchManualPlanReqVO reqVO) {
        // 校验场站与订单：订单必须全部在订单池中
        StationDO depot = validateDepotExists(reqVO.getDepotStationId());
        Map<Long, TransportOrderDO> orderMap = validatePooledOrders(reqVO.getOrderIds());
        VehicleDO vehicle = validateVehicleExists(reqVO.getVehicleId());

        List<TransportOrderDO> orders = reqVO.getOrderIds().stream().map(orderMap::get).collect(Collectors.toList());
        // 按 orderIds 顺序生成闭环经停：DEPART(场站) -> 各订单作业点 -> RETURN(场站)；
        // 客运多人单按乘客数拆成多条算法订单（契约中一张客运单 = 1 人）
        List<AlgorithmRouteStopDTO> stops = new ArrayList<>();
        stops.add(buildStop(depot.getId(), null, AlgorithmRouteStopDTO.ACTION_DEPART));
        for (TransportOrderDO order : orders) {
            if (Objects.equals(order.getOrderType(), 1)) { // 客运：上车 + 下车
                for (String algorithmOrderId : passengerAlgorithmOrderIds(order)) {
                    stops.add(buildStop(order.getPickupStationId(), algorithmOrderId, AlgorithmRouteStopDTO.ACTION_BOARD));
                    stops.add(buildStop(order.getDeliveryStationId(), algorithmOrderId, AlgorithmRouteStopDTO.ACTION_ALIGHT));
                }
            } else { // 货运/邮快件：终点为场站 → 揽收(村→场站)，否则 → 派送(场站→村)
                boolean isPickup = Objects.equals(order.getDeliveryStationId(), depot.getId());
                stops.add(buildStop(isPickup ? order.getPickupStationId() : order.getDeliveryStationId(),
                        String.valueOf(order.getId()),
                        isPickup ? AlgorithmRouteStopDTO.ACTION_PICKUP : AlgorithmRouteStopDTO.ACTION_DELIVER));
            }
        }
        stops.add(buildStop(depot.getId(), null, AlgorithmRouteStopDTO.ACTION_RETURN));
        AlgorithmPlanReqDTO algorithmReq = buildPlanRequest(depot, Collections.singletonList(vehicle), orders,
                null, SCENARIO_MANUAL);
        AlgorithmPlanRespDTO algorithmResp = AlgorithmPlanRespDTO.builder()
                .status(AlgorithmPlanRespDTO.STATUS_FEASIBLE)
                .vehiclePlans(Collections.singletonList(AlgorithmVehiclePlanDTO.builder()
                        .vehicleId(vehicle.getId()).stops(stops).build()))
                .build();
        AlgorithmResultValidator.validate(algorithmReq, algorithmResp);

        // 落库：任务 -> 方案 -> 经停明细 -> 订单置为已分配
        LocalDateTime[] batch = currentBatch();
        DispatchTaskDO task = DispatchTaskDO.builder()
                .taskNo(generateTaskNo())
                .snapshotId("manual-" + IdUtil.fastSimpleUUID())
                .planningTime(LocalDateTime.now())
                .batchStart(batch[0]).batchEnd(batch[1])
                .scenario(SCENARIO_MANUAL)
                .status(DispatchTaskStatusEnum.SUCCESS.getStatus())
                .build();
        dispatchTaskMapper.insert(task);
        DispatchPlanDO plan = createPlan(task, DispatchPlanModeEnum.MANUAL, null, null, null);
        insertPlanItems(plan.getId(), vehicle.getId(), stops);
        updateOrdersStatus(reqVO.getOrderIds(), TransportOrderStatusEnum.ASSIGNED);
        return plan.getId();
    }

    /**
     * 智能派单。任务终态（无可行解/失败）需要在异常抛出后仍然落库，故 ServiceException 不回滚。
     */
    @Override
    @Transactional(noRollbackFor = ServiceException.class)
    public Long createSmartPlan(DispatchSmartPlanReqVO reqVO) {
        // 取订单池；为空直接报错
        List<TransportOrderDO> pooledOrders = orderMapper.selectList(new LambdaQueryWrapperX<TransportOrderDO>()
                .eq(TransportOrderDO::getStatus, TransportOrderStatusEnum.POOLED.getStatus()));
        if (pooledOrders.isEmpty()) {
            throw exception(DISPATCH_POOL_EMPTY);
        }
        StationDO depot = validateDepotExists(reqVO.getDepotStationId());
        List<VehicleDO> vehicles = vehicleMapper.selectBatchIds(reqVO.getVehicleIds());

        // 构建快照并落任务（规划中）
        AlgorithmPlanReqDTO algorithmReq = buildPlanRequest(depot, vehicles, pooledOrders,
                reqVO.getAlgorithmConfig(), reqVO.getScenario());
        LocalDateTime[] batch = currentBatch();
        String taskNo = generateTaskNo();
        DispatchTaskDO task = DispatchTaskDO.builder()
                .taskNo(taskNo)
                .snapshotId(taskNo) // 快照编号暂用任务号，保证唯一约束
                .planningTime(LocalDateTime.now())
                .batchStart(batch[0]).batchEnd(batch[1])
                .scenario(reqVO.getScenario())
                .status(DispatchTaskStatusEnum.PLANNING.getStatus())
                .build();
        dispatchTaskMapper.insert(task);

        // 调用算法；失败时任务置 FAILED 后透传异常
        AlgorithmPlanRespDTO result;
        try {
            result = algorithmAdapter.plan(algorithmReq);
        } catch (ServiceException ex) {
            task.setStatus(DispatchTaskStatusEnum.FAILED.getStatus());
            task.setErrorMessage(ex.getMessage());
            dispatchTaskMapper.updateById(task);
            throw ex;
        }
        if (AlgorithmPlanRespDTO.STATUS_INFEASIBLE.equals(result.getStatus())) {
            task.setStatus(DispatchTaskStatusEnum.INFEASIBLE.getStatus());
            dispatchTaskMapper.updateById(task);
            throw exception(DISPATCH_NO_FEASIBLE, result.getReasonCode());
        }

        // 可行：任务置成功，方案与经停明细落库，订单置为已分配
        task.setStatus(DispatchTaskStatusEnum.SUCCESS.getStatus());
        task.setAlgorithmJobId(result.getRequestId());
        dispatchTaskMapper.updateById(task);
        DispatchPlanDO plan = createPlan(task, DispatchPlanModeEnum.SMART,
                result.getTotalDistance() != null ? BigDecimal.valueOf(result.getTotalDistance()) : null,
                result.getAlgorithmVersion(), result.getParameterVersion());
        for (AlgorithmVehiclePlanDTO vehiclePlan : result.getVehiclePlans()) {
            insertPlanItems(plan.getId(), vehiclePlan.getVehicleId(), vehiclePlan.getStops());
        }
        updateOrdersStatus(pooledOrders.stream().map(TransportOrderDO::getId).collect(Collectors.toList()),
                TransportOrderStatusEnum.ASSIGNED);
        return plan.getId();
    }

    @Override
    @Transactional
    public void reviewPlan(DispatchPlanReviewReqVO reqVO) {
        DispatchPlanDO plan = validatePlanExists(reqVO.getPlanId());
        if (!Objects.equals(plan.getStatus(), DispatchPlanStatusEnum.PENDING.getStatus())) {
            throw exception(DISPATCH_PLAN_STATUS_ILLEGAL);
        }
        if (Boolean.TRUE.equals(reqVO.getApprove())) {
            plan.setStatus(DispatchPlanStatusEnum.ISSUED.getStatus());
            plan.setApprovedBy(SecurityFrameworkUtils.getLoginUserId());
            plan.setApprovedTime(LocalDateTime.now());
            dispatchPlanMapper.updateById(plan);
        } else {
            if (StrUtil.isBlank(reqVO.getReason())) {
                throw exception(BAD_REQUEST);
            }
            plan.setStatus(DispatchPlanStatusEnum.VOID.getStatus());
            dispatchPlanMapper.updateById(plan);
            // 驳回后方案内订单回到订单池，可重新派单
            updateOrdersStatus(selectPlanOrderIds(reqVO.getPlanId(), null), TransportOrderStatusEnum.POOLED);
        }
        insertPlanLog(plan.getId(), DispatchPlanStatusEnum.PENDING.getStatus(), plan.getStatus(), reqVO.getReason());
    }

    @Override
    @Transactional
    public void departureCheck(DispatchCheckReqVO reqVO) {
        DispatchPlanDO plan = validatePlanExists(reqVO.getPlanId());
        if (!Objects.equals(plan.getStatus(), DispatchPlanStatusEnum.ISSUED.getStatus())
                && !Objects.equals(plan.getStatus(), DispatchPlanStatusEnum.RUNNING.getStatus())) {
            throw exception(DISPATCH_PLAN_STATUS_ILLEGAL);
        }
        departureCheckMapper.insert(DepartureCheckDO.builder()
                .planId(reqVO.getPlanId())
                .vehicleId(reqVO.getVehicleId())
                .result(Boolean.TRUE.equals(reqVO.getPass())
                        ? DepartureCheckResultEnum.PASS.getResult() : DepartureCheckResultEnum.REJECT.getResult())
                .remark(reqVO.getRemark())
                .checker(currentOperator())
                .build());
        if (!Boolean.TRUE.equals(reqVO.getPass())) {
            return;
        }
        // 核验通过：方案进入执行中，该车辆的订单置为已发车
        if (Objects.equals(plan.getStatus(), DispatchPlanStatusEnum.ISSUED.getStatus())) {
            plan.setStatus(DispatchPlanStatusEnum.RUNNING.getStatus());
            dispatchPlanMapper.updateById(plan);
            insertPlanLog(plan.getId(), DispatchPlanStatusEnum.ISSUED.getStatus(),
                    DispatchPlanStatusEnum.RUNNING.getStatus(), reqVO.getRemark());
        }
        updateOrdersStatus(selectPlanOrderIds(reqVO.getPlanId(), reqVO.getVehicleId()),
                TransportOrderStatusEnum.DEPARTED);
    }

    @Override
    public DispatchPlanRespVO getPlan(Long id) {
        DispatchPlanDO plan = validatePlanExists(id);
        DispatchPlanRespVO respVO = BeanUtils.toBean(plan, DispatchPlanRespVO.class);
        respVO.setItems(dispatchPlanItemMapper.selectList(new LambdaQueryWrapperX<DispatchPlanItemDO>()
                .eq(DispatchPlanItemDO::getPlanId, id)
                .orderByAsc(DispatchPlanItemDO::getVisitSequence)));
        return respVO;
    }

    @Override
    public PageResult<DispatchPlanDO> getPlanPage(DispatchPlanPageReqVO reqVO) {
        return dispatchPlanMapper.selectPage(reqVO);
    }

    @Override
    public DispatchValidateRespVO validate(DispatchValidateReqVO reqVO) {
        // 订单池（与智能派单取数一致：已入池订单）
        List<TransportOrderDO> pooledOrders = orderMapper.selectList(new LambdaQueryWrapperX<TransportOrderDO>()
                .eq(TransportOrderDO::getStatus, TransportOrderStatusEnum.POOLED.getStatus()));
        StationDO depot = validateDepotExists(reqVO.getDepotStationId());
        List<VehicleDO> vehicles = vehicleMapper.selectBatchIds(reqVO.getVehicleIds());
        Map<Long, StationDO> stationMap = stationMapper.selectList().stream()
                .collect(Collectors.toMap(StationDO::getId, java.util.function.Function.identity()));

        // 订单统计 + 站点作业标记 + 客运时序检查
        DispatchValidateRespVO.OrderStats stats = new DispatchValidateRespVO.OrderStats();
        stats.setPassengerCount(0);
        stats.setDeliveryCount(0);
        stats.setPickupCount(0);
        stats.setParcelCount(0);
        Map<Long, DispatchValidateRespVO.Marker> markerMap = new LinkedHashMap<>();
        List<DispatchValidateRespVO.TimeSeqIssue> issues = new ArrayList<>();
        for (TransportOrderDO order : pooledOrders) {
            if (Objects.equals(order.getOrderType(), 1)) { // 客运
                stats.setPassengerCount(stats.getPassengerCount() + getPassengerCount(order));
                addMarker(markerMap, order.getPickupStationId(), AlgorithmRouteStopDTO.ACTION_BOARD, stationMap);
                addMarker(markerMap, order.getDeliveryStationId(), AlgorithmRouteStopDTO.ACTION_ALIGHT, stationMap);
                if (order.getPickupStationId() == null || order.getDeliveryStationId() == null) {
                    issues.add(timeSeqIssue(order, "上车站或下车站缺失"));
                } else if (Objects.equals(order.getPickupStationId(), order.getDeliveryStationId())) {
                    issues.add(timeSeqIssue(order, "上车站与下车站相同，先上后下时序无法成立"));
                }
            } else { // 货运/邮快件：下车站为场站 → 揽收(村→场站)，否则 → 派送(场站→村)
                boolean isPickup = Objects.equals(order.getDeliveryStationId(), depot.getId());
                if (isPickup) {
                    stats.setPickupCount(stats.getPickupCount() + 1);
                    addMarker(markerMap, order.getPickupStationId(), AlgorithmRouteStopDTO.ACTION_PICKUP, stationMap);
                } else {
                    stats.setDeliveryCount(stats.getDeliveryCount() + 1);
                    addMarker(markerMap, order.getDeliveryStationId(), AlgorithmRouteStopDTO.ACTION_DELIVER, stationMap);
                }
                stats.setParcelCount(stats.getParcelCount() + getItemCount(order));
            }
        }

        // 运力校验：总容量 vs 总需求，超出即预警
        DispatchValidateRespVO.CapacityCheck capacityCheck = new DispatchValidateRespVO.CapacityCheck();
        int passengerCapacity = vehicles.stream()
                .mapToInt(v -> v.getPassengerCapacity() != null ? v.getPassengerCapacity() : 0).sum();
        int cargoCapacity = vehicles.stream()
                .mapToInt(v -> v.getCargoCapacity() != null ? v.getCargoCapacity() : 0).sum();
        capacityCheck.setTotalPassengerCapacity(passengerCapacity);
        capacityCheck.setTotalCargoCapacity(cargoCapacity);
        capacityCheck.setPassengerExceed(Math.max(0, stats.getPassengerCount() - passengerCapacity));
        capacityCheck.setCargoExceed(Math.max(0, stats.getParcelCount() - cargoCapacity));
        capacityCheck.setOverCapacity(capacityCheck.getPassengerExceed() > 0 || capacityCheck.getCargoExceed() > 0);

        List<DispatchValidateRespVO.VehicleItem> vehicleItems = vehicles.stream().map(v -> {
            DispatchValidateRespVO.VehicleItem item = new DispatchValidateRespVO.VehicleItem();
            item.setVehicleId(v.getId());
            item.setPlateNo(v.getPlateNo());
            item.setPassengerCapacity(v.getPassengerCapacity());
            item.setCargoCapacity(v.getCargoCapacity());
            return item;
        }).toList();

        DispatchValidateRespVO respVO = new DispatchValidateRespVO();
        respVO.setOrderStats(stats);
        respVO.setVehicles(vehicleItems);
        respVO.setCapacityCheck(capacityCheck);
        respVO.setMarkers(markerMap.values().stream().toList());
        respVO.setTimeSeqIssues(issues);
        return respVO;
    }

    /** 聚合站点作业标记：同站多个动作去重，订单数累加 */
    private void addMarker(Map<Long, DispatchValidateRespVO.Marker> markerMap, Long stationId, String action,
                           Map<Long, StationDO> stationMap) {
        if (stationId == null) {
            return;
        }
        DispatchValidateRespVO.Marker marker = markerMap.computeIfAbsent(stationId, k -> {
            DispatchValidateRespVO.Marker m = new DispatchValidateRespVO.Marker();
            m.setStationId(k);
            StationDO station = stationMap.get(k);
            m.setStationName(station != null ? station.getStationName() : null);
            m.setLongitude(station != null ? toDouble(station.getLongitude()) : null);
            m.setLatitude(station != null ? toDouble(station.getLatitude()) : null);
            m.setTypes(new ArrayList<>());
            m.setOrderCount(0);
            return m;
        });
        if (!marker.getTypes().contains(action)) {
            marker.getTypes().add(action);
        }
        marker.setOrderCount(marker.getOrderCount() + 1);
    }

    private DispatchValidateRespVO.TimeSeqIssue timeSeqIssue(TransportOrderDO order, String issue) {
        DispatchValidateRespVO.TimeSeqIssue item = new DispatchValidateRespVO.TimeSeqIssue();
        item.setOrderId(order.getId());
        item.setOrderNo(order.getOrderNo());
        item.setIssue(issue);
        return item;
    }

    private static Double toDouble(BigDecimal value) {
        return value != null ? value.doubleValue() : null;
    }

    @Override
    public DispatchSettlementRespVO settlement(DispatchSettlementReqVO reqVO) {
        // 已完成方案（返程结算口径：方案状态=3 已完成）
        List<DispatchPlanDO> plans = dispatchPlanMapper.selectList(new LambdaQueryWrapperX<DispatchPlanDO>()
                .eq(DispatchPlanDO::getStatus, DispatchPlanStatusEnum.COMPLETED.getStatus())
                .between(DispatchPlanDO::getCreateTime, reqVO.getBatchStart(), reqVO.getBatchEnd()));
        DispatchSettlementRespVO respVO = new DispatchSettlementRespVO();
        if (plans.isEmpty()) {
            respVO.setTotalDistance(BigDecimal.ZERO);
            respVO.setPassengerCount(0);
            respVO.setParcelCount(0);
            respVO.setAvgPassengerWaitMinutes(null);
            respVO.setPerVehicle(List.of());
            return respVO;
        }
        List<Long> planIds = plans.stream().map(DispatchPlanDO::getId).toList();
        Map<Long, DispatchPlanDO> planMap = plans.stream()
                .collect(Collectors.toMap(DispatchPlanDO::getId, java.util.function.Function.identity()));
        List<DispatchPlanItemDO> items = dispatchPlanItemMapper.selectList(new LambdaQueryWrapperX<DispatchPlanItemDO>()
                .in(DispatchPlanItemDO::getPlanId, planIds));

        // 总里程
        BigDecimal totalDistance = plans.stream()
                .map(DispatchPlanDO::getTotalDistance).filter(Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        // 经停明细 → 订单、订单所属车辆/方案
        Map<Long, Long> orderVehicleMap = new HashMap<>();
        Map<Long, Long> orderPlanMap = new HashMap<>();
        Set<Long> orderIds = new HashSet<>();
        for (DispatchPlanItemDO item : items) {
            if (item.getOrderId() != null) {
                orderVehicleMap.putIfAbsent(item.getOrderId(), item.getVehicleId());
                orderPlanMap.putIfAbsent(item.getOrderId(), item.getPlanId());
                orderIds.add(item.getOrderId());
            }
        }
        Map<Long, TransportOrderDO> orderMap = new HashMap<>();
        if (!orderIds.isEmpty()) {
            orderMapper.selectBatchIds(orderIds).forEach(o -> orderMap.put(o.getId(), o));
        }

        // 汇总乘客/包裹与平均等待
        Map<Long, Integer> vehiclePassenger = new HashMap<>();
        Map<Long, Integer> vehicleParcel = new HashMap<>();
        Map<Long, Set<Long>> vehiclePlans = new HashMap<>();
        int passengerCount = 0;
        int parcelCount = 0;
        List<Double> waits = new ArrayList<>();
        for (DispatchPlanItemDO item : items) {
            if (item.getVehicleId() != null && item.getPlanId() != null) {
                vehiclePlans.computeIfAbsent(item.getVehicleId(), k -> new HashSet<>()).add(item.getPlanId());
            }
        }
        for (TransportOrderDO order : orderMap.values()) {
            Long vehicleId = orderVehicleMap.get(order.getId());
            if (Objects.equals(order.getOrderType(), 1)) { // 客运
                int count = getPassengerCount(order);
                passengerCount += count;
                vehiclePassenger.merge(vehicleId, count, Integer::sum);
                // 平均等待：下单 → 方案创建（近似）
                Long planId = orderPlanMap.get(order.getId());
                DispatchPlanDO plan = planId != null ? planMap.get(planId) : null;
                if (order.getCreateTime() != null && plan != null && plan.getCreateTime() != null) {
                    waits.add((double) Duration.between(order.getCreateTime(), plan.getCreateTime()).toMinutes());
                }
            } else { // 货运/邮快件
                int count = getItemCount(order);
                parcelCount += count;
                vehicleParcel.merge(vehicleId, count, Integer::sum);
            }
        }
        // 分车汇总
        List<DispatchSettlementRespVO.VehicleSettlement> perVehicle = vehiclePlans.entrySet().stream()
                .map(e -> {
                    DispatchSettlementRespVO.VehicleSettlement vs = new DispatchSettlementRespVO.VehicleSettlement();
                    vs.setVehicleId(e.getKey());
                    VehicleDO vehicle = vehicleMapper.selectById(e.getKey());
                    vs.setPlateNo(vehicle != null ? vehicle.getPlateNo() : null);
                    vs.setRunCount(e.getValue().size());
                    vs.setPassengerCount(vehiclePassenger.getOrDefault(e.getKey(), 0));
                    vs.setParcelCount(vehicleParcel.getOrDefault(e.getKey(), 0));
                    return vs;
                }).toList();

        respVO.setTotalDistance(totalDistance);
        respVO.setPassengerCount(passengerCount);
        respVO.setParcelCount(parcelCount);
        respVO.setAvgPassengerWaitMinutes(waits.isEmpty() ? null
                : Math.round(waits.stream().mapToDouble(Double::doubleValue).average().orElse(0) * 10) / 10.0);
        respVO.setPerVehicle(perVehicle);
        return respVO;
    }

    // ==================== 私有方法 ====================

    private StationDO validateDepotExists(Long depotStationId) {
        StationDO depot = stationMapper.selectById(depotStationId);
        if (depot == null) {
            throw exception(DISPATCH_DEPOT_NOT_EXISTS);
        }
        return depot;
    }

    private VehicleDO validateVehicleExists(Long vehicleId) {
        VehicleDO vehicle = vehicleMapper.selectById(vehicleId);
        if (vehicle == null) {
            throw exception(VEHICLE_NOT_EXISTS);
        }
        return vehicle;
    }

    /** 校验订单全部存在且已入池，返回 id -> 订单映射 */
    private Map<Long, TransportOrderDO> validatePooledOrders(List<Long> orderIds) {
        Map<Long, TransportOrderDO> orderMap = new HashMap<>();
        orderMapper.selectBatchIds(orderIds).forEach(order -> orderMap.put(order.getId(), order));
        for (Long orderId : orderIds) {
            TransportOrderDO order = orderMap.get(orderId);
            if (order == null || !Objects.equals(order.getStatus(), TransportOrderStatusEnum.POOLED.getStatus())) {
                throw exception(DISPATCH_ORDER_NOT_POOLED);
            }
        }
        return orderMap;
    }

    private DispatchPlanDO validatePlanExists(Long planId) {
        DispatchPlanDO plan = dispatchPlanMapper.selectById(planId);
        if (plan == null) {
            throw exception(DISPATCH_PLAN_NOT_EXISTS);
        }
        return plan;
    }

    /** 构建算法规划请求快照：站点 = 场站 + 订单引用站点去重 */
    private AlgorithmPlanReqDTO buildPlanRequest(StationDO depot, List<VehicleDO> vehicles,
                                                 List<TransportOrderDO> orders,
                                                 Map<String, Object> algorithmConfig, String scenario) {
        Set<Long> stationIds = new LinkedHashSet<>();
        orders.forEach(order -> {
            stationIds.add(order.getPickupStationId());
            stationIds.add(order.getDeliveryStationId());
        });
        stationIds.remove(depot.getId());
        AlgorithmStationDTO depotDTO = toStationDTO(depot);
        List<AlgorithmStationDTO> stations = new ArrayList<>();
        stations.add(depotDTO);
        stationMapper.selectBatchIds(stationIds).stream().map(this::toStationDTO).forEach(stations::add);
        List<AlgorithmVehicleDTO> vehicleDTOs = vehicles.stream()
                .map(vehicle -> AlgorithmVehicleDTO.builder()
                        .vehicleId(vehicle.getId())
                        .passengerCapacity(vehicle.getPassengerCapacity())
                        // 货仓件数取车辆档案，缺省契约默认值 4
                        .cargoCapacity(vehicle.getCargoCapacity() != null
                                ? vehicle.getCargoCapacity() : AlgorithmVehicleDTO.DEFAULT_CARGO_CAPACITY)
                        .build())
                .collect(Collectors.toList());
        List<AlgorithmOrderDTO> orderDTOs = orders.stream()
                .map(order -> toAlgorithmOrders(order, depot.getId())).flatMap(List::stream).collect(Collectors.toList());

        LocalDateTime[] batch = currentBatch();
        return AlgorithmPlanReqDTO.builder()
                .batchStart(batch[0].atOffset(BATCH_ZONE_OFFSET))
                .batchEnd(batch[1].atOffset(BATCH_ZONE_OFFSET))
                .depot(depotDTO)
                .stations(stations)
                .vehicles(vehicleDTOs)
                .orders(orderDTOs)
                .algorithmConfig(algorithmConfig)
                .scenario(scenario)
                .build();
    }

    private AlgorithmStationDTO toStationDTO(StationDO station) {
        return AlgorithmStationDTO.builder()
                .stationId(String.valueOf(station.getId()))
                .longitude(station.getLongitude() != null ? station.getLongitude().doubleValue() : null)
                .latitude(station.getLatitude() != null ? station.getLatitude().doubleValue() : null)
                .build();
    }

    /** 订单映射为算法订单：客运按乘客数拆单（一张算法客运单 = 1 人）；
     *  货运/邮快件按下车站是否为场站区分：场站→站点为派送(DELIVERY)，站点→场站为揽收(PICKUP)。 */
    private List<AlgorithmOrderDTO> toAlgorithmOrders(TransportOrderDO order, Long depotStationId) {
        if (Objects.equals(order.getOrderType(), 1)) { // 客运
            List<AlgorithmOrderDTO> result = new ArrayList<>();
            for (String algorithmOrderId : passengerAlgorithmOrderIds(order)) {
                result.add(AlgorithmOrderDTO.builder()
                        .orderId(algorithmOrderId)
                        .orderType(AlgorithmOrderDTO.TYPE_PASSENGER)
                        .boardingStationId(String.valueOf(order.getPickupStationId()))
                        .alightingStationId(String.valueOf(order.getDeliveryStationId()))
                        .build());
            }
            return result;
        }
        // 揽收：村→场站（收货站为场站），算法经停上车站点并装载
        if (depotStationId != null && Objects.equals(order.getDeliveryStationId(), depotStationId)) {
            return Collections.singletonList(AlgorithmOrderDTO.builder()
                    .orderId(String.valueOf(order.getId()))
                    .orderType(AlgorithmOrderDTO.TYPE_PICKUP)
                    .stationId(String.valueOf(order.getPickupStationId()))
                    .itemCount(getItemCount(order))
                    .build());
        }
        // 派送：场站→村
        return Collections.singletonList(AlgorithmOrderDTO.builder()
                .orderId(String.valueOf(order.getId()))
                .orderType(AlgorithmOrderDTO.TYPE_DELIVERY)
                .stationId(String.valueOf(order.getDeliveryStationId()))
                .itemCount(getItemCount(order))
                .build());
    }

    /** 客运多人单拆分为 "业务订单号#序号" 的算法订单编号 */
    private List<String> passengerAlgorithmOrderIds(TransportOrderDO order) {
        int count = getPassengerCount(order);
        List<String> ids = new ArrayList<>(count);
        for (int i = 1; i <= count; i++) {
            ids.add(order.getId() + "#" + i);
        }
        return ids;
    }

    /** 客运人数取自子表，缺省 1 人 */
    private int getPassengerCount(TransportOrderDO order) {
        List<PassengerOrderDO> subs = passengerOrderMapper.selectList(new LambdaQueryWrapperX<PassengerOrderDO>()
                .eq(PassengerOrderDO::getOrderId, order.getId()));
        return subs.isEmpty() || subs.get(0).getPassengerCount() == null ? 1 : subs.get(0).getPassengerCount();
    }

    /** 算法订单编号还原业务订单编号：去掉 "#序号" 拆单后缀 */
    private static Long toBusinessOrderId(String algorithmOrderId) {
        int suffixIndex = algorithmOrderId.indexOf('#');
        return Long.valueOf(suffixIndex >= 0 ? algorithmOrderId.substring(0, suffixIndex) : algorithmOrderId);
    }

    /** 货运/邮快件件数取自子表，缺省 1 件 */
    private int getItemCount(TransportOrderDO order) {
        Integer itemCount = null;
        if (Objects.equals(order.getOrderType(), 2)) { // 货运
            List<CargoOrderDO> subs = cargoOrderMapper.selectList(CargoOrderDO::getOrderId, order.getId());
            itemCount = subs.isEmpty() ? null : subs.get(0).getItemCount();
        } else if (Objects.equals(order.getOrderType(), 3)) { // 邮快件
            List<PostalOrderDO> subs = postalOrderMapper.selectList(PostalOrderDO::getOrderId, order.getId());
            itemCount = subs.isEmpty() ? null : subs.get(0).getItemCount();
        }
        return itemCount != null ? itemCount : 1;
    }

    private AlgorithmRouteStopDTO buildStop(Long stationId, String orderId, String action) {
        return AlgorithmRouteStopDTO.builder()
                .stationId(String.valueOf(stationId))
                .orderId(orderId)
                .action(action)
                .build();
    }

    private DispatchPlanDO createPlan(DispatchTaskDO task, DispatchPlanModeEnum mode, BigDecimal totalDistance,
                                      String algorithmVersion, String parameterVersion) {
        DispatchPlanDO plan = DispatchPlanDO.builder()
                .taskId(task.getId())
                .planVersion(1)
                .mode(mode.getMode())
                .algorithmVersion(algorithmVersion)
                .parameterVersion(parameterVersion)
                .totalDistance(totalDistance)
                .status(DispatchPlanStatusEnum.PENDING.getStatus())
                .build();
        dispatchPlanMapper.insert(plan);
        return plan;
    }

    /** 经停明细落库：visit_sequence 从 1 递增；补填司机归属（按车辆当前有效人车绑定），预计到达时间待算法输出带出 */
    private void insertPlanItems(Long planId, Long vehicleId, List<AlgorithmRouteStopDTO> stops) {
        Long driverId = resolveDriverId(vehicleId);
        for (int i = 0; i < stops.size(); i++) {
            AlgorithmRouteStopDTO stop = stops.get(i);
            PlanItemActionEnum action = PlanItemActionEnum.fromCode(stop.getAction());
            dispatchPlanItemMapper.insert(DispatchPlanItemDO.builder()
                    .planId(planId)
                    .vehicleId(vehicleId)
                    .driverId(driverId)
                    .stationId(stop.getStationId() != null ? Long.valueOf(stop.getStationId()) : null)
                    .orderId(stop.getOrderId() != null ? toBusinessOrderId(stop.getOrderId()) : null)
                    .visitSequence(i + 1)
                    .actionType(action != null ? action.getAction() : null)
                    .build());
        }
    }

    /** 按车辆当前有效人车绑定解析司机编号（算法派单结果按司机可查的前提） */
    private Long resolveDriverId(Long vehicleId) {
        // 防御：单测等非 Spring 上下文可能未注入 mapper，此时不派司机即可
        if (vehicleId == null || driverVehicleMapper == null) {
            return null;
        }
        List<DriverVehicleDO> bindings = driverVehicleMapper.selectActiveBindings();
        if (bindings == null) {
            return null;
        }
        return bindings.stream()
                .filter(bind -> Objects.equals(bind.getVehicleId(), vehicleId))
                .map(DriverVehicleDO::getDriverId)
                .findFirst()
                .orElse(null);
    }

    private void insertPlanLog(Long planId, Integer fromStatus, Integer toStatus, String reason) {
        dispatchPlanLogMapper.insert(DispatchPlanLogDO.builder()
                .planId(planId)
                .fromStatus(fromStatus)
                .toStatus(toStatus)
                .operator(currentOperator())
                .reason(reason)
                .build());
    }

    /** 方案内订单编号；vehicleId 为空表示方案全部订单 */
    private List<Long> selectPlanOrderIds(Long planId, Long vehicleId) {
        return dispatchPlanItemMapper.selectList(new LambdaQueryWrapperX<DispatchPlanItemDO>()
                        .eq(DispatchPlanItemDO::getPlanId, planId)
                        .eqIfPresent(DispatchPlanItemDO::getVehicleId, vehicleId)
                        .isNotNull(DispatchPlanItemDO::getOrderId))
                .stream().map(DispatchPlanItemDO::getOrderId).distinct().collect(Collectors.toList());
    }

    private void updateOrdersStatus(List<Long> orderIds, TransportOrderStatusEnum status) {
        if (orderIds.isEmpty()) {
            return;
        }
        TransportOrderDO updateObj = new TransportOrderDO();
        updateObj.setStatus(status.getStatus());
        orderMapper.update(updateObj, new LambdaQueryWrapperX<TransportOrderDO>()
                .in(TransportOrderDO::getId, orderIds));
    }

    /** 当前半小时批次区间：分钟 < 30 则 :00，否则 :30 */
    private static LocalDateTime[] currentBatch() {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime start = now.withMinute(now.getMinute() < 30 ? 0 : 30).withSecond(0).withNano(0);
        return new LocalDateTime[]{start, start.plusMinutes(30)};
    }

    /** 当前操作人：优先昵称，其次用户编号 */
    private static String currentOperator() {
        String nickname = SecurityFrameworkUtils.getLoginUserNickname();
        if (StrUtil.isNotBlank(nickname)) {
            return nickname;
        }
        Long userId = SecurityFrameworkUtils.getLoginUserId();
        return userId != null ? String.valueOf(userId) : null;
    }

    private String generateTaskNo() {
        return "DT" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmssSSS"));
    }

}
