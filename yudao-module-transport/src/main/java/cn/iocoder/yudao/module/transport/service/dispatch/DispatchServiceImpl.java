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

import cn.iocoder.yudao.module.transport.dal.dataobject.route.RouteStationDO;

import cn.iocoder.yudao.module.transport.dal.dataobject.shift.ShiftDO;

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

import cn.iocoder.yudao.module.transport.dal.mysql.route.RouteStationMapper;

import cn.iocoder.yudao.module.transport.dal.mysql.shift.ShiftMapper;

import cn.iocoder.yudao.module.transport.dal.mysql.station.StationMapper;

import cn.iocoder.yudao.module.transport.dal.mysql.vehicle.VehicleMapper;

import cn.iocoder.yudao.module.transport.enums.dispatch.*;

import cn.iocoder.yudao.module.transport.integration.algorithm.AlgorithmAdapter;

import cn.iocoder.yudao.module.transport.integration.algorithm.AlgorithmResultValidator;

import cn.iocoder.yudao.module.transport.integration.algorithm.dto.*;

import cn.iocoder.yudao.module.transport.util.GeoDistanceUtil;

import cn.iocoder.yudao.module.transport.util.StationAccessUtil;

import cn.iocoder.yudao.framework.common.enums.UserTypeEnum;

import cn.iocoder.yudao.module.member.api.user.MemberUserApi;

import cn.iocoder.yudao.module.member.api.user.dto.MemberUserRespDTO;

import cn.iocoder.yudao.module.system.api.social.SocialClientApi;

import cn.iocoder.yudao.module.system.api.social.dto.SocialWxaSubscribeMessageSendReqDTO;

import cn.iocoder.yudao.module.transport.dal.dataobject.driver.DriverDO;

import cn.iocoder.yudao.module.transport.dal.mysql.driver.DriverMapper;

import lombok.extern.slf4j.Slf4j;

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

@Slf4j

public class DispatchServiceImpl implements DispatchService {



    /** 算法契约时区固定 +08:00，见 docs/api/algorithm-api.yaml */

    private static final ZoneOffset BATCH_ZONE_OFFSET = ZoneOffset.ofHours(8);

    /** 手工派单场景标记 */

    private static final String SCENARIO_MANUAL = "MANUAL";

    /** 算法规模上限（与算法服务 app.py 契约一致）：30 站点 / 25 订单 / 3 车 */

    private static final int MAX_ALGORITHM_STATIONS = 30;

    private static final int MAX_ALGORITHM_ORDERS = 25;

    private static final int MAX_ALGORITHM_VEHICLES = 3;

    /**

     * 批次规划窗口(分钟)：算法要求"整批任务总耗时 ≤ 窗口时长"。

     * 30 分钟（原来的半小时批次）对真实路网（高德时空时长 + 装卸作业）太紧，

     * 2 单以上很容易判 TIME_WINDOW_EXCEEDED；这里按 2 小时规划（可覆盖 yudao.dispatch.batch-minutes）。

     */

    private static final int BATCH_MINUTES = 120;



    @Resource private TransportOrderMapper orderMapper;

    @Resource private CargoOrderMapper cargoOrderMapper;

    @Resource private PostalOrderMapper postalOrderMapper;

    @Resource private PassengerOrderMapper passengerOrderMapper;

    @Resource private StationMapper stationMapper;

    @Resource private ShiftMapper shiftMapper;

    @Resource private RouteStationMapper routeStationMapper;

    @Resource private VehicleMapper vehicleMapper;

    @Resource private DriverVehicleMapper driverVehicleMapper;

    @Resource private TransportDispatchTaskMapper dispatchTaskMapper;

    @Resource private DispatchPlanMapper dispatchPlanMapper;

    @Resource private DispatchPlanItemMapper dispatchPlanItemMapper;

    @Resource private DispatchPlanLogMapper dispatchPlanLogMapper;

    @Resource private DepartureCheckMapper departureCheckMapper;

    @Resource private AlgorithmAdapter algorithmAdapter;

    @Resource private DispatchEstimationService dispatchEstimationService;

    @Resource private MultiLegService multiLegService;

    @Resource private cn.iocoder.yudao.module.transport.service.geo.RoadPolylineService roadPolylineService;

    @Resource private SocialClientApi socialClientApi;

    @Resource private MemberUserApi memberUserApi;

    @Resource private DriverMapper driverMapper;
    @Resource private cn.iocoder.yudao.module.transport.service.notification.UserNotificationService userNotificationService;



    @Override

    public PageResult<TransportOrderDO> getOrderPoolPage(DispatchPoolPageReqVO reqVO) {

        return orderMapper.selectPage(reqVO, new LambdaQueryWrapperX<TransportOrderDO>()

                .inIfPresent(TransportOrderDO::getStatus, TransportOrderStatusEnum.READY_FOR_POOL.getStatus(),

                        TransportOrderStatusEnum.POOLED.getStatus())

                .eqIfPresent(TransportOrderDO::getStatus, reqVO.getStatus())

                .eqIfPresent(TransportOrderDO::getOrderType, reqVO.getOrderType())

                .betweenIfPresent(TransportOrderDO::getCreateTime, reqVO.getCreateTime())

                .orderByDesc(TransportOrderDO::getId));

    }



    @Override

    @Transactional

    public int collectOrders(DispatchCollectReqVO reqVO) {

        // 一键归集：把当前所有「待入池」订单全部入池（演示/批量场景，免勾选）

        if (Boolean.TRUE.equals(reqVO.getAll())) {

            return collectAllReadyForPool();

        }

        // 推荐：按勾选订单归集（orderIds 优先，前端勾选后按 ID 入池）

        if (reqVO.getOrderIds() != null && !reqVO.getOrderIds().isEmpty()) {

            return collectByOrderIds(reqVO.getOrderIds());

        }

        // 兼容：按时间范围归集（批次区间内待调度订单，货运需审核通过）

        return collectByTimeRange(reqVO);

    }



    /** 一键归集全部「待入池」订单（状态 8 → 1），无可见订单返回 0 */

    private int collectAllReadyForPool() {

        List<TransportOrderDO> orders = orderMapper.selectList(new LambdaQueryWrapperX<TransportOrderDO>()

                .eq(TransportOrderDO::getStatus, TransportOrderStatusEnum.READY_FOR_POOL.getStatus()));

        if (orders.isEmpty()) {

            return 0;

        }

        TransportOrderDO updateObj = new TransportOrderDO();

        updateObj.setStatus(TransportOrderStatusEnum.POOLED.getStatus());

        return orderMapper.update(updateObj, new LambdaQueryWrapperX<TransportOrderDO>()

                .eq(TransportOrderDO::getStatus, TransportOrderStatusEnum.READY_FOR_POOL.getStatus()));

    }



    /**

     * 按勾选订单归集：校验全部存在 / 承运审核通过（READY_FOR_POOL 待入池）。

     * Phase 2：只有「待入池」订单可入池——客户提交 → 承运审核 → READY_FOR_POOL → 归集入池。

     * 任一非法明确报错（不悄悄跳过）。

     */

    private int collectByOrderIds(List<Long> orderIds) {

        List<TransportOrderDO> orders = orderMapper.selectBatchIds(orderIds);

        if (orders.size() != orderIds.size()) {

            throw exception(ORDER_NOT_EXISTS);

        }

        for (TransportOrderDO order : orders) {

            if (!Objects.equals(order.getStatus(), TransportOrderStatusEnum.READY_FOR_POOL.getStatus())) {

                throw exception(DISPATCH_ORDER_NOT_COLLECTABLE);

            }

        }

        TransportOrderDO updateObj = new TransportOrderDO();

        updateObj.setStatus(TransportOrderStatusEnum.POOLED.getStatus());

        return orderMapper.update(updateObj, new LambdaQueryWrapperX<TransportOrderDO>()

                .in(TransportOrderDO::getId, orderIds)

                .eq(TransportOrderDO::getStatus, TransportOrderStatusEnum.READY_FOR_POOL.getStatus()));

    }



