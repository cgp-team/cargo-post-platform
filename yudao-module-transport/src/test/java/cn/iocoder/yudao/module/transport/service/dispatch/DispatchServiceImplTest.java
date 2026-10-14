package cn.iocoder.yudao.module.transport.service.dispatch;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.module.transport.controller.admin.dispatch.vo.*;
import cn.iocoder.yudao.module.transport.dal.dataobject.dispatch.*;
import cn.iocoder.yudao.module.transport.dal.dataobject.order.CargoOrderDO;
import cn.iocoder.yudao.module.transport.dal.dataobject.order.PassengerOrderDO;
import cn.iocoder.yudao.module.transport.dal.dataobject.order.PostalOrderDO;
import cn.iocoder.yudao.module.transport.dal.dataobject.order.TransportOrderDO;
import cn.iocoder.yudao.module.transport.dal.dataobject.route.RouteStationDO;
import cn.iocoder.yudao.module.transport.dal.dataobject.shift.ShiftDO;
import cn.iocoder.yudao.module.transport.dal.dataobject.station.StationDO;
import com.baomidou.mybatisplus.core.toolkit.support.SFunction;
import cn.iocoder.yudao.module.transport.dal.dataobject.vehicle.VehicleDO;
import cn.iocoder.yudao.module.transport.dal.mysql.dispatch.*;
import cn.iocoder.yudao.module.transport.dal.mysql.driver.DriverVehicleMapper;
import cn.iocoder.yudao.module.transport.dal.mysql.route.RouteStationMapper;
import cn.iocoder.yudao.module.transport.dal.mysql.shift.ShiftMapper;
import cn.iocoder.yudao.module.transport.dal.mysql.order.CargoOrderMapper;
import cn.iocoder.yudao.module.transport.dal.mysql.order.PassengerOrderMapper;
import cn.iocoder.yudao.module.transport.dal.mysql.order.PostalOrderMapper;
import cn.iocoder.yudao.module.transport.dal.mysql.order.TransportOrderMapper;
import cn.iocoder.yudao.module.transport.dal.mysql.station.StationMapper;
import cn.iocoder.yudao.module.transport.dal.mysql.vehicle.VehicleMapper;
import cn.iocoder.yudao.module.transport.enums.dispatch.*;
import cn.iocoder.yudao.module.transport.integration.algorithm.AlgorithmAdapter;
import cn.iocoder.yudao.module.transport.integration.algorithm.dto.AlgorithmOrderDTO;
import cn.iocoder.yudao.module.transport.integration.algorithm.dto.AlgorithmPlanReqDTO;
import cn.iocoder.yudao.module.transport.integration.algorithm.dto.AlgorithmPlanRespDTO;
import cn.iocoder.yudao.module.transport.integration.algorithm.dto.AlgorithmRouteStopDTO;
import cn.iocoder.yudao.module.transport.integration.algorithm.dto.AlgorithmVehicleDTO;
import cn.iocoder.yudao.module.transport.integration.algorithm.dto.AlgorithmVehiclePlanDTO;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.AbstractWrapper;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static cn.iocoder.yudao.module.transport.enums.ErrorCodeConstants.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 调度闭环纯 Mockito 单测：归集入池、智能派单、方案审核、发车核验的状态流转。
 */
@ExtendWith(MockitoExtension.class)
class DispatchServiceImplTest {

    @Mock private TransportOrderMapper orderMapper;
    @Mock private CargoOrderMapper cargoOrderMapper;
    @Mock private PostalOrderMapper postalOrderMapper;
    @Mock private PassengerOrderMapper passengerOrderMapper;
    @Mock private StationMapper stationMapper;
    @Mock private VehicleMapper vehicleMapper;
    @Mock private DriverVehicleMapper driverVehicleMapper;
    @Mock private TransportDispatchTaskMapper dispatchTaskMapper;
    @Mock private DispatchPlanMapper dispatchPlanMapper;
    @Mock private DispatchPlanItemMapper dispatchPlanItemMapper;
    @Mock private DispatchPlanLogMapper dispatchPlanLogMapper;
    @Mock private TransportLegMapper transportLegMapper;
    @Mock private DepartureCheckMapper departureCheckMapper;
    @Mock private ShiftMapper shiftMapper;
    @Mock private RouteStationMapper routeStationMapper;
    @Mock private AlgorithmAdapter algorithmAdapter;
    @Mock private DispatchEstimationService dispatchEstimationService;
    @Mock private MultiLegService multiLegService;
    @Mock private cn.iocoder.yudao.module.transport.service.geo.RoadPolylineService roadPolylineService;

    private DispatchServiceImpl dispatchService;

    @BeforeEach
    void setUp() {
        dispatchService = new DispatchServiceImpl();
        ReflectionTestUtils.setField(dispatchService, "orderMapper", orderMapper);
        ReflectionTestUtils.setField(dispatchService, "cargoOrderMapper", cargoOrderMapper);
        ReflectionTestUtils.setField(dispatchService, "postalOrderMapper", postalOrderMapper);
        ReflectionTestUtils.setField(dispatchService, "passengerOrderMapper", passengerOrderMapper);
        ReflectionTestUtils.setField(dispatchService, "stationMapper", stationMapper);
        ReflectionTestUtils.setField(dispatchService, "vehicleMapper", vehicleMapper);
        ReflectionTestUtils.setField(dispatchService, "driverVehicleMapper", driverVehicleMapper);
        ReflectionTestUtils.setField(dispatchService, "dispatchTaskMapper", dispatchTaskMapper);
        ReflectionTestUtils.setField(dispatchService, "dispatchPlanMapper", dispatchPlanMapper);
        ReflectionTestUtils.setField(dispatchService, "dispatchPlanItemMapper", dispatchPlanItemMapper);
        ReflectionTestUtils.setField(dispatchService, "dispatchPlanLogMapper", dispatchPlanLogMapper);
        ReflectionTestUtils.setField(dispatchService, "legMapper", transportLegMapper);
        ReflectionTestUtils.setField(dispatchService, "departureCheckMapper", departureCheckMapper);
        ReflectionTestUtils.setField(dispatchService, "shiftMapper", shiftMapper);
        ReflectionTestUtils.setField(dispatchService, "routeStationMapper", routeStationMapper);
        ReflectionTestUtils.setField(dispatchService, "algorithmAdapter", algorithmAdapter);
        ReflectionTestUtils.setField(dispatchService, "dispatchEstimationService", dispatchEstimationService);
        // 多段联运：mock 的 planLegs 默认返回空列表（不新建运输段），不影响既有直达方案断言
        ReflectionTestUtils.setField(dispatchService, "multiLegService", multiLegService);
        ReflectionTestUtils.setField(dispatchService, "roadPolylineService", roadPolylineService);
        // 注：driverVehicleMapper 为 Mockito mock，selectActiveBindings() 默认返回空列表，
        // 派单明细 driverId 为空，不影响既有断言；无需显式 stub（避免 UnnecessaryStubbing）
    }

    @Test
    void collectOrders_marks_ready_for_pool_orders_pooled() {
        // 时间范围归集：待入池(READY_FOR_POOL)订单可入池（Phase 2：审核通过才可归集）
        when(orderMapper.selectList(any())).thenReturn(List.of(
                TransportOrderDO.builder().id(1L).orderType(2)
                        .status(TransportOrderStatusEnum.READY_FOR_POOL.getStatus()).build(),
                TransportOrderDO.builder().id(2L).orderType(2)
                        .status(TransportOrderStatusEnum.READY_FOR_POOL.getStatus()).build(),
                TransportOrderDO.builder().id(3L).orderType(3)
                        .status(TransportOrderStatusEnum.READY_FOR_POOL.getStatus()).build()));
        when(orderMapper.update(any(TransportOrderDO.class), any())).thenReturn(3);

        DispatchCollectReqVO reqVO = new DispatchCollectReqVO();
        reqVO.setBatchStart(LocalDateTime.of(2026, 8, 9, 10, 0));
        reqVO.setBatchEnd(LocalDateTime.of(2026, 8, 9, 10, 30));
        int count = dispatchService.collectOrders(reqVO);

        assertEquals(3, count);
        ArgumentCaptor<TransportOrderDO> captor = ArgumentCaptor.forClass(TransportOrderDO.class);
        verify(orderMapper).update(captor.capture(), any());
        assertEquals(TransportOrderStatusEnum.POOLED.getStatus(), captor.getValue().getStatus());
    }

