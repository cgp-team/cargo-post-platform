package cn.iocoder.yudao.module.transport.service.dispatch;

import cn.iocoder.yudao.module.transport.dal.dataobject.dispatch.DispatchPlanDO;
import cn.iocoder.yudao.module.transport.dal.dataobject.dispatch.DispatchPlanItemDO;
import cn.iocoder.yudao.module.transport.dal.dataobject.order.CargoOrderDO;
import cn.iocoder.yudao.module.transport.dal.dataobject.order.PassengerOrderDO;
import cn.iocoder.yudao.module.transport.dal.dataobject.order.TransportOrderDO;
import cn.iocoder.yudao.module.transport.dal.dataobject.station.StationDO;
import cn.iocoder.yudao.module.transport.dal.mysql.dispatch.DispatchPlanItemMapper;
import cn.iocoder.yudao.module.transport.dal.mysql.dispatch.DispatchPlanMapper;
import cn.iocoder.yudao.module.transport.dal.mysql.order.CargoOrderMapper;
import cn.iocoder.yudao.module.transport.dal.mysql.order.PassengerOrderMapper;
import cn.iocoder.yudao.module.transport.dal.mysql.order.PostalOrderMapper;
import cn.iocoder.yudao.module.transport.dal.mysql.order.TransportOrderMapper;
import cn.iocoder.yudao.module.transport.dal.mysql.station.StationMapper;
import cn.iocoder.yudao.module.transport.enums.dispatch.TaskItemStatusEnum;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.toolkit.support.SFunction;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.*;

/**
 * DispatchEstimationService 纯 Mockito 单测：ETA 逐站累计（行驶分钟 + 停站作业分钟）、
 * 坐标缺失不中断、多车独立、空输入不处理、方案摘要（耗时/收入/成本）。
 *
 * 坐标基准（与 GeoDistanceUtilTest 同口径）：0.01° 经度 @ 纬度 30° ≈ 0.963km，
 * 25 km/h 下 0.963km → 2 分钟，0.02° ≈ 1.926km → 5 分钟。
 */
@ExtendWith(MockitoExtension.class)
class DispatchEstimationServiceTest {

    private static final LocalDateTime T0 = LocalDateTime.of(2026, 8, 23, 8, 0);

    @Mock private DispatchPlanItemMapper dispatchPlanItemMapper;
    @Mock private DispatchPlanMapper dispatchPlanMapper;
    @Mock private StationMapper stationMapper;
    @Mock private TransportOrderMapper orderMapper;
    @Mock private PassengerOrderMapper passengerOrderMapper;
    @Mock private CargoOrderMapper cargoOrderMapper;
    @Mock private PostalOrderMapper postalOrderMapper;
    @Mock private PricingRuleService pricingRuleService;

    private DispatchEstimationService estimationService;

    @BeforeEach
    void setUp() {
        estimationService = new DispatchEstimationService();
        ReflectionTestUtils.setField(estimationService, "dispatchPlanItemMapper", dispatchPlanItemMapper);
        ReflectionTestUtils.setField(estimationService, "dispatchPlanMapper", dispatchPlanMapper);
        ReflectionTestUtils.setField(estimationService, "stationMapper", stationMapper);
        ReflectionTestUtils.setField(estimationService, "orderMapper", orderMapper);
        ReflectionTestUtils.setField(estimationService, "passengerOrderMapper", passengerOrderMapper);
        ReflectionTestUtils.setField(estimationService, "cargoOrderMapper", cargoOrderMapper);
        ReflectionTestUtils.setField(estimationService, "postalOrderMapper", postalOrderMapper);
        ReflectionTestUtils.setField(estimationService, "pricingRuleService", pricingRuleService);
    }