    /** 兼容：按时间范围归集（区间内待入池订单；未提供区间返回 0） */

    private int collectByTimeRange(DispatchCollectReqVO reqVO) {

        if (reqVO.getBatchStart() == null || reqVO.getBatchEnd() == null) {

            return 0;

        }

        List<TransportOrderDO> orders = orderMapper.selectList(new LambdaQueryWrapperX<TransportOrderDO>()

                .eq(TransportOrderDO::getStatus, TransportOrderStatusEnum.READY_FOR_POOL.getStatus())

                .between(TransportOrderDO::getCreateTime, reqVO.getBatchStart(), reqVO.getBatchEnd()));

        List<Long> ids = orders.stream().map(TransportOrderDO::getId).toList();

        if (ids.isEmpty()) {

            return 0;

        }

        TransportOrderDO updateObj = new TransportOrderDO();

        updateObj.setStatus(TransportOrderStatusEnum.POOLED.getStatus());

        return orderMapper.update(updateObj, new LambdaQueryWrapperX<TransportOrderDO>()

                .in(TransportOrderDO::getId, ids)

                .eq(TransportOrderDO::getStatus, TransportOrderStatusEnum.READY_FOR_POOL.getStatus()));

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

        Map<Long, PassengerOrderDO> passengerMap = preloadPassengerOrders(orders);

        List<AlgorithmRouteStopDTO> stops = new ArrayList<>();

        stops.add(buildStop(depot.getId(), null, AlgorithmRouteStopDTO.ACTION_DEPART));

        for (TransportOrderDO order : orders) {

            if (Objects.equals(order.getOrderType(), 1)) { // 客运：上车 + 下车

                for (String algorithmOrderId : passengerAlgorithmOrderIds(order, passengerMap)) {

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

                null, SCENARIO_MANUAL, null);

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

        // 估算每站预计到达时间（口径同智能派单：批次开始时刻出发，逐站累计行驶 + 停站作业分钟）

        dispatchEstimationService.estimatePlan(plan.getId(), batch[0]);

        updateOrdersStatus(reqVO.getOrderIds(), TransportOrderStatusEnum.ASSIGNED,

                TransportOrderStatusEnum.POOLED);

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

        // 一键智能调度（auto=true）：后端自动选场站 + 自动挑候选车辆（实际车辆数由算法决定）

        StationDO depot;

        List<VehicleDO> vehicles;

        if (Boolean.TRUE.equals(reqVO.getAuto())) {

            List<StationDO> stations = stationMapper.selectList();

            Map<Long, StationDO> stationMap = stations.stream()

                    .filter(s -> s.getId() != null)

                    .collect(Collectors.toMap(StationDO::getId, java.util.function.Function.identity(), (a, b) -> a));

            // 片区分批：订单池可能同时有多个片区（如重庆邮电大学片区 + 成都片区），

            // 混批会让车辆跨城跑几百公里 → 算法 infeasible；这里只取"最新那单所在片区"，

            // 其余片区留在池里，再次点击「一键调度」自动成下一套方案。

            pooledOrders = AutoDispatchPlanner.selectAutoBatch(pooledOrders, stationMap, MAX_ALGORITHM_ORDERS);

            depot = AutoDispatchPlanner.selectDepot(pooledOrders, stations);

            if (depot == null) {

                throw exception(STATION_NOT_EXISTS);

            }

            // 候选车辆：优先避开"已被在途方案占用"的车（同一台车不能同时跑两套方案；

            // 多片区各出一套方案时，两套都排同一台车会让司机端任务混在一起）

            List<VehicleDO> allVehicles = autoCandidateVehicles();

            Set<Long> busyVehicleIds = busyVehicleIds();

            vehicles = AutoDispatchPlanner.selectVehicles(allVehicles, MAX_ALGORITHM_VEHICLES, busyVehicleIds);

            if (vehicles.isEmpty()) {

                // 全部车辆都在执行别的方案 → 退回不排除（保证能出方案，由调度员人工取舍）

                vehicles = AutoDispatchPlanner.selectVehicles(allVehicles, MAX_ALGORITHM_VEHICLES);

            }

            if (vehicles.isEmpty()) {

                throw exception(VEHICLE_NOT_EXISTS);

            }

        } else {

            if (reqVO.getDepotStationId() == null) {

                throw exception(STATION_NOT_EXISTS);

            }

            if (reqVO.getVehicleIds() == null || reqVO.getVehicleIds().isEmpty()) {

                throw exception(VEHICLE_NOT_EXISTS);

            }

            depot = validateDepotExists(reqVO.getDepotStationId());

            vehicles = vehicleMapper.selectBatchIds(reqVO.getVehicleIds());

            if (vehicles.size() < reqVO.getVehicleIds().size()) {

                throw exception(VEHICLE_NOT_EXISTS);

            }

        }

        List<Long> pooledIds = pooledOrders.stream().map(TransportOrderDO::getId).toList();



        // 先构建快照并做规模预检（只读，不占单）：无效输入快速失败，避免先 CAS 抢占后再抛错需要回滚。

        // 联合调度：指定班次时车辆按班次线路公交骨架经停，货运作为绕行插入（Phase 5）

        List<String> skeleton = resolveSkeleton(reqVO.getShiftId(), depot.getId());

        AlgorithmPlanReqDTO algorithmReq = buildPlanRequest(depot, vehicles, pooledOrders,

                AutoDispatchPlanner.mergeAlgorithmConfig(reqVO.getAlgorithmConfig()), reqVO.getScenario(), skeleton);

        // 规模上限预检：客运按人数拆单后可能超 25 单，超限直接报错而非等算法 413

        validateScaleLimit(algorithmReq);



        // P1-004 并发防护：CAS 抢占订单池（仅已入池可推进为已分配）。InnoDB 行锁会串行化并发智能派单：

        // 后到的派单在 CAS 上阻塞至先到提交，随后因订单已非 POOLED 而影响 0 行 → 走 DISPATCH_POOL_EMPTY。

        // 抢占成功后订单即为 ASSIGNED，方案落库后无需再改状态；算法失败/无解需显式回滚抢占（本方法

        // noRollbackFor=ServiceException 不回滚，必须手动释放，否则订单会滞留 ASSIGNED 而无方案）。

        TransportOrderDO claim = new TransportOrderDO();

        claim.setStatus(TransportOrderStatusEnum.ASSIGNED.getStatus());

        int claimed = orderMapper.update(claim, new LambdaQueryWrapperX<TransportOrderDO>()

                .in(TransportOrderDO::getId, pooledIds)

                .eq(TransportOrderDO::getStatus, TransportOrderStatusEnum.POOLED.getStatus()));

        if (claimed == 0) {

            throw exception(DISPATCH_POOL_EMPTY); // 池已被并发派单抢占

        }

        if (claimed < pooledIds.size()) {

            // 池被非智能派单路径并发修改（部分订单已非 POOLED）：抛非 ServiceException 触发整事务回滚，

            // 撤销本次已抢占的订单，避免与其它路径的订单归属产生歧义。

            throw new IllegalStateException("订单池状态并发变更，请刷新后重试");

        }



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



        // 调用算法；失败时任务置 FAILED、订单回滚抢占后透传异常

        AlgorithmPlanRespDTO result;

        try {

            result = algorithmAdapter.plan(algorithmReq);

        } catch (ServiceException ex) {

            task.setStatus(DispatchTaskStatusEnum.FAILED.getStatus());

            task.setErrorMessage(ex.getMessage());

            dispatchTaskMapper.updateById(task);

            releaseClaimedOrders(pooledIds);

            throw ex;

        }

        if (AlgorithmPlanRespDTO.STATUS_INFEASIBLE.equals(result.getStatus())) {

            task.setStatus(DispatchTaskStatusEnum.INFEASIBLE.getStatus());

            dispatchTaskMapper.updateById(task);

            releaseClaimedOrders(pooledIds);

            throw exception(DISPATCH_NO_FEASIBLE, reasonCodeText(result.getReasonCode()));

        }



        // 可行：任务置成功，方案与经停明细落库（订单已在 CAS 抢占时置为已分配）

        task.setStatus(DispatchTaskStatusEnum.SUCCESS.getStatus());

        task.setAlgorithmJobId(result.getRequestId());

        dispatchTaskMapper.updateById(task);

        // 总里程：按算法返回的里程单位处理——degree 时按经停站点坐标 Haversine 换算真实公里，km 时直接使用

        BigDecimal totalDistanceKm = resolveTotalDistanceKm(result, buildCoordMap(algorithmReq));

        DispatchPlanDO plan = createPlan(task, DispatchPlanModeEnum.SMART,

                totalDistanceKm, result.getAlgorithmVersion(), result.getParameterVersion());

        for (AlgorithmVehiclePlanDTO vehiclePlan : result.getVehiclePlans()) {

            insertPlanItems(plan.getId(), vehiclePlan.getVehicleId(), vehiclePlan.getStops());

        }

        // 估算每站预计到达时间（算法不产出耗时，业务后端按经停坐标与均速自估；

        // distanceUnit=km 时携带算法返回的路网分段时长/里程，按真实路网时长累计）

        Map<String, DispatchEstimationService.RoadSegment> roadSegments = new HashMap<>();

        if (AlgorithmPlanRespDTO.DISTANCE_UNIT_KM.equals(result.getDistanceUnit())) {

            for (AlgorithmVehiclePlanDTO vehiclePlan : result.getVehiclePlans()) {

                List<AlgorithmRouteStopDTO> stops = vehiclePlan.getStops();

                for (int i = 0; i < stops.size(); i++) {

                    AlgorithmRouteStopDTO stop = stops.get(i);

                    if (stop.getSegmentDuration() != null) {

                        roadSegments.put(vehiclePlan.getVehicleId() + ":" + (i + 1),

                                new DispatchEstimationService.RoadSegment(

                                        stop.getSegmentDuration(), stop.getSegmentDistance()));

                    }

                }

            }

        }

        dispatchEstimationService.estimatePlan(plan.getId(), batch[0], roadSegments);

        // 多段联运：为方案内每个订单规划运输段（MultiLegPlanner 决定直达/2段/3段），并回写方案聚合字段。

        // 段规划失败不影响已生成的直达方案（多段是增强能力），逐单兜底记录日志。

        List<TransportLegDO> allLegs = new ArrayList<>();

        List<String> reasons = new ArrayList<>();

        // 订单 → 算法分配到的车辆/司机（同一辆车可拼多单；取货段按"该订单所属车辆"派车）

        Map<Long, Long[]> orderVehicleMap = new HashMap<>();

        for (AlgorithmVehiclePlanDTO vehiclePlan : result.getVehiclePlans()) {

            Long plannedVehicleId = vehiclePlan.getVehicleId();

            Long plannedDriverId = resolveDriverId(plannedVehicleId);

            for (AlgorithmRouteStopDTO stop : vehiclePlan.getStops()) {

                Long businessOrderId = stop.getOrderId() != null ? toBusinessOrderId(stop.getOrderId()) : null;

                if (businessOrderId != null) {

                    orderVehicleMap.putIfAbsent(businessOrderId, new Long[]{plannedVehicleId, plannedDriverId});

                }

            }

        }

        for (Long orderId : pooledIds) {

            try {

                Long[] plannedVehicle = orderVehicleMap.get(orderId);

                allLegs.addAll(multiLegService.planLegs(orderId, plan.getId(),

                        plannedVehicle != null ? plannedVehicle[0] : null,

                        plannedVehicle != null ? plannedVehicle[1] : null));

                MultiLegPlanner.PlanResult preview = multiLegService.preview(orderId);

                if (!reasons.contains(preview.reason())) {

                    reasons.add(preview.reason());

                }

            } catch (Exception ex) {

                log.warn("[createSmartPlan] 订单 {} 运输段规划失败：{}", orderId, ex.getMessage());

            }

        }

        if (!allLegs.isEmpty()) {

            int transferCount = pooledIds.size() == 0 ? 0 : allLegs.size() - (int) allLegs.stream()

                    .map(TransportLegDO::getOrderId).distinct().count();

            // 方案总里程口径统一：各运输段**真实路网里程**之和（Leg 已由高德路网回写；无路网时为估算值）

            BigDecimal totalLegDistance = allLegs.stream().map(TransportLegDO::getDistanceKm)

                    .filter(Objects::nonNull).reduce(BigDecimal.ZERO, BigDecimal::add);

            DispatchPlanDO planUpdate = new DispatchPlanDO();

            planUpdate.setId(plan.getId());

            planUpdate.setPlanNo(taskNo + "-P" + plan.getId());

            planUpdate.setPlanningMode(allLegs.size() > pooledIds.size()

                    ? DispatchPlanningModeEnum.MULTI_LEG.getMode() : DispatchPlanningModeEnum.DIRECT.getMode());

            planUpdate.setTotalLegCount(allLegs.size());

            planUpdate.setTransferCount(Math.max(0, transferCount));

            planUpdate.setPlanReason(cn.hutool.core.util.StrUtil.sub(String.join("；", reasons), 0, 2000));

            planUpdate.setEstimatedStartTime(allLegs.stream().map(TransportLegDO::getEstimatedDeparture)

                    .filter(Objects::nonNull).min(LocalDateTime::compareTo).orElse(null));

            planUpdate.setEstimatedArrivalTime(allLegs.stream().map(TransportLegDO::getEstimatedArrival)

                    .filter(Objects::nonNull).max(LocalDateTime::compareTo).orElse(null));

            if (totalLegDistance.compareTo(BigDecimal.ZERO) > 0) {

                planUpdate.setTotalDistance(totalLegDistance);

            }

            dispatchPlanMapper.updateById(planUpdate);

            plan.setPlanNo(planUpdate.getPlanNo());

            plan.setPlanningMode(planUpdate.getPlanningMode());

            plan.setTotalLegCount(planUpdate.getTotalLegCount());

            plan.setTransferCount(planUpdate.getTransferCount());

            plan.setPlanReason(planUpdate.getPlanReason());

        }

        return plan.getId();

    }



    /** 算法失败/无解时回滚 CAS 抢占：已分配订单回到订单池，可重新派单（P1-004） */

    private void releaseClaimedOrders(List<Long> orderIds) {

        updateOrdersStatus(orderIds, TransportOrderStatusEnum.POOLED, TransportOrderStatusEnum.ASSIGNED);

    }



    /**

     * 已被"在途方案"占用的车辆：方案状态 ∈ {待审核, 已下发, 执行中} 的经停明细里出现过的车。

     * 一键调度按片区连出多套方案时用它做避让，避免同一台车被两套在途方案同时占用。

     */

    private Set<Long> busyVehicleIds() {

        List<DispatchPlanDO> activePlans = dispatchPlanMapper.selectList(new LambdaQueryWrapperX<DispatchPlanDO>()

                .in(DispatchPlanDO::getStatus, DispatchPlanStatusEnum.PENDING.getStatus(),

                        DispatchPlanStatusEnum.ISSUED.getStatus(), DispatchPlanStatusEnum.RUNNING.getStatus()));

        Set<Long> planIds = activePlans.stream().map(DispatchPlanDO::getId)

                .filter(Objects::nonNull).collect(Collectors.toSet());

        if (planIds.isEmpty()) {

            return Set.of();

        }

        return dispatchPlanItemMapper.selectList(new LambdaQueryWrapperX<DispatchPlanItemDO>()

                        .in(DispatchPlanItemDO::getPlanId, planIds)).stream()

                .map(DispatchPlanItemDO::getVehicleId)

                .filter(Objects::nonNull)

                .collect(Collectors.toSet());

    }



    /**

     * 自动调度候选车辆：优先取"有在职司机绑定"的车辆。

     *

     * 原因：司机端按「登录会员手机号 = 司机档案手机号」认领任务（`DriverAppServiceImpl.currentDriverOrNull`），

     * 派给没有任何司机绑定的车，任务在司机端根本看不到（只能管理员代核验）。没有可用绑定或车辆表为空时

     * 退回全部车辆，保证仍然能出方案。

     */

    private List<VehicleDO> autoCandidateVehicles() {

        List<VehicleDO> vehicles = vehicleMapper.selectList();

        if (vehicles == null || vehicles.isEmpty() || driverVehicleMapper == null) {

            return vehicles == null ? List.of() : vehicles;

        }

        List<DriverVehicleDO> bindings = driverVehicleMapper.selectActiveBindings();

        if (bindings == null || bindings.isEmpty()) {

            return vehicles;

        }

        Set<Long> boundVehicleIds = bindings.stream().map(DriverVehicleDO::getVehicleId)

                .filter(Objects::nonNull).collect(Collectors.toSet());

        List<VehicleDO> bound = vehicles.stream().filter(v -> boundVehicleIds.contains(v.getId())).toList();

        return bound.isEmpty() ? vehicles : bound;

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

            // 派单下发后：给方案涉及车辆司机发微信订阅消息（发送失败不影响派单主流程）

            notifyDriversOfPlan(plan.getId());

              // 通知订单所属用户：方案已下发
              try {
                  List<Long> orderIds = selectPlanOrderIds(plan.getId(), null);
                  DispatchPlanDO planInfo = dispatchPlanMapper.selectById(plan.getId());
                  String planNo = planInfo != null ? planInfo.getPlanNo() : String.valueOf(plan.getId());
                  for (Long orderId : orderIds) {
                      userNotificationService.sendToOrderUser(orderId, TransportOrderEventTypeEnum.PLAN_ISSUED,
                              cn.iocoder.yudao.module.transport.enums.notification.NotificationLevelEnum.SUCCESS, false,
                              "运输方案已下发",
                              "您的订单已安排运输（方案" + planNo + "），司机将在指定站点取件，请关注配送进度");
                  }
              } catch (Exception ex) {
                  log.warn("[reviewPlan] notify user plan issued failed", ex);
              }

        } else {

            if (StrUtil.isBlank(reqVO.getReason())) {

                throw exception(BAD_REQUEST);

            }

            plan.setStatus(DispatchPlanStatusEnum.VOID.getStatus());

            dispatchPlanMapper.updateById(plan);

            // 驳回后方案内订单回到订单池，可重新派单

            updateOrdersStatus(selectPlanOrderIds(reqVO.getPlanId(), null), TransportOrderStatusEnum.POOLED,

                    TransportOrderStatusEnum.ASSIGNED);

        }

        insertPlanLog(plan.getId(), DispatchPlanStatusEnum.PENDING.getStatus(), plan.getStatus(), reqVO.getReason());

    }



    /**

     * 派单下发后：给方案涉及车辆司机发微信订阅消息「新派单任务」。

     *

     * 【预留项 · 当前未生效】个人主体小程序在微信公众平台看不到订阅消息入口/模板，

     * 需等小程序换「组织主体」并在公众平台申请订阅消息模板后才能真正推送到司机微信。

     * 落地条件（TODO）：

     *   1. 小程序换组织主体（个人主体 → 企业/组织）；

     *   2. 微信公众平台「功能 → 订阅消息」申请一次性订阅模板，模板标题与此处 templateTitle 对齐；

     *   3. 后端 wx.miniapp.appid/secret 配成真实小程序（建议走环境变量 WX_MINIAPP_APPID/SECRET，secret 不入库）。

     *

     * 发送失败静默降级（未配置 appid/secret 或无 openid 时 sendWxaSubscribeMessage 内部已 warn），不影响派单。

     * 模板字段 key（thing1/thing2）需与微信公众平台申请的订阅消息模板字段对齐。

     */

    private void notifyDriversOfPlan(Long planId) {

        try {

            List<DispatchPlanItemDO> items = dispatchPlanItemMapper.selectList(new LambdaQueryWrapperX<DispatchPlanItemDO>()

                    .eq(DispatchPlanItemDO::getPlanId, planId));

            Set<Long> vehicleIds = items.stream().map(DispatchPlanItemDO::getVehicleId)

                    .filter(Objects::nonNull).collect(Collectors.toSet());

            if (vehicleIds.isEmpty()) {

                return;

            }

            List<DriverVehicleDO> bindings = driverVehicleMapper.selectActiveBindings();

            for (Long vehicleId : vehicleIds) {

                Long driverId = bindings.stream()

                        .filter(b -> Objects.equals(b.getVehicleId(), vehicleId))

                        .map(DriverVehicleDO::getDriverId).findFirst().orElse(null);

                if (driverId == null) {

                    continue;

                }

                DriverDO driver = driverMapper.selectById(driverId);

                if (driver == null || StrUtil.isBlank(driver.getMobile())) {

                    continue;

                }

                MemberUserRespDTO member = memberUserApi.getUserByMobile(driver.getMobile());

                if (member == null) {

                    continue;

                }

                // 统计该司机的任务摘要
                List<DispatchPlanItemDO> driverItems = items.stream()
                        .filter(it -> Objects.equals(it.getVehicleId(), vehicleId)).toList();
                int stopCount = driverItems.size();
                int orderCount = (int) driverItems.stream()
                        .map(DispatchPlanItemDO::getOrderId).filter(Objects::nonNull).distinct().count();
                String taskSummary = stopCount + "个站点" + (orderCount > 0 ? "、" + orderCount + "单货物" : "");

                // 微信订阅消息
                socialClientApi.sendWxaSubscribeMessage(new SocialWxaSubscribeMessageSendReqDTO()
                        .setUserId(member.getId())
                        .setUserType(UserTypeEnum.MEMBER.getValue())
                        .setTemplateTitle("派单通知")
                        .setPage("pages/driver/workbench/workbench")
                        .addMessage("thing1", "新任务：" + taskSummary)
                        .addMessage("thing2", "请查看司机端工作台"));

                // 站内通知（司机端消息中心）
                userNotificationService.sendToDriver(driverId,
                        TransportOrderEventTypeEnum.LEG_ASSIGNED,
                        cn.iocoder.yudao.module.transport.enums.notification.NotificationLevelEnum.ACTION_REQUIRED,
                        true, "您有新的运输任务",
                        "方案#" + planId + "已分配给您：" + taskSummary + "，请及时接单",
                        null, planId, null);

            }

        } catch (Exception e) {

            log.warn("[notifyDriversOfPlan][planId={} 发送订阅消息失败，不影响派单]", planId, e);

        }

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

                TransportOrderStatusEnum.DEPARTED, TransportOrderStatusEnum.ASSIGNED);

    }



