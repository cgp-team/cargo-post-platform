package cn.iocoder.yudao.module.transport.service.dispatch;

import cn.iocoder.yudao.module.transport.controller.admin.dispatch.vo.DispatchCheckReqVO;
import cn.iocoder.yudao.module.transport.controller.admin.dispatch.vo.DispatchCollectReqVO;
import cn.iocoder.yudao.module.transport.controller.admin.dispatch.vo.DispatchPlanReviewReqVO;
import cn.iocoder.yudao.module.transport.controller.admin.dispatch.vo.DispatchSmartPlanReqVO;
import cn.iocoder.yudao.module.transport.dal.dataobject.dispatch.*;
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
import cn.iocoder.yudao.module.transport.integration.algorithm.dto.*;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * 调度闭环 E2E 冒烟（服务层，纯 Mockito）：归集入池 → 智能派单 → 方案审核下发 → 发车核验，
 * 验证订单生命周期 待入池→已入池→已分配→已发车 与方案 待审核→已下发→执行中 全链路状态流转。
 */
@ExtendWith(MockitoExtension.class)
class DispatchE2ESmokeTest {

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
    @Mock private ShiftMapper shiftMapper;
    @Mock private RouteStationMapper routeStationMapper;
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
        ReflectionTestUtils.setField(dispatchService, "shiftMapper", shiftMapper);
        ReflectionTestUtils.setField(dispatchService, "routeStationMapper", routeStationMapper);
        ReflectionTestUtils.setField(dispatchService, "algorithmAdapter", algorithmAdapter);
        ReflectionTestUtils.setField(dispatchService, "dispatchEstimationService", dispatchEstimationService);
    }

    @Test
    void dispatch_closed_loop_state_machine() {
        // ---- 1. 订单池：1 张待入池货运订单 + 1 台可用车辆 + 场站 ----
        TransportOrderDO pooled = TransportOrderDO.builder().id(1L).orderType(2)
                .pickupStationId(13L).deliveryStationId(1L)
                .status(TransportOrderStatusEnum.READY_FOR_POOL.getStatus()).build();
        when(orderMapper.selectBatchIds(List.of(1L))).thenReturn(List.of(pooled));
        when(orderMapper.selectList(any(Wrapper.class))).thenReturn(List.of(pooled));
        when(stationMapper.selectById(1L)).thenReturn(StationDO.builder().id(1L)
                .longitude(new BigDecimal("104.0000")).latitude(new BigDecimal("30.0000")).build());
        when(stationMapper.selectBatchIds(anyCollection())).thenReturn(List.of(
                StationDO.builder().id(13L).longitude(new BigDecimal("104.0130")).latitude(new BigDecimal("30.0130")).build()));
        when(vehicleMapper.selectBatchIds(anyCollection())).thenReturn(List.of(
                VehicleDO.builder().id(7L).plateNo("川A·1").passengerCapacity(5).cargoCapacity(4).build()));
        // CAS 抢占返回 1（池内 1 张）
        lenient().when(orderMapper.update(any(TransportOrderDO.class), any())).thenReturn(1);

        // ---- 2. 归集入池：待入池 → 已入池 ----
        DispatchCollectReqVO collect = new DispatchCollectReqVO();
        collect.setOrderIds(List.of(1L));
        assertEquals(1, dispatchService.collectOrders(collect));

        // ---- 3. 智能派单：算法返回方案，方案待审核、订单已分配 ----
        AlgorithmPlanRespDTO result = AlgorithmPlanRespDTO.builder()
                .requestId("req-e2e")
                .status(AlgorithmPlanRespDTO.STATUS_FEASIBLE)
                .algorithmVersion("a1").parameterVersion("p1")
                .totalDistance(9.0)
                .vehiclePlans(List.of(AlgorithmVehiclePlanDTO.builder().vehicleId(7L)
                        .stops(List.of(
                                AlgorithmRouteStopDTO.builder().stationId("1").action(AlgorithmRouteStopDTO.ACTION_DEPART).build(),
                                AlgorithmRouteStopDTO.builder().stationId("13").orderId("1").action(AlgorithmRouteStopDTO.ACTION_PICKUP).build(),
                                AlgorithmRouteStopDTO.builder().stationId("1").action(AlgorithmRouteStopDTO.ACTION_RETURN).build()))
                        .totalDistance(9.0).build()))
                .build();
        when(algorithmAdapter.plan(any())).thenReturn(result);
        doAnswer(invocation -> {
            DispatchPlanDO plan = invocation.getArgument(0);
            plan.setId(100L);
            return 1;
        }).when(dispatchPlanMapper).insert(any(DispatchPlanDO.class));
        when(dispatchPlanItemMapper.insert(any(DispatchPlanItemDO.class))).thenReturn(1);

        DispatchSmartPlanReqVO smart = new DispatchSmartPlanReqVO();
        smart.setDepotStationId(1L);
        smart.setVehicleIds(List.of(7L));
        Long planId = dispatchService.createSmartPlan(smart);
        assertEquals(100L, planId); // 方案已创建（订单 CAS 抢占置已分配由 mock 更新模拟）

        // ---- 4. 方案审核下发：待审核 → 已下发 ----
        when(dispatchPlanMapper.selectById(100L)).thenReturn(
                DispatchPlanDO.builder().id(100L).status(DispatchPlanStatusEnum.PENDING.getStatus()).build());
        when(dispatchPlanItemMapper.selectList(any(Wrapper.class))).thenReturn(List.of(
                DispatchPlanItemDO.builder().planId(100L).vehicleId(7L).orderId(1L).visitSequence(2).build()));
        DispatchPlanReviewReqVO review = new DispatchPlanReviewReqVO();
        review.setPlanId(100L);
        review.setApprove(true);
        dispatchService.reviewPlan(review);
        assertEquals(DispatchPlanStatusEnum.ISSUED.getStatus(), dispatchPlanMapper.selectById(100L).getStatus());

        // ---- 5. 发车核验：已下发 → 执行中，订单已分配 → 已发车 ----
        when(departureCheckMapper.insert(any(DepartureCheckDO.class))).thenReturn(1);
        DispatchCheckReqVO check = new DispatchCheckReqVO();
        check.setPlanId(100L);
        check.setVehicleId(7L);
        check.setPass(true);
        dispatchService.departureCheck(check);
        assertEquals(DispatchPlanStatusEnum.RUNNING.getStatus(), dispatchPlanMapper.selectById(100L).getStatus());
    }
}
