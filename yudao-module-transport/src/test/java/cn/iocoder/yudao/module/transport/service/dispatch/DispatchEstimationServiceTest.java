package cn.iocoder.yudao.module.transport.service.dispatch;

import cn.iocoder.yudao.module.transport.dal.dataobject.dispatch.DispatchPlanItemDO;
import cn.iocoder.yudao.module.transport.dal.dataobject.station.StationDO;
import cn.iocoder.yudao.module.transport.dal.mysql.dispatch.DispatchPlanItemMapper;
import cn.iocoder.yudao.module.transport.dal.mysql.station.StationMapper;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
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
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.*;

/**
 * DispatchEstimationService 纯 Mockito 单测：ETA 逐站累计（行驶分钟 + 停站作业分钟）、
 * 坐标缺失不中断、多车独立、空输入不处理。
 *
 * 坐标基准（与 GeoDistanceUtilTest 同口径）：0.01° 经度 @ 纬度 30° ≈ 0.963km，
 * 25 km/h 下 0.963km → 2 分钟，0.02° ≈ 1.926km → 5 分钟。
 */
@ExtendWith(MockitoExtension.class)
class DispatchEstimationServiceTest {

    private static final LocalDateTime T0 = LocalDateTime.of(2026, 8, 23, 8, 0);

    @Mock private DispatchPlanItemMapper dispatchPlanItemMapper;
    @Mock private StationMapper stationMapper;

    private DispatchEstimationService estimationService;

    @BeforeEach
    void setUp() {
        estimationService = new DispatchEstimationService();
        ReflectionTestUtils.setField(estimationService, "dispatchPlanItemMapper", dispatchPlanItemMapper);
        ReflectionTestUtils.setField(estimationService, "stationMapper", stationMapper);
    }

    @Test
    void estimateAndFillPlanEtas_single_vehicle_chain() {
        // 单车闭环：DEPART(S0) → BOARD(S1) → ALIGHT(S2) → RETURN(S0)；故意乱序给出，验证按 visitSequence 排序
        mockStations(station(10L, "104.0000"), station(11L, "104.0100"), station(12L, "104.0200"));
        mockItems(
                item(4L, 7L, 10L, 4, 5), // RETURN
                item(2L, 7L, 11L, 2, 1), // BOARD
                item(1L, 7L, 10L, 1, 0), // DEPART
                item(3L, 7L, 12L, 3, 2)  // ALIGHT
        );

        estimationService.estimateAndFillPlanEtas(100L, T0);

        Map<Long, LocalDateTime> etaById = captureEtas(4);
        assertEquals(T0, etaById.get(1L));                          // DEPART：出发时刻
        assertEquals(T0.plusMinutes(2), etaById.get(2L));           // BOARD：S0→S1 行驶 2 分钟
        assertEquals(T0.plusMinutes(2 + 3 + 2), etaById.get(3L));   // ALIGHT：BOARD 作业 3 + S1→S2 行驶 2
        assertEquals(T0.plusMinutes(2 + 3 + 2 + 3 + 5), etaById.get(4L)); // RETURN：ALIGHT 作业 3 + S2→S0 行驶 5
    }

    @Test
    void estimateAndFillPlanEtas_missing_coords_continues_chain() {
        // S1 无坐标：相关站段按 0 里程，估算链不中断
        mockStations(station(10L, "104.0000"), StationDO.builder().id(11L).stationName("无名站").build(),
                station(12L, "104.0200"));
        mockItems(item(1L, 7L, 10L, 1, 0), item(2L, 7L, 11L, 2, 1), item(3L, 7L, 12L, 3, 2));

        estimationService.estimateAndFillPlanEtas(100L, T0);

        Map<Long, LocalDateTime> etaById = captureEtas(3);
        assertEquals(T0, etaById.get(1L));                // DEPART
        assertEquals(T0, etaById.get(2L));                // BOARD：S1 无坐标，站段 0 里程
        assertEquals(T0.plusMinutes(3), etaById.get(3L)); // ALIGHT：BOARD 作业 3 分钟，站段仍 0 里程
    }

    @Test
    void estimateAndFillPlanEtas_multi_vehicle_independent() {
        // 两车各自从出发时刻独立累计，互不影响
        mockStations(station(10L, "104.0000"), station(11L, "104.0100"));
        mockItems(
                item(1L, 7L, 10L, 1, 0), item(2L, 7L, 11L, 2, 4),   // 车 7：DEPART→PICKUP
                item(3L, 8L, 10L, 1, 0), item(4L, 8L, 11L, 2, 3));  // 车 8：DEPART→DELIVER

        estimationService.estimateAndFillPlanEtas(100L, T0);

        Map<Long, LocalDateTime> etaById = captureEtas(4);
        assertEquals(T0, etaById.get(1L));
        assertEquals(T0.plusMinutes(2), etaById.get(2L)); // 车 7 第二站
        assertEquals(T0, etaById.get(3L));
        assertEquals(T0.plusMinutes(2), etaById.get(4L)); // 车 8 第二站（不受车 7 影响）
    }

    @Test
    void estimateAndFillPlanEtas_empty_or_null_skips() {
        estimationService.estimateAndFillPlanEtas(null, T0);
        estimationService.estimateAndFillPlanEtas(100L, null);
        verifyNoInteractions(dispatchPlanItemMapper);

        mockItems();
        estimationService.estimateAndFillPlanEtas(100L, T0);
        verify(dispatchPlanItemMapper, never()).updateById(any(DispatchPlanItemDO.class));
    }

    // ==================== 测试夹具 ====================

    private DispatchPlanItemDO item(Long id, Long vehicleId, Long stationId, int seq, int actionType) {
        return DispatchPlanItemDO.builder().id(id).planId(100L).vehicleId(vehicleId)
                .stationId(stationId).visitSequence(seq).actionType(actionType).build();
    }

    private StationDO station(Long id, String longitude) {
        return StationDO.builder().id(id).stationName("站" + id)
                .longitude(new BigDecimal(longitude)).latitude(new BigDecimal("30.0000")).build();
    }

    private void mockStations(StationDO... stations) {
        when(stationMapper.selectBatchIds(anyCollection())).thenReturn(List.of(stations));
    }

    private void mockItems(DispatchPlanItemDO... items) {
        // BaseMapperX.selectList(SFunction, value) 的默认方法体会转调 selectList(Wrapper)，需 stub Wrapper 重载
        when(dispatchPlanItemMapper.selectList(any(Wrapper.class))).thenReturn(List.of(items));
    }

    private Map<Long, LocalDateTime> captureEtas(int expectedUpdates) {
        ArgumentCaptor<DispatchPlanItemDO> captor = ArgumentCaptor.forClass(DispatchPlanItemDO.class);
        verify(dispatchPlanItemMapper, times(expectedUpdates)).updateById(captor.capture());
        return captor.getAllValues().stream().collect(Collectors.toMap(
                DispatchPlanItemDO::getId, DispatchPlanItemDO::getEstimatedArrivalTime));
    }
}