    @Override

    public DispatchPlanRespVO getPlan(Long id) {

        DispatchPlanDO plan = validatePlanExists(id);

        DispatchPlanRespVO respVO = BeanUtils.toBean(plan, DispatchPlanRespVO.class);

        List<DispatchPlanItemDO> items = dispatchPlanItemMapper.selectList(new LambdaQueryWrapperX<DispatchPlanItemDO>()

                .eq(DispatchPlanItemDO::getPlanId, id)

                .orderByAsc(DispatchPlanItemDO::getVisitSequence));

        fillItemDisplayNames(items);

        respVO.setItems(items);

        // 摘要（一键智能调度结果卡/方案列表用）：订单数、车辆数、场站名

        respVO.setOrderCount((int) items.stream().map(DispatchPlanItemDO::getOrderId)

                .filter(Objects::nonNull).distinct().count());

        respVO.setVehicleCount((int) items.stream().map(DispatchPlanItemDO::getVehicleId)

                .filter(Objects::nonNull).distinct().count());

        Long depotStationId = items.stream().filter(i -> Objects.equals(i.getActionType(), 1))

                .map(DispatchPlanItemDO::getStationId).filter(Objects::nonNull).findFirst().orElse(null);

        if (depotStationId != null) {

            StationDO depot = stationMapper.selectById(depotStationId);

            respVO.setDepotStationName(depot != null ? depot.getStationName() : null);

        }

        return respVO;

    }