    @Test
    void estimatePlan_single_vehicle_chain() {
        // 单车闭环：DEPART(S0) → BOARD(S1) → ALIGHT(S2) → RETURN(S0)；故意乱序给出，验证按 visitSequence 排序
        mockRule();
        mockStations(station(10L, "104.0000"), station(11L, "104.0100"), station(12L, "104.0200"));
        mockItems(
                item(4L, 7L, 10L, 4, 5, null),      // RETURN
                item(2L, 7L, 11L, 2, 1, 1001L),    // BOARD
                item(1L, 7L, 10L, 1, 0, null),     // DEPART
                item(3L, 7L, 12L, 3, 2, 1001L)     // ALIGHT
        );

        estimationService.estimatePlan(100L, T0);

        Map<Long, LocalDateTime> etaById = captureItemEtas(4);
        assertEquals(T0, etaById.get(1L));                          // DEPART：出发时刻
        assertEquals(T0.plusMinutes(2), etaById.get(2L));           // BOARD：S0→S1 行驶 2 分钟
        assertEquals(T0.plusMinutes(2 + 3 + 2), etaById.get(3L));   // ALIGHT：BOARD 作业 3 + S1→S2 行驶 2
        assertEquals(T0.plusMinutes(2 + 3 + 2 + 3 + 5), etaById.get(4L)); // RETURN：ALIGHT 作业 3 + S2→S0 行驶 5
    }

    @Test
    void estimatePlan_missing_coords_continues_chain() {
        // S1 无坐标：相关站段按 0 里程，估算链不中断
        mockRule();
        mockStations(station(10L, "104.0000"), StationDO.builder().id(11L).stationName("无名站").build(),
                station(12L, "104.0200"));
        mockItems(item(1L, 7L, 10L, 1, 0, null), item(2L, 7L, 11L, 2, 1, null),
                item(3L, 7L, 12L, 3, 2, null));

        estimationService.estimatePlan(100L, T0);

        Map<Long, LocalDateTime> etaById = captureItemEtas(3);
        assertEquals(T0, etaById.get(1L));                // DEPART
        assertEquals(T0, etaById.get(2L));                // BOARD：S1 无坐标，站段 0 里程
        assertEquals(T0.plusMinutes(3), etaById.get(3L)); // ALIGHT：BOARD 作业 3 分钟，站段仍 0 里程
    }

    @Test
    void estimatePlan_multi_vehicle_independent() {
        // 两车各自从出发时刻独立累计，互不影响
        mockRule();
        mockStations(station(10L, "104.0000"), station(11L, "104.0100"));
        mockItems(
                item(1L, 7L, 10L, 1, 0, null), item(2L, 7L, 11L, 2, 4, null),   // 车 7：DEPART→PICKUP
                item(3L, 8L, 10L, 1, 0, null), item(4L, 8L, 11L, 2, 3, null));  // 车 8：DEPART→DELIVER

        estimationService.estimatePlan(100L, T0);

        Map<Long, LocalDateTime> etaById = captureItemEtas(4);
        assertEquals(T0, etaById.get(1L));
        assertEquals(T0.plusMinutes(2), etaById.get(2L)); // 车 7 第二站
        assertEquals(T0, etaById.get(3L));
        assertEquals(T0.plusMinutes(2), etaById.get(4L)); // 车 8 第二站（不受车 7 影响）
    }

