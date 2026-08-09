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
import cn.iocoder.yudao.module.transport.dal.dataobject.order.CargoOrderDO;
import cn.iocoder.yudao.module.transport.dal.dataobject.order.PassengerOrderDO;
import cn.iocoder.yudao.module.transport.dal.dataobject.order.PostalOrderDO;
import cn.iocoder.yudao.module.transport.dal.dataobject.order.TransportOrderDO;
import cn.iocoder.yudao.module.transport.dal.dataobject.station.StationDO;
import cn.iocoder.yudao.module.transport.dal.dataobject.vehicle.VehicleDO;
import cn.iocoder.yudao.module.transport.dal.mysql.dispatch.*;
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
        TransportOrderDO updateObj = new TransportOrderDO();
        updateObj.setStatus(TransportOrderStatusEnum.POOLED.getStatus());
        return orderMapper.update(updateObj, new LambdaQueryWrapperX<TransportOrderDO>()
                .eq(TransportOrderDO::getStatus, TransportOrderStatusEnum.CREATED.getStatus())
                .between(TransportOrderDO::getCreateTime, reqVO.getBatchStart(), reqVO.getBatchEnd()));
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
            } else { // 货运/邮快件：派送
                stops.add(buildStop(order.getDeliveryStationId(), String.valueOf(order.getId()),
                        AlgorithmRouteStopDTO.ACTION_DELIVER));
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
                .map(this::toAlgorithmOrders).flatMap(List::stream).collect(Collectors.toList());

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

    /** 订单映射为算法订单：客运按乘客数拆单（一张算法客运单 = 1 人），货运/邮快件 -> DELIVERY */
    private List<AlgorithmOrderDTO> toAlgorithmOrders(TransportOrderDO order) {
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

    /** 经停明细落库：visit_sequence 从 1 递增，预计到达时间留空 */
    private void insertPlanItems(Long planId, Long vehicleId, List<AlgorithmRouteStopDTO> stops) {
        for (int i = 0; i < stops.size(); i++) {
            AlgorithmRouteStopDTO stop = stops.get(i);
            PlanItemActionEnum action = PlanItemActionEnum.fromCode(stop.getAction());
            dispatchPlanItemMapper.insert(DispatchPlanItemDO.builder()
                    .planId(planId)
                    .vehicleId(vehicleId)
                    .stationId(stop.getStationId() != null ? Long.valueOf(stop.getStationId()) : null)
                    .orderId(stop.getOrderId() != null ? toBusinessOrderId(stop.getOrderId()) : null)
                    .visitSequence(i + 1)
                    .actionType(action != null ? action.getAction() : null)
                    .build());
        }
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