    @Override

    public PageResult<DispatchPlanDO> getPlanPage(DispatchPlanPageReqVO reqVO) {

        return dispatchPlanMapper.selectPage(reqVO);

    }



    /**

     * 方案真实道路地图数据（调度可视化用）：

     * 按「车辆 + 经停序号」给出每段（上一站→本站）的真实道路轨迹，

     * 高德不可用/失败时该段退化成两点直线并标注 {@code EUCLIDEAN}（不伪装真实道路）。

     */

    @Override

    public DispatchRoadmapRespVO getPlanRoadmap(Long id) {

        validatePlanExists(id);

        List<DispatchPlanItemDO> items = dispatchPlanItemMapper.selectList(new LambdaQueryWrapperX<DispatchPlanItemDO>()

                .eq(DispatchPlanItemDO::getPlanId, id)

                .orderByAsc(DispatchPlanItemDO::getVehicleId)

                .orderByAsc(DispatchPlanItemDO::getVisitSequence));

        DispatchRoadmapRespVO respVO = new DispatchRoadmapRespVO();

        respVO.setPlanId(id);

        if (items.isEmpty()) {

            respVO.setProvider("EUCLIDEAN");

            respVO.setSegments(List.of());

            return respVO;

        }

        Set<Long> stationIds = items.stream().map(DispatchPlanItemDO::getStationId)

                .filter(Objects::nonNull).collect(Collectors.toSet());

        Map<Long, StationDO> stationMap = stationIds.isEmpty() ? Map.of()

                : stationMapper.selectBatchIds(stationIds).stream()

                        .collect(Collectors.toMap(StationDO::getId, s -> s, (a, b) -> a));



        List<DispatchRoadmapRespVO.Segment> segments = new ArrayList<>();

        boolean anyReal = false;

        boolean anyFallback = false;

        // 按车辆分组：组内 visitSequence 升序，相邻两站构成一段

        Map<Long, List<DispatchPlanItemDO>> byVehicle = new LinkedHashMap<>();

        for (DispatchPlanItemDO item : items) {

            byVehicle.computeIfAbsent(item.getVehicleId() == null ? 0L : item.getVehicleId(),

                    k -> new ArrayList<>()).add(item);

        }

        for (Map.Entry<Long, List<DispatchPlanItemDO>> entry : byVehicle.entrySet()) {

            List<DispatchPlanItemDO> stops = entry.getValue();

            stops.sort(Comparator.comparing(DispatchPlanItemDO::getVisitSequence,

                    Comparator.nullsLast(Comparator.naturalOrder())));

            for (int i = 1; i < stops.size(); i++) {

                StationDO from = stops.get(i - 1).getStationId() == null ? null

                        : stationMap.get(stops.get(i - 1).getStationId());

                StationDO to = stops.get(i).getStationId() == null ? null

                        : stationMap.get(stops.get(i).getStationId());

                if (from == null || to == null || from.getLongitude() == null || from.getLatitude() == null

                        || to.getLongitude() == null || to.getLatitude() == null) {

                    continue;

                }

                List<double[]> road = roadPolylineService.route(

                        from.getLongitude().doubleValue(), from.getLatitude().doubleValue(),

                        to.getLongitude().doubleValue(), to.getLatitude().doubleValue());

                boolean real = road != null && road.size() >= 2;

                if (real) {

                    anyReal = true;

                } else {

                    anyFallback = true;

                }

                List<double[]> points = real ? road : List.of(

                        new double[]{from.getLongitude().doubleValue(), from.getLatitude().doubleValue()},

                        new double[]{to.getLongitude().doubleValue(), to.getLatitude().doubleValue()});

                DispatchRoadmapRespVO.Segment segment = new DispatchRoadmapRespVO.Segment();

                segment.setVehicleId(stops.get(i).getVehicleId());

                segment.setVisitSequence(stops.get(i).getVisitSequence());

                segment.setFromStationId(from.getId());

                segment.setToStationId(to.getId());

                segment.setFromStationName(from.getStationName());

                segment.setToStationName(to.getStationName());

                segment.setProvider(real ? "AMAP" : "EUCLIDEAN");

                segment.setPoints(points.stream().map(p -> {

                    DispatchRoadmapRespVO.Point point = new DispatchRoadmapRespVO.Point();

                    point.setLongitude(p[0]);

                    point.setLatitude(p[1]);

                    return point;

                }).toList());

                segments.add(segment);

            }

        }

        respVO.setSegments(segments);

        respVO.setProvider(anyReal && anyFallback ? "MIXED" : (anyReal ? "AMAP" : "EUCLIDEAN"));

        return respVO;

    }