    @Test
    void estimatePlan_road_segments_used_for_eta_and_cost() {
        // 路网分段时长/里程优先于直线÷均速：ETA 按秒数累计、成本按路网真实公里
        mockRule();
        mockStations(station(10L, "104.0000"), station(11L, "104.0100"), station(12L, "104.0200"));
        mockItems(
                item(1L, 7L, 10L, 1, 0, null), item(2L, 7L, 11L, 2, 1, 1001L),
                item(3L, 7L, 12L, 3, 2, 1001L), item(4L, 7L, 10L, 4, 5, null));
        when(orderMapper.selectBatchIds(anyCollection())).thenReturn(List.of(
                TransportOrderDO.builder().id(1001L).orderType(2)
                        .pickupStationId(11L).deliveryStationId(12L).build()));
        when(cargoOrderMapper.selectOne(any(SFunction.class), any()))
                .thenReturn(CargoOrderDO.builder().itemCount(2).build());
        Map<String, DispatchEstimationService.RoadSegment> roadSegments = Map.of(
                "7:2", new DispatchEstimationService.RoadSegment(120L, 2.5),
                "7:3", new DispatchEstimationService.RoadSegment(180L, 3.0),
                "7:4", new DispatchEstimationService.RoadSegment(300L, 4.5));

        estimationService.estimatePlan(100L, T0, roadSegments);

        Map<Long, LocalDateTime> etaById = captureItemEtas(4);
        assertEquals(T0, etaById.get(1L));                                // DEPART
        assertEquals(T0.plusSeconds(120), etaById.get(2L));               // BOARD：+120s
        assertEquals(T0.plusMinutes(2 + 3 + 3), etaById.get(3L));         // ALIGHT：作业 3 分钟 +180s
        assertEquals(T0.plusMinutes(2 + 3 + 3 + 3 + 5), etaById.get(4L)); // RETURN：作业 3 分钟 +300s
        ArgumentCaptor<DispatchPlanDO> captor = ArgumentCaptor.forClass(DispatchPlanDO.class);
        verify(dispatchPlanMapper).updateById(captor.capture());
        // 耗时 16 分钟；成本按路网公里 2.5+3.0+4.5=10.0 × 2.50 = 25.00（非直线 3.852km）
        assertEquals(16, captor.getValue().getEstDurationMinutes());
        assertEquals(0, captor.getValue().getEstCost().compareTo(new BigDecimal("25.00")));
        // 有路网分段 → 明确记录 AMAP（高德真实时长）
        assertEquals("AMAP", captor.getValue().getRouteProvider());
    }

    @Test
    void estimatePlan_persists_segment_duration_and_distance() {
        // P1-001 回归：分段路网时长/里程随 ETA 一并回写明细（不再只写 estimatedArrivalTime）
        mockRule();
        mockStations(station(10L, "104.0000"), station(11L, "104.0100"), station(12L, "104.0200"));
        mockItems(
                item(1L, 7L, 10L, 1, 0, null), item(2L, 7L, 11L, 2, 1, 1001L),
                item(3L, 7L, 12L, 3, 2, 1001L), item(4L, 7L, 10L, 4, 5, null));
        when(orderMapper.selectBatchIds(anyCollection())).thenReturn(List.of(
                TransportOrderDO.builder().id(1001L).orderType(2)
                        .pickupStationId(11L).deliveryStationId(12L).build()));
        when(cargoOrderMapper.selectOne(any(SFunction.class), any()))
                .thenReturn(CargoOrderDO.builder().itemCount(1).build());
        Map<String, DispatchEstimationService.RoadSegment> roadSegments = Map.of(
                "7:2", new DispatchEstimationService.RoadSegment(120L, 2.5),  // S0→S1 高德
                "7:3", new DispatchEstimationService.RoadSegment(180L, 3.0),  // S1→S2 高德
                "7:4", new DispatchEstimationService.RoadSegment(300L, 4.5)); // S2→S0 高德

        estimationService.estimatePlan(100L, T0, roadSegments);

        ArgumentCaptor<DispatchPlanItemDO> captor = ArgumentCaptor.forClass(DispatchPlanItemDO.class);
        verify(dispatchPlanItemMapper, times(4)).updateById(captor.capture());
        Map<Long, DispatchPlanItemDO> byId = captor.getAllValues().stream()
                .collect(Collectors.toMap(DispatchPlanItemDO::getId, Function.identity()));
        // DEPART(seq1) 无上一站 → 无分段
        assertNull(byId.get(1L).getSegmentDurationSeconds());
        assertNull(byId.get(1L).getSegmentDistanceKm());
        // 有路网分段 → 时长取高德秒数、里程取高德公里
        assertEquals(120, byId.get(2L).getSegmentDurationSeconds());
        assertEquals(0, byId.get(2L).getSegmentDistanceKm().compareTo(new BigDecimal("2.5")));
        assertEquals(180, byId.get(3L).getSegmentDurationSeconds());
        assertEquals(0, byId.get(3L).getSegmentDistanceKm().compareTo(new BigDecimal("3.0")));
        assertEquals(300, byId.get(4L).getSegmentDurationSeconds());
        assertEquals(0, byId.get(4L).getSegmentDistanceKm().compareTo(new BigDecimal("4.5")));
    }

