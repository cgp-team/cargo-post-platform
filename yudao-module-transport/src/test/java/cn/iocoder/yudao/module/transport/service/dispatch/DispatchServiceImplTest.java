package cn.iocoder.yudao.module.transport.service.dispatch;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.module.transport.controller.admin.dispatch.vo.*;
import cn.iocoder.yudao.module.transport.dal.dataobject.dispatch.*;
import cn.iocoder.yudao.module.transport.dal.dataobject.order.CargoOrderDO;
import cn.iocoder.yudao.module.transport.dal.dataobject.order.PassengerOrderDO;
import cn.iocoder.yudao.module.transport.dal.dataobject.order.PostalOrderDO;
import cn.iocoder.yudao.module.transport.dal.dataobject.order.TransportOrderDO;
import cn.iocoder.yudao.module.transport.dal.dataobject.station.StationDO;
import com.baomidou.mybatisplus.core.toolkit.support.SFunction;
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
    @Mock private DepartureCheckMapper departureCheckMapper;
    @Mock private AlgorithmAdapter algorithmAdapter;
    @Mock private DispatchEstimationService dispatchEstimationService;

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
        ReflectionTestUtils.setField(dispatchService, "departureCheckMapper", departureCheckMapper);
        ReflectionTestUtils.setField(dispatchService, "algorithmAdapter", algorithmAdapter);
        ReflectionTestUtils.setField(dispatchService, "dispatchEstimationService", dispatchEstimationService);
        // 注：driverVehicleMapper 为 Mockito mock，selectActiveBindings() 默认返回空列表，
        // 派单明细 driverId 为空，不影响既有断言；无需显式 stub（避免 UnnecessaryStubbing）
    }

    @Test
    void collectOrders_marks_created_orders_pooled() {
        // 候选订单：2 货运(已审核通过) + 1 邮快件，均可归集
        when(orderMapper.selectList(any())).thenReturn(List.of(
                TransportOrderDO.builder().id(1L).orderType(2).build(),
                TransportOrderDO.builder().id(2L).orderType(2).build(),
                TransportOrderDO.builder().id(3L).orderType(3).build()));
        when(cargoOrderMapper.selectOne(any(SFunction.class), any()))
                .thenReturn(CargoOrderDO.builder().auditStatus(1).build());
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
    void collectOrders_skips_unaudited_cargo() {
        // 候选订单：2 货运(订单1已审核/订单2未审核) + 1 邮快件 → 未审核货运不入池
        when(orderMapper.selectList(any())).thenReturn(List.of(
                TransportOrderDO.builder().id(1L).orderType(2).build(),
                TransportOrderDO.builder().id(2L).orderType(2).build(),
                TransportOrderDO.builder().id(3L).orderType(3).build()));
        when(cargoOrderMapper.selectOne(any(SFunction.class), eq(1L)))
                .thenReturn(CargoOrderDO.builder().orderId(1L).auditStatus(1).build()); // 已审核
        when(cargoOrderMapper.selectOne(any(SFunction.class), eq(2L)))
                .thenReturn(CargoOrderDO.builder().orderId(2L).auditStatus(0).build()); // 未审核
        when(orderMapper.update(any(TransportOrderDO.class), any())).thenReturn(2);

        DispatchCollectReqVO reqVO = new DispatchCollectReqVO();
        reqVO.setBatchStart(LocalDateTime.of(2026, 8, 9, 10, 0));
        reqVO.setBatchEnd(LocalDateTime.of(2026, 8, 9, 10, 30));
        int count = dispatchService.collectOrders(reqVO);

        // 2 个入池：1 个已审核货运 + 1 个邮快件（未审核货运被过滤）
        assertEquals(2, count);
        // 捕获入池更新条件：in 的订单编号含 1、3，不含 2
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
        verify(dispatchEstimationService).estimateAndFillPlanEtas(eq(100L), any(LocalDateTime.class));
        ArgumentCaptor<TransportOrderDO> orderCaptor = ArgumentCaptor.forClass(TransportOrderDO.class);
        verify(orderMapper).update(orderCaptor.capture(), any());
        assertEquals(TransportOrderStatusEnum.ASSIGNED.getStatus(), orderCaptor.getValue().getStatus());
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

}