    /**

     * 补齐经停明细的展示字段（站点名/订单号，均不落库）：

     * 后台「方案详情」与「调度结果可视化」不需要再逐条回查站点与订单。

     */

    private void fillItemDisplayNames(List<DispatchPlanItemDO> items) {

        if (items == null || items.isEmpty()) {

            return;

        }

        Set<Long> stationIds = items.stream().map(DispatchPlanItemDO::getStationId)

                .filter(Objects::nonNull).collect(Collectors.toSet());

        Map<Long, String> stationNames = stationIds.isEmpty() ? Map.of()

                : stationMapper.selectList(new LambdaQueryWrapperX<StationDO>().in(StationDO::getId, stationIds))

                        .stream().collect(Collectors.toMap(StationDO::getId, StationDO::getStationName, (a, b) -> a));

        Set<Long> orderIds = items.stream().map(DispatchPlanItemDO::getOrderId)

                .filter(Objects::nonNull).collect(Collectors.toSet());

        Map<Long, String> orderNos = orderIds.isEmpty() ? Map.of()

                : orderMapper.selectList(new LambdaQueryWrapperX<TransportOrderDO>().in(TransportOrderDO::getId, orderIds))

                        .stream().collect(Collectors.toMap(TransportOrderDO::getId, TransportOrderDO::getOrderNo, (a, b) -> a));

        for (DispatchPlanItemDO item : items) {

            item.setStationName(item.getStationId() != null ? stationNames.get(item.getStationId()) : null);

            item.setOrderNo(item.getOrderId() != null ? orderNos.get(item.getOrderId()) : null);

        }

    }



    @Override