    @Test
    void estimatePlan_persists_segment_fallback_to_haversine() {
        // P1-001 回归：无路网段（手工派单/欧氏）→ 分段时长/里程按直线÷均速兜底回写
        mockRule();
        mockStations(station(10L, "104.0000"), station(11L, "104.0100"), station(12L, "104.0200"));
        mockItems(item(1L, 7L, 10L, 1, 0, null), item(2L, 7L, 11L, 2, 1, null), item(3L, 7L, 12L, 3, 2, null));

        estimationService.estimatePlan(100L, T0);

        ArgumentCaptor<DispatchPlanItemDO> captor = ArgumentCaptor.forClass(DispatchPlanItemDO.class);
        verify(dispatchPlanItemMapper, times(3)).updateById(captor.capture());
        Map<Long, DispatchPlanItemDO> byId = captor.getAllValues().stream()
                .collect(Collectors.toMap(DispatchPlanItemDO::getId, Function.identity()));
        assertNull(byId.get(1L).getSegmentDurationSeconds()); // DEPART 无分段
        // 0.01° @ 纬度30 ≈ 0.963km，25km/h → 2 分钟 = 120 秒（travelMinutes 四舍五入）
        assertEquals(120, byId.get(2L).getSegmentDurationSeconds());
        assertEquals(120, byId.get(3L).getSegmentDurationSeconds());
        assertEquals(0, byId.get(2L).getSegmentDistanceKm().compareTo(new BigDecimal("0.963")));
    }

    @Test
    void estimatePlan_persists_task_segment_fields() {
        // Phase 4 回归：任务段明细回写 计划离站/作业时长/数量/状态 + 方案任务段窗口
        mockRule();
        mockStations(station(10L, "104.0000"), station(11L, "104.0100"), station(12L, "104.0200"));
        mockItems(
                item(1L, 7L, 10L, 1, 0, null), item(2L, 7L, 11L, 2, 1, 1001L),
                item(3L, 7L, 12L, 3, 2, 1001L), item(4L, 7L, 10L, 4, 5, null));
        when(orderMapper.selectBatchIds(anyCollection())).thenReturn(List.of(
                TransportOrderDO.builder().id(1001L).orderType(1)
                        .pickupStationId(11L).deliveryStationId(12L).build()));
        when(passengerOrderMapper.selectList(any(Wrapper.class))).thenReturn(List.of(
                PassengerOrderDO.builder().orderId(1001L).passengerCount(2).build()));

        estimationService.estimatePlan(100L, T0);

        ArgumentCaptor<DispatchPlanItemDO> itemCaptor = ArgumentCaptor.forClass(DispatchPlanItemDO.class);
        verify(dispatchPlanItemMapper, times(4)).updateById(itemCaptor.capture());
        Map<Long, DispatchPlanItemDO> byId = itemCaptor.getAllValues().stream()
                .collect(Collectors.toMap(DispatchPlanItemDO::getId, Function.identity()));
        // BOARD(seq2)：到达 T0+2，作业 3 分钟 → 离站 T0+5，数量=2 人，状态待执行
        assertEquals(T0.plusMinutes(2), byId.get(2L).getEstimatedArrivalTime());
        assertEquals(T0.plusMinutes(5), byId.get(2L).getPlannedDepartureTime());
        assertEquals(180, byId.get(2L).getServiceDurationSeconds());
        assertEquals(2, byId.get(2L).getQuantity());
        assertEquals(TaskItemStatusEnum.PENDING.getStatus(), byId.get(2L).getStatus());
        // ALIGHT(seq3)：作业 3 分钟，数量 2
        assertEquals(180, byId.get(3L).getServiceDurationSeconds());
        assertEquals(2, byId.get(3L).getQuantity());
        // RETURN(seq4)：无作业、无数量
        assertEquals(0, byId.get(4L).getServiceDurationSeconds());
        assertNull(byId.get(4L).getQuantity());
        // 方案任务段窗口：开始=出发时刻，结束=开始+预计耗时(15 分钟)
        ArgumentCaptor<DispatchPlanDO> planCaptor = ArgumentCaptor.forClass(DispatchPlanDO.class);
        verify(dispatchPlanMapper).updateById(planCaptor.capture());
        assertEquals(T0, planCaptor.getValue().getTaskWindowStart());
        assertEquals(T0.plusMinutes(15), planCaptor.getValue().getTaskWindowEnd());
    }

