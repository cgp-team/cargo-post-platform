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
        when(multiLegService.planLegs(eq(1L), eq(100L), any(), any())).thenReturn(List.of(
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