    public DispatchValidateRespVO validate(DispatchValidateReqVO reqVO) {

        // 订单池（与智能派单取数一致：已入池订单）

        List<TransportOrderDO> pooledOrders = orderMapper.selectList(new LambdaQueryWrapperX<TransportOrderDO>()

                .eq(TransportOrderDO::getStatus, TransportOrderStatusEnum.POOLED.getStatus()));

        // 自动模式：场站与候选车辆由后端确定性规则推导（UI 只点一次「开始智能调度」）

        boolean auto = Boolean.TRUE.equals(reqVO.getAuto());

        StationDO depot;

        List<VehicleDO> vehicles;

        int availableVehicleCount;

        if (auto) {

            List<StationDO> stations = stationMapper.selectList();

            Map<Long, StationDO> stationMapForBatch = stations.stream()

                    .filter(s -> s.getId() != null)

                    .collect(Collectors.toMap(StationDO::getId, java.util.function.Function.identity(), (a, b) -> a));

            // 与 createSmartPlan 同一批次口径：校验看到的订单数就是本次真正会被调度的订单数

            pooledOrders = AutoDispatchPlanner.selectAutoBatch(pooledOrders, stationMapForBatch, MAX_ALGORITHM_ORDERS);

            depot = AutoDispatchPlanner.selectDepot(pooledOrders, stations);

            if (depot == null) {

                throw exception(STATION_NOT_EXISTS);

            }

            List<VehicleDO> available = autoCandidateVehicles();

            availableVehicleCount = AutoDispatchPlanner.selectVehicles(available, Integer.MAX_VALUE).size();

            // 与 createSmartPlan 同口径：避开在途方案占用的车辆，全部在途时回退

            vehicles = AutoDispatchPlanner.selectVehicles(available, MAX_ALGORITHM_VEHICLES, busyVehicleIds());

            if (vehicles.isEmpty()) {

                vehicles = AutoDispatchPlanner.selectVehicles(available, MAX_ALGORITHM_VEHICLES);

            }

            if (vehicles.isEmpty()) {

                throw exception(VEHICLE_NOT_EXISTS);

            }

        } else {

            if (reqVO.getDepotStationId() == null || reqVO.getVehicleIds() == null || reqVO.getVehicleIds().isEmpty()) {

                throw exception(VEHICLE_NOT_EXISTS);

            }

            depot = validateDepotExists(reqVO.getDepotStationId());

            vehicles = vehicleMapper.selectBatchIds(reqVO.getVehicleIds());

            // 车辆存在性校验（口径对齐 createManualPlan 的 validateVehicleExists）

            if (vehicles.size() < reqVO.getVehicleIds().size()) {

                throw exception(VEHICLE_NOT_EXISTS);

            }

            availableVehicleCount = vehicles.size();

        }

        Map<Long, StationDO> stationMap = stationMapper.selectList().stream()

                .collect(Collectors.toMap(StationDO::getId, java.util.function.Function.identity()));

        // 子表一次加载，内存匹配（消除逐单查询的 N+1）

        Map<Long, PassengerOrderDO> passengerMap = preloadPassengerOrders(pooledOrders);

        Map<Long, CargoOrderDO> cargoMap = preloadCargoOrders(pooledOrders);

        Map<Long, PostalOrderDO> postalMap = preloadPostalOrders(pooledOrders);



        // 订单统计 + 站点作业标记 + 客运时序检查

        DispatchValidateRespVO.OrderStats stats = new DispatchValidateRespVO.OrderStats();

        stats.setPassengerCount(0);

        stats.setDeliveryCount(0);

        stats.setPickupCount(0);

        stats.setParcelCount(0);

        // 净载荷双维度口径：派送件 / 揽收件 分别累计（出程派送、返程揽收，货仓依次复用）

        int deliveryItems = 0;

        int pickupItems = 0;

        Map<Long, DispatchValidateRespVO.Marker> markerMap = new LinkedHashMap<>();

        List<DispatchValidateRespVO.TimeSeqIssue> issues = new ArrayList<>();

        for (TransportOrderDO order : pooledOrders) {

            if (Objects.equals(order.getOrderType(), 1)) { // 客运

                stats.setPassengerCount(stats.getPassengerCount() + getPassengerCount(order, passengerMap));

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

                    pickupItems += getItemCount(order, cargoMap, postalMap);

                    addMarker(markerMap, order.getPickupStationId(), AlgorithmRouteStopDTO.ACTION_PICKUP, stationMap);

                } else {

                    stats.setDeliveryCount(stats.getDeliveryCount() + 1);

                    deliveryItems += getItemCount(order, cargoMap, postalMap);

                    addMarker(markerMap, order.getDeliveryStationId(), AlgorithmRouteStopDTO.ACTION_DELIVER, stationMap);

                }

                stats.setParcelCount(stats.getParcelCount() + getItemCount(order, cargoMap, postalMap));

            }

        }



        // 运力校验：总容量 vs 总需求，超出即预警。

        // 载货按净载荷双维度口径：max(派送件, 揽收件) 与总货仓容量比较（与算法 solver 一致），

        // 不再把「派送+揽收」全部累计（旧口径把返程可用仓位置 0，闲置运力无法利用）。

        DispatchValidateRespVO.CapacityCheck capacityCheck = new DispatchValidateRespVO.CapacityCheck();

        int passengerCapacity = vehicles.stream()

                .mapToInt(v -> v.getPassengerCapacity() != null ? v.getPassengerCapacity() : 0).sum();

        int cargoCapacity = vehicles.stream()

                .mapToInt(v -> v.getCargoCapacity() != null ? v.getCargoCapacity() : 0).sum();

        capacityCheck.setTotalPassengerCapacity(passengerCapacity);

        capacityCheck.setTotalCargoCapacity(cargoCapacity);

        capacityCheck.setPassengerExceed(Math.max(0, stats.getPassengerCount() - passengerCapacity));

        capacityCheck.setCargoExceed(Math.max(0, Math.max(deliveryItems, pickupItems) - cargoCapacity));

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

        respVO.setAuto(auto);

        respVO.setDepotStationId(depot.getId());

        respVO.setDepotStationName(depot.getStationName());

        respVO.setAvailableVehicleCount(availableVehicleCount);

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

        if (reqVO.getBatchStart() == null || reqVO.getBatchEnd() == null

                || !reqVO.getBatchStart().isBefore(reqVO.getBatchEnd())) {

            throw exception(BAD_REQUEST);

        }

        // 执行中/已完成方案（返程结算口径：方案状态 IN (2 执行中, 3 已完成)；方案 COMPLETED 流转留待后续迭代，当前终态为执行中）