    @Test
    void estimatePlan_route_provider_fallback_when_no_road_segments() {
        // 手工派单/无路网段（estimatePlan 无参重载 = 空 roadSegments）→ 全量直线，记录 EUCLIDEAN_FALLBACK
        mockRule();
        mockStations(station(10L, "104.0000"), station(11L, "104.0100"), station(12L, "104.0200"));
        mockItems(item(1L, 7L, 10L, 1, 0, null), item(2L, 7L, 11L, 2, 1, null), item(3L, 7L, 12L, 3, 2, null));

        estimationService.estimatePlan(100L, T0);

        ArgumentCaptor<DispatchPlanDO> captor = ArgumentCaptor.forClass(DispatchPlanDO.class);
        verify(dispatchPlanMapper).updateById(captor.capture());
        assertEquals("EUCLIDEAN_FALLBACK", captor.getValue().getRouteProvider());
    }

    @Test
    void estimatePlan_road_segments_missing_falls_back_to_haversine() {
        // 路网分段缺失的站段回退直线÷均速：seq2 无分段（直线 2 分钟），seq3 有分段（+180s）
        mockRule();
        mockStations(station(10L, "104.0000"), station(11L, "104.0100"), station(12L, "104.0200"));
        mockItems(item(1L, 7L, 10L, 1, 0, null), item(2L, 7L, 11L, 2, 1, null),
                item(3L, 7L, 12L, 3, 2, null));
        Map<String, DispatchEstimationService.RoadSegment> roadSegments = Map.of(
                "7:3", new DispatchEstimationService.RoadSegment(180L, 3.0));

        estimationService.estimatePlan(100L, T0, roadSegments);

        Map<Long, LocalDateTime> etaById = captureItemEtas(3);
        assertEquals(T0, etaById.get(1L));
        assertEquals(T0.plusMinutes(2), etaById.get(2L));          // seq2 回退直线 2 分钟
        assertEquals(T0.plusMinutes(2 + 3 + 3), etaById.get(3L));  // seq3：作业 3 分钟 +180s
    }

    @Test
    void estimatePlan_empty_or_null_skips() {
        estimationService.estimatePlan(null, T0);
        estimationService.estimatePlan(100L, null);
        verifyNoInteractions(dispatchPlanItemMapper);

        mockItems();
        estimationService.estimatePlan(100L, T0);
        verify(dispatchPlanItemMapper, never()).updateById(any(DispatchPlanItemDO.class));
        verifyNoInteractions(dispatchPlanMapper, pricingRuleService, orderMapper);
    }