    @Test
    void collectOrders_only_selects_ready_for_pool() {
        // SQL 按 status=READY_FOR_POOL 过滤：非待入池订单（待审核/取消等）不会出现在候选里
        when(orderMapper.selectList(any())).thenReturn(List.of(
                TransportOrderDO.builder().id(1L).orderType(2)
                        .status(TransportOrderStatusEnum.READY_FOR_POOL.getStatus()).build(),
                TransportOrderDO.builder().id(3L).orderType(3)
                        .status(TransportOrderStatusEnum.READY_FOR_POOL.getStatus()).build()));
        when(orderMapper.update(any(TransportOrderDO.class), any())).thenReturn(2);

        DispatchCollectReqVO reqVO = new DispatchCollectReqVO();
        reqVO.setBatchStart(LocalDateTime.of(2026, 8, 9, 10, 0));
        reqVO.setBatchEnd(LocalDateTime.of(2026, 8, 9, 10, 30));
        int count = dispatchService.collectOrders(reqVO);

        assertEquals(2, count);
        // 捕获入池更新条件：in 的订单编号含 1、3，不含非待入池订单
        // （MyBatis-Plus 3.5.16 的 wrapper 参数延迟物化：先注册表信息，再触发 SQL 物化后才能读到参数）
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), TransportOrderDO.class);
        ArgumentCaptor<Wrapper<TransportOrderDO>> wrapperCaptor = ArgumentCaptor.forClass(Wrapper.class);
        verify(orderMapper).update(any(TransportOrderDO.class), wrapperCaptor.capture());
        AbstractWrapper<?, ?, ?> wrapper = (AbstractWrapper<?, ?, ?>) wrapperCaptor.getValue();
        wrapper.getSqlSegment(); // 触发物化，填充 paramNameValuePairs
        List<Object> params = wrapper.getParamNameValuePairs().values().stream()
                .flatMap(v -> v instanceof java.util.Collection
                        ? ((java.util.Collection<?>) v).stream() : java.util.stream.Stream.of(v))
                .toList();
        assertTrue(params.contains(1L));
        assertTrue(params.contains(3L));
        assertFalse(params.contains(2L));
    }

    @Test
    void createSmartPlan_feasible_creates_plan_and_assigns_orders() {
        mockSmartPlanContext();
        when(algorithmAdapter.plan(any())).thenReturn(feasibleResult());
        doAnswer(invocation -> {
            DispatchPlanDO plan = invocation.getArgument(0);
            plan.setId(100L);
            return 1;
        }).when(dispatchPlanMapper).insert(any(DispatchPlanDO.class));

        Long planId = dispatchService.createSmartPlan(smartReqVO());

        assertEquals(100L, planId);
        // 任务置为规划成功并回写算法任务编号
        ArgumentCaptor<DispatchTaskDO> taskCaptor = ArgumentCaptor.forClass(DispatchTaskDO.class);
        verify(dispatchTaskMapper).updateById(taskCaptor.capture());
        assertEquals(DispatchTaskStatusEnum.SUCCESS.getStatus(), taskCaptor.getValue().getStatus());
        assertEquals("req-1", taskCaptor.getValue().getAlgorithmJobId());
        // 方案：智能派单、待审核、算法版本与总里程落库
        ArgumentCaptor<DispatchPlanDO> planCaptor = ArgumentCaptor.forClass(DispatchPlanDO.class);
        verify(dispatchPlanMapper).insert(planCaptor.capture());
        assertEquals(DispatchPlanModeEnum.SMART.getMode(), planCaptor.getValue().getMode());
        assertEquals(DispatchPlanStatusEnum.PENDING.getStatus(), planCaptor.getValue().getStatus());
        assertEquals("algo-1.0", planCaptor.getValue().getAlgorithmVersion());
        // 总里程按经停坐标 Haversine 换算为真实公里：1→11(0.01°≈0.963) + 11→12(≈0.963) + 12→1(≈1.926) ≈ 3.852km
        assertEquals(0, planCaptor.getValue().getTotalDistance().compareTo(new java.math.BigDecimal("3.852")));
        // 经停明细 4 条（DEPART/BOARD/ALIGHT/RETURN），订单置为已分配
        verify(dispatchPlanItemMapper, times(4)).insert(any(DispatchPlanItemDO.class));
        // 明细落库后估算每站 ETA（出发时刻 = 批次开始）
        verify(dispatchEstimationService).estimatePlan(eq(100L), any(LocalDateTime.class), anyMap());
        ArgumentCaptor<TransportOrderDO> orderCaptor = ArgumentCaptor.forClass(TransportOrderDO.class);
        verify(orderMapper).update(orderCaptor.capture(), any());
        assertEquals(TransportOrderStatusEnum.ASSIGNED.getStatus(), orderCaptor.getValue().getStatus());
    }

    @Test
    void createSmartPlan_feasible_km_unit_uses_algorithm_distance_directly() {
        // distanceUnit=km（路网距离）：totalDistance 直接使用，不再 Haversine 换算
        mockSmartPlanContext();
        AlgorithmPlanRespDTO kmResult = feasibleResult();
        kmResult.setDistanceUnit(AlgorithmPlanRespDTO.DISTANCE_UNIT_KM);
        kmResult.setTotalDistance(88.5);
        // 路网路径的分段时长：估算 ETA 应按秒数累计（经 DispatchEstimationService 的路网分段表传入）
        kmResult.getVehiclePlans().get(0).getStops().get(2).setSegmentDuration(180L);
        when(algorithmAdapter.plan(any())).thenReturn(kmResult);
        doAnswer(invocation -> {
            DispatchPlanDO plan = invocation.getArgument(0);
            plan.setId(100L);
            return 1;
        }).when(dispatchPlanMapper).insert(any(DispatchPlanDO.class));

        dispatchService.createSmartPlan(smartReqVO());

        ArgumentCaptor<DispatchPlanDO> planCaptor = ArgumentCaptor.forClass(DispatchPlanDO.class);
        verify(dispatchPlanMapper).insert(planCaptor.capture());
        assertEquals(0, planCaptor.getValue().getTotalDistance().compareTo(new java.math.BigDecimal("88.5")));
        // 路网分段表只含带 segmentDuration 的经停（key = vehicleId:visitSequence）
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, DispatchEstimationService.RoadSegment>> segmentCaptor = ArgumentCaptor.forClass(Map.class);
        verify(dispatchEstimationService).estimatePlan(eq(100L), any(LocalDateTime.class), segmentCaptor.capture());
        assertEquals(1, segmentCaptor.getValue().size());
        assertEquals(180L, segmentCaptor.getValue().get("7:3").durationSeconds());
    }

    @Test
    void createSmartPlan_infeasible_throws_and_marks_task() {
        mockSmartPlanContext();
        when(algorithmAdapter.plan(any())).thenReturn(AlgorithmPlanRespDTO.builder()
                .requestId("req-1")
                .status(AlgorithmPlanRespDTO.STATUS_INFEASIBLE)
                .reasonCode(AlgorithmPlanRespDTO.REASON_OVER_CAPACITY)
                .build());

        ServiceException ex = assertThrows(ServiceException.class,
                () -> dispatchService.createSmartPlan(smartReqVO()));

        assertEquals(DISPATCH_NO_FEASIBLE.getCode(), ex.getCode());
        // 原因码转可读中文文案（前端直接展示）
        assertTrue(ex.getMessage().contains("运力不足"));
        ArgumentCaptor<DispatchTaskDO> taskCaptor = ArgumentCaptor.forClass(DispatchTaskDO.class);
        verify(dispatchTaskMapper).updateById(taskCaptor.capture());
        assertEquals(DispatchTaskStatusEnum.INFEASIBLE.getStatus(), taskCaptor.getValue().getStatus());
        verify(dispatchPlanMapper, never()).insert(any(DispatchPlanDO.class));
    }

    @Test
    void createSmartPlan_over_scale_limit_throws() {
        // 26 张在池订单（每张 1 算法单）超过算法 25 单上限 → 预检直接报错，不再调用算法
        List<TransportOrderDO> orders = new ArrayList<>();
        for (long i = 1; i <= 26; i++) {
            orders.add(TransportOrderDO.builder().id(i).orderType(2)
                    .pickupStationId(13L).deliveryStationId(1L)
                    .status(TransportOrderStatusEnum.POOLED.getStatus()).build());
        }
        when(orderMapper.selectList(any(Wrapper.class))).thenReturn(orders);
        when(stationMapper.selectById(1L)).thenReturn(StationDO.builder().id(1L).build());
        when(vehicleMapper.selectBatchIds(anyCollection())).thenReturn(List.of(
                VehicleDO.builder().id(7L).passengerCapacity(5).cargoCapacity(4).build()));
        when(stationMapper.selectBatchIds(anyCollection())).thenReturn(List.of(
                StationDO.builder().id(13L).build()));

        DispatchSmartPlanReqVO reqVO = smartReqVO();
        ServiceException ex = assertThrows(ServiceException.class,
                () -> dispatchService.createSmartPlan(reqVO));

        assertEquals(DISPATCH_SCALE_OVER_LIMIT.getCode(), ex.getCode());
        verify(algorithmAdapter, never()).plan(any());
    }

    @Test
    void createSmartPlan_attaches_bus_skeleton_when_shift_provided() {
        // Phase 5 回归：指定班次时，算法车辆 DTO 携带公交骨架（线路站点按 sequenceNo 排序、去场站）
        mockSmartPlanContext();
        when(shiftMapper.selectById(99L)).thenReturn(ShiftDO.builder().id(99L).routeId(88L).build());
        when(routeStationMapper.selectListByRouteIds(List.of(88L))).thenReturn(List.of(
                RouteStationDO.builder().stationId(12L).sequenceNo(2).build(),
                RouteStationDO.builder().stationId(11L).sequenceNo(1).build()));
        ArgumentCaptor<AlgorithmPlanReqDTO> reqCaptor = ArgumentCaptor.forClass(AlgorithmPlanReqDTO.class);
        when(algorithmAdapter.plan(reqCaptor.capture())).thenReturn(feasibleResult());
        doAnswer(invocation -> {
            DispatchPlanDO plan = invocation.getArgument(0);
            plan.setId(100L);
            return 1;
        }).when(dispatchPlanMapper).insert(any(DispatchPlanDO.class));

        DispatchSmartPlanReqVO reqVO = smartReqVO();
        reqVO.setShiftId(99L);
        dispatchService.createSmartPlan(reqVO);

        // 骨架按顺序、去场站(1)：11 → 12
        assertEquals(List.of("11", "12"), reqCaptor.getValue().getVehicles().get(0).getSkeleton());
    }

    @Test
    void createSmartPlan_pairs_cargo_pickup_and_delivery_as_shipment() {
        // PDPTW 口径回归（Li & Lim 2001）：取货站 → 送达站（两端都不是场站）的货运单，
        // 必须作为「配对货运单」下发给算法（展开成同一辆车的 PICKUP + DELIVERY，先取后送）；
        // 历史实现只发一个 DELIVERY 节点并丢掉取货站 = 货凭空出现在送达站，站点清单里看不到揽收点。
        TransportOrderDO cargoOrder = TransportOrderDO.builder().id(1L).orderType(2)
                .pickupStationId(11L).deliveryStationId(12L)
                .status(TransportOrderStatusEnum.POOLED.getStatus()).build();
        when(orderMapper.selectList(any(Wrapper.class))).thenReturn(List.of(cargoOrder));
        when(stationMapper.selectById(1L)).thenReturn(StationDO.builder().id(1L)
                .longitude(new BigDecimal("104.0000")).latitude(new BigDecimal("30.0000")).build());
        when(vehicleMapper.selectBatchIds(anyCollection())).thenReturn(List.of(
                VehicleDO.builder().id(7L).passengerCapacity(5).cargoCapacity(10).build()));
        when(stationMapper.selectBatchIds(anyCollection())).thenReturn(List.of(
                StationDO.builder().id(11L).longitude(new BigDecimal("104.0100")).latitude(new BigDecimal("30.0000")).build(),
                StationDO.builder().id(12L).longitude(new BigDecimal("104.0200")).latitude(new BigDecimal("30.0000")).build()));
        when(cargoOrderMapper.selectList(any())).thenReturn(List.of(
                CargoOrderDO.builder().id(1L).orderId(1L).itemCount(3)
                        .weightKg(new BigDecimal("5.5")).volumeM3(new BigDecimal("0.0200")).build()));
        lenient().when(orderMapper.update(any(TransportOrderDO.class), any())).thenReturn(1);
        ArgumentCaptor<AlgorithmPlanReqDTO> reqCaptor = ArgumentCaptor.forClass(AlgorithmPlanReqDTO.class);
        when(algorithmAdapter.plan(reqCaptor.capture())).thenReturn(feasibleResult());
        doAnswer(invocation -> {
            DispatchPlanDO plan = invocation.getArgument(0);
            plan.setId(100L);
            return 1;
        }).when(dispatchPlanMapper).insert(any(DispatchPlanDO.class));

        dispatchService.createSmartPlan(smartReqVO());

        AlgorithmPlanReqDTO req = reqCaptor.getValue();
        assertEquals(1, req.getShipments().size(), "村→村货运单应下发 1 张配对货运单");
        assertEquals("1", req.getShipments().get(0).getShipmentId());
        assertEquals("11", req.getShipments().get(0).getPickupStationId(), "配对单必须带取货站");
        assertEquals("12", req.getShipments().get(0).getDeliveryStationId());
        assertEquals(3, req.getShipments().get(0).getQuantity());
        assertTrue(req.getOrders() == null || req.getOrders().stream()
                        .noneMatch(o -> "1".equals(o.getOrderId())),
                "配对单不应再以单向订单节点下发（否则取货站会丢）");
    }

    @Test
    void createSmartPlan_sends_per_order_timeliness_and_vehicle_weight_capacity() {
        // 订单档案里的 earliest_pickup_time / latest_delivery_time 是真数据；此前没下发，
        // 算法只能按批窗口判，迟到的单照样被报成可行。车辆货仓载重同理取车辆档案真实值。
        AlgorithmPlanReqDTO req = planRequestForCargoOrder(11L, 12L, new BigDecimal("50.00"),
                LocalDateTime.parse("2026-08-09T08:10:00"), LocalDateTime.parse("2026-08-09T08:25:00"),
                new BigDecimal("5.5"), new BigDecimal("0.0200"), null);

        assertEquals(OffsetDateTime.parse("2026-08-09T08:10:00+08:00"), req.getShipments().get(0).getReadyTime());
        assertEquals(OffsetDateTime.parse("2026-08-09T08:25:00+08:00"),
                req.getShipments().get(0).getDeliveryDeadline());
        assertNull(req.getShipments().get(0).getPickupDeadline(), "业务侧没有揽收截止字段，不得凭空下发");
        assertEquals(Double.valueOf(50.0), req.getVehicles().get(0).getCargoWeightCapacityKg());
        assertEquals(Double.valueOf(5.5), req.getShipments().get(0).getWeightKg());
    }

    @Test
    void createSmartPlan_stops_dropping_weight_on_single_leg_cargo_orders() {
        // 场站→村（派送）与村→场站（揽收）是客货邮的主力形态，此前只有配对货运单带重量，
        // 单向订单分支把货运子表的重量/体积丢了 —— 载重校验对绝大多数单等于没生效
        AlgorithmPlanReqDTO req = planRequestForCargoOrder(1L, 12L, null, null, null,
                new BigDecimal("5.5"), new BigDecimal("0.0200"), null);

        assertEquals(1, req.getOrders().size(), "场站→站点应作为单向派送订单下发");
        assertEquals(Double.valueOf(5.5), req.getOrders().get(0).getWeightKg());
        assertEquals(Double.valueOf(0.02), req.getOrders().get(0).getVolumeM3());
        assertNull(req.getVehicles().get(0).getCargoWeightCapacityKg(), "车辆档案未配载重时不编造上限");
    }

    @Test
    void createSmartPlan_tags_roadAccess_blocked_only_for_stations_closed_to_buses() {
        // 站点档案 vehicle_access=false = 公交车进不去，算法据此改最近骨架站交接
        AlgorithmPlanReqDTO blocked = planRequestForCargoOrder(1L, 12L, null, null, null, null, null, false);
        assertEquals("BLOCKED", blocked.getOrders().get(0).getRoadAccess());

        // 对照：档案未显式标注（历史数据为 null）时行为必须与过去完全一致
        AlgorithmPlanReqDTO unset = planRequestForCargoOrder(1L, 12L, null, null, null, null, null, null);
        assertNull(unset.getOrders().get(0).getRoadAccess());
    }

    @Test
    void createSmartPlan_tags_paired_shipment_blocked_when_either_leg_is_closed_to_buses() {
        AlgorithmPlanReqDTO req = planRequestForCargoOrder(11L, 12L, null, null, null, null, null, false);
        assertEquals(1, req.getShipments().size());
        assertEquals("BLOCKED", req.getShipments().get(0).getRoadAccess(),
                "配对单不能只挪一条腿，任一腿进不去就整单改站点交接");
    }

    @Test
    void createSmartPlan_never_tags_passenger_orders_with_roadAccess() {
        // 业务底线：每站必停拉客，乘客点位由不得算法改派 —— 即便站点标注车辆不可进入也不下发标记
        mockSmartPlanContext();
        when(stationMapper.selectBatchIds(anyCollection())).thenReturn(List.of(
                StationDO.builder().id(11L).longitude(new BigDecimal("104.0100")).latitude(new BigDecimal("30.0000")).build(),
                StationDO.builder().id(12L).vehicleAccess(false)
                        .longitude(new BigDecimal("104.0200")).latitude(new BigDecimal("30.0000")).build()));
        ArgumentCaptor<AlgorithmPlanReqDTO> reqCaptor = ArgumentCaptor.forClass(AlgorithmPlanReqDTO.class);
        when(algorithmAdapter.plan(reqCaptor.capture())).thenReturn(feasibleResult());
        doAnswer(invocation -> {
            DispatchPlanDO plan = invocation.getArgument(0);
            plan.setId(100L);
            return 1;
        }).when(dispatchPlanMapper).insert(any(DispatchPlanDO.class));

        dispatchService.createSmartPlan(smartReqVO());

        List<AlgorithmOrderDTO> orders = reqCaptor.getValue().getOrders();
        assertFalse(orders.isEmpty());
        orders.forEach(order -> {
            assertEquals(AlgorithmOrderDTO.TYPE_PASSENGER, order.getOrderType());
            assertNull(order.getRoadAccess(), "客运单不得标 roadAccess");
            // 客运同样不下发时效：公交每站必停、无预约乘车，给算法一个"乘客可上车时刻"
            // 等于授权它为了带货而让车在站外等 —— 这条底线不由字段贯通顺带破掉。
            assertNull(order.getReadyTime(), "客运单不得带 readyTime");
            assertNull(order.getDeliveryDeadline(), "客运单不得带送达截止");
        });
    }

    @Test
    void createSmartPlan_omits_weight_cap_when_vehicle_kg_is_the_db_default_zero() {
        // DB 列 `cargo_capacity_kg NOT NULL DEFAULT 0`（transport-schema.sql:9）⇒ "没核定"读出来是 0，
        // 而算法把"有值"当真上限（feasibility 的 `cargo_weight_capacity_kg is not None and 超了就不行`）
        // ⇒ 下发 0.0 会让任何带重量的件一律判超限，货运全被舍弃。所以 0 必须折算成"不下发"。
        AlgorithmPlanReqDTO req = planRequestForCargoOrder(11L, 12L, BigDecimal.ZERO,
                null, null, new BigDecimal("50"), null, true);
        assertNull(req.getVehicles().get(0).getCargoWeightCapacityKg(),
                "载重未核定（DB 默认 0）不得被当成 0kg 硬上限下发");
        assertNotNull(req.getVehicles().get(0).getPassengerCapacity());
    }

    @Test
    void createSmartPlan_reports_data_gap_instead_of_422_when_no_vehicle_has_seats() {
        // `passenger_capacity` 在 DDL 是 NOT NULL DEFAULT 0（transport-schema.sql:8），而算法契约要求 ≥1
        // （algorithm/app/models.py:56）：原样下发 0 会让整批派单在算法侧 422（一个看不懂的 HTTP 错误），
        // 而替业务编一个座位数更不可接受（乘客"每站必停"全靠这个数兑现）。
        // 正确出口是一条指名车辆的可读业务错误，且**不发起算法调用**。
        mockSmartPlanContext();
        when(vehicleMapper.selectBatchIds(anyCollection())).thenReturn(List.of(
                VehicleDO.builder().id(7L).passengerCapacity(0).cargoCapacity(4).build()));

        cn.iocoder.yudao.framework.common.exception.ServiceException ex = assertThrows(
                cn.iocoder.yudao.framework.common.exception.ServiceException.class,
                () -> dispatchService.createSmartPlan(smartReqVO()));
        assertEquals(DISPATCH_VEHICLE_PROFILE_INCOMPLETE.getCode(), ex.getCode());
        assertTrue(ex.getMessage().contains("7"), "错误消息要点名是哪台车：" + ex.getMessage());
        verify(algorithmAdapter, never()).plan(any(AlgorithmPlanReqDTO.class));
    }

    @Test
    void createSmartPlan_excludes_the_vehicle_without_seated_capacity() {
        // 同批里还有一台核定过的车 → 不能因为一台没录就整批失败，也不能带着 0 座去派乘客。
        mockSmartPlanContext();
        when(vehicleMapper.selectBatchIds(anyCollection())).thenReturn(List.of(
                VehicleDO.builder().id(7L).passengerCapacity(0).cargoCapacity(4).build(),
                VehicleDO.builder().id(8L).passengerCapacity(9).cargoCapacity(4).build()));
        ArgumentCaptor<AlgorithmPlanReqDTO> reqCaptor = ArgumentCaptor.forClass(AlgorithmPlanReqDTO.class);
        when(algorithmAdapter.plan(reqCaptor.capture())).thenReturn(feasibleResult());
        doAnswer(invocation -> {
            DispatchPlanDO plan = invocation.getArgument(0);
            plan.setId(100L);
            return 1;
        }).when(dispatchPlanMapper).insert(any(DispatchPlanDO.class));

        dispatchService.createSmartPlan(smartReqVO());

        List<AlgorithmVehicleDTO> sent = reqCaptor.getValue().getVehicles();
        assertEquals(1, sent.size(), "未核定载客的车不得进算法请求（下发 0 座会 422）");
        assertEquals(8L, sent.get(0).getVehicleId());
        assertEquals(9, sent.get(0).getPassengerCapacity());
    }

    @Test
    void createSmartPlan_sends_timeliness_on_station_delivery_order() {
        // 任务 #32：站点货运单（场站→村）此前不下发任何时效 ⇒ 迟到方案照样报可行。
        // 派送单有 DELIVER 事件锚点，所以送达截止必须真的进请求（算法侧据此判 ETA_MISSED_DELIVERY）。
        LocalDateTime earliest = LocalDateTime.of(2026, 10, 12, 8, 5);
        LocalDateTime latest = LocalDateTime.of(2026, 10, 12, 8, 40);
        AlgorithmOrderDTO order = planRequestForStationFreight(false, earliest, latest).getOrders().get(0);

        assertEquals(AlgorithmOrderDTO.TYPE_DELIVERY, order.getOrderType());
        assertEquals("12", order.getStationId());
        assertEquals(latest, order.getDeliveryDeadline().toLocalDateTime(), "送达截止不得在映射中丢失");
        assertEquals(earliest, order.getReadyTime().toLocalDateTime());
        // 契约要求带时区（algorithm/app/models.py 的 datetime），业务侧 LocalDateTime 按班次时区补
        assertEquals(ZoneOffset.ofHours(8), order.getDeliveryDeadline().getOffset(),
                "时效必须带 +08:00，否则算法按 UTC 判会早 8 小时");
    }

    @Test
    void createSmartPlan_sends_timeliness_on_station_pickup_order() {
        // 揽收单（村→场站）同样下发真实时效字段。算法侧目前只锚 readyTime（PICKUP 事件要等到能取件），
        // deliveryDeadline 随请求下发但暂不判（货随车回场站才算送达，收班事件不带 task_id）——
        // 这条用例钉的是"业务事实要如实下发"，不钉"算法判了它"。
        LocalDateTime earliest = LocalDateTime.of(2026, 10, 12, 7, 30);
        LocalDateTime latest = LocalDateTime.of(2026, 10, 12, 11, 0);
        AlgorithmOrderDTO order = planRequestForStationFreight(true, earliest, latest).getOrders().get(0);

        assertEquals(AlgorithmOrderDTO.TYPE_PICKUP, order.getOrderType());
        assertEquals("13", order.getStationId());
        assertEquals(earliest, order.getReadyTime().toLocalDateTime());
        assertEquals(latest, order.getDeliveryDeadline().toLocalDateTime());
    }

    @Test
    void createSmartPlan_sends_no_timeliness_for_orders_without_promised_times() {
        // 订单档案里两个时刻都为空 = 业务侧没承诺（不是承诺在 0 点）：必须原样留 null，
        // 否则算法会拿一个编造出来的截止时刻把正常方案判成迟到。
        AlgorithmOrderDTO order = planRequestForStationFreight(false, null, null).getOrders().get(0);

        assertEquals(AlgorithmOrderDTO.TYPE_DELIVERY, order.getOrderType());
        assertNull(order.getReadyTime());
        assertNull(order.getDeliveryDeadline());
    }

    /**
     * 站点货运单快照桩：一张单、一辆车，按腿方向映射成 DELIVERY（场站 1→村点 12）
     * 或 PICKUP（村点 13→场站 1），返回真正下发给算法的请求。
     */
    private AlgorithmPlanReqDTO planRequestForStationFreight(boolean pickupLeg,
                                                             LocalDateTime earliestPickup,
                                                             LocalDateTime latestDelivery) {
        TransportOrderDO cargoOrder = TransportOrderDO.builder().id(1L).orderType(2)
                .pickupStationId(pickupLeg ? 13L : 1L).deliveryStationId(pickupLeg ? 1L : 12L)
                .earliestPickupTime(earliestPickup).latestDeliveryTime(latestDelivery)
                .status(TransportOrderStatusEnum.POOLED.getStatus()).build();
        when(orderMapper.selectList(any(Wrapper.class))).thenReturn(List.of(cargoOrder));
        when(stationMapper.selectById(1L)).thenReturn(StationDO.builder().id(1L)
                .longitude(new BigDecimal("104.0000")).latitude(new BigDecimal("30.0000")).build());
        when(vehicleMapper.selectBatchIds(anyCollection())).thenReturn(List.of(
                VehicleDO.builder().id(7L).passengerCapacity(5).cargoCapacity(10).build()));
        when(stationMapper.selectBatchIds(anyCollection())).thenReturn(List.of(
                StationDO.builder().id(12L).longitude(new BigDecimal("104.0200")).latitude(new BigDecimal("30.0000")).build(),
                StationDO.builder().id(13L).longitude(new BigDecimal("104.0300")).latitude(new BigDecimal("30.0000")).build()));
        lenient().when(orderMapper.update(any(TransportOrderDO.class), any())).thenReturn(1);
        ArgumentCaptor<AlgorithmPlanReqDTO> reqCaptor = ArgumentCaptor.forClass(AlgorithmPlanReqDTO.class);
        when(algorithmAdapter.plan(reqCaptor.capture()))
                .thenReturn(pickupLeg ? feasiblePickupResult() : cargoDeliverPlan(true));
        doAnswer(invocation -> {
            DispatchPlanDO plan = invocation.getArgument(0);
            plan.setId(100L);
            return 1;
        }).when(dispatchPlanMapper).insert(any(DispatchPlanDO.class));

        dispatchService.createSmartPlan(smartReqVO());
        return reqCaptor.getValue();
    }

    /**
     * 智能派单快照桩：一张货运单（pickup → delivery）+ 车辆载重 + 货运子表重量体积 + 送达站通行标记，
     * 返回真正下发给算法的请求。station 1 为场站，11/12 为村点。
     */
    private AlgorithmPlanReqDTO planRequestForCargoOrder(Long pickupStationId, Long deliveryStationId,
                                                         BigDecimal vehicleCargoCapacityKg,
                                                         LocalDateTime earliestPickup, LocalDateTime latestDelivery,
                                                         BigDecimal weightKg, BigDecimal volumeM3,
                                                         Boolean deliveryStationVehicleAccess) {
        TransportOrderDO cargoOrder = TransportOrderDO.builder().id(1L).orderType(2)
                .pickupStationId(pickupStationId).deliveryStationId(deliveryStationId)
                .earliestPickupTime(earliestPickup).latestDeliveryTime(latestDelivery)
                .status(TransportOrderStatusEnum.POOLED.getStatus()).build();
        when(orderMapper.selectList(any(Wrapper.class))).thenReturn(List.of(cargoOrder));
        when(stationMapper.selectById(1L)).thenReturn(StationDO.builder().id(1L)
                .longitude(new BigDecimal("104.0000")).latitude(new BigDecimal("30.0000")).build());
        when(vehicleMapper.selectBatchIds(anyCollection())).thenReturn(List.of(
                VehicleDO.builder().id(7L).passengerCapacity(5).cargoCapacity(10)
                        .cargoCapacityKg(vehicleCargoCapacityKg).build()));
        when(stationMapper.selectBatchIds(anyCollection())).thenReturn(List.of(
                StationDO.builder().id(11L).longitude(new BigDecimal("104.0100")).latitude(new BigDecimal("30.0000")).build(),
                StationDO.builder().id(12L).vehicleAccess(deliveryStationVehicleAccess)
                        .longitude(new BigDecimal("104.0200")).latitude(new BigDecimal("30.0000")).build()));
        when(cargoOrderMapper.selectList(any())).thenReturn(List.of(
                CargoOrderDO.builder().id(1L).orderId(1L).itemCount(3).weightKg(weightKg).volumeM3(volumeM3).build()));
        lenient().when(orderMapper.update(any(TransportOrderDO.class), any())).thenReturn(1);
        ArgumentCaptor<AlgorithmPlanReqDTO> reqCaptor = ArgumentCaptor.forClass(AlgorithmPlanReqDTO.class);
        when(algorithmAdapter.plan(reqCaptor.capture())).thenReturn(feasibleResult());
        doAnswer(invocation -> {
            DispatchPlanDO plan = invocation.getArgument(0);
            plan.setId(100L);
            return 1;
        }).when(dispatchPlanMapper).insert(any(DispatchPlanDO.class));

        dispatchService.createSmartPlan(smartReqVO());
        return reqCaptor.getValue();
    }

    @Test
    void createSmartPlan_rejected_stop_is_not_turned_into_a_driver_task() {
        // 算法明确说"这一趟不在这里服务这一单"（accepted=false）时，这条经停只是解释信息：
        // 写成任务明细司机会照单去一个算法判定不该去的点，而这一单又不会进联运接力 —— 货凭空消失。
        mockCargoDeliverContext();
        when(algorithmAdapter.plan(any())).thenReturn(cargoDeliverPlan(false));

        dispatchService.createSmartPlan(smartReqVO());

        // 只剩 DEPART + RETURN 两条：被拒的 DELIVER 不落明细
        verify(dispatchPlanItemMapper, times(2)).insert(any(DispatchPlanItemDO.class));
        ArgumentCaptor<Long> vehicleCaptor = ArgumentCaptor.forClass(Long.class);
        verify(multiLegService).planLegs(eq(1L), eq(100L), vehicleCaptor.capture(), any(), any());
        assertNull(vehicleCaptor.getValue(), "被拒的经停不得算作已分配，该单必须进多段联运接力");
    }

    @Test
    void createSmartPlan_accepted_stop_is_still_written_and_assigned() {
        // 正向对照（同一份方案，只把 accepted 从 false 改成 true）：明细照常落库、订单归到这辆车上。
        // 没有这一条，上一例可以靠"所有 DELIVER 都不落库"骗绿。
        mockCargoDeliverContext();
        when(algorithmAdapter.plan(any())).thenReturn(cargoDeliverPlan(true));

        dispatchService.createSmartPlan(smartReqVO());

        verify(dispatchPlanItemMapper, times(3)).insert(any(DispatchPlanItemDO.class));
        ArgumentCaptor<Long> vehicleCaptor = ArgumentCaptor.forClass(Long.class);
        verify(multiLegService).planLegs(eq(1L), eq(100L), vehicleCaptor.capture(), any(), any());
        assertEquals(Long.valueOf(7L), vehicleCaptor.getValue());
    }

    /** 场站→村点 12 的单向派送货运单快照（一张单、一辆车、无骨架） */
    private void mockCargoDeliverContext() {
        TransportOrderDO cargoOrder = TransportOrderDO.builder().id(1L).orderType(2)
                .pickupStationId(1L).deliveryStationId(12L)
                .status(TransportOrderStatusEnum.POOLED.getStatus()).build();
        when(orderMapper.selectList(any(Wrapper.class))).thenReturn(List.of(cargoOrder));
        when(stationMapper.selectById(1L)).thenReturn(StationDO.builder().id(1L)
                .longitude(new BigDecimal("104.0000")).latitude(new BigDecimal("30.0000")).build());
        when(vehicleMapper.selectBatchIds(anyCollection())).thenReturn(List.of(
                VehicleDO.builder().id(7L).passengerCapacity(5).cargoCapacity(10).build()));
        when(stationMapper.selectBatchIds(anyCollection())).thenReturn(List.of(
                StationDO.builder().id(12L).longitude(new BigDecimal("104.0200")).latitude(new BigDecimal("30.0000")).build()));
        lenient().when(orderMapper.update(any(TransportOrderDO.class), any())).thenReturn(1);
        doAnswer(invocation -> {
            DispatchPlanDO plan = invocation.getArgument(0);
            plan.setId(100L);
            return 1;
        }).when(dispatchPlanMapper).insert(any(DispatchPlanDO.class));
    }

    /** DEPART@1 → DELIVER@12（订单 1，accepted 由用例决定）→ RETURN@1 */
    private AlgorithmPlanRespDTO cargoDeliverPlan(Boolean accepted) {
        List<AlgorithmRouteStopDTO> stops = List.of(
                AlgorithmRouteStopDTO.builder().stationId("1").action(AlgorithmRouteStopDTO.ACTION_DEPART).build(),
                AlgorithmRouteStopDTO.builder().stationId("12").orderId("1")
                        .action(AlgorithmRouteStopDTO.ACTION_DELIVER)
                        .accepted(accepted)
                        .reasonCode(Boolean.FALSE.equals(accepted) ? "DETOUR_OVER_THRESHOLD" : null)
                        .build(),
                AlgorithmRouteStopDTO.builder().stationId("1").action(AlgorithmRouteStopDTO.ACTION_RETURN).build());
        return AlgorithmPlanRespDTO.builder()
                .requestId("req-1")
                .status(AlgorithmPlanRespDTO.STATUS_FEASIBLE)
                .algorithmVersion("algo-1.0")
                .parameterVersion("param-1.0")
                .totalDistance(2.0)
                .vehiclePlans(List.of(AlgorithmVehiclePlanDTO.builder()
                        .vehicleId(7L).stops(stops).totalDistance(2.0).build()))
                .build();
    }

    @Test
    void createSmartPlan_pool_claimed_by_concurrent_throws() {
        // P1-004 回归：并发智能派单——订单池已被其它派单 CAS 抢占（update 返回 0）→ 不重复派单
        mockSmartPlanContext();
        when(orderMapper.update(any(TransportOrderDO.class), any())).thenReturn(0);

        ServiceException ex = assertThrows(ServiceException.class,
                () -> dispatchService.createSmartPlan(smartReqVO()));

        assertEquals(DISPATCH_POOL_EMPTY.getCode(), ex.getCode());
        verify(algorithmAdapter, never()).plan(any());
        verify(dispatchPlanMapper, never()).insert(any(DispatchPlanDO.class));
    }

    @Test
    void createSmartPlan_partial_claim_rolls_back() {
        // P1-004 回归：订单池被非派单路径并发修改（CAS 仅抢占部分）→ 抛非 ServiceException 触发整事务回滚
        when(orderMapper.selectList(any(Wrapper.class))).thenReturn(List.of(
                TransportOrderDO.builder().id(1L).orderType(2)
                        .pickupStationId(13L).deliveryStationId(1L)
                        .status(TransportOrderStatusEnum.POOLED.getStatus()).build(),
                TransportOrderDO.builder().id(2L).orderType(2)
                        .pickupStationId(13L).deliveryStationId(1L)
                        .status(TransportOrderStatusEnum.POOLED.getStatus()).build()));
        when(stationMapper.selectById(1L)).thenReturn(StationDO.builder().id(1L).build());
        when(vehicleMapper.selectBatchIds(anyCollection())).thenReturn(List.of(
                VehicleDO.builder().id(7L).passengerCapacity(5).cargoCapacity(4).build()));
        when(stationMapper.selectBatchIds(anyCollection())).thenReturn(List.of(
                StationDO.builder().id(13L).build()));
        when(orderMapper.update(any(TransportOrderDO.class), any())).thenReturn(1); // 仅抢占 1/2

        // 并发抢占失败：异常统一转成业务可读错误（前端不再只显示"服务器错误，请联系管理员"）
        var ex = assertThrows(cn.iocoder.yudao.framework.common.exception.ServiceException.class,
                () -> dispatchService.createSmartPlan(smartReqVO()));
        org.junit.jupiter.api.Assertions.assertTrue(ex.getMessage() != null && ex.getMessage().contains("智能调度"));
        verify(algorithmAdapter, never()).plan(any());
    }

    @Test
    void createSmartPlan_infeasible_releases_claimed_orders() {
        // P1-004 回归：算法无解时回滚 CAS 抢占（订单回池可重新派单，不滞留 ASSIGNED）
        mockSmartPlanContext();
        when(algorithmAdapter.plan(any())).thenReturn(AlgorithmPlanRespDTO.builder()
                .requestId("req-1")
                .status(AlgorithmPlanRespDTO.STATUS_INFEASIBLE)
                .reasonCode(AlgorithmPlanRespDTO.REASON_TIMING_CONFLICT)
                .build());

        ServiceException ex = assertThrows(ServiceException.class,
                () -> dispatchService.createSmartPlan(smartReqVO()));
        assertEquals(DISPATCH_NO_FEASIBLE.getCode(), ex.getCode());
        // 两次更新：CAS 抢占(ASSIGNED) + 失败回滚(POOLED)，最后一次应为回池
        ArgumentCaptor<TransportOrderDO> orderCaptor = ArgumentCaptor.forClass(TransportOrderDO.class);
        verify(orderMapper, atLeast(2)).update(orderCaptor.capture(), any());
        List<TransportOrderDO> updates = orderCaptor.getAllValues();
        assertEquals(TransportOrderStatusEnum.POOLED.getStatus(),
                updates.get(updates.size() - 1).getStatus());
        verify(dispatchPlanMapper, never()).insert(any(DispatchPlanDO.class));
    }

    @Test
    void createSmartPlan_plan_persist_failure_releases_claimed_orders() {
        // P0-1 回归：方案落库阶段抛异常（实测为 plan_reason 列宽不足的 Data too long）时，
        // 已 CAS 抢占的订单必须释放回订单池——否则订单停在「已分配但无方案」，池子被清空无法重跑。
        mockSmartPlanContext();
        when(algorithmAdapter.plan(any())).thenReturn(feasibleResult());
        when(dispatchPlanMapper.insert(any(DispatchPlanDO.class)))
                .thenThrow(new org.springframework.dao.DataIntegrityViolationException(
                        "Data too long for column 'plan_reason' at row 1"));

        ServiceException ex = assertThrows(ServiceException.class,
                () -> dispatchService.createSmartPlan(smartReqVO()));

        // 技术异常转成可读业务错误（前端能看到根因）
        assertEquals(ALGORITHM_RESULT_INVALID.getCode(), ex.getCode());
        // BE-27：对外只返回稳定文案，根因（plan_reason）只进日志不再透传前端
        assertTrue(ex.getMessage().contains("智能调度执行失败"));
        assertFalse(ex.getMessage().contains("plan_reason"));
        // 订单状态更新序列：CAS 抢占(已分配) → 失败释放(已入池)，最后一次必须是回池
        ArgumentCaptor<TransportOrderDO> orderCaptor = ArgumentCaptor.forClass(TransportOrderDO.class);
        verify(orderMapper, atLeast(2)).update(orderCaptor.capture(), any());
        List<TransportOrderDO> updates = orderCaptor.getAllValues();
        assertEquals(TransportOrderStatusEnum.ASSIGNED.getStatus(), updates.get(0).getStatus());
        assertEquals(TransportOrderStatusEnum.POOLED.getStatus(),
                updates.get(updates.size() - 1).getStatus());
        // 方案未落库 → 无经停明细、无半成品方案需要清理
        verify(dispatchPlanItemMapper, never()).insert(any(DispatchPlanItemDO.class));
        verify(dispatchPlanMapper, never()).deleteById(anyLong());
    }

    @Test
    void createSmartPlan_estimation_failure_cleans_partial_plan_and_releases_orders() {
        // P0-1 回归：方案与经停明细已落库、随后估算 ETA 失败 → 半成品方案（明细/段/日志/方案）必须清掉，
        // 订单回池，不能留下「方案存在但订单已回池」的孤儿数据（孤儿段会被下次调度复用，见 P0-A）。
        mockSmartPlanContext();
        when(algorithmAdapter.plan(any())).thenReturn(feasibleResult());
        // 任务落库后由 MyBatis-Plus 回填主键（mock 里手工回填，failDispatchTask 依赖任务主键回写失败态）
        doAnswer(invocation -> {
            DispatchTaskDO task = invocation.getArgument(0);
            task.setId(900L);
            return 1;
        }).when(dispatchTaskMapper).insert(any(DispatchTaskDO.class));
        doAnswer(invocation -> {
            DispatchPlanDO plan = invocation.getArgument(0);
            plan.setId(100L);
            return 1;
        }).when(dispatchPlanMapper).insert(any(DispatchPlanDO.class));
        doThrow(new IllegalStateException("估算服务不可用"))
                .when(dispatchEstimationService).estimatePlan(eq(100L), any(LocalDateTime.class), anyMap());

        ServiceException ex = assertThrows(ServiceException.class,
                () -> dispatchService.createSmartPlan(smartReqVO()));
        // BE-27：根因（估算服务不可用）只进日志，对外为稳定文案
        assertTrue(ex.getMessage().contains("智能调度执行失败"));
        assertFalse(ex.getMessage().contains("估算服务不可用"));

        // 清理顺序：本方案的运输段 → 经停明细 → 状态日志 → 方案本体
        verify(transportLegMapper).delete(any());
        verify(dispatchPlanItemMapper).delete(any());
        verify(dispatchPlanLogMapper).delete(any());
        verify(dispatchPlanMapper).deleteById(100L);
        // 订单最后一次更新为回池
        ArgumentCaptor<TransportOrderDO> orderCaptor = ArgumentCaptor.forClass(TransportOrderDO.class);
        verify(orderMapper, atLeast(2)).update(orderCaptor.capture(), any());
        List<TransportOrderDO> updates = orderCaptor.getAllValues();
        assertEquals(TransportOrderStatusEnum.POOLED.getStatus(),
                updates.get(updates.size() - 1).getStatus());
        // 调度任务被置为失败，便于后台排查（事务不回滚，任务终态需要落库）
        ArgumentCaptor<DispatchTaskDO> taskCaptor = ArgumentCaptor.forClass(DispatchTaskDO.class);
        verify(dispatchTaskMapper, atLeast(2)).updateById(taskCaptor.capture());
        DispatchTaskDO lastTask = taskCaptor.getAllValues().get(taskCaptor.getAllValues().size() - 1);
        assertEquals(DispatchTaskStatusEnum.FAILED.getStatus(), lastTask.getStatus());
    }

    @Test
    void createSmartPlan_keeps_plan_reason_longer_than_500_chars() {
        // P0-2 回归：plan_reason 列宽已由 varchar(500) 扩到 varchar(2000)（见
        // sql/mysql/transport-schema-incremental.sql 的幂等 MODIFY）。这里断言 681 字符的解释
        // 原样落库（不截断到 500），避免以后又被列宽卡住导致一键演示中断。
        mockSmartPlanContext();
        when(algorithmAdapter.plan(any())).thenReturn(feasibleResult());
        doAnswer(invocation -> {
            DispatchPlanDO plan = invocation.getArgument(0);
            plan.setId(100L);
            return 1;
        }).when(dispatchPlanMapper).insert(any(DispatchPlanDO.class));
        when(multiLegService.planLegs(eq(1L), eq(100L), any(), any(), any())).thenReturn(List.of(
                TransportLegDO.builder().orderId(1L).planId(100L).legSequence(1)
                        .fromStationId(11L).toStationId(12L).distanceKm(new BigDecimal("5.0"))
                        .estimatedDeparture(LocalDateTime.of(2026, 9, 12, 10, 0))
                        .estimatedArrival(LocalDateTime.of(2026, 9, 12, 10, 30)).build()));
        String longReason = "多段联运".repeat(170) + "x"; // 681 字符（实测方案 32 的解释长度）
        when(multiLegService.preview(1L)).thenReturn(new MultiLegPlanner.PlanResult(
                DispatchPlanningModeEnum.MULTI_LEG.getMode(), 3, 2, 18.0, 60, 1.0,
                List.of(), List.of(), null, longReason));

        Long planId = dispatchService.createSmartPlan(smartReqVO());

        assertEquals(100L, planId);
        ArgumentCaptor<DispatchPlanDO> planCaptor = ArgumentCaptor.forClass(DispatchPlanDO.class);
        verify(dispatchPlanMapper).updateById(planCaptor.capture());
        String persisted = planCaptor.getValue().getPlanReason();
        assertEquals(681, longReason.length());
        assertTrue(persisted.length() > 500, "超过 500 字符的解释必须原样落库（列宽已扩到 2000）");
        // 方案解释现在会先写"任务窗口"，但长解释本身必须完整保留（不得被截断）
        assertTrue(persisted.startsWith("任务窗口 "), "方案解释应先写明本次任务窗口：" + persisted);
        assertTrue(persisted.contains(longReason), "超过 500 字符的方案解释必须完整落库");
    }

    @Test
    void createSmartPlan_surfaces_the_solver_shedding_explanation_in_plan_reason() {
        // 算法侧的口径是"少服务必须是事实而不是默认值"：带不下的单进 unassignedOrderIds，
        // 同时在 warnings 里说清成因（超载 / 必迟到 / 超窗 / 绕行出界）。后端此前收下 warnings
        // 却全仓无人读取 —— 运营只看得到"少了单"，看不到"为什么少"。
        mockSmartPlanContext();
        AlgorithmPlanRespDTO withWarnings = feasibleResult();
        withWarnings.setWarnings(List.of("DISCARDED_ORDERS=7,8", "DISCARDED_CAPACITY_OVERFLOW=7",
                "SEARCH_START_MS=1234", "ITERATION_COUNT=2"));
        when(algorithmAdapter.plan(any())).thenReturn(withWarnings);
        doAnswer(invocation -> {
            DispatchPlanDO plan = invocation.getArgument(0);
            plan.setId(100L);
            return 1;
        }).when(dispatchPlanMapper).insert(any(DispatchPlanDO.class));
        when(multiLegService.planLegs(eq(1L), eq(100L), any(), any(), any())).thenReturn(List.of(
                TransportLegDO.builder().orderId(1L).planId(100L).legSequence(1)
                        .fromStationId(11L).toStationId(12L).distanceKm(new BigDecimal("5.0"))
                        .estimatedDeparture(LocalDateTime.of(2026, 9, 12, 10, 0))
                        .estimatedArrival(LocalDateTime.of(2026, 9, 12, 10, 30)).build()));
        when(multiLegService.preview(1L)).thenReturn(new MultiLegPlanner.PlanResult(
                DispatchPlanningModeEnum.DIRECT.getMode(), 1, 0, 5.0, 30, 1.0,
                List.of(), List.of(), null, "直达"));

        dispatchService.createSmartPlan(smartReqVO());

        ArgumentCaptor<DispatchPlanDO> planCaptor = ArgumentCaptor.forClass(DispatchPlanDO.class);
        verify(dispatchPlanMapper).updateById(planCaptor.capture());
        String persisted = planCaptor.getValue().getPlanReason();
        assertTrue(persisted.contains("带不下 2 单"), persisted);
        assertTrue(persisted.contains("货舱超载 1 单"), persisted);
        assertTrue(persisted.contains("7、8"), persisted);
        // 纯遥测不许挤进业务解释：操作员读不懂，还会占掉方案理由那 2000 字的额度
        assertFalse(persisted.contains("SEARCH_START_MS"), persisted);
        assertFalse(persisted.contains("ITERATION_COUNT"), persisted);
    }

    @Test
    void createSmartPlan_invents_no_shedding_line_when_the_solver_shed_nothing() {
        // 对照：warnings 只有遥测时不能冒出"带不下 N 单"——解释只能翻译算法真说了的东西
        mockSmartPlanContext();
        AlgorithmPlanRespDTO telemetryOnly = feasibleResult();
        telemetryOnly.setWarnings(List.of("SEARCH_START_MS=1234", "ITERATION_COUNT=2"));
        when(algorithmAdapter.plan(any())).thenReturn(telemetryOnly);
        doAnswer(invocation -> {
            DispatchPlanDO plan = invocation.getArgument(0);
            plan.setId(100L);
            return 1;
        }).when(dispatchPlanMapper).insert(any(DispatchPlanDO.class));
        when(multiLegService.planLegs(eq(1L), eq(100L), any(), any(), any())).thenReturn(List.of(
                TransportLegDO.builder().orderId(1L).planId(100L).legSequence(1)
                        .fromStationId(11L).toStationId(12L).distanceKm(new BigDecimal("5.0"))
                        .estimatedDeparture(LocalDateTime.of(2026, 9, 12, 10, 0))
                        .estimatedArrival(LocalDateTime.of(2026, 9, 12, 10, 30)).build()));
        when(multiLegService.preview(1L)).thenReturn(new MultiLegPlanner.PlanResult(
                DispatchPlanningModeEnum.DIRECT.getMode(), 1, 0, 5.0, 30, 1.0,
                List.of(), List.of(), null, "直达"));

        dispatchService.createSmartPlan(smartReqVO());

        ArgumentCaptor<DispatchPlanDO> planCaptor = ArgumentCaptor.forClass(DispatchPlanDO.class);
        verify(dispatchPlanMapper).updateById(planCaptor.capture());
        String persisted = planCaptor.getValue().getPlanReason();
        assertFalse(persisted.contains("带不下"), persisted);
        assertFalse(persisted.contains("SEARCH_START_MS"), persisted);
    }

    @Test
    void getPlanRoadmap_aggregates_by_legs_so_vehicle_view_matches_order_view() {
        // P0-B 回归：多段联运方案（v3 → v101 → v5 接力）的车辆视角必须由本方案运输段聚合。
        // 历史缺陷：车辆视角取算法的单车经停明细，方案 30 上表现为「同一台车 南岸→重大→再回南岸」，
        // 而订单视角是 3 段接力 —— 两个视角对同一订单给出相反结论。
        when(dispatchPlanMapper.selectById(100L)).thenReturn(DispatchPlanDO.builder().id(100L).build());
        when(transportLegMapper.selectListByPlanId(100L)).thenReturn(List.of(
                leg(11L, 3L, 21L, 1, 21L, 22L, "106.60,29.52;106.55,29.50"), // 本段已缓存真实道路
                leg(12L, 101L, 21L, 2, 22L, 23L, null),
                leg(13L, 5L, 21L, 3, 23L, 24L, null)));
        when(stationMapper.selectBatchIds(anyCollection())).thenReturn(List.of(
                station(21L, "重邮南门货运站", "106.60", "29.52"),
                station(22L, "换乘站A", "106.55", "29.50"),
                station(23L, "换乘站B", "106.50", "29.48"),
                station(24L, "重大A区", "106.46", "29.56")));
        when(roadPolylineService.route(anyDouble(), anyDouble(), anyDouble(), anyDouble()))
                .thenReturn(List.of(new double[]{106.5, 29.5}, new double[]{106.48, 29.49}));

        DispatchRoadmapRespVO roadmap = dispatchService.getPlanRoadmap(100L);

        assertEquals("LEG", roadmap.getSource());
        assertEquals(3, roadmap.getSegments().size());
        // 每段一台车：segment.vehicleId 与订单视角（topology/order）的 leg 车辆一一对应，
        // 不再出现「同一台车跨片区跑完全程再回来」
        assertEquals(List.of(3L, 101L, 5L), roadmap.getSegments().stream()
                .map(DispatchRoadmapRespVO.Segment::getVehicleId).toList());
        // 段与订单/段序可追溯（前端据此与订单视角对齐）
        assertEquals(List.of(21L, 21L, 21L), roadmap.getSegments().stream()
                .map(DispatchRoadmapRespVO.Segment::getOrderId).toList());
        assertEquals(List.of(1, 2, 3), roadmap.getSegments().stream()
                .map(DispatchRoadmapRespVO.Segment::getLegSequence).toList());
        assertTrue(roadmap.getSegments().stream().allMatch(s -> s.getVisitSequence() == 1)); // 每台车各 1 段
        // 段上已缓存的高德轨迹直接复用（省掉前端 30 秒冷却重试），只有缺轨迹的段才补一次路网
        assertEquals("AMAP", roadmap.getSegments().get(0).getProvider());
        verify(roadPolylineService, times(2)).route(anyDouble(), anyDouble(), anyDouble(), anyDouble());
        // 不再走「按经停明细出段」的旧口径
        verify(dispatchPlanItemMapper, never()).selectList(any(Wrapper.class));
    }

    @Test
    void createSmartPlan_persists_orders_that_got_no_leg() {
        // V029（任务 #34）：算法与多段联运都没能给出**任何一段**的单，必须落库"为什么少带这一单"。
        // 此前算法返回的 unassignedOrderIds 只被用来判空 + 拼一句解释文案（实测：它只出现在 isEmpty
        // 判断里），事后查不到是哪几张单被舍、也无法回流成度量台的题 —— 那正是 #33 的语料缺口。
        mockSmartPlanContext();
        when(algorithmAdapter.plan(any())).thenReturn(feasibleResult());
        doAnswer(invocation -> {
            DispatchPlanDO plan = invocation.getArgument(0);
            plan.setId(100L);
            return 1;
        }).when(dispatchPlanMapper).insert(any(DispatchPlanDO.class));
        when(multiLegService.planLegs(eq(1L), eq(100L), any(), any(), any())).thenReturn(List.of());
        when(multiLegService.preview(1L)).thenReturn(new MultiLegPlanner.PlanResult(
                DispatchPlanningModeEnum.DIRECT.getMode(), 1, 0, 5.0, 30, 1.0,
                List.of(), List.of(), null, "无可行段"));

        dispatchService.createSmartPlan(smartReqVO());

        ArgumentCaptor<DispatchPlanDO> captor = ArgumentCaptor.forClass(DispatchPlanDO.class);
        verify(dispatchPlanMapper, atLeastOnce()).updateById(captor.capture());
        String shed = captor.getAllValues().stream()
                .filter(u -> u.getUnassignedOrders() != null)
                .map(DispatchPlanDO::getUnassignedOrders)
                .findFirst().orElse(null);
        assertNotNull(shed, "一单都没排出段却不落库 ⇒ 事后查不到这批为什么空");
        assertTrue(shed.contains("\"orderId\":1"), shed);
        assertTrue(shed.contains("NO_LEG_PLANNED"), shed);
    }

    @Test
    void reviewPlan_not_pending_throws() {
        DispatchPlanDO plan = DispatchPlanDO.builder().id(100L)
                .status(DispatchPlanStatusEnum.ISSUED.getStatus()).build();
        when(dispatchPlanMapper.selectById(100L)).thenReturn(plan);

        DispatchPlanReviewReqVO reqVO = new DispatchPlanReviewReqVO();
        reqVO.setPlanId(100L);
        reqVO.setApprove(true);
        ServiceException ex = assertThrows(ServiceException.class, () -> dispatchService.reviewPlan(reqVO));

        assertEquals(DISPATCH_PLAN_STATUS_ILLEGAL.getCode(), ex.getCode());
        verify(dispatchPlanMapper, never()).updateById(any(DispatchPlanDO.class));
    }

    @Test
    void reviewPlan_reject_voids_plan_and_returns_orders_to_pool() {
        DispatchPlanDO plan = DispatchPlanDO.builder().id(100L)
                .status(DispatchPlanStatusEnum.PENDING.getStatus()).build();
        when(dispatchPlanMapper.selectById(100L)).thenReturn(plan);
        when(dispatchPlanItemMapper.selectList(any(Wrapper.class))).thenReturn(List.of(
                DispatchPlanItemDO.builder().planId(100L).orderId(1L).build(),
                DispatchPlanItemDO.builder().planId(100L).orderId(2L).build()));

        DispatchPlanReviewReqVO reqVO = new DispatchPlanReviewReqVO();
        reqVO.setPlanId(100L);
        reqVO.setApprove(false);
        reqVO.setReason("线路不合理");
        dispatchService.reviewPlan(reqVO);

        // 方案作废、订单回到订单池、状态日志落库
        assertEquals(DispatchPlanStatusEnum.VOID.getStatus(), plan.getStatus());
        ArgumentCaptor<TransportOrderDO> orderCaptor = ArgumentCaptor.forClass(TransportOrderDO.class);
        verify(orderMapper).update(orderCaptor.capture(), any());
        assertEquals(TransportOrderStatusEnum.POOLED.getStatus(), orderCaptor.getValue().getStatus());
        ArgumentCaptor<DispatchPlanLogDO> logCaptor = ArgumentCaptor.forClass(DispatchPlanLogDO.class);
        verify(dispatchPlanLogMapper).insert(logCaptor.capture());
        assertEquals(DispatchPlanStatusEnum.PENDING.getStatus(), logCaptor.getValue().getFromStatus());
        assertEquals(DispatchPlanStatusEnum.VOID.getStatus(), logCaptor.getValue().getToStatus());
        assertEquals("线路不合理", logCaptor.getValue().getReason());
    }

    @Test
    void departureCheck_pass_runs_plan_and_departs_orders() {
        DispatchPlanDO plan = DispatchPlanDO.builder().id(100L)
                .status(DispatchPlanStatusEnum.ISSUED.getStatus()).build();
        when(dispatchPlanMapper.selectById(100L)).thenReturn(plan);
        when(dispatchPlanItemMapper.selectList(any(Wrapper.class))).thenReturn(List.of(
                DispatchPlanItemDO.builder().planId(100L).vehicleId(7L).orderId(1L).build()));

        DispatchCheckReqVO reqVO = new DispatchCheckReqVO();
        reqVO.setPlanId(100L);
        reqVO.setVehicleId(7L);
        reqVO.setPass(true);
        dispatchService.departureCheck(reqVO);

        // 核验记录：通过
        ArgumentCaptor<DepartureCheckDO> checkCaptor = ArgumentCaptor.forClass(DepartureCheckDO.class);
        verify(departureCheckMapper).insert(checkCaptor.capture());
        assertEquals(DepartureCheckResultEnum.PASS.getResult(), checkCaptor.getValue().getResult());
        // 方案进入执行中并写日志，订单置为已发车
        assertEquals(DispatchPlanStatusEnum.RUNNING.getStatus(), plan.getStatus());
        verify(dispatchPlanMapper).updateById(plan);
        verify(dispatchPlanLogMapper).insert(any(DispatchPlanLogDO.class));
        ArgumentCaptor<TransportOrderDO> orderCaptor = ArgumentCaptor.forClass(TransportOrderDO.class);
        verify(orderMapper).update(orderCaptor.capture(), any());
        assertEquals(TransportOrderStatusEnum.DEPARTED.getStatus(), orderCaptor.getValue().getStatus());
    }

    @Test
    void departureCheck_constrains_order_source_state() {
        // P1-003 回归：发车核验只推进「已分配(ASSIGNED)」订单为已发车，不盲目覆盖已完成/已取消等其它状态订单
        DispatchPlanDO plan = DispatchPlanDO.builder().id(100L)
                .status(DispatchPlanStatusEnum.ISSUED.getStatus()).build();
        when(dispatchPlanMapper.selectById(100L)).thenReturn(plan);
        when(dispatchPlanItemMapper.selectList(any(Wrapper.class))).thenReturn(List.of(
                DispatchPlanItemDO.builder().planId(100L).vehicleId(7L).orderId(1L).build(),
                DispatchPlanItemDO.builder().planId(100L).vehicleId(7L).orderId(2L).build()));

        DispatchCheckReqVO reqVO = new DispatchCheckReqVO();
        reqVO.setPlanId(100L);
        reqVO.setVehicleId(7L);
        reqVO.setPass(true);
        dispatchService.departureCheck(reqVO);

        // 更新条件必须带来源状态约束：仅已分配(2)订单可被推进为已发车
        ArgumentCaptor<AbstractWrapper> wrapperCaptor = ArgumentCaptor.forClass(AbstractWrapper.class);
        verify(orderMapper).update(any(), wrapperCaptor.capture());
        String sql = wrapperCaptor.getValue().getSqlSegment();
        assertTrue(sql.contains("status"), "更新条件应约束来源状态，实际 SQL: " + sql);
        // 参数值中包含已分配状态(2)（订单 id 为 Long，不会与 Integer 2 冲突）
        assertTrue(wrapperCaptor.getValue().getParamNameValuePairs().values().stream()
                        .anyMatch(v -> Integer.valueOf(TransportOrderStatusEnum.ASSIGNED.getStatus()).equals(v)),
                "更新条件来源状态应为已分配(2)，实际参数: " + wrapperCaptor.getValue().getParamNameValuePairs());
    }

    // ==================== 测试桩数据 ====================

    @Test
    void createSmartPlan_splits_passenger_order_by_count() {
        mockSmartPlanContext();
        // 客运订单实际 3 人：契约中一张算法客运单 = 1 人，快照应拆成 3 条
        when(passengerOrderMapper.selectList(any(Wrapper.class))).thenReturn(List.of(
                PassengerOrderDO.builder().orderId(1L).passengerCount(3).build()));
        ArgumentCaptor<AlgorithmPlanReqDTO> reqCaptor = ArgumentCaptor.forClass(AlgorithmPlanReqDTO.class);
        when(algorithmAdapter.plan(reqCaptor.capture())).thenReturn(feasibleResult());
        doAnswer(invocation -> {
            DispatchPlanDO plan = invocation.getArgument(0);
            plan.setId(100L);
            return 1;
        }).when(dispatchPlanMapper).insert(any(DispatchPlanDO.class));

        dispatchService.createSmartPlan(smartReqVO());

        assertEquals(List.of("1#1", "1#2", "1#3"),
                reqCaptor.getValue().getOrders().stream().map(AlgorithmOrderDTO::getOrderId).toList());
    }

    @Test
    void createSmartPlan_maps_collection_cargo_to_pickup() {
        // 货运订单终点=场站(1) → 算法订单应映射为 PICKUP(上车站 13)，件数透传
        TransportOrderDO pooled = TransportOrderDO.builder().id(1L).orderType(2)
                .pickupStationId(13L).deliveryStationId(1L)
                .status(TransportOrderStatusEnum.POOLED.getStatus()).build();
        when(orderMapper.selectList(any(Wrapper.class))).thenReturn(List.of(pooled));
        when(stationMapper.selectById(1L)).thenReturn(StationDO.builder().id(1L).build());
        when(vehicleMapper.selectBatchIds(anyCollection())).thenReturn(List.of(
                VehicleDO.builder().id(7L).passengerCapacity(5).cargoCapacity(4).build()));
        when(stationMapper.selectBatchIds(anyCollection())).thenReturn(List.of(
                StationDO.builder().id(13L).build()));
        // 注：cargoOrderMapper 不显式桩（selectList(SFunction,Object) 的类匹配对方法引用不可靠），
        // getItemCount 命中空列表 → 件数取默认 1
        // P1-004：CAS 抢占返回池内订单数（1 张）
        when(orderMapper.update(any(TransportOrderDO.class), any())).thenReturn(1);
        ArgumentCaptor<AlgorithmPlanReqDTO> reqCaptor = ArgumentCaptor.forClass(AlgorithmPlanReqDTO.class);
        when(algorithmAdapter.plan(reqCaptor.capture())).thenReturn(feasiblePickupResult());
        doAnswer(invocation -> {
            DispatchPlanDO plan = invocation.getArgument(0);
            plan.setId(101L);
            return 1;
        }).when(dispatchPlanMapper).insert(any(DispatchPlanDO.class));

        dispatchService.createSmartPlan(smartReqVO());

        AlgorithmOrderDTO order = reqCaptor.getValue().getOrders().get(0);
        assertEquals(AlgorithmOrderDTO.TYPE_PICKUP, order.getOrderType());
        assertEquals("13", order.getStationId());
        assertEquals(1, order.getItemCount()); // 未桩件数 → 默认 1
    }

    @Test
    void validate_reports_capacity_markers_and_time_issues() {
        // 订单池：2 客运(各1人) + 1 货运(终点=场站→揽收) + 1 邮快件(终点=村→派送)
        TransportOrderDO p1 = TransportOrderDO.builder().id(1L).orderNo("P1").orderType(1)
                .pickupStationId(11L).deliveryStationId(12L)
                .status(TransportOrderStatusEnum.POOLED.getStatus()).build();
        TransportOrderDO p2 = TransportOrderDO.builder().id(2L).orderNo("P2").orderType(1)
                .pickupStationId(11L).deliveryStationId(11L) // 上/下同站 → 时序问题
                .status(TransportOrderStatusEnum.POOLED.getStatus()).build();
        TransportOrderDO pickup = TransportOrderDO.builder().id(3L).orderNo("C1").orderType(2)
                .pickupStationId(13L).deliveryStationId(1L) // 终点=场站 → 揽收
                .status(TransportOrderStatusEnum.POOLED.getStatus()).build();
        TransportOrderDO delivery = TransportOrderDO.builder().id(4L).orderNo("M1").orderType(3)
                .pickupStationId(1L).deliveryStationId(14L) // 终点=村 → 派送
                .status(TransportOrderStatusEnum.POOLED.getStatus()).build();
        when(orderMapper.selectList(any())).thenReturn(List.of(p1, p2, pickup, delivery));
        when(stationMapper.selectById(1L)).thenReturn(StationDO.builder().id(1L).build());
        when(vehicleMapper.selectBatchIds(anyCollection())).thenReturn(List.of(
                VehicleDO.builder().id(7L).plateNo("川A·1").passengerCapacity(5).cargoCapacity(4).build()));
        when(stationMapper.selectList()).thenReturn(List.of(
                StationDO.builder().id(11L).stationName("S11")
                        .longitude(new BigDecimal("103.1")).latitude(new BigDecimal("30.1")).build(),
                StationDO.builder().id(12L).stationName("S12").build(),
                StationDO.builder().id(13L).stationName("S13").build(),
                StationDO.builder().id(14L).stationName("S14").build()));
        when(passengerOrderMapper.selectList(any(Wrapper.class))).thenReturn(List.of(
                PassengerOrderDO.builder().orderId(1L).passengerCount(1).build(),
                PassengerOrderDO.builder().orderId(2L).passengerCount(1).build()));
        // 注：cargo/postal 子表不显式桩，getItemCount 默认每件 1，包裹总数 = 2

        DispatchValidateReqVO reqVO = new DispatchValidateReqVO();
        reqVO.setDepotStationId(1L);
        reqVO.setVehicleIds(Collections.singletonList(7L));
        DispatchValidateRespVO resp = dispatchService.validate(reqVO);

        assertEquals(2, resp.getOrderStats().getPassengerCount());
        assertEquals(1, resp.getOrderStats().getPickupCount());   // C1 终点=场站 → 揽收
        assertEquals(1, resp.getOrderStats().getDeliveryCount()); // M1 终点=村 → 派送
        assertEquals(2, resp.getOrderStats().getParcelCount());
        // 运力：载客 5 ≥ 2、载货 4 ≥ 2 → 不预警
        assertFalse(resp.getCapacityCheck().getOverCapacity());
        assertEquals(0, resp.getCapacityCheck().getPassengerExceed());
        assertEquals(0, resp.getCapacityCheck().getCargoExceed());
        // 时序问题：P2 上/下同站
        assertEquals(1, resp.getTimeSeqIssues().size());
        assertEquals("P2", resp.getTimeSeqIssues().get(0).getOrderNo());
        // 站点标记：S11 上车(2单)+下车(1单，p2 上下同站)、S12 下车、S13 揽收、S14 派送
        assertEquals(4, resp.getMarkers().size());
        assertEquals(3, resp.getMarkers().get(0).getOrderCount());
        assertEquals(List.of("BOARD", "ALIGHT"), resp.getMarkers().get(0).getTypes());
        assertEquals(List.of("PICKUP"), resp.getMarkers().get(2).getTypes());
    }

    @Test
    void validate_warns_when_over_capacity() {
        TransportOrderDO p = TransportOrderDO.builder().id(1L).orderNo("P1").orderType(1)
                .pickupStationId(11L).deliveryStationId(12L)
                .status(TransportOrderStatusEnum.POOLED.getStatus()).build();
        when(orderMapper.selectList(any())).thenReturn(List.of(p));
        when(stationMapper.selectById(1L)).thenReturn(StationDO.builder().id(1L).build());
        when(vehicleMapper.selectBatchIds(anyCollection())).thenReturn(List.of(
                VehicleDO.builder().id(7L).passengerCapacity(2).cargoCapacity(1).build()));
        when(stationMapper.selectList()).thenReturn(List.of(
                StationDO.builder().id(11L).build(), StationDO.builder().id(12L).build()));
        when(passengerOrderMapper.selectList(any(Wrapper.class))).thenReturn(List.of(
                PassengerOrderDO.builder().orderId(1L).passengerCount(3).build()));

        DispatchValidateReqVO reqVO = new DispatchValidateReqVO();
        reqVO.setDepotStationId(1L);
        reqVO.setVehicleIds(Collections.singletonList(7L));
        DispatchValidateRespVO resp = dispatchService.validate(reqVO);

        // 载客 3 > 2 → 超员 1，运力不足预警
        assertTrue(resp.getCapacityCheck().getOverCapacity());
        assertEquals(1, resp.getCapacityCheck().getPassengerExceed());
    }

    @Test
    void validate_cargo_capacity_uses_net_load_dimensions() {
        // P1-002 回归：载货按净载荷双维度口径（派送/揽收分别累计），不再「派送+揽收」全部累计。
        // 3 件派送 + 3 件揽收、货仓容量 4：旧口径 6 > 4 会误报超载；净载荷口径 max(3,3)=3 ≤ 4 不预警。
        TransportOrderDO pickup1 = TransportOrderDO.builder().id(1L).orderNo("K1").orderType(2)
                .pickupStationId(11L).deliveryStationId(1L) // 终点=场站 → 揽收
                .status(TransportOrderStatusEnum.POOLED.getStatus()).build();
        TransportOrderDO pickup2 = TransportOrderDO.builder().id(2L).orderNo("K2").orderType(2)
                .pickupStationId(12L).deliveryStationId(1L)
                .status(TransportOrderStatusEnum.POOLED.getStatus()).build();
        TransportOrderDO pickup3 = TransportOrderDO.builder().id(3L).orderNo("K3").orderType(3)
                .pickupStationId(13L).deliveryStationId(1L)
                .status(TransportOrderStatusEnum.POOLED.getStatus()).build();
        TransportOrderDO delivery1 = TransportOrderDO.builder().id(4L).orderNo("M1").orderType(2)
                .pickupStationId(1L).deliveryStationId(14L) // 终点=村 → 派送
                .status(TransportOrderStatusEnum.POOLED.getStatus()).build();
        TransportOrderDO delivery2 = TransportOrderDO.builder().id(5L).orderNo("M2").orderType(2)
                .pickupStationId(1L).deliveryStationId(15L)
                .status(TransportOrderStatusEnum.POOLED.getStatus()).build();
        TransportOrderDO delivery3 = TransportOrderDO.builder().id(6L).orderNo("M3").orderType(3)
                .pickupStationId(1L).deliveryStationId(16L)
                .status(TransportOrderStatusEnum.POOLED.getStatus()).build();
        when(orderMapper.selectList(any())).thenReturn(List.of(pickup1, pickup2, pickup3, delivery1, delivery2, delivery3));
        when(stationMapper.selectById(1L)).thenReturn(StationDO.builder().id(1L).build());
        when(vehicleMapper.selectBatchIds(anyCollection())).thenReturn(List.of(
                VehicleDO.builder().id(7L).passengerCapacity(5).cargoCapacity(4).build()));
        when(stationMapper.selectList()).thenReturn(List.of(
                StationDO.builder().id(11L).stationName("S11").build(),
                StationDO.builder().id(12L).stationName("S12").build(),
                StationDO.builder().id(13L).stationName("S13").build(),
                StationDO.builder().id(14L).stationName("S14").build(),
                StationDO.builder().id(15L).stationName("S15").build(),
                StationDO.builder().id(16L).stationName("S16").build()));
        // 注：cargo/postal 子表不显式桩，getItemCount 默认每件 1

        DispatchValidateReqVO reqVO = new DispatchValidateReqVO();
        reqVO.setDepotStationId(1L);
        reqVO.setVehicleIds(Collections.singletonList(7L));
        DispatchValidateRespVO resp = dispatchService.validate(reqVO);

        assertEquals(3, resp.getOrderStats().getPickupCount());
        assertEquals(3, resp.getOrderStats().getDeliveryCount());
        assertEquals(6, resp.getOrderStats().getParcelCount()); // 总件数展示仍为 6
        // 净载荷口径：max(3 派送, 3 揽收) = 3 ≤ 货仓 4 → 不预警（旧口径 6 > 4 会误报超载）
        assertFalse(resp.getCapacityCheck().getOverCapacity());
        assertEquals(0, resp.getCapacityCheck().getCargoExceed());
    }

    @Test
    void settlement_aggregates_completed_plans() {
        LocalDateTime start = LocalDateTime.of(2026, 8, 9, 10, 0);
        LocalDateTime end = LocalDateTime.of(2026, 8, 9, 10, 30);
        // 已完成方案：1 张，里程 12.5，含 1 客运(2人) + 1 货运
        DispatchPlanDO plan = DispatchPlanDO.builder().id(100L).totalDistance(new BigDecimal("12.5"))
                .status(DispatchPlanStatusEnum.COMPLETED.getStatus()).build();
        plan.setCreateTime(LocalDateTime.of(2026, 8, 9, 10, 5));
        when(dispatchPlanMapper.selectList(any(Wrapper.class))).thenReturn(List.of(plan));
        when(dispatchPlanItemMapper.selectList(any(Wrapper.class))).thenReturn(List.of(
                DispatchPlanItemDO.builder().planId(100L).vehicleId(7L).orderId(1L).build(),
                DispatchPlanItemDO.builder().planId(100L).vehicleId(7L).orderId(2L).build()));
        TransportOrderDO passengerOrder = TransportOrderDO.builder().id(1L).orderType(1).build();
        passengerOrder.setCreateTime(LocalDateTime.of(2026, 8, 9, 10, 0));
        when(orderMapper.selectBatchIds(anyCollection())).thenReturn(List.of(
                passengerOrder,
                TransportOrderDO.builder().id(2L).orderType(2).build()));
        when(passengerOrderMapper.selectList(any(Wrapper.class))).thenReturn(List.of(
                PassengerOrderDO.builder().orderId(1L).passengerCount(2).build()));
        when(vehicleMapper.selectBatchIds(anyCollection())).thenReturn(List.of(
                VehicleDO.builder().id(7L).plateNo("川A·5201").build()));

        DispatchSettlementReqVO reqVO = new DispatchSettlementReqVO();
        reqVO.setBatchStart(start);
        reqVO.setBatchEnd(end);
        DispatchSettlementRespVO resp = dispatchService.settlement(reqVO);

        assertEquals(0, new BigDecimal("12.5").compareTo(resp.getTotalDistance()));
        assertEquals(2, resp.getPassengerCount());
        assertEquals(1, resp.getParcelCount()); // 货运未桩件数 → 默认 1
        assertEquals(5.0, resp.getAvgPassengerWaitMinutes()); // 下单 10:00 → 方案 10:05 = 5 分钟
        assertEquals(1, resp.getPerVehicle().size());
        assertEquals("川A·5201", resp.getPerVehicle().get(0).getPlateNo());
        assertEquals(2, resp.getPerVehicle().get(0).getPassengerCount());
        assertEquals(1, resp.getPerVehicle().get(0).getRunCount());
    }

    @Test
    void settlement_empty_when_no_completed_plan() {
        when(dispatchPlanMapper.selectList(any(Wrapper.class))).thenReturn(List.of());
        DispatchSettlementReqVO reqVO = new DispatchSettlementReqVO();
        reqVO.setBatchStart(LocalDateTime.of(2026, 8, 9, 10, 0));
        reqVO.setBatchEnd(LocalDateTime.of(2026, 8, 9, 10, 30));
        DispatchSettlementRespVO resp = dispatchService.settlement(reqVO);
        assertEquals(0, new BigDecimal("0").compareTo(resp.getTotalDistance()));
        assertEquals(0, resp.getPassengerCount());
        assertTrue(resp.getPerVehicle().isEmpty());
    }

    @Test
    void createManualPlan_splits_passenger_order_and_maps_back_business_id() {
        TransportOrderDO pooled = TransportOrderDO.builder().id(1L).orderType(1)
                .pickupStationId(11L).deliveryStationId(12L)
                .status(TransportOrderStatusEnum.POOLED.getStatus()).build();
        when(orderMapper.selectBatchIds(anyCollection())).thenReturn(List.of(pooled));
        when(stationMapper.selectById(1L)).thenReturn(StationDO.builder().id(1L).build());
        when(vehicleMapper.selectById(7L)).thenReturn(
                VehicleDO.builder().id(7L).passengerCapacity(5).cargoCapacity(4).build());
        when(stationMapper.selectBatchIds(anyCollection())).thenReturn(List.of(
                StationDO.builder().id(11L).build(), StationDO.builder().id(12L).build()));
        when(passengerOrderMapper.selectList(any(Wrapper.class))).thenReturn(List.of(
                PassengerOrderDO.builder().orderId(1L).passengerCount(2).build()));
        doAnswer(invocation -> {
            DispatchPlanDO plan = invocation.getArgument(0);
            plan.setId(200L);
            return 1;
        }).when(dispatchPlanMapper).insert(any(DispatchPlanDO.class));

        DispatchManualPlanReqVO reqVO = new DispatchManualPlanReqVO();
        reqVO.setDepotStationId(1L);
        reqVO.setVehicleId(7L);
        reqVO.setOrderIds(List.of(1L));
        Long planId = dispatchService.createManualPlan(reqVO);

        assertEquals(200L, planId);
        // 2 人 -> DEPART + 2×(BOARD+ALIGHT) + RETURN 共 6 条明细，且都还原为业务订单 1
        ArgumentCaptor<DispatchPlanItemDO> itemCaptor = ArgumentCaptor.forClass(DispatchPlanItemDO.class);
        verify(dispatchPlanItemMapper, times(6)).insert(itemCaptor.capture());
        itemCaptor.getAllValues().stream()
                .filter(item -> item.getOrderId() != null)
                .forEach(item -> assertEquals(1L, item.getOrderId()));
        ArgumentCaptor<TransportOrderDO> orderCaptor = ArgumentCaptor.forClass(TransportOrderDO.class);
        verify(orderMapper).update(orderCaptor.capture(), any());
        assertEquals(TransportOrderStatusEnum.ASSIGNED.getStatus(), orderCaptor.getValue().getStatus());
    }

    /** 智能派单公共上下文：1 台车、1 个场站、1 张客运在池订单 */
    private void mockSmartPlanContext() {
        TransportOrderDO pooled = TransportOrderDO.builder().id(1L).orderType(1)
                .pickupStationId(11L).deliveryStationId(12L)
                .status(TransportOrderStatusEnum.POOLED.getStatus()).build();
        when(orderMapper.selectList(any(Wrapper.class))).thenReturn(List.of(pooled));
        when(stationMapper.selectById(1L)).thenReturn(StationDO.builder().id(1L)
                .longitude(new BigDecimal("104.0000")).latitude(new BigDecimal("30.0000")).build());
        when(vehicleMapper.selectBatchIds(anyCollection())).thenReturn(List.of(
                VehicleDO.builder().id(7L).passengerCapacity(5).build()));
        when(stationMapper.selectBatchIds(anyCollection())).thenReturn(List.of(
                StationDO.builder().id(11L).longitude(new BigDecimal("104.0100")).latitude(new BigDecimal("30.0000")).build(),
                StationDO.builder().id(12L).longitude(new BigDecimal("104.0200")).latitude(new BigDecimal("30.0000")).build()));
        // P1-004：CAS 抢占返回池内订单数（1 张）。lenient：并发抢占用例会覆盖为 0
        lenient().when(orderMapper.update(any(TransportOrderDO.class), any())).thenReturn(1);
    }

    private DispatchSmartPlanReqVO smartReqVO() {
        DispatchSmartPlanReqVO reqVO = new DispatchSmartPlanReqVO();
        reqVO.setDepotStationId(1L);
        reqVO.setVehicleIds(Collections.singletonList(7L));
        return reqVO;
    }

    /** 与 mockSmartPlanContext 快照一致的完整解：DEPART@1 -> BOARD@11 -> ALIGHT@12 -> RETURN@1 */
    private AlgorithmPlanRespDTO feasibleResult() {
        List<AlgorithmRouteStopDTO> stops = List.of(
                AlgorithmRouteStopDTO.builder().stationId("1").action(AlgorithmRouteStopDTO.ACTION_DEPART).build(),
                AlgorithmRouteStopDTO.builder().stationId("11").orderId("1").action(AlgorithmRouteStopDTO.ACTION_BOARD).build(),
                AlgorithmRouteStopDTO.builder().stationId("12").orderId("1").action(AlgorithmRouteStopDTO.ACTION_ALIGHT).build(),
                AlgorithmRouteStopDTO.builder().stationId("1").action(AlgorithmRouteStopDTO.ACTION_RETURN).build());
        return AlgorithmPlanRespDTO.builder()
                .requestId("req-1")
                .status(AlgorithmPlanRespDTO.STATUS_FEASIBLE)
                .algorithmVersion("algo-1.0")
                .parameterVersion("param-1.0")
                .totalDistance(12.5)
                .vehiclePlans(List.of(AlgorithmVehiclePlanDTO.builder()
                        .vehicleId(7L).stops(stops).totalDistance(12.5).build()))
                .build();
    }

    /** 揽收场景可行解：DEPART@1 -> PICKUP@13 -> RETURN@1 */
    private AlgorithmPlanRespDTO feasiblePickupResult() {
        List<AlgorithmRouteStopDTO> stops = List.of(
                AlgorithmRouteStopDTO.builder().stationId("1").action(AlgorithmRouteStopDTO.ACTION_DEPART).build(),
                AlgorithmRouteStopDTO.builder().stationId("13").orderId("1").action(AlgorithmRouteStopDTO.ACTION_PICKUP).build(),
                AlgorithmRouteStopDTO.builder().stationId("1").action(AlgorithmRouteStopDTO.ACTION_RETURN).build());
        return AlgorithmPlanRespDTO.builder()
                .requestId("req-2")
                .status(AlgorithmPlanRespDTO.STATUS_FEASIBLE)
                .algorithmVersion("algo-1.0")
                .parameterVersion("param-1.0")
                .totalDistance(9.0)
                .vehiclePlans(List.of(AlgorithmVehiclePlanDTO.builder()
                        .vehicleId(7L).stops(stops).totalDistance(9.0).build()))
                .build();
    }

    // ==================== 按勾选订单归集（orderIds） ====================

    @Test
    void collectAll_ready_for_pool_orders_pooled_without_selection() {
        // 一键演示：不勾选任何订单，把所有「待入池」订单一次性入池
        when(orderMapper.selectList(any())).thenReturn(List.of(
                TransportOrderDO.builder().id(1L).status(TransportOrderStatusEnum.READY_FOR_POOL.getStatus()).build(),
                TransportOrderDO.builder().id(2L).status(TransportOrderStatusEnum.READY_FOR_POOL.getStatus()).build()));
        when(orderMapper.update(any(TransportOrderDO.class), any())).thenReturn(2);

        DispatchCollectReqVO reqVO = new DispatchCollectReqVO();
        reqVO.setAll(true);
        int count = dispatchService.collectOrders(reqVO);

        assertEquals(2, count);
        ArgumentCaptor<TransportOrderDO> captor = ArgumentCaptor.forClass(TransportOrderDO.class);
        verify(orderMapper).update(captor.capture(), any());
        assertEquals(TransportOrderStatusEnum.POOLED.getStatus(), captor.getValue().getStatus());
    }

    @Test
    void collectAll_without_ready_orders_returns_zero() {
        when(orderMapper.selectList(any())).thenReturn(List.of());

        DispatchCollectReqVO reqVO = new DispatchCollectReqVO();
        reqVO.setAll(true);

        assertEquals(0, dispatchService.collectOrders(reqVO));
    }

    @Test
    void collectByOrderIds_all_ready_for_pool_pooled() {
        // 全部待入池（READY_FOR_POOL，Phase 2 审核通过）→ 全部入池
        when(orderMapper.selectBatchIds(List.of(1L, 2L))).thenReturn(List.of(
                TransportOrderDO.builder().id(1L).status(TransportOrderStatusEnum.READY_FOR_POOL.getStatus()).orderType(2).build(),
                TransportOrderDO.builder().id(2L).status(TransportOrderStatusEnum.READY_FOR_POOL.getStatus()).orderType(3).build()));
        when(orderMapper.update(any(TransportOrderDO.class), any())).thenReturn(2);

        DispatchCollectReqVO reqVO = new DispatchCollectReqVO();
        reqVO.setOrderIds(List.of(1L, 2L));
        int count = dispatchService.collectOrders(reqVO);

        assertEquals(2, count);
        ArgumentCaptor<TransportOrderDO> captor = ArgumentCaptor.forClass(TransportOrderDO.class);
        verify(orderMapper).update(captor.capture(), any());
        assertEquals(TransportOrderStatusEnum.POOLED.getStatus(), captor.getValue().getStatus());
    }

    @Test
    void collectByOrderIds_mixed_status_throws() {
        // 一个待入池 + 一个已入池 → 明确报错，不悄悄跳过（已入池不能再归集）
        when(orderMapper.selectBatchIds(List.of(1L, 2L))).thenReturn(List.of(
                TransportOrderDO.builder().id(1L).status(TransportOrderStatusEnum.READY_FOR_POOL.getStatus()).build(),
                TransportOrderDO.builder().id(2L).status(TransportOrderStatusEnum.POOLED.getStatus()).build()));

        DispatchCollectReqVO reqVO = new DispatchCollectReqVO();
        reqVO.setOrderIds(List.of(1L, 2L));
        ServiceException ex = assertThrows(ServiceException.class, () -> dispatchService.collectOrders(reqVO));

        assertEquals(DISPATCH_ORDER_NOT_COLLECTABLE.getCode(), ex.getCode());
        verify(orderMapper, never()).update(any(TransportOrderDO.class), any());
    }

    @Test
    void collectByOrderIds_not_ready_for_pool_throws() {
        // 未审核通过（待审核/已取消等非 READY_FOR_POOL）→ 明确报错，未审核订单不得入池
        when(orderMapper.selectBatchIds(List.of(1L))).thenReturn(List.of(
                TransportOrderDO.builder().id(1L).status(TransportOrderStatusEnum.PENDING_REVIEW.getStatus()).orderType(2).build()));

        DispatchCollectReqVO reqVO = new DispatchCollectReqVO();
        reqVO.setOrderIds(List.of(1L));
        ServiceException ex = assertThrows(ServiceException.class, () -> dispatchService.collectOrders(reqVO));

        assertEquals(DISPATCH_ORDER_NOT_COLLECTABLE.getCode(), ex.getCode());
    }

    @Test
    void collectByOrderIds_missing_order_throws() {
        // orderIds 有订单查不到 → 报不存在
        when(orderMapper.selectBatchIds(List.of(1L, 99L))).thenReturn(List.of(
                TransportOrderDO.builder().id(1L).status(TransportOrderStatusEnum.READY_FOR_POOL.getStatus()).build()));

        DispatchCollectReqVO reqVO = new DispatchCollectReqVO();
        reqVO.setOrderIds(List.of(1L, 99L));
        ServiceException ex = assertThrows(ServiceException.class, () -> dispatchService.collectOrders(reqVO));

        assertEquals(ORDER_NOT_EXISTS.getCode(), ex.getCode());
    }

    @Test
    void collectByOrderIds_batch_multiple_cargo() {
        // 批量：2 货运待入池 + 1 邮快件待入池 → 全部入池
        when(orderMapper.selectBatchIds(List.of(1L, 2L, 3L))).thenReturn(List.of(
                TransportOrderDO.builder().id(1L).status(TransportOrderStatusEnum.READY_FOR_POOL.getStatus()).orderType(2).build(),
                TransportOrderDO.builder().id(2L).status(TransportOrderStatusEnum.READY_FOR_POOL.getStatus()).orderType(2).build(),
                TransportOrderDO.builder().id(3L).status(TransportOrderStatusEnum.READY_FOR_POOL.getStatus()).orderType(3).build()));
        when(orderMapper.update(any(TransportOrderDO.class), any())).thenReturn(3);

        DispatchCollectReqVO reqVO = new DispatchCollectReqVO();
        reqVO.setOrderIds(List.of(1L, 2L, 3L));
        int count = dispatchService.collectOrders(reqVO);

        assertEquals(3, count);
    }

    /** 运输段桩数据（P0-B：车辆视角由段聚合） */
    private static TransportLegDO leg(Long id, Long vehicleId, Long orderId, int legSequence,
                                      Long fromStationId, Long toStationId, String polyline) {
        return TransportLegDO.builder().id(id).vehicleId(vehicleId).driverId(vehicleId).orderId(orderId)
                .legSequence(legSequence).fromStationId(fromStationId).toStationId(toStationId)
                .status(TransportLegStatusEnum.ASSIGNED.getStatus())
                .handoverRequired(legSequence < 3)
                .navigationSource(polyline != null ? "AMAP" : "ESTIMATED")
                .navigationPolyline(polyline)
                .estimatedDeparture(LocalDateTime.of(2026, 9, 12, 10, 0).plusMinutes(30L * legSequence))
                .distanceKm(new BigDecimal("12.0"))
                .build();
    }

    /** 站点桩数据 */
    private static StationDO station(Long id, String name, String longitude, String latitude) {
        return StationDO.builder().id(id).stationName(name)
                .longitude(new BigDecimal(longitude)).latitude(new BigDecimal(latitude)).build();
    }

}