        List<DispatchPlanDO> plans = dispatchPlanMapper.selectList(new LambdaQueryWrapperX<DispatchPlanDO>()

                .in(DispatchPlanDO::getStatus, DispatchPlanStatusEnum.RUNNING.getStatus(),

                        DispatchPlanStatusEnum.COMPLETED.getStatus())

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

        // 子表一次加载，内存匹配（消除逐单查询的 N+1）

        List<TransportOrderDO> itemOrders = List.copyOf(orderMap.values());

        Map<Long, PassengerOrderDO> passengerMap = preloadPassengerOrders(itemOrders);

        Map<Long, CargoOrderDO> cargoMap = preloadCargoOrders(itemOrders);

        Map<Long, PostalOrderDO> postalMap = preloadPostalOrders(itemOrders);



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

                int count = getPassengerCount(order, passengerMap);

                passengerCount += count;

                vehiclePassenger.merge(vehicleId, count, Integer::sum);

                // 平均等待：下单 → 方案创建（近似）

                Long planId = orderPlanMap.get(order.getId());

                DispatchPlanDO plan = planId != null ? planMap.get(planId) : null;

                if (order.getCreateTime() != null && plan != null && plan.getCreateTime() != null) {

                    waits.add((double) Duration.between(order.getCreateTime(), plan.getCreateTime()).toMinutes());

                }

            } else { // 货运/邮快件

                int count = getItemCount(order, cargoMap, postalMap);

                parcelCount += count;

                vehicleParcel.merge(vehicleId, count, Integer::sum);

            }

        }

        // 分车汇总（车辆一次批量加载，消除逐车查询）

        Map<Long, VehicleDO> vehicleMap = new HashMap<>();

        if (!vehiclePlans.isEmpty()) {

            vehicleMapper.selectBatchIds(vehiclePlans.keySet()).forEach(v -> vehicleMap.put(v.getId(), v));

        }

        List<DispatchSettlementRespVO.VehicleSettlement> perVehicle = vehiclePlans.entrySet().stream()

                .map(e -> {

                    DispatchSettlementRespVO.VehicleSettlement vs = new DispatchSettlementRespVO.VehicleSettlement();

                    vs.setVehicleId(e.getKey());

                    VehicleDO vehicle = vehicleMap.get(e.getKey());

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



    /** 算法规模上限预检（30 站点 / 25 订单 / 3 车）：客运按人数拆单后可能超 25 单，超限直接报错避免等算法 413 */

    private void validateScaleLimit(AlgorithmPlanReqDTO algorithmReq) {

        int stationCount = algorithmReq.getStations() != null ? algorithmReq.getStations().size() : 0;

        int orderCount = algorithmReq.getOrders() != null ? algorithmReq.getOrders().size() : 0;

        int vehicleCount = algorithmReq.getVehicles() != null ? algorithmReq.getVehicles().size() : 0;

        if (stationCount > MAX_ALGORITHM_STATIONS

                || orderCount > MAX_ALGORITHM_ORDERS

                || vehicleCount > MAX_ALGORITHM_VEHICLES) {

            throw exception(DISPATCH_SCALE_OVER_LIMIT,

                    "站点 " + stationCount + " / 订单 " + orderCount + " / 车辆 " + vehicleCount);

        }

    }



    /** 从算法快照构建 站点Id -> {lon, lat} 坐标表（含场站），用于把度数里程换算为真实公里 */

    private static Map<String, double[]> buildCoordMap(AlgorithmPlanReqDTO algorithmReq) {

        Map<String, double[]> coordMap = new HashMap<>();

        if (algorithmReq.getDepot() != null && algorithmReq.getDepot().getLongitude() != null

                && algorithmReq.getDepot().getLatitude() != null) {

            coordMap.put(algorithmReq.getDepot().getStationId(),

                    new double[]{algorithmReq.getDepot().getLongitude(), algorithmReq.getDepot().getLatitude()});

        }

        if (algorithmReq.getStations() != null) {

            for (AlgorithmStationDTO station : algorithmReq.getStations()) {

                if (station.getLongitude() != null && station.getLatitude() != null) {

                    coordMap.put(station.getStationId(),

                            new double[]{station.getLongitude(), station.getLatitude()});

                }

            }

        }

        return coordMap;

    }



    /** 方案总里程解析：算法返回 km（路网距离）时直接使用；degree（或缺省，欧氏直线）时按经停坐标 Haversine 换算 */

    private static BigDecimal resolveTotalDistanceKm(AlgorithmPlanRespDTO result, Map<String, double[]> coordMap) {

        if (AlgorithmPlanRespDTO.DISTANCE_UNIT_KM.equals(result.getDistanceUnit())) {

            double km = result.getTotalDistance() != null ? result.getTotalDistance() : 0;

            return BigDecimal.valueOf(Math.round(km * 1000) / 1000.0);

        }

        return computeTotalDistanceKm(result.getVehiclePlans(), coordMap);

    }



    /** 按各车经停序列用 Haversine 累加真实公里数；坐标缺失的分段跳过（不记里程） */

    private static BigDecimal computeTotalDistanceKm(List<AlgorithmVehiclePlanDTO> vehiclePlans,

                                                     Map<String, double[]> coordMap) {

        if (vehiclePlans == null || vehiclePlans.isEmpty()) {

            return BigDecimal.ZERO;

        }

        double total = 0;

        for (AlgorithmVehiclePlanDTO vehiclePlan : vehiclePlans) {

            List<AlgorithmRouteStopDTO> stops = vehiclePlan.getStops();

            if (stops == null || stops.size() < 2) {

                continue;

            }

            for (int i = 1; i < stops.size(); i++) {

                double[] from = coordMap.get(stops.get(i - 1).getStationId());

                double[] to = coordMap.get(stops.get(i).getStationId());

                if (from != null && to != null) {

                    total += GeoDistanceUtil.haversineKm(from[0], from[1], to[0], to[1]);

                }

            }

        }

        // 保留 3 位小数公里

        return BigDecimal.valueOf(Math.round(total * 1000) / 1000.0);

    }



    /** 无解原因码转可读文案（前端直接展示） */

    private static String reasonCodeText(String reasonCode) {

        if (reasonCode == null) {

            return "未知原因";

        }

        return switch (reasonCode) {

            case AlgorithmPlanRespDTO.REASON_OVER_CAPACITY -> "运力不足（订单总需求超出可用车辆总容量）";

            case AlgorithmPlanRespDTO.REASON_TIMING_CONFLICT -> "客运上/下车时序冲突，无法排程";

            case AlgorithmPlanRespDTO.REASON_PARTIAL_ONLY -> "当前订单组合只能部分完成，无法生成完整方案";

            // 自研算法服务（ortools）扩展原因码：契约仅约定上三者，以下为 V2 优化新增的细分原因

            case "TIME_WINDOW_EXCEEDED" -> "任务超出批次时间窗，无法在指定时段内完成";

            case "PRELOAD_INSUFFICIENT" -> "场站预装货物不足，无法完成派送";

            case "DISTANCE_MATRIX_INCOMPLETE" -> "路网距离矩阵不完整，暂无法规划";

            case "ROUTE_DURATION_UNAVAILABLE" -> "路网行驶时长缺失，暂无法规划";

            case "VEHICLE_NOT_FOUND" -> "方案引用的车辆不存在";

            default -> reasonCode;

        };

    }



    private StationDO validateDepotExists(Long depotStationId) {

        StationDO depot = stationMapper.selectById(depotStationId);

        if (depot == null) {

            throw exception(DISPATCH_DEPOT_NOT_EXISTS);

        }

        // 站点启用 ≠ 可用于调度：手工派单同样要校验"是否开放调度"（不相信前端）

        if (!StationAccessUtil.dispatchEnabled(depot)) {

            throw exception(STATION_NOT_DISPATCH_ENABLED);

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



    /**

     * 班次线路公交骨架（Mandatory Passenger Service）：班次 → 线路 → 按 sequenceNo 排序的站点编号，

     * 去掉场站（场站由算法自动作为起终点）。shiftId 为空/班次不存在/线路无站点时返回 null（纯 VRP）。

     */

    private List<String> resolveSkeleton(Long shiftId, Long depotStationId) {

        if (shiftId == null) {

            return null;

        }

        ShiftDO shift = shiftMapper.selectById(shiftId);

        if (shift == null || shift.getRouteId() == null) {

            return null;

        }

        List<RouteStationDO> routeStations = routeStationMapper.selectListByRouteIds(List.of(shift.getRouteId()));

        if (routeStations.isEmpty()) {

            return null;

        }

        return routeStations.stream()

                .sorted(Comparator.comparing(RouteStationDO::getSequenceNo,

                        Comparator.nullsLast(Integer::compareTo)))

                .map(rs -> String.valueOf(rs.getStationId()))

                .filter(sid -> !sid.equals(String.valueOf(depotStationId)))

                .collect(Collectors.toList());

    }



    /** 构建算法规划请求快照：站点 = 场站 + 订单引用站点去重；skeleton 非空时该批车辆按公交骨架经停（联合调度） */

    private AlgorithmPlanReqDTO buildPlanRequest(StationDO depot, List<VehicleDO> vehicles,

                                                 List<TransportOrderDO> orders,

                                                 Map<String, Object> algorithmConfig, String scenario,

                                                 List<String> skeleton) {

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

                        // 公交骨架（Mandatory Passenger Service）：指定班次时该车辆按线路站点经停

                        .skeleton(skeleton)

                        .build())

                .collect(Collectors.toList());

        // 子表一次加载，内存匹配（消除逐单查询的 N+1）

        Map<Long, PassengerOrderDO> passengerMap = preloadPassengerOrders(orders);

        Map<Long, CargoOrderDO> cargoMap = preloadCargoOrders(orders);

        Map<Long, PostalOrderDO> postalMap = preloadPostalOrders(orders);

        List<AlgorithmOrderDTO> orderDTOs = orders.stream()

                .map(order -> toAlgorithmOrders(order, depot.getId(), passengerMap, cargoMap, postalMap))

                .flatMap(List::stream).collect(Collectors.toList());



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

    private List<AlgorithmOrderDTO> toAlgorithmOrders(TransportOrderDO order, Long depotStationId,

                                                      Map<Long, PassengerOrderDO> passengerMap,

                                                      Map<Long, CargoOrderDO> cargoMap,

                                                      Map<Long, PostalOrderDO> postalMap) {

        if (Objects.equals(order.getOrderType(), 1)) { // 客运

            List<AlgorithmOrderDTO> result = new ArrayList<>();

            for (String algorithmOrderId : passengerAlgorithmOrderIds(order, passengerMap)) {

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

                    .itemCount(getItemCount(order, cargoMap, postalMap))

                    .build());

        }

        // 派送：场站→村

        return Collections.singletonList(AlgorithmOrderDTO.builder()

                .orderId(String.valueOf(order.getId()))

                .orderType(AlgorithmOrderDTO.TYPE_DELIVERY)

                .stationId(String.valueOf(order.getDeliveryStationId()))

                .itemCount(getItemCount(order, cargoMap, postalMap))

                .build());

    }



    /** 客运多人单拆分为 "业务订单号#序号" 的算法订单编号 */

    private List<String> passengerAlgorithmOrderIds(TransportOrderDO order, Map<Long, PassengerOrderDO> passengerMap) {

        int count = getPassengerCount(order, passengerMap);

        List<String> ids = new ArrayList<>(count);

        for (int i = 1; i <= count; i++) {

            ids.add(order.getId() + "#" + i);

        }

        return ids;

    }



    /** 预加载客运子表（按订单编号索引，消除逐单查询的 N+1） */

    private Map<Long, PassengerOrderDO> preloadPassengerOrders(List<TransportOrderDO> orders) {

        List<Long> orderIds = orders.stream()

                .filter(order -> Objects.equals(order.getOrderType(), 1))

                .map(TransportOrderDO::getId).toList();

        if (orderIds.isEmpty()) {

            return Map.of();

        }

        return passengerOrderMapper.selectList(new LambdaQueryWrapperX<PassengerOrderDO>()

                        .in(PassengerOrderDO::getOrderId, orderIds))

                .stream().collect(Collectors.toMap(PassengerOrderDO::getOrderId,

                        java.util.function.Function.identity(), (a, b) -> a));

    }



    /** 预加载货运子表（按订单编号索引，消除逐单查询的 N+1） */

    private Map<Long, CargoOrderDO> preloadCargoOrders(List<TransportOrderDO> orders) {

        List<Long> orderIds = orders.stream()

                .filter(order -> Objects.equals(order.getOrderType(), 2))

                .map(TransportOrderDO::getId).toList();

        if (orderIds.isEmpty()) {

            return Map.of();

        }

        return cargoOrderMapper.selectList(new LambdaQueryWrapperX<CargoOrderDO>()

                        .in(CargoOrderDO::getOrderId, orderIds))

                .stream().collect(Collectors.toMap(CargoOrderDO::getOrderId,

                        java.util.function.Function.identity(), (a, b) -> a));

    }



    /** 预加载邮快件子表（按订单编号索引，消除逐单查询的 N+1） */

    private Map<Long, PostalOrderDO> preloadPostalOrders(List<TransportOrderDO> orders) {

        List<Long> orderIds = orders.stream()

                .filter(order -> Objects.equals(order.getOrderType(), 3))

                .map(TransportOrderDO::getId).toList();

        if (orderIds.isEmpty()) {

            return Map.of();

        }

        return postalOrderMapper.selectList(new LambdaQueryWrapperX<PostalOrderDO>()

                        .in(PostalOrderDO::getOrderId, orderIds))

                .stream().collect(Collectors.toMap(PostalOrderDO::getOrderId,

                        java.util.function.Function.identity(), (a, b) -> a));

    }



    /** 客运人数取自预加载子表，缺省 1 人 */

    private int getPassengerCount(TransportOrderDO order, Map<Long, PassengerOrderDO> passengerMap) {

        PassengerOrderDO sub = passengerMap.get(order.getId());

        return sub == null || sub.getPassengerCount() == null ? 1 : sub.getPassengerCount();

    }



    /** 算法订单编号还原业务订单编号：去掉 "#序号" 拆单后缀 */

    private static Long toBusinessOrderId(String algorithmOrderId) {

        int suffixIndex = algorithmOrderId.indexOf('#');

        return Long.valueOf(suffixIndex >= 0 ? algorithmOrderId.substring(0, suffixIndex) : algorithmOrderId);

    }



    /** 货运/邮快件件数取自预加载子表，缺省 1 件 */

    private int getItemCount(TransportOrderDO order, Map<Long, CargoOrderDO> cargoMap,

                             Map<Long, PostalOrderDO> postalMap) {

        Integer itemCount = null;

        if (Objects.equals(order.getOrderType(), 2)) { // 货运

            CargoOrderDO sub = cargoMap.get(order.getId());

            itemCount = sub != null ? sub.getItemCount() : null;

        } else if (Objects.equals(order.getOrderType(), 3)) { // 邮快件

            PostalOrderDO sub = postalMap.get(order.getId());

            itemCount = sub != null ? sub.getItemCount() : null;

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



    /** 经停明细落库：visit_sequence 从 1 递增；补填司机归属（按车辆当前有效人车绑定）。

     *  预计到达时间由 {@link DispatchEstimationService#estimatePlan} 在明细落库后统一估算回写 */

    private void insertPlanItems(Long planId, Long vehicleId, List<AlgorithmRouteStopDTO> stops) {

        Long driverId = resolveDriverId(vehicleId);

        for (int i = 0; i < stops.size(); i++) {

            AlgorithmRouteStopDTO stop = stops.get(i);

            PlanItemActionEnum action = PlanItemActionEnum.fromCode(stop.getAction());

            var itemBuilder = DispatchPlanItemDO.builder()

                    .planId(planId)

                    .vehicleId(vehicleId)

                    .driverId(driverId)

                    .stationId(stop.getStationId() != null ? Long.valueOf(stop.getStationId()) : null)

                    .orderId(stop.getOrderId() != null ? toBusinessOrderId(stop.getOrderId()) : null)

                    .visitSequence(i + 1)

                    .actionType(action != null ? action.getAction() : null);

            // 算法解释（Phase 5）：货运/揽收经停携带服务方式/服务点/绕行/乘客影响/原因码

            if (Boolean.TRUE.equals(stop.getAccepted()) || stop.getServiceMode() != null || stop.getReasonCode() != null

                    || stop.getPassengerImpact() != null) {

                itemBuilder.serviceMode(stop.getServiceMode())

                        .servicePointStationId(stop.getServicePoint() != null

                                ? Long.valueOf(stop.getServicePoint()) : null)

                        .detourDistanceKm(stop.getDetourDistance() != null

                                ? BigDecimal.valueOf(stop.getDetourDistance()) : null)

                        .detourDurationSeconds(stop.getDetourDuration() != null

                                ? stop.getDetourDuration().intValue() : null)

                        .passengerImpactSeconds(stop.getPassengerImpact() != null

                                ? stop.getPassengerImpact().intValue() : null)

                        .reasonCode(stop.getReasonCode());

            }

            dispatchPlanItemMapper.insert(itemBuilder.build());

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



    /**

     * 批量推进订单状态，并约束来源状态（P1-003：防跨状态误写）。

     * source 为 null 时不约束来源（仅用于无明确来源的兼容场景）；建议始终显式传来源。

     */

    private void updateOrdersStatus(List<Long> orderIds, TransportOrderStatusEnum status,

                                    TransportOrderStatusEnum source) {

        if (orderIds.isEmpty()) {

            return;

        }

        TransportOrderDO updateObj = new TransportOrderDO();

        updateObj.setStatus(status.getStatus());

        LambdaQueryWrapperX<TransportOrderDO> wrapper = new LambdaQueryWrapperX<TransportOrderDO>()

                .in(TransportOrderDO::getId, orderIds);

        if (source != null) {

            wrapper.eq(TransportOrderDO::getStatus, source.getStatus());

        }

        orderMapper.update(updateObj, wrapper);

    }



    /** 当前半小时批次区间：分钟 < 30 则 :00，否则 :30 */

    private static LocalDateTime[] currentBatch() {

        LocalDateTime now = LocalDateTime.now();

        LocalDateTime start = now.withMinute(now.getMinute() < 30 ? 0 : 30).withSecond(0).withNano(0);

        return new LocalDateTime[]{start, start.plusMinutes(BATCH_MINUTES)};

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