    @Test
    void estimatePlan_summary_duration_revenue_cost() {
        // 车 7：DEPART(S0)→BOARD(S1)→ALIGHT(S2)→RETURN(S0)，载客运单 1001（2 人，S1→S2）
        // 车 8：DEPART(S0)→DELIVER(S2)→RETURN(S0)，载货运单 1002（2 件）
        // 默认计价：人公里 1.00、货运件 5.00、公里成本 2.50、时速 25、停站 3 分钟
        mockRule();
        mockStations(station(10L, "104.0000"), station(11L, "104.0100"), station(12L, "104.0200"));
        mockItems(
                item(1L, 7L, 10L, 1, 0, null), item(2L, 7L, 11L, 2, 1, 1001L),
                item(3L, 7L, 12L, 3, 2, 1001L), item(4L, 7L, 10L, 4, 5, null),
                item(5L, 8L, 10L, 1, 0, null), item(6L, 8L, 12L, 2, 3, 1002L),
                item(7L, 8L, 10L, 3, 5, null));
        when(orderMapper.selectBatchIds(anyCollection())).thenReturn(List.of(
                TransportOrderDO.builder().id(1001L).orderType(1)
                        .pickupStationId(11L).deliveryStationId(12L).build(),
                TransportOrderDO.builder().id(1002L).orderType(2)
                        .pickupStationId(12L).deliveryStationId(10L).build()));
        when(passengerOrderMapper.selectOne(any(SFunction.class), any()))
                .thenReturn(PassengerOrderDO.builder().passengerCount(2).build());
        when(cargoOrderMapper.selectOne(any(SFunction.class), any()))
                .thenReturn(CargoOrderDO.builder().itemCount(2).build());

        estimationService.estimatePlan(100L, T0);

        ArgumentCaptor<DispatchPlanDO> captor = ArgumentCaptor.forClass(DispatchPlanDO.class);
        verify(dispatchPlanMapper).updateById(captor.capture());
        DispatchPlanDO plan = captor.getValue();
        // 耗时：车 7 为 2+3+2+3+5=15 分钟，车 8 为 5+3+5=13 分钟，取最大 15
        assertEquals(15, plan.getEstDurationMinutes());
        // 收入：客运 2 人 × 0.963km × 1.00 = 1.93；货运 2 件 × 5.00 = 10.00；合计 11.93
        assertEquals(0, plan.getEstRevenue().compareTo(new BigDecimal("11.93")));
        // 成本：两车总里程 (3.852 + 3.852)km × 2.50 = 19.26
        assertEquals(0, plan.getEstCost().compareTo(new BigDecimal("19.26")));
    }

    // ==================== 测试夹具 ====================

    private DispatchPlanItemDO item(Long id, Long vehicleId, Long stationId, int seq, int actionType, Long orderId) {
        return DispatchPlanItemDO.builder().id(id).planId(100L).vehicleId(vehicleId)
                .stationId(stationId).visitSequence(seq).actionType(actionType).orderId(orderId).build();
    }

    private StationDO station(Long id, String longitude) {
        return StationDO.builder().id(id).stationName("站" + id)
                .longitude(new BigDecimal(longitude)).latitude(new BigDecimal("30.0000")).build();
    }

    private void mockRule() {
        when(pricingRuleService.getRule()).thenReturn(PricingRuleService.defaultRule());
    }

    private void mockStations(StationDO... stations) {
        when(stationMapper.selectBatchIds(anyCollection())).thenReturn(List.of(stations));
    }

    private void mockItems(DispatchPlanItemDO... items) {
        // BaseMapperX.selectList(SFunction, value) 的默认方法体会转调 selectList(Wrapper)，需 stub Wrapper 重载
        when(dispatchPlanItemMapper.selectList(any(Wrapper.class))).thenReturn(List.of(items));
    }

    private Map<Long, LocalDateTime> captureItemEtas(int expectedUpdates) {
        ArgumentCaptor<DispatchPlanItemDO> captor = ArgumentCaptor.forClass(DispatchPlanItemDO.class);
        verify(dispatchPlanItemMapper, times(expectedUpdates)).updateById(captor.capture());
        return captor.getAllValues().stream().collect(Collectors.toMap(
                DispatchPlanItemDO::getId, DispatchPlanItemDO::getEstimatedArrivalTime));
    }
}
