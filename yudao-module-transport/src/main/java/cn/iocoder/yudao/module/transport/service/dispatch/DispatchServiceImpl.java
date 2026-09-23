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

import cn.iocoder.yudao.module.transport.dal.mysql.route.RouteMapper;

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

import org.springframework.beans.factory.annotation.Value;

import org.springframework.stereotype.Service;

import org.springframework.transaction.annotation.Transactional;

import org.springframework.validation.annotation.Validated;



import java.math.BigDecimal;

import java.time.Duration;

import java.time.LocalDateTime;

import java.time.LocalTime;

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

     * 30 分钟（原来的半小时批次）对真实路网（高德时空时长 + 装卸作业）太紧；

     * 2 小时对公交骨架线路同样不够：一条 25~35 站的线路按真实路网跑完要 2~2.5 小时
     * （实测重邮片区 35 站骨架 8920s > 7200s），整批订单会被判"排不进时间窗"
     * （算法返回 TIME_WINDOW_EXCEEDED / INCOMPLETE_SOLUTION，一键演示直接失败）。

     * 默认按一个班次 8 小时，可用 yudao.dispatch.batch-minutes 覆盖。

     */

    @Value("${yudao.dispatch.batch-minutes:480}")

    private int batchMinutes;



    @Resource private TransportOrderMapper orderMapper;

    @Resource private CargoOrderMapper cargoOrderMapper;

    @Resource private PostalOrderMapper postalOrderMapper;

    @Resource private PassengerOrderMapper passengerOrderMapper;

    @Resource private StationMapper stationMapper;

    @Resource private ShiftMapper shiftMapper;

    @Resource private RouteStationMapper routeStationMapper;

    @Resource private RouteMapper routeMapper;

    @Resource private VehicleMapper vehicleMapper;

    @Resource private DriverVehicleMapper driverVehicleMapper;

    @Resource private TransportDispatchTaskMapper dispatchTaskMapper;

    @Resource private DispatchPlanMapper dispatchPlanMapper;

    @Resource private DispatchPlanItemMapper dispatchPlanItemMapper;

    @Resource private DispatchPlanLogMapper dispatchPlanLogMapper;

    /** 运输段（P0-1 失败兜底清理半成品方案时需要按 planId 删除本方案已生成的段） */

    @Resource private TransportLegMapper legMapper;

    @Resource private DepartureCheckMapper departureCheckMapper;

    @Resource private AlgorithmAdapter algorithmAdapter;
    /** P1 长事务重构：createSmartPlan 拆段编排用（方法级 @Transactional 已移除，见 doCreateSmartPlan） */
    @Resource private PlatformTransactionManager transactionManager;

    @Resource private DispatchEstimationService dispatchEstimationService;

    @Resource private MultiLegService multiLegService;
    @Resource private CargoPricingService cargoPricingService;
    @Resource private ReachabilityDecisionService reachabilityDecisionService;
    @Resource private TaskSegmentCompletionService taskSegmentCompletionService;
    @Resource private cn.iocoder.yudao.module.transport.service.order.OrderEventService orderEventService;

    @Resource private cn.iocoder.yudao.module.transport.service.geo.RoadPolylineService roadPolylineService;

    /** 线路走廊：站间轨迹跟随公交线路真实几何（避免点对点路径进隧道/绕远/掉头） */
    @Resource private cn.iocoder.yudao.module.transport.service.geo.RouteCorridorService routeCorridorService;

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

                null, SCENARIO_MANUAL, Map.of(), currentBatch());

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

     * 智能派单。

     * <p>事务策略（P1 长事务重构）：本方法整体不再包 @Transactional——算法 HTTP 调用（超时上限

     * 15s）若发生在事务内，会同时占住 DB 连接与 CAS 抢占的行锁，并发派单会在行锁上串行阻塞。

     * 现按「只读准备 → 调算法（无事务）→ 短事务抢占+建任务 → 短事务落库」四段执行，各段用

     * TransactionTemplate 编排；任务终态落库由「各段独立短事务」保证（等价于原先

     * noRollbackFor=ServiceException 的意图），算法失败/落库失败的补偿链见 doCreateSmartPlan。

     */

    @Override

    public Long createSmartPlan(DispatchSmartPlanReqVO reqVO) {
        try {
            return doCreateSmartPlan(reqVO);
        } catch (ServiceException ex) {
            throw ex; // 业务异常原样抛出（前端展示具体原因，如容量越界、无可行解）
        } catch (Exception ex) {
            // 兜底：把技术异常的根因写进业务错误，避免前端只看到"服务器错误，请联系管理员"
            log.error("[createSmartPlan] 智能调度异常", ex);
            Throwable root = ex;
            while (root.getCause() != null && root.getCause() != root) {
                root = root.getCause();
            }
            String detail = root.getMessage() == null ? root.getClass().getSimpleName() : root.getMessage();
            throw exception(ALGORITHM_RESULT_INVALID, "智能调度执行失败：" + detail);
        }
    }

    /** 智能派单主体（异常统一由 {@link #createSmartPlan} 兜底转成可读业务错误） */
    private Long doCreateSmartPlan(DispatchSmartPlanReqVO reqVO) {

        // 取订单池；为空直接报错

        List<TransportOrderDO> pooledOrders = orderMapper.selectList(new LambdaQueryWrapperX<TransportOrderDO>()

                .eq(TransportOrderDO::getStatus, TransportOrderStatusEnum.POOLED.getStatus()));

        if (pooledOrders.isEmpty()) {

            throw exception(DISPATCH_POOL_EMPTY);

        }

        // ============ 任务窗口（例：早上 8-10 点这一班） ============
        // 业务口径：调度是给"某个时间段"排任务，而不是"收到单就让车掉头去取"。
        // ① 时间窗与本窗口无交集的订单本批不派（留给下一班次）；
        // ② 窗口开始时每台车已经开到线路哪一站，由 OperatingLineTimeline 算出来，
        //    已经开过的站不再派它去取货（见下方 buildVehicleTimelines / validateNoBacktracking）。
        LocalDateTime[] taskWindow = resolveTaskWindow(reqVO);
        List<String> windowReasons = new ArrayList<>();
        List<TransportOrderDO> inWindow = filterByTaskWindow(pooledOrders, taskWindow[0], taskWindow[1], windowReasons);
        if (inWindow.isEmpty()) {
            // 全部订单的时间窗都与本批次窗口无交集（现场常见：自建订单写了早于窗口的送达时限，
            // 或演示窗口与订单窗口没重叠）→ 早期实现直接报"没有可派订单"，演示时点一次什么都出不来。
            // 现在兜底：按"不限时间窗"把订单池全部订单交给算法，并把提示写进方案解释，由调度员判断。
            windowReasons.add("全部订单时间窗与本批次窗口无交集，已按「不限时间窗」兜底派单（请核对送达时限）");
        } else {
            pooledOrders = inWindow;
        }

        // 一键智能调度（auto=true）：后端自动选场站 + 自动挑候选车辆（实际车辆数由算法决定）

        StationDO depot;

        List<VehicleDO> vehicles;

        // 车辆在本任务窗口内的线路行程（哪一站已经开过 / 窗口内还会依次经过哪些站）；仅自动模式填充
        Map<Long, VehicleWindow> vehicleWindows = new LinkedHashMap<>();

        if (Boolean.TRUE.equals(reqVO.getAuto())) {

            List<StationDO> stations = stationMapper.selectList();

            Map<Long, StationDO> stationMap = stations.stream()

                    .filter(s -> s.getId() != null)

                    .collect(Collectors.toMap(StationDO::getId, java.util.function.Function.identity(), (a, b) -> a));

            // 片区分批：订单池可能同时有多个片区（如重庆邮电大学片区 + 成都片区），

            // 混批会让车辆跨城跑几百公里 → 算法 infeasible；这里只取"最新那单所在片区"，

            // 其余片区留在池里，再次点击「一键调度」自动成下一套方案。

            // 站点预算 = 算法站点上限 - 1（场站）：避免 25 单跨多条线路时站点数超限，
            // 导致一键调度直接抛"规模超出算法上限"、现场出不了方案。
            pooledOrders = AutoDispatchPlanner.selectAutoBatch(pooledOrders, stationMap, MAX_ALGORITHM_ORDERS,
                    MAX_ALGORITHM_STATIONS - 1);

            depot = AutoDispatchPlanner.selectDepot(pooledOrders, stations);

            if (depot == null) {

                throw exception(STATION_NOT_EXISTS);

            }

            // 候选车辆：优先避开"已被在途方案占用"的车（同一台车不能同时跑两套方案；

            // 多片区各出一套方案时，两套都排同一台车会让司机端任务混在一起）

            List<VehicleDO> allVehicles = autoCandidateVehicles();

            Set<Long> busyVehicleIds = busyVehicleIds();

            List<DriverVehicleDO> bindings = driverVehicleMapper == null
                    ? List.of() : driverVehicleMapper.selectActiveBindings();

            Map<Long, List<RouteStationDO>> routeStationMap = loadRouteStationMap(bindings);

            Map<Long, List<Long>> routeStations = stationIdsByRoute(routeStationMap);

            // 候选线路：优先按"订单的联运拆段结果"（MultiLegPlanner）推——每个运输段的起终点都要有本线路的车跑。
            // 例：「重邮 → 重庆交通大学」会被拆成 347 路 邮电大学→南坪站、303 路 南坪站→七公里，
            // 候选线路自然就是这两条（+ 覆盖最多运输段的线路），而不是按运力乱挑。
            Set<Long> neededRoutes = neededRouteIdsByLegs(pooledOrders, bindings, routeStations);

            // 默认口径：**一条运营线路只跑一辆公交车**（同线路多台绑定取 ID 最小者）
            vehicles = selectLineVehicles(neededRoutes, pooledOrders, allVehicles, bindings, routeStations,
                    MAX_ALGORITHM_VEHICLES, busyVehicleIds);

            if (vehicles.isEmpty()) {

                // 覆盖本批站点的车都在跑别的方案 → 退回不避让（保证能出方案，由调度员人工取舍）

                vehicles = selectLineVehicles(neededRoutes, pooledOrders, allVehicles, bindings, routeStations,
                        MAX_ALGORITHM_VEHICLES, Set.of());

            }

            if (vehicles.isEmpty()) {

                // 本地没有"线路覆盖本批站点"的车（自建片区 / 站点未挂线路）→ 退回原运力口径兜底

                vehicles = AutoDispatchPlanner.selectVehicles(allVehicles, MAX_ALGORITHM_VEHICLES, busyVehicleIds);

                if (vehicles.isEmpty()) {

                    vehicles = AutoDispatchPlanner.selectVehicles(allVehicles, MAX_ALGORITHM_VEHICLES);

                }

            }

            if (vehicles.isEmpty()) {

                throw exception(VEHICLE_NOT_EXISTS);

            }

            // 运力预算：按**实际选中的车辆**的总货仓件数裁剪本批订单。
            // 算法预检口径是 pickups ≤ Σ cargoCapacity 且 deliveries ≤ Σ cargoCapacity，
            // 超了直接返回 OVER_CAPACITY（前端文案"运力不足（订单总需求超出可用车辆总容量）"）。
            // 这里把放不下的订单留给下一批（前端一键演示会继续下一轮，自然成下一套方案），不丢单。
            int cargoBudget = AutoDispatchPlanner.capacityBudget(vehicles, vehicles.size(),
                    AlgorithmVehicleDTO.DEFAULT_CARGO_CAPACITY);
            if (cargoBudget > 0) {
                Map<Long, Integer> itemCounts = new LinkedHashMap<>();
                Map<Long, CargoOrderDO> cargoForBatch = preloadCargoOrders(pooledOrders);
                Map<Long, PostalOrderDO> postalForBatch = preloadPostalOrders(pooledOrders);
                // 客运单不占货仓（算法按"人数 vs 客位"单独判），不参与件数预算
                pooledOrders.forEach(order -> itemCounts.put(order.getId(),
                        Objects.equals(order.getOrderType(), 1) ? 0
                                : getItemCount(order, cargoForBatch, postalForBatch)));
                pooledOrders = AutoDispatchPlanner.capByTotalItems(pooledOrders, itemCounts, cargoBudget);
            }

            // 每台车在本窗口的线路行程：已开过的站不能取货（不折返），窗口内还会经过的站作为算法骨架（顺路带货、有先后）
            vehicleWindows = buildVehicleWindows(vehicles, bindings, routeStationMap, taskWindow);

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

        // 不折返 —— **只提示，绝不因此丢单**：
        // 早期实现把"取货站已被本批候选车开过"的订单直接从本批剔除，结果一批订单跨多条线路时，
        // 绝大多数订单的取货站都不在"本批 ≤3 台候选车"的剩余站里 → 整批被清空，
        // 现场表现就是"全都不行了，一条方案都出不来"。
        // 现在如实写进方案解释，订单照样交给算法（骨架仍是方向偏好，会尽量让车顺着线路走，
        // 个别不合理的分配由调度员据提示人工调整）。
        List<String> passedPickupWarnings = vehicleWindows.isEmpty()
                ? List.of() : warnPickupAlreadyPassed(pooledOrders, vehicleWindows);
        if (!passedPickupWarnings.isEmpty()) {
            windowReasons.add("以下订单的取货站已被本批车辆驶过（不阻断，仍会排入本批，建议人工确认是否折返/改派）："
                    + String.join("、", passedPickupWarnings));
        }

        // 先构建快照并做规模预检（只读，不占单）：无效输入快速失败，避免先 CAS 抢占后再抛错需要回滚。

        // 联合调度：指定班次时车辆按班次线路公交骨架经停，货运作为绕行插入（Phase 5）

        List<String> skeleton = resolveSkeleton(reqVO.getShiftId(), depot.getId());

        // 骨架（= 车辆"必须按序经停"的站，货物只能在骨架间隙里顺路插进去）：
        // 指定班次时全部车辆按该班次的线路站序（原行为）；否则按"每台车自己运营线路在本窗口的行程"分别给，
        // 这样每台车只在自己这条线上带货、站点顺序与行驶方向一致 → 天然不会掉头取货。
        Map<Long, List<String>> vehicleSkeletons = skeleton != null
                ? vehicles.stream().collect(Collectors.toMap(VehicleDO::getId, v -> skeleton,
                        (a, b) -> a, LinkedHashMap::new))
                : buildVehicleSkeletons(vehicles, vehicleWindows, pooledOrders);

        AlgorithmPlanReqDTO algorithmReq = buildPlanRequest(depot, vehicles, pooledOrders,

                AutoDispatchPlanner.mergeAlgorithmConfig(reqVO.getAlgorithmConfig()), reqVO.getScenario(),
                vehicleSkeletons, taskWindow);

        // 规模上限预检：客运按人数拆单后可能超 25 单，超限直接报错而非等算法 413

        validateScaleLimit(algorithmReq);



        // 任务窗口（可显式指定，如早上 8-10 点）：任务/方案/运输段的时间口径一律用本次派单的窗口
        LocalDateTime[] batch = taskWindow;
        String taskNo = generateTaskNo();

        // 调用算法（无事务）：此时尚未抢占订单、尚未建任务，算法异常直接透传、无需任何补偿；
        // 关键是不再持有 DB 连接与 CAS 行锁——原先最长 15s 的算法调用在事务内，会把连接池和
        // 并发派单一起拖住（P1 长事务根因）。代价是并发两次派单各自算一版，后提交者在下方
        // CAS 处影响 0 行 → 走 DISPATCH_POOL_EMPTY（原先是在行锁上阻塞至先者提交，体验更差）。
        AlgorithmPlanRespDTO result = algorithmAdapter.plan(algorithmReq);

        if (AlgorithmPlanRespDTO.STATUS_INFEASIBLE.equals(result.getStatus())) {
            // 无可行解：只落任务终态（独立短事务，等价原 noRollbackFor=ServiceException 的落库意图）；
            // 订单从未被抢占，无需回池。运力不足最容易"看不懂"：把本批的实际需求与候选车运力
            // 一起回给前端，现场就能判断是"订单太多"还是"车辆容量太小/车辆选错"，不用再翻日志。
            String reason = reasonCodeText(result.getReasonCode());
            if (AlgorithmPlanRespDTO.REASON_OVER_CAPACITY.equals(result.getReasonCode())) {
                reason = reason + "（" + describeBatchLoad(algorithmReq) + "）";
            }
            DispatchTaskDO infeasibleTask = DispatchTaskDO.builder()
                    .taskNo(taskNo)
                    .snapshotId(taskNo) // 快照编号暂用任务号，保证唯一约束
                    .planningTime(LocalDateTime.now())
                    .batchStart(batch[0]).batchEnd(batch[1])
                    .scenario(reqVO.getScenario())
                    .status(DispatchTaskStatusEnum.INFEASIBLE.getStatus())
                    .build();
            new TransactionTemplate(transactionManager)
                    .executeWithoutResult(status -> dispatchTaskMapper.insert(infeasibleTask));
            throw exception(DISPATCH_NO_FEASIBLE, reason);
        }

        // P1-004 并发防护：CAS 抢占订单池（仅已入池可推进为已分配），与建任务同一短事务。
        // 抢占失败（含部分失败）抛异常 → 整事务回滚，不存在「抢了一半」的中间态，无需手工补偿；
        // 算法已在此之前完成，本事务只含这几条写，毫秒级提交并释放连接。
        TransportOrderDO claim = new TransportOrderDO();
        claim.setStatus(TransportOrderStatusEnum.ASSIGNED.getStatus());
        DispatchTaskDO task = DispatchTaskDO.builder()
                .taskNo(taskNo)
                .snapshotId(taskNo) // 快照编号暂用任务号，保证唯一约束
                .planningTime(LocalDateTime.now())
                .batchStart(batch[0]).batchEnd(batch[1])
                .scenario(reqVO.getScenario())
                .status(DispatchTaskStatusEnum.PLANNING.getStatus())
                .build();
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            int claimed = orderMapper.update(claim, new LambdaQueryWrapperX<TransportOrderDO>()
                    .in(TransportOrderDO::getId, pooledIds)
                    .eq(TransportOrderDO::getStatus, TransportOrderStatusEnum.POOLED.getStatus()));
            if (claimed == 0) {
                throw exception(DISPATCH_POOL_EMPTY); // 池已被并发派单抢占
            }
            if (claimed < pooledIds.size()) {
                // 池被非智能派单路径并发修改（部分订单已非 POOLED）：整事务回滚撤销抢占，
                // 避免与其它路径的订单归属产生歧义。
                throw new IllegalStateException("订单池状态并发变更，请刷新后重试");
            }
            dispatchTaskMapper.insert(task);
        });

        // 安全网（不折返）——**只提示，不再让整批派单失败**：
        // 已经告诉算法"这些车本窗口沿途会经过哪些站"（作为骨架/方向偏好），
        // 但骨架在算法里是 prior 而非硬约束，个别订单仍可能被排到"本窗口已驶过"的站。
        // 早期版本在这里直接判 INFEASIBLE → 现场表现是"整天都出不了方案"（报"车辆已驶过站点，不能掉头取货"）。
        // 现在如实记进方案解释，由调度员决定是否人工调整（真正的"不能掉头"由派单前的
        // filterByNoBacktracking 把关：取货站被所有候选车开过的订单本批不派）。
        List<String> backtrackingWarnings = vehicleWindows.isEmpty()
                ? List.of() : findBacktrackingViolations(result, vehicleWindows);
        if (!backtrackingWarnings.isEmpty()) {
            log.warn("[createSmartPlan] 检测到疑似折返取货（仅提示，不阻断）：{}", backtrackingWarnings);
        }

        // 可行：任务置成功，方案与经停明细落库（订单已在上方短事务 CAS 抢占时置为已分配）
        task.setStatus(DispatchTaskStatusEnum.SUCCESS.getStatus());
        task.setAlgorithmJobId(result.getRequestId());

        // P0-1：以下进入「方案落库阶段」（独立短事务）。事务回滚保证 plan/明细/估算原子落库；
        // planLegs 是 REQUIRES_NEW、已独立提交不受回滚影响，因此失败仍需手工补偿：
        // 任务置失败 + 清理段数据 + 抢占订单回池，保证「要么成方案、要么回池」。
        DispatchPlanDO[] planHolder = new DispatchPlanDO[1];
        try {
            Long planId = new TransactionTemplate(transactionManager).execute(status -> {

        // 任务置成功：与方案落库同一短事务，原子生效（原实现这行在事务里但算法调用也在，
        // 失败时连同任务一起回滚——那才是真 bug；现在要么 SUCCESS+方案 都落，要么 FAILED+回池）

        dispatchTaskMapper.updateById(task);

        // 总里程：仅 distanceUnit=km 为路网正式里程；degree 仅直线估算参考（routeProvider 标注，禁止展示为真实道路）
        DistanceEstimate distanceEstimate = resolveTotalDistanceKm(result, buildCoordMap(algorithmReq));
        if (!distanceEstimate.formalRoad()) {
            log.warn("[createSmartPlan] 算法 distanceUnit={}，总里程仅为直线估算下界，不得当作正式路网成本",
                    result.getDistanceUnit());
        }

        DispatchPlanDO plan = createPlan(task, DispatchPlanModeEnum.SMART,

                distanceEstimate.km(), distanceEstimate.routeProvider(), result.getAlgorithmVersion(), result.getParameterVersion());

        planHolder[0] = plan;

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

        // 方案解释先写"任务窗口 + 每台车这一班的线路行程"：调度员/答辩时一眼看清
        // "这一批是给哪个时间段排的、每台车跑到哪一站了、后面还会经过哪些站"。
        reasons.add("任务窗口 " + formatWindow(taskWindow));
        for (VehicleWindow window : vehicleWindows.values()) {
            reasons.add(window.label() + " 这一班在 " + window.currentStationName() + " 站之后，"
                    + "本窗口还会依次经过 " + window.stations().size() + " 站（已开过的站不派取货）");
        }
        reasons.addAll(windowReasons);

        // 疑似折返取货（只提示、不阻断）：调度员据此人工确认是否调整
        if (!backtrackingWarnings.isEmpty()) {
            reasons.add("提示：本班次可能折返取货（不阻断派单，建议人工确认）："
                    + String.join("；", backtrackingWarnings));
        }

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

            });

            return planId;

        } catch (RuntimeException ex) {

            // P0-1 兜底：plan/明细/估算已随短事务回滚，这里只清理「REQUIRES_NEW 已独立提交」的
            // 运输段数据 + 抢占订单回池，绝不留下「已分配但无方案」的脏订单。
            // 顺序说明：先清段（此时订单仍为已分配，删除不会误伤在途数据），再放订单回池。

            failDispatchTask(task, ex);

            cleanupPartialPlan(planHolder[0]);

            releaseClaimedOrders(pooledIds);

            throw ex;

        }

    }



    /** 算法失败/无解时回滚 CAS 抢占：已分配订单回到订单池，可重新派单（P1-004） */

    private void releaseClaimedOrders(List<Long> orderIds) {

        updateOrdersStatus(orderIds, TransportOrderStatusEnum.POOLED, TransportOrderStatusEnum.ASSIGNED);

    }



    /**
     * P0-1：清理「半成品方案」。方案落库阶段（createPlan 之后）任一异常都要把已落库的方案、
     * 经停明细、本方案的运输段、状态日志一起删掉，避免留下「方案存在但订单已回池」的孤儿数据
     * ——孤儿段会在下一次一键演示时被复用（P0-A），订单视角与方案视角因而错位。
     *
     * 清理本身是最佳努力：失败只记日志，绝不掩盖原始异常。
     */

    private void cleanupPartialPlan(DispatchPlanDO plan) {

        if (plan == null || plan.getId() == null) {

            return;

        }

        Long planId = plan.getId();

        try {

            if (legMapper != null) {

                legMapper.delete(new LambdaQueryWrapperX<TransportLegDO>()

                        .eq(TransportLegDO::getPlanId, planId));

            }

            dispatchPlanItemMapper.delete(new LambdaQueryWrapperX<DispatchPlanItemDO>()

                    .eq(DispatchPlanItemDO::getPlanId, planId));

            dispatchPlanLogMapper.delete(new LambdaQueryWrapperX<DispatchPlanLogDO>()

                    .eq(DispatchPlanLogDO::getPlanId, planId));

            dispatchPlanMapper.deleteById(planId);

            log.warn("[createSmartPlan] 方案 {} 落库阶段失败，已清理半成品方案并释放抢占订单", planId);

        } catch (Exception ex) {

            log.warn("[createSmartPlan] 清理半成品方案 {} 失败：{}", planId, ex.getMessage());

        }

    }



    /** P0-1：技术异常时把调度任务置为失败并记录根因（事务不回滚，任务终态需要落库供后台排查） */

    private void failDispatchTask(DispatchTaskDO task, Exception ex) {

        if (task == null || task.getId() == null) {

            return;

        }

        try {

            Throwable root = ex;

            while (root.getCause() != null && root.getCause() != root) {

                root = root.getCause();

            }

            String detail = root.getMessage() == null ? root.getClass().getSimpleName() : root.getMessage();

            task.setStatus(DispatchTaskStatusEnum.FAILED.getStatus());

            task.setErrorMessage(StrUtil.sub(detail, 0, 500));

            dispatchTaskMapper.updateById(task);

        } catch (Exception e) {

            log.warn("[createSmartPlan] 回写调度任务失败状态出错：{}", e.getMessage());

        }

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

        // P0-B：多段联运方案必须「车辆视角 = 订单视角」。运输段（transport_leg）才是车辆真实的行驶轨迹：
        // 订单视角是 v3→v101→v5 接力，而算法的单车经停明细会让同一台车跨片区「南岸→重大→再回来」。
        // 本方案的段存在时，一律按「leg.vehicleId + 段序」出段（plan item 只保留站点与作业）。

        List<TransportLegDO> planLegs = legMapper == null ? List.of() : legMapper.selectListByPlanId(id);

        if (!planLegs.isEmpty()) {

            return buildLegRoadmap(id, planLegs);

        }

        List<DispatchPlanItemDO> items = dispatchPlanItemMapper.selectList(new LambdaQueryWrapperX<DispatchPlanItemDO>()

                .eq(DispatchPlanItemDO::getPlanId, id)

                .orderByAsc(DispatchPlanItemDO::getVehicleId)

                .orderByAsc(DispatchPlanItemDO::getVisitSequence));

        DispatchRoadmapRespVO respVO = new DispatchRoadmapRespVO();

        respVO.setPlanId(id);

        if (items.isEmpty()) {

            respVO.setSource("ITEM");

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

                // 站间走向优先跟随"车辆运营线路走廊"（同一段线路上的两站 → 取线路真实几何的切片）；
                // 取不到再退回点对点驾车规划。

                List<double[]> road = routeCorridorService == null ? null

                        : routeCorridorService.alongOperatingLine(

                                entry.getKey(), stops.get(i - 1).getStationId(), stops.get(i).getStationId());

                if (road == null || road.size() < 2) {

                    road = roadPolylineService.route(

                        from.getLongitude().doubleValue(), from.getLatitude().doubleValue(),

                        to.getLongitude().doubleValue(), to.getLatitude().doubleValue());

                }

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

        respVO.setSource("ITEM"); // 无运输段（手工方案/历史数据）：退化口径，前端按经停明细绘图

        respVO.setProvider(anyReal && anyFallback ? "MIXED" : (anyReal ? "AMAP" : "EUCLIDEAN"));

        return respVO;

    }



    /**

     * P0-B：按「本方案的运输段」出段（车辆视角 = 订单视角）。

     *

     * 每台车在本方案内的段按「预计出发时间 → 订单内段序 → 订单编号」排序，段序号从 1 递增

     * （前端用它做「该车第 N 段」的轨迹 key）；段上已缓存的高德轨迹直接用，缺轨迹时才按需补一次路网

     * （同时省掉前端 30 秒冷却重试）。

     */

    private DispatchRoadmapRespVO buildLegRoadmap(Long planId, List<TransportLegDO> legs) {

        DispatchRoadmapRespVO respVO = new DispatchRoadmapRespVO();

        respVO.setPlanId(planId);

        respVO.setSource("LEG");

        Set<Long> stationIds = legs.stream()

                .flatMap(leg -> java.util.stream.Stream.of(leg.getFromStationId(), leg.getToStationId()))

                .filter(Objects::nonNull).collect(Collectors.toSet());

        Map<Long, StationDO> stationMap = stationIds.isEmpty() ? Map.of()

                : stationMapper.selectBatchIds(stationIds).stream()

                        .collect(Collectors.toMap(StationDO::getId, s -> s, (a, b) -> a));

        // 按车辆分组（车辆为空归到 0，前端同样按 0 兜底）
        Map<Long, List<TransportLegDO>> byVehicle = new LinkedHashMap<>();

        for (TransportLegDO leg : legs) {

            byVehicle.computeIfAbsent(leg.getVehicleId() == null ? 0L : leg.getVehicleId(),

                    k -> new ArrayList<>()).add(leg);

        }

        List<DispatchRoadmapRespVO.Segment> segments = new ArrayList<>();

        boolean anyReal = false;

        boolean anyFallback = false;

        for (Map.Entry<Long, List<TransportLegDO>> entry : byVehicle.entrySet()) {

            List<TransportLegDO> vehicleLegs = entry.getValue();

            vehicleLegs.sort(Comparator

                    .comparing(TransportLegDO::getEstimatedDeparture,

                            Comparator.nullsLast(Comparator.naturalOrder()))

                    .thenComparing(TransportLegDO::getLegSequence, Comparator.nullsLast(Comparator.naturalOrder()))

                    .thenComparing(TransportLegDO::getOrderId, Comparator.nullsLast(Comparator.naturalOrder())));

            int sequence = 0; // 只对"能画出轨迹"的段计数，保证与前端跳过的无效冲点一致

            for (TransportLegDO leg : vehicleLegs) {

                StationDO from = leg.getFromStationId() == null ? null : stationMap.get(leg.getFromStationId());

                StationDO to = leg.getToStationId() == null ? null : stationMap.get(leg.getToStationId());

                if (from == null || to == null || from.getLongitude() == null || from.getLatitude() == null

                        || to.getLongitude() == null || to.getLatitude() == null) {

                    continue;

                }

                sequence++;

                // 站间走向**优先跟随"车辆运营线路走廊"**：点对点驾车规划在站牌位于道路另一侧、
                // 或该点需上下桥/下穿时，会规划成"进隧道 → 绕远 → 掉头"，与司机按线路行驶不符；
                // 库里老方案存的也是那种点对点轨迹，所以这里走廊优先，能把历史方案一起纠正。
                List<double[]> road = routeCorridorService == null ? null

                        : routeCorridorService.alongOperatingLine(

                                entry.getKey(), leg.getFromStationId(), leg.getToStationId());

                if (road == null) {

                    road = parseNavigationPolyline(leg.getNavigationPolyline());

                }

                if (road == null || road.size() < 2) {

                    road = roadPolylineService.route(

                            from.getLongitude().doubleValue(), from.getLatitude().doubleValue(),

                            to.getLongitude().doubleValue(), to.getLatitude().doubleValue());

                }

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

                segment.setVehicleId(entry.getKey() == 0L ? leg.getVehicleId() : entry.getKey());

                segment.setVisitSequence(sequence);

                segment.setLegId(leg.getId());

                segment.setOrderId(leg.getOrderId());

                segment.setLegSequence(leg.getLegSequence());

                segment.setHandoverRequired(leg.getHandoverRequired());

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
     * 预热真实道路轨迹（高德配额恢复后跑一次即可）。
     *
     * <p>口径 = 演示会看到的那批路线：订单池（待入池/已入池）的取送站点对 + 今天方案里运输段的起终点对。
     * 逐对调高德，取到就<b>落库</b>到运输段（transport_leg.navigation_polyline，幂等），
     * 之后打开「调度结果可视化」直接就是真实道路轨迹，不用再等临时缓存，也不会出现两点直线。</p>
     *
     * @return 本次成功取到并落库/预热的站点对数（高德不可用时返回 0，如实反映，不伪造）
     */
    @Override
    public int prefetchRoadGeometry() {
        if (roadPolylineService == null || !roadPolylineService.available() || stationMapper == null) {
            return 0;
        }
        List<TransportOrderDO> poolOrders = orderMapper == null ? List.of()
                : orderMapper.selectList(new LambdaQueryWrapperX<TransportOrderDO>()
                .in(TransportOrderDO::getStatus, TransportOrderStatusEnum.READY_FOR_POOL.getStatus(),
                        TransportOrderStatusEnum.POOLED.getStatus()));
        List<TransportLegDO> todayLegs = legMapper == null ? List.of()
                : legMapper.selectList(new LambdaQueryWrapperX<TransportLegDO>()
                .ge(TransportLegDO::getCreateTime, java.time.LocalDate.now().atStartOfDay()));

        Set<Long> stationIds = new LinkedHashSet<>();
        poolOrders.forEach(order -> {
            if (order.getPickupStationId() != null) {
                stationIds.add(order.getPickupStationId());
            }
            if (order.getDeliveryStationId() != null) {
                stationIds.add(order.getDeliveryStationId());
            }
        });
        todayLegs.forEach(leg -> {
            if (leg.getFromStationId() != null) {
                stationIds.add(leg.getFromStationId());
            }
            if (leg.getToStationId() != null) {
                stationIds.add(leg.getToStationId());
            }
        });
        if (stationIds.isEmpty()) {
            return 0;
        }
        Map<Long, StationDO> stationMap = stationMapper.selectBatchIds(stationIds).stream()
                .filter(station -> station.getLongitude() != null && station.getLatitude() != null)
                .collect(Collectors.toMap(StationDO::getId, station -> station, (a, b) -> a));

        int fetched = 0;
        // 1) 今天的运输段：逐段取真实轨迹并落库（与可视化同一口径；取到即持久化，重启/缓存过期后无需重取）
        for (TransportLegDO leg : todayLegs) {
            StationDO from = stationMap.get(leg.getFromStationId());
            StationDO to = stationMap.get(leg.getToStationId());
            if (from == null || to == null) {
                continue;
            }
                // 走廊优先（站间走向跟线路走，避免隧道/掉头）：即使库里已存了旧的点对点轨迹，
                // 也按线路走向重算并覆盖 → "预热真实路线"这个按钮同时成了"纠正历史轨迹"的修复入口。
                List<double[]> road = routeCorridorService == null ? null
                        : routeCorridorService.resolveForLeg(
                                leg.getVehicleId(), leg.getFromStationId(), leg.getToStationId());
            if (road == null) {
                road = parseNavigationPolyline(leg.getNavigationPolyline());
            }
            if (road == null) {
                road = roadPolylineService.route(from.getLongitude().doubleValue(), from.getLatitude().doubleValue(),
                        to.getLongitude().doubleValue(), to.getLatitude().doubleValue());
            }
            if (road != null && road.size() >= 2) {
                persistLegPolyline(leg, road);
                fetched++;
            }
        }
        // 2) 订单池里还没成段的订单：按「取 → 送」站点对预热（服务内缓存 10 分钟，调度后打开可视化即可直接画真实路线）
        Set<String> warmed = new HashSet<>();
        for (TransportOrderDO order : poolOrders) {
            StationDO from = stationMap.get(order.getPickupStationId());
            StationDO to = stationMap.get(order.getDeliveryStationId());
            if (from == null || to == null || !warmed.add(from.getId() + "->" + to.getId())) {
                continue;
            }
            List<double[]> road = roadPolylineService.route(from.getLongitude().doubleValue(),
                    from.getLatitude().doubleValue(), to.getLongitude().doubleValue(), to.getLatitude().doubleValue());
            if (road != null && road.size() >= 2) {
                fetched++;
            }
        }
        // 3) 运营线路走廊预热：整条线路的真实几何一次落库（transport_route），
        // 之后"站间切片"直接按线路几何取，不再逐段打高德，司机端/订单视角也不再缺线。
        Set<Long> warmRouteIds = new LinkedHashSet<>();
        todayLegs.forEach(leg -> {
            Long routeId = routeCorridorService == null ? null
                    : routeCorridorService.operatingRouteId(leg.getVehicleId());
            if (routeId != null) {
                warmRouteIds.add(routeId);
            }
        });
        if (!warmRouteIds.isEmpty()) {
            fetched += routeCorridorService.warmRouteCorridors(warmRouteIds);
        }
        return fetched;
    }

    /**
     * 真实道路轨迹落库到运输段（幂等，失败只记日志）：
     * 高德配额有限，取到一次就持久化，可视化不再依赖"临时缓存 + 直线兜底"。
     */
    private void persistLegPolyline(TransportLegDO leg, List<double[]> road) {
        if (legMapper == null || leg == null || leg.getId() == null || road == null || road.size() < 2) {
            return;
        }
        try {
            String polyline = serializeNavigationPolyline(road);
            if (StrUtil.isBlank(polyline)) {
                return;
            }
            TransportLegDO update = new TransportLegDO();
            update.setId(leg.getId());
            update.setNavigationPolyline(polyline);
            update.setNavigationSource("AMAP");
            legMapper.updateById(update);
            leg.setNavigationPolyline(polyline);
            leg.setNavigationSource("AMAP");
        } catch (Exception ex) {
            log.warn("[roadmap] 运输段 {} 真实轨迹落库失败：{}", leg.getId(), ex.getMessage());
        }
    }

    /** 轨迹序列化：与 {@link #parseNavigationPolyline(String)} 对称的 "lon,lat;lon,lat;..." */
    private static String serializeNavigationPolyline(List<double[]> points) {
        StringBuilder sb = new StringBuilder(points.size() * 16);
        for (double[] p : points) {
            if (p == null || p.length < 2) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append(';');
            }
            sb.append(String.format(java.util.Locale.ROOT, "%.6f", p[0]))
                    .append(',')
                    .append(String.format(java.util.Locale.ROOT, "%.6f", p[1]));
        }
        return sb.toString();
    }

    /** 解析运输段上缓存的高德轨迹（"lon,lat;lon,lat;..."）；无/不合法返回 null（由调用方按需补路网） */
    private static List<double[]> parseNavigationPolyline(String polyline) {

        if (StrUtil.isBlank(polyline)) {

            return null;

        }

        List<double[]> points = new ArrayList<>();

        for (String pair : polyline.split(";")) {

            String[] lonLat = pair.split(",");

            if (lonLat.length < 2) {

                continue;

            }

            try {

                points.add(new double[]{Double.parseDouble(lonLat[0].trim()), Double.parseDouble(lonLat[1].trim())});

            } catch (NumberFormatException ignored) {

                return null; // 脏数据：宁可补一次路网，也不要画出错误轨迹

            }

        }

        return points.size() >= 2 ? points : null;

    }



    /**
     * 两点之间的真实道路轨迹：调度可视化「按订单视角」绘制线路用。
     * 运输段落库时若高德不可用会退化成两点直线，这里按需补一次真实路网（服务端有 10 分钟缓存）。
     */
    @Override
    public List<DispatchRoadmapRespVO.Point> routeBetween(Double fromLongitude, Double fromLatitude,
                                                          Double toLongitude, Double toLatitude) {
        return routeBetween(fromLongitude, fromLatitude, toLongitude, toLatitude, null);
    }

    /**
     * 订单池手动取消：只取消还在池里的单（待入池/已入池），不影响其它订单，
     * 也不动已进入方案执行的单（那种要走改派/回收，避免把在途货物取消掉）。
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public int cancelPoolOrders(List<Long> orderIds) {
        if (orderIds == null || orderIds.isEmpty()) {
            return 0;
        }
        List<TransportOrderDO> orders = orderMapper.selectBatchIds(orderIds);
        if (orders == null || orders.isEmpty()) {
            return 0;
        }
        List<Long> cancellable = orders.stream()
                .filter(order -> Objects.equals(order.getStatus(), TransportOrderStatusEnum.POOLED.getStatus())
                        || Objects.equals(order.getStatus(), TransportOrderStatusEnum.READY_FOR_POOL.getStatus()))
                .map(TransportOrderDO::getId)
                .toList();
        if (cancellable.isEmpty()) {
            return 0;
        }
        TransportOrderDO update = new TransportOrderDO();
        update.setStatus(TransportOrderStatusEnum.CANCELLED.getStatus());
        int updated = orderMapper.update(update, new LambdaQueryWrapperX<TransportOrderDO>()
                .in(TransportOrderDO::getId, cancellable)
                .in(TransportOrderDO::getStatus, TransportOrderStatusEnum.POOLED.getStatus(),
                        TransportOrderStatusEnum.READY_FOR_POOL.getStatus()));
        cancellable.forEach(orderId -> {
            try {
                orderEventService.record(orderId, TransportOrderEventTypeEnum.CANCELLED,
                        "调度订单池手动取消", null);
            } catch (Exception ex) {
                log.debug("[dispatch] 订单 {} 取消事件记录失败：{}", orderId, ex.getMessage());
            }
        });
        log.info("[dispatch] 订单池手动取消 {} 单：{}", updated, cancellable);
        return updated;
    }

    /**
     * Two-point road geometry. With a {@code legId} the leg's operating line corridor wins, so the
     * order view draws the road the bus really drives instead of a point-to-point detour.
     */
    @Override
    public List<DispatchRoadmapRespVO.Point> routeBetween(Double fromLongitude, Double fromLatitude,
                                                          Double toLongitude, Double toLatitude, Long legId) {
        if (fromLongitude == null || fromLatitude == null || toLongitude == null || toLatitude == null) {
            return List.of();
        }
        List<double[]> road = null;
        if (legId != null && legMapper != null && routeCorridorService != null) {
            TransportLegDO leg = legMapper.selectById(legId);
            if (leg != null) {
                road = routeCorridorService.resolveForLeg(leg.getVehicleId(),
                        leg.getFromStationId(), leg.getToStationId());
                if (road == null) {
                    road = parseNavigationPolyline(leg.getNavigationPolyline());
                }
            }
        }
        if (road == null || road.size() < 2) {
            road = roadPolylineService.route(fromLongitude, fromLatitude, toLongitude, toLatitude);
        }
        if (road == null || road.size() < 2) {
            return List.of();
        }
        return road.stream().map(p -> {
            DispatchRoadmapRespVO.Point point = new DispatchRoadmapRespVO.Point();
            point.setLongitude(p[0]);
            point.setLatitude(p[1]);
            return point;
        }).toList();
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

            pooledOrders = AutoDispatchPlanner.selectAutoBatch(pooledOrders, stationMapForBatch,
                    MAX_ALGORITHM_ORDERS, MAX_ALGORITHM_STATIONS - 1);

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

        // 任务数 = 单向订单 + 配对货运单（一个 shipment 展开成 PICKUP + DELIVERY 两个节点，
        // 与算法契约"客运+包裹订单合计不超过 25"同一口径）
        int orderCount = (algorithmReq.getOrders() != null ? algorithmReq.getOrders().size() : 0)
                + (algorithmReq.getShipments() != null ? algorithmReq.getShipments().size() : 0);

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



    /** 路线成本来源：FORMAL_ROAD=路网正式；STRAIGHT_ESTIMATE=Haversine 下界参考（非正式） */
    private record DistanceEstimate(BigDecimal km, String routeProvider) {
        boolean formalRoad() {
            return "FORMAL_ROAD".equals(routeProvider);
        }
    }

    private static final String ROUTE_PROVIDER_FORMAL = "FORMAL_ROAD";
    private static final String ROUTE_PROVIDER_STRAIGHT = "STRAIGHT_ESTIMATE";

    /**
     * 方案总里程解析。km→FORMAL_ROAD；degree/缺省→STRAIGHT_ESTIMATE（非正式成本，禁止冒充路网里程）。
     */
    private static DistanceEstimate resolveTotalDistanceKm(AlgorithmPlanRespDTO result, Map<String, double[]> coordMap) {

        if (AlgorithmPlanRespDTO.DISTANCE_UNIT_KM.equals(result.getDistanceUnit())) {

            double km = result.getTotalDistance() != null ? result.getTotalDistance() : 0;

            return new DistanceEstimate(BigDecimal.valueOf(Math.round(km * 1000) / 1000.0), ROUTE_PROVIDER_FORMAL);

        }

        return new DistanceEstimate(computeTotalDistanceKm(result.getVehiclePlans(), coordMap), ROUTE_PROVIDER_STRAIGHT);

    }



    /** 按各车经停序列Haversine 累加估算公里（直线参考，非正式道路里程）；坐标缺失的分段跳过（不记里程） */

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
    private static String describeBatchLoad(AlgorithmPlanReqDTO req) {
        if (req == null) {
            return "本批需求信息缺失";
        }
        int algorithmOrders = req.getOrders() == null ? 0 : req.getOrders().size();
        int passengers = 0;
        int pickupItems = 0;
        int deliveryItems = 0;
        if (req.getOrders() != null) {
            for (AlgorithmOrderDTO order : req.getOrders()) {
                if (AlgorithmOrderDTO.TYPE_PASSENGER.equals(order.getOrderType())) {
                    passengers++;
                } else if (AlgorithmOrderDTO.TYPE_PICKUP.equals(order.getOrderType())) {
                    pickupItems += order.getItemCount() == null ? 1 : order.getItemCount();
                } else if (AlgorithmOrderDTO.TYPE_DELIVERY.equals(order.getOrderType())) {
                    deliveryItems += order.getItemCount() == null ? 1 : order.getItemCount();
                }
            }
        }
        int shipmentItems = 0;
        if (req.getShipments() != null) {
            for (AlgorithmShipmentDTO shipment : req.getShipments()) {
                shipmentItems += shipment.getQuantity() == null ? 1 : shipment.getQuantity();
            }
        }
        int vehicleCount = req.getVehicles() == null ? 0 : req.getVehicles().size();
        int cargoCapacity = 0;
        int passengerCapacity = 0;
        if (req.getVehicles() != null) {
            for (AlgorithmVehicleDTO vehicle : req.getVehicles()) {
                cargoCapacity += vehicle.getCargoCapacity() == null
                        ? AlgorithmVehicleDTO.DEFAULT_CARGO_CAPACITY : vehicle.getCargoCapacity();
                passengerCapacity += vehicle.getPassengerCapacity() == null ? 0 : vehicle.getPassengerCapacity();
            }
        }
        return "本批 " + algorithmOrders + " 张算法单（取货 " + (pickupItems + shipmentItems) + " 件 / 送达 "
                + (deliveryItems + shipmentItems) + " 件 / 客运 " + passengers + " 人），候选车 " + vehicleCount
                + " 台（货仓 " + cargoCapacity + " 件 / 客位 " + passengerCapacity + " 个）";
    }

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



    /**
     * 构建算法规划请求快照：站点 = 场站 + 订单引用站点去重。
     *
     * <p>{@code vehicleSkeletons} 是**每台车各自**的骨架（该车在本任务窗口内沿途依次经过的站）：
     * 算法只会在骨架间隙里插货运任务，车辆按自己的线路顺序走 → 顺路带货、有先后、不会掉头。
     * 骨架里不属于本批请求的站会被剔除（算法只认请求里的站点），保持请求规模可控。</p>
     *
     * @param window 任务窗口（同时作为算法的 batchStart/batchEnd）
     */

    private AlgorithmPlanReqDTO buildPlanRequest(StationDO depot, List<VehicleDO> vehicles,

                                                 List<TransportOrderDO> orders,

                                                 Map<String, Object> algorithmConfig, String scenario,

                                                 Map<Long, List<String>> vehicleSkeletons,

                                                 LocalDateTime[] window) {

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

                        // 公交骨架（Mandatory Passenger Service）：该车辆按"自己这条线在本窗口的站序"经停，
                        // 货运任务只能插进骨架间隙 → 顺路带货、有先后、不会掉头取货

                        .skeleton(filterSkeleton(vehicleSkeletons.get(vehicle.getId()), stationIds, depot.getId()))

                        .build())

                .collect(Collectors.toList());

        // 子表一次加载，内存匹配（消除逐单查询的 N+1）

        Map<Long, PassengerOrderDO> passengerMap = preloadPassengerOrders(orders);

        Map<Long, CargoOrderDO> cargoMap = preloadCargoOrders(orders);

        Map<Long, PostalOrderDO> postalMap = preloadPostalOrders(orders);

        // 配对货运单收集器（取货站 → 送达站，两端都不是场站的完整链路，见 toAlgorithmOrders）
        List<AlgorithmShipmentDTO> shipmentDTOs = new ArrayList<>();

        List<AlgorithmOrderDTO> orderDTOs = orders.stream()

                .map(order -> toAlgorithmOrders(order, depot.getId(), passengerMap, cargoMap, postalMap,
                        shipmentDTOs))

                .flatMap(List::stream).collect(Collectors.toList());



        return AlgorithmPlanReqDTO.builder()

                .batchStart(window[0].atOffset(BATCH_ZONE_OFFSET))

                .batchEnd(window[1].atOffset(BATCH_ZONE_OFFSET))

                .depot(depotDTO)

                .stations(stations)

                .vehicles(vehicleDTOs)

                .orders(orderDTOs)

                // 配对货运单（取货站 → 送达站，两端都不是场站的完整链路）：
                // 算法展开为同一辆车的 PICKUP + DELIVERY 两个节点，并保证"先取后送"。
                .shipments(shipmentDTOs)

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

                                                      Map<Long, PostalOrderDO> postalMap,

                                                      List<AlgorithmShipmentDTO> shipments) {

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

                    .economicValue(resolveEconomicValue(order, cargoMap, postalMap))

                    .build());

        }

        // 派送：场站→村

        if (depotStationId != null && Objects.equals(order.getPickupStationId(), depotStationId)) {

            return Collections.singletonList(AlgorithmOrderDTO.builder()

                    .orderId(String.valueOf(order.getId()))

                    .orderType(AlgorithmOrderDTO.TYPE_DELIVERY)

                    .stationId(String.valueOf(order.getDeliveryStationId()))

                    .itemCount(getItemCount(order, cargoMap, postalMap))

                    .economicValue(resolveEconomicValue(order, cargoMap, postalMap))

                    .build());

        }

        // 完整链路：取货站 → 送达站（两端都不是场站）→ 发「配对货运单」。
        //
        // 为什么必须配对（PDPTW 口径，Li & Lim 2001）：取货点与送货点必须同一辆车承运、且先取后送。
        // 历史实现这里退化成"只在送达站发一个 DELIVERY 节点 + 丢掉取货站"，等于货物凭空出现在送达站：
        // 既看不出先后顺序，也没法约束同车与取送顺序，站点清单里也就看不到揽收点。
        // 现在按 PlanShipment 下发，算法展开为 PICKUP + DELIVERY 两个节点（同车 + 顺序约束由算法保证），
        // 后端 AlgorithmResultValidator 已支持按 shipmentId 校验配对完整性。
        CargoOrderDO cargo = Objects.equals(order.getOrderType(), 2) ? cargoMap.get(order.getId()) : null;

        shipments.add(AlgorithmShipmentDTO.builder()

                .shipmentId(String.valueOf(order.getId()))

                .pickupStationId(String.valueOf(order.getPickupStationId()))

                .deliveryStationId(String.valueOf(order.getDeliveryStationId()))

                .quantity(getItemCount(order, cargoMap, postalMap))
                .economicValue(resolveEconomicValue(order, cargoMap, postalMap))

                .weightKg(cargo != null && cargo.getWeightKg() != null ? cargo.getWeightKg().doubleValue() : null)

                .volumeM3(cargo != null && cargo.getVolumeM3() != null ? cargo.getVolumeM3().doubleValue() : null)

                .build());

        return Collections.emptyList();

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

    /**
     * 用 CargoPricingService 真实报价填充算法 economicValue（可选、向后兼容）。
     * 无报价/异常时返回 null，算法侧不造假价格。
     */
    private Double resolveEconomicValue(TransportOrderDO order, Map<Long, CargoOrderDO> cargoMap,
                                        Map<Long, PostalOrderDO> postalMap) {
        try {
            if (order == null || order.getPickupStationId() == null || order.getDeliveryStationId() == null) {
                return null;
            }
            int itemCount = getItemCount(order, cargoMap, postalMap);
            CargoPricingService.CargoQuote quote = cargoPricingService.quote(
                    order.getPickupStationId(), order.getDeliveryStationId(), itemCount);
            return quote != null && quote.amount() != null ? quote.amount().doubleValue() : null;
        } catch (Exception ex) {
            log.warn("[resolveEconomicValue] skip economic value for order {}", order != null ? order.getId() : null, ex);
            return null;
        }
    }



    private AlgorithmRouteStopDTO buildStop(Long stationId, String orderId, String action) {

        return AlgorithmRouteStopDTO.builder()

                .stationId(String.valueOf(stationId))

                .orderId(orderId)

                .action(action)

                .build();

    }



    private DispatchPlanDO createPlan(DispatchTaskDO task, DispatchPlanModeEnum mode, BigDecimal totalDistance,
                                      String routeProvider, String algorithmVersion, String parameterVersion) {

        DispatchPlanDO plan = DispatchPlanDO.builder()

                .taskId(task.getId())

                .planVersion(1)

                .mode(mode.getMode())

                .algorithmVersion(algorithmVersion)

                .parameterVersion(parameterVersion)

                .totalDistance(totalDistance)

                .routeProvider(routeProvider)

                .status(DispatchPlanStatusEnum.PENDING.getStatus())

                .build();

        dispatchPlanMapper.insert(plan);

        return plan;

    }

    private DispatchPlanDO createPlan(DispatchTaskDO task, DispatchPlanModeEnum mode, BigDecimal totalDistance,
                                      String algorithmVersion, String parameterVersion) {
        return createPlan(task, mode, totalDistance, null, algorithmVersion, parameterVersion);
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



    /** 当前半小时批次区间：分钟 < 30 则 :00，否则 :30；窗长 batchMinutes（默认一个班次） */
    private LocalDateTime[] currentBatch() {

        LocalDateTime now = LocalDateTime.now();

        LocalDateTime start = now.withMinute(now.getMinute() < 30 ? 0 : 30).withSecond(0).withNano(0);

        return new LocalDateTime[]{start, start.plusMinutes(batchMinutes)};

    }

    // ==================== 任务窗口 + 线路行程（不折返） ====================

    /**
     * 单车在本任务窗口内的线路行程：窗口开始时在哪一站、已经开过哪些站、窗口内还会依次经过哪些站。
     *
     * @param label              展示文案（车牌 · 线路名），写进方案解释
     * @param currentStationName 窗口开始时所处的站名
     * @param position           线路位置（含"已经开过、本班不会再经过"的站集合）
     * @param stations           窗口内还会依次经过的站（有序，算法骨架用）
     * @param stationsSet        上面的集合形式（不折返判定用）
     */
    private record VehicleWindow(String label, String currentStationName,
                                 OperatingLineTimeline.Position position,
                                 List<Long> stations, Set<Long> stationsSet) {
    }

    /**
     * 任务窗口：显式指定（当天 08:00~10:00）优先；否则沿用系统默认批次窗口（当前时刻起一个班次）。
     * 只传一端、或开始不早于结束的非法输入一律忽略，回退默认窗口（不因参数问题卡住演示）。
     */
    private LocalDateTime[] resolveTaskWindow(DispatchSmartPlanReqVO reqVO) {
        LocalTime start = reqVO == null ? null : reqVO.getWindowStart();
        LocalTime end = reqVO == null ? null : reqVO.getWindowEnd();
        if (start != null && end != null && start.isBefore(end)) {
            java.time.LocalDate today = java.time.LocalDate.now();
            return new LocalDateTime[]{LocalDateTime.of(today, start), LocalDateTime.of(today, end)};
        }
        return currentBatch();
    }

    /** 窗口文案（HH:mm-HH:mm） */
    private static String formatWindow(LocalDateTime[] window) {
        DateTimeFormatter fmt = DateTimeFormatter.ofPattern("HH:mm");
        return window[0].format(fmt) + "-" + window[1].format(fmt);
    }

    /**
     * 任务窗口过滤：订单的 [最早取货, 最晚送达] 与任务窗口有交集才纳入本批。
     * 太晚（窗口结束前取不到货）或已过期（窗口开始前就该送到）的订单本批不派，留给下一班次。
     */
    private static List<TransportOrderDO> filterByTaskWindow(List<TransportOrderDO> orders,
                                                             LocalDateTime windowStart, LocalDateTime windowEnd,
                                                             List<String> reasons) {
        List<TransportOrderDO> kept = new ArrayList<>();
        for (TransportOrderDO order : orders) {
            boolean tooLate = order.getEarliestPickupTime() != null
                    && order.getEarliestPickupTime().isAfter(windowEnd);
            boolean expired = order.getLatestDeliveryTime() != null
                    && order.getLatestDeliveryTime().isBefore(windowStart);
            if (tooLate || expired) {
                reasons.add(order.getOrderNo() + (tooLate ? "（取货时间晚于窗口结束）" : "（送达时限早于窗口开始）"));
                continue;
            }
            kept.add(order);
        }
        return kept;
    }

    /** 人车绑定的运营线路 → 该线路站点（含站序与计划分钟，算车辆位置要用） */
    private Map<Long, List<RouteStationDO>> loadRouteStationMap(List<DriverVehicleDO> bindings) {
        if (bindings == null || bindings.isEmpty() || routeStationMapper == null) {
            return Map.of();
        }
        List<Long> routeIds = bindings.stream().map(DriverVehicleDO::getRouteId)
                .filter(Objects::nonNull).distinct().toList();
        if (routeIds.isEmpty()) {
            return Map.of();
        }
        return routeStationMapper.selectListByRouteIds(routeIds).stream()
                .filter(rs -> rs != null && rs.getRouteId() != null && rs.getStationId() != null)
                .collect(Collectors.groupingBy(RouteStationDO::getRouteId, LinkedHashMap::new, Collectors.toList()));
    }

    /** 运营线路 → 站点编号序列（按站序） */
    private static Map<Long, List<Long>> stationIdsByRoute(Map<Long, List<RouteStationDO>> routeStationMap) {
        Map<Long, List<Long>> result = new LinkedHashMap<>();
        routeStationMap.forEach((routeId, stations) -> result.put(routeId,
                OperatingLineTimeline.orderedStations(stations).stream()
                        .map(RouteStationDO::getStationId).toList()));
        return result;
    }

    /**
     * 从"订单的联运拆段结果"推导本次派单**需要哪些运营线路**。
     *
     * <p>怎么算：对每张订单跑一次 {@link MultiLegService#preview(Long)} 拿到运输段
     * （直达=1 段；跨线路=2~3 段，例如「重邮 → 重庆交通大学」拆成 347 路 邮电大学→南坪站、
     * 303 路 南坪站→七公里），再把"起终点都在这条线路上"的线路记一次命中；
     * 按命中运输段数降序返回（同分按线路编号升序，保证结果确定）。</p>
     *
     * <p>这样挑车才是"这批货要走哪几条线路，就派哪几条线路的车"，
     * 而不是按运力把不相干的线路派进来。</p>
     */
    private Set<Long> neededRouteIdsByLegs(List<TransportOrderDO> orders, List<DriverVehicleDO> bindings,
                                           Map<Long, List<Long>> routeStations) {
        Map<Long, Integer> hits = new LinkedHashMap<>();
        if (multiLegService == null || orders == null || orders.isEmpty()
                || bindings == null || bindings.isEmpty()) {
            return new LinkedHashSet<>();
        }
        for (TransportOrderDO order : orders) {
            if (order.getId() == null) {
                continue;
            }
            MultiLegPlanner.PlanResult preview;
            try {
                preview = multiLegService.preview(order.getId());
            } catch (RuntimeException ex) {
                // 拆段失败不影响挑车：后面还有"线路覆盖分"兜底
                log.warn("[createSmartPlan] 订单 {} 拆段预览失败：{}", order.getId(), ex.getMessage());
                continue;
            }
            if (preview == null || preview.legs() == null) {
                continue;
            }
            for (MultiLegPlanner.LegDraft leg : preview.legs()) {
                if (leg == null || leg.fromStationId() == null || leg.toStationId() == null) {
                    continue;
                }
                // A leg may be covered by several lines (city stops are shared between lines).
                // Count only the *most specific* one - the line that serves both stops with the
                // fewest stops - so the carrier is the line the pair really belongs to:
                // 中研所 -> 上新街 is served by 346路 (14 stops), not by a long trunk line that
                // merely passes both stops. Otherwise long lines win the counting and the demo
                // ends up with 346-line freight riding another line's bus.
                Long bestRouteId = null;
                int bestScopeSize = Integer.MAX_VALUE;
                for (DriverVehicleDO binding : bindings) {
                    if (binding == null || binding.getRouteId() == null) {
                        continue;
                    }
                    List<Long> stations = routeStations.get(binding.getRouteId());
                    if (stations == null) {
                        continue;
                    }
                    if (!stations.contains(leg.fromStationId()) || !stations.contains(leg.toStationId())) {
                        continue;
                    }
                    int scopeSize = stations.size();
                    if (scopeSize < bestScopeSize
                            || (scopeSize == bestScopeSize && bestRouteId != null
                                    && binding.getRouteId() < bestRouteId)) {
                        bestScopeSize = scopeSize;
                        bestRouteId = binding.getRouteId();
                    }
                }
                if (bestRouteId != null) {
                    hits.merge(bestRouteId, 1, Integer::sum);
                }
            }
        }
        return hits.entrySet().stream()
                .sorted(Map.Entry.<Long, Integer>comparingByValue().reversed()
                        .thenComparing(Map.Entry::getKey))
                .map(Map.Entry::getKey)
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    /**
     * 挑候选车辆：先按"需要的线路"（联运拆段结果）每条线取一辆车，不够再用"线路覆盖分"补齐；
     * 仍为空时由调用方退回运力口径兜底。**一条运营线路只出一辆车**。
     */
    private List<VehicleDO> selectLineVehicles(Set<Long> neededRoutes, List<TransportOrderDO> orders,
                                               List<VehicleDO> allVehicles, List<DriverVehicleDO> bindings,
                                               Map<Long, List<Long>> routeStations, int max,
                                               Set<Long> excludedVehicleIds) {
        List<VehicleDO> picked = new ArrayList<>();
        if (allVehicles == null || allVehicles.isEmpty() || max <= 0) {
            return picked;
        }
        Set<Long> excluded = excludedVehicleIds == null ? Set.of() : excludedVehicleIds;
        Map<Long, VehicleDO> vehicleMap = allVehicles.stream().filter(v -> v.getId() != null)
                .collect(Collectors.toMap(VehicleDO::getId, v -> v, (a, b) -> a));

        // 1) 联运拆段需要的线路：每条线取绑定 ID 最小的那台车
        if (neededRoutes != null && bindings != null) {
            for (Long routeId : neededRoutes) {
                if (picked.size() >= max) {
                    break;
                }
                DriverVehicleDO best = null;
                for (DriverVehicleDO binding : bindings) {
                    if (binding == null || !routeId.equals(binding.getRouteId()) || binding.getVehicleId() == null) {
                        continue;
                    }
                    if (!vehicleMap.containsKey(binding.getVehicleId()) || excluded.contains(binding.getVehicleId())) {
                        continue;
                    }
                    if (best == null || (binding.getId() != null && best.getId() != null
                            && binding.getId() < best.getId())) {
                        best = binding;
                    }
                }
                if (best != null) {
                    picked.add(vehicleMap.get(best.getVehicleId()));
                }
            }
        }

        // 2) 不够再用"线路覆盖分"补齐（拆段结果缺失 / 需要的线路没有车时兜底）
        if (picked.size() < max) {
            for (VehicleDO candidate : AutoDispatchPlanner.selectVehiclesByLineCoverage(
                    orders, allVehicles, bindings, routeStations, max, excluded)) {
                if (picked.size() >= max) {
                    break;
                }
                if (picked.stream().anyMatch(v -> Objects.equals(v.getId(), candidate.getId()))) {
                    continue;
                }
                picked.add(candidate);
            }
        }
        return picked;
    }

    /**
     * 逐车计算"本任务窗口内的线路行程"。
     *
     * <p>班次口径与实时公交模拟器一致：同一路线取"窗口开始时正在跑的那一班"；
     * 找不到班次的线路按"还没发车"处理（整条线都在前方），保证演示数据缺班次时不会误判成"已开过"。</p>
     */
    private Map<Long, VehicleWindow> buildVehicleWindows(List<VehicleDO> vehicles,
                                                         List<DriverVehicleDO> bindings,
                                                         Map<Long, List<RouteStationDO>> routeStationMap,
                                                         LocalDateTime[] window) {
        if (vehicles == null || vehicles.isEmpty() || bindings == null || bindings.isEmpty()
                || routeStationMap.isEmpty() || window == null) {
            return Map.of();
        }
        // 一条线路一辆车：同一线路多台绑定取绑定 ID 最小者（与挑车口径一致，结果确定）
        Map<Long, DriverVehicleDO> vehicleRoute = new LinkedHashMap<>();
        for (DriverVehicleDO binding : bindings) {
            if (binding == null || binding.getVehicleId() == null || binding.getRouteId() == null
                    || !routeStationMap.containsKey(binding.getRouteId())) {
                continue;
            }
            vehicleRoute.merge(binding.getVehicleId(), binding,
                    (a, b) -> a.getId() != null && b.getId() != null && b.getId() < a.getId() ? b : a);
        }
        if (vehicleRoute.isEmpty()) {
            return Map.of();
        }

        // 每个路线取"窗口开始时正在跑的那一班"
        LocalTime at = window[0].toLocalTime();
        LocalTime until = window[1].toLocalTime();
        Map<Long, ShiftDO> shiftByRoute = new HashMap<>();
        if (shiftMapper != null) {
            Map<Long, List<ShiftDO>> grouped = shiftMapper.selectList().stream()
                    .filter(s -> s.getRouteId() != null && s.getPlannedDepartureTime() != null)
                    .filter(s -> s.getStatus() == null || s.getStatus() == 0)
                    .collect(Collectors.groupingBy(ShiftDO::getRouteId));
            grouped.forEach((routeId, shifts) -> shiftByRoute.put(routeId,
                    OperatingLineTimeline.selectCurrentShift(shifts, at)));
        }

        Map<Long, VehicleWindow> result = new LinkedHashMap<>();
        for (VehicleDO vehicle : vehicles) {
            DriverVehicleDO binding = vehicleRoute.get(vehicle.getId());
            if (binding == null) {
                continue;
            }
            List<RouteStationDO> lineStations = routeStationMap.get(binding.getRouteId());
            ShiftDO shift = shiftByRoute.get(binding.getRouteId());
            List<Long> stations = null;
            OperatingLineTimeline.Position position = null;
            // 只有"窗口开始时这台车正在跑某一班"才谈得上"哪一站已经开过"；
            // 没班次 / 班次还没发车 / 当天班次已跑完，都按"整条线都在前方"处理
            // （不把线路误判成已开过，避免演示数据班次覆盖不全时订单被全部过滤）。
            if (shift != null && shift.getPlannedDepartureTime() != null) {
                int duration = shift.getPlannedDurationMinutes() != null && shift.getPlannedDurationMinutes() > 0
                        ? shift.getPlannedDurationMinutes() : OperatingLineTimeline.DEFAULT_DURATION_MINUTES;
                long elapsed = java.time.Duration.between(shift.getPlannedDepartureTime(), at).toMinutes();
                if (elapsed >= 0 && elapsed <= duration) {
                    position = OperatingLineTimeline.at(lineStations, shift, at);
                    stations = OperatingLineTimeline.windowStations(lineStations, shift, at, until);
                }
            }
            if (position == null || stations == null) {
                stations = OperatingLineTimeline.orderedStations(lineStations).stream()
                        .map(RouteStationDO::getStationId).toList();
                position = new OperatingLineTimeline.Position(OperatingLineTimeline.Direction.FORWARD,
                        stations.isEmpty() ? null : stations.get(0), stations, List.of());
            }
            result.put(vehicle.getId(), new VehicleWindow(
                    vehicleLabel(vehicle.getId(), binding.getRouteId()),
                    stationNameOf(position.currentStationId()),
                    position, stations, new LinkedHashSet<>(stations)));
        }
        return result;
    }

    /** 车牌 · 线路名（方案解释用） */
    private String vehicleLabel(Long vehicleId, Long routeId) {
        String plate = "车辆#" + vehicleId;
        if (vehicleMapper != null && vehicleId != null) {
            var vehicle = vehicleMapper.selectById(vehicleId);
            if (vehicle != null && vehicle.getPlateNo() != null) {
                plate = vehicle.getPlateNo();
            }
        }
        if (routeMapper != null && routeId != null) {
            var route = routeMapper.selectById(routeId);
            if (route != null && route.getRouteName() != null) {
                return plate + " · " + route.getRouteName();
            }
        }
        return plate;
    }

    /** 站名（查不到给 #id） */
    private String stationNameOf(Long stationId) {
        if (stationId == null || stationMapper == null) {
            return "—";
        }
        var station = stationMapper.selectById(stationId);
        return station == null || station.getStationName() == null
                ? "#" + stationId : station.getStationName();
    }

    /**
     * 不折返预过滤：取货站必须是**某台候选车本窗口还会经过**的站。
     * 所有候选车都已经开过这个站 → 本批不派这单，而不是让已经开过去的公交车掉头回去取货。
     */
    private static List<String> warnPickupAlreadyPassed(List<TransportOrderDO> orders,
                                                        Map<Long, VehicleWindow> windows) {
        Set<Long> reachable = new LinkedHashSet<>();
        windows.values().forEach(window -> reachable.addAll(window.stationsSet()));
        List<String> warnings = new ArrayList<>();
        for (TransportOrderDO order : orders) {
            if (order.getPickupStationId() != null && !reachable.contains(order.getPickupStationId())) {
                warnings.add(order.getOrderNo());
            }
        }
        return warnings;
    }

    /** 算法骨架：每台车"本窗口还会依次经过"的站（只保留本批订单涉及的站，保序） */
    private static Map<Long, List<String>> buildVehicleSkeletons(List<VehicleDO> vehicles,
                                                                 Map<Long, VehicleWindow> windows,
                                                                 List<TransportOrderDO> orders) {
        if (vehicles == null || vehicles.isEmpty() || windows == null || windows.isEmpty()) {
            return Map.of();
        }
        Set<Long> orderStations = new LinkedHashSet<>();
        if (orders != null) {
            orders.forEach(order -> {
                if (order.getPickupStationId() != null) {
                    orderStations.add(order.getPickupStationId());
                }
                if (order.getDeliveryStationId() != null) {
                    orderStations.add(order.getDeliveryStationId());
                }
            });
        }
        Map<Long, List<String>> result = new LinkedHashMap<>();
        for (VehicleDO vehicle : vehicles) {
            VehicleWindow window = windows.get(vehicle.getId());
            if (window == null) {
                continue;
            }
            List<String> skeleton = window.stations().stream()
                    .filter(orderStations::contains)
                    .map(String::valueOf)
                    .toList();
            if (!skeleton.isEmpty()) {
                result.put(vehicle.getId(), skeleton);
            }
        }
        return result;
    }

    /**
     * 骨架过滤：只保留本批请求里存在的站点（算法只认请求里的站点；场站不放进骨架）。
     * 顺手去掉相邻重复站，避免"同一站连着出现两次"这种无意义的骨架。
     */
    private static List<String> filterSkeleton(List<String> skeleton, Set<Long> requestStationIds, Long depotId) {
        if (skeleton == null || skeleton.isEmpty() || requestStationIds == null) {
            return List.of();
        }
        List<String> filtered = new ArrayList<>();
        for (String stationId : skeleton) {
            if (stationId == null || (depotId != null && stationId.equals(String.valueOf(depotId)))) {
                continue;
            }
            Long id;
            try {
                id = Long.valueOf(stationId);
            } catch (NumberFormatException ex) {
                continue;
            }
            if (!requestStationIds.contains(id)) {
                continue;
            }
            if (!filtered.isEmpty() && filtered.get(filtered.size() - 1).equals(stationId)) {
                continue;
            }
            filtered.add(stationId);
        }
        return filtered;
    }

    /**
     * 安全网：算法若把某台车派到"它本班已经开过、之后不会再经过"的站取货（= 让公交车掉头），
     * 这里判出来并让整单失败（返回可读原因），而不是放任一条掉头路线落库。
     */
    private List<String> findBacktrackingViolations(AlgorithmPlanRespDTO result,
                                                    Map<Long, VehicleWindow> windows) {
        List<String> violations = new ArrayList<>();
        if (result == null || result.getVehiclePlans() == null) {
            return violations;
        }
        for (AlgorithmVehiclePlanDTO vehiclePlan : result.getVehiclePlans()) {
            if (vehiclePlan == null || vehiclePlan.getVehicleId() == null || vehiclePlan.getStops() == null) {
                continue;
            }
            VehicleWindow window = windows.get(vehiclePlan.getVehicleId());
            if (window == null || window.position() == null || window.position().passedStations().isEmpty()) {
                continue;
            }
            Set<Long> passed = new LinkedHashSet<>(window.position().passedStations());
            for (AlgorithmRouteStopDTO stop : vehiclePlan.getStops()) {
                Long stationId = parseStationId(stop == null ? null : stop.getStationId());
                if (stationId != null && passed.contains(stationId)) {
                    violations.add(window.label() + " 已驶过 " + stationNameOf(stationId) + " 站");
                }
            }
        }
        return violations.stream().distinct().toList();
    }

    /** 算法契约里的站点编号是字符串，这里转回业务编号（非法值返回 null） */
    private static Long parseStationId(String stationId) {
        if (stationId == null || stationId.isBlank()) {
            return null;
        }
        try {
            return Long.valueOf(stationId.trim());
        } catch (NumberFormatException ex) {
            return null;
        }
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




    @Override
    public java.util.Map<String, Object> allocateDynamic(java.util.Map<String, Object> payload) {
        // 前端不直连算法；此处唯一出口。失败由 AlgorithmClient 重试/冷却语义处理。
        return algorithmAdapter.allocateRaw(payload);
    }
}

