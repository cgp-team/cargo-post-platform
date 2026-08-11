package cn.iocoder.yudao.module.transport.service.dispatch;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.module.transport.controller.admin.dispatch.vo.*;
import cn.iocoder.yudao.module.transport.dal.dataobject.dispatch.*;
import cn.iocoder.yudao.module.transport.dal.dataobject.order.PassengerOrderDO;
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
import cn.iocoder.yudao.module.transport.integration.algorithm.dto.AlgorithmOrderDTO;
import cn.iocoder.yudao.module.transport.integration.algorithm.dto.AlgorithmPlanReqDTO;
import cn.iocoder.yudao.module.transport.integration.algorithm.dto.AlgorithmPlanRespDTO;
import cn.iocoder.yudao.module.transport.integration.algorithm.dto.AlgorithmRouteStopDTO;
import cn.iocoder.yudao.module.transport.integration.algorithm.dto.AlgorithmVehiclePlanDTO;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
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
        // 注：driverVehicleMapper 为 Mockito mock，selectActiveBindings() 默认返回空列表，
        // 派单明细 driverId 为空，不影响既有断言；无需显式 stub（避免 UnnecessaryStubbing）
    }

    @Test
    void collectOrders_marks_created_orders_pooled() {
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
        assertEquals(0, planCaptor.getValue().getTotalDistance().compareTo(new java.math.BigDecimal("12.5")));
        // 经停明细 4 条（DEPART/BOARD/ALIGHT/RETURN），订单置为已分配
        verify(dispatchPlanItemMapper, times(4)).insert(any(DispatchPlanItemDO.class));
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
        ArgumentCaptor<DispatchTaskDO> taskCaptor = ArgumentCaptor.forClass(DispatchTaskDO.class);
        verify(dispatchTaskMapper).updateById(taskCaptor.capture());
        assertEquals(DispatchTaskStatusEnum.INFEASIBLE.getStatus(), taskCaptor.getValue().getStatus());
        verify(dispatchPlanMapper, never()).insert(any(DispatchPlanDO.class));
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
        when(stationMapper.selectById(1L)).thenReturn(StationDO.builder().id(1L).build());
        when(vehicleMapper.selectBatchIds(anyCollection())).thenReturn(List.of(
                VehicleDO.builder().id(7L).passengerCapacity(5).build()));
        when(stationMapper.selectBatchIds(anyCollection())).thenReturn(List.of(
                StationDO.builder().id(11L).build(), StationDO.builder().id(12L).build()));
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

}
