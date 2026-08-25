package cn.iocoder.yudao.module.transport.service.simulation;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 模拟引擎核心逻辑纯单测：polyline 插值、任务段推进、状态机与倍速。
 */
class SimulationEngineTest {

    private static final LocalDateTime T0 = LocalDateTime.of(2026, 8, 26, 8, 0);

    /** 三段经停：DEPART场站(0s) → S1(600s, 作业120s) → S2(1200s, 作业120s) → RETURN场站(1800s) */
    private List<SimulationEngine.SimSegment> segments() {
        return List.of(
                new SimulationEngine.SimSegment(11L, "S1",
                        List.of(new double[]{104.00, 30.00}, new double[]{104.01, 30.01}), 600, 120, false),
                new SimulationEngine.SimSegment(12L, "S2",
                        List.of(new double[]{104.01, 30.01}, new double[]{104.02, 30.02}), 1200, 120, false),
                new SimulationEngine.SimSegment(null, "场站",
                        List.of(new double[]{104.02, 30.02}, new double[]{104.00, 30.00}), 1800, 0, true));
    }

    private SimulationEngine.SimRun run() {
        return new SimulationEngine.SimRun(100L, 7L, T0, segments());
    }

    @Test
    void interpolate_polyline_progress() {
        List<double[]> poly = List.of(new double[]{0, 0}, new double[]{0, 0.01}, new double[]{0, 0.02});
        assertArrayEquals(new double[]{0, 0}, SimulationEngine.interpolatePolyline(poly, 0));
        assertArrayEquals(new double[]{0, 0.02}, SimulationEngine.interpolatePolyline(poly, 1));
        double[] mid = SimulationEngine.interpolatePolyline(poly, 0.5);
        assertTrue(Math.abs(mid[1] - 0.01) < 1e-9, "中点应在 polyline 中点");
    }

    @Test
    void tick_at_start_and_mid_segment() {
        SimulationEngine.SimRun run = run();
        // 模拟 0s → 起点 S1 polyline 首点
        SimulationEngine.SimTick t0 = SimulationEngine.computeTick(run, 0);
        assertEquals(104.00, t0.getLongitude(), 1e-9);
        assertFalse(t0.isArrived());
        // 模拟 300s（S1 段内一半）→ 应在 polyline 中点
        SimulationEngine.SimTick t300 = SimulationEngine.computeTick(run, 300);
        assertEquals(104.005, t300.getLongitude(), 1e-9);
        assertEquals(0, t300.getSegmentIndex());
    }

    @Test
    void tick_arrived_when_in_service_window() {
        SimulationEngine.SimRun run = run();
        // 600s 到达 S1，600~720s 作业中 → arrived=true，位置=S1 终点
        SimulationEngine.SimTick t600 = SimulationEngine.computeTick(run, 600);
        assertTrue(t600.isArrived());
        assertEquals("S1", t600.getStationName());
        assertEquals(104.01, t600.getLongitude(), 1e-9);
        // 作业结束后继续行驶
        SimulationEngine.SimTick t800 = SimulationEngine.computeTick(run, 800);
        assertFalse(t800.isArrived());
        assertEquals(1, t800.getSegmentIndex()); // 已进入 S2 段
    }

    @Test
    void run_completed_after_last_segment() {
        SimulationEngine.SimRun run = run();
        // totalSimSeconds = 1800 → 到达终点段（RETURN 场站）
        SimulationEngine.SimTick tick = SimulationEngine.computeTick(run, 1800);
        assertNotNull(tick);
        assertEquals(2, tick.getSegmentIndex());
        assertEquals("场站", tick.getStationName());
    }

    @Test
    void controls_state_transitions() {
        SimulationEngine engine = new SimulationEngine();
        engine.setSimulationEnabled(true);
        engine.start(100L, 7L, T0, segments(), 10.0);
        assertEquals(SimulationEngine.STATUS_RUNNING, engine.getRun(7L).getStatus());
        engine.pause(7L);
        assertEquals(SimulationEngine.STATUS_PAUSED, engine.getRun(7L).getStatus());
        engine.resume(7L);
        assertEquals(SimulationEngine.STATUS_RUNNING, engine.getRun(7L).getStatus());
        engine.setSpeed(7L, 60.0);
        assertEquals(60.0, engine.getRun(7L).getMultiplier());
        engine.reset(7L);
        assertNull(engine.getRun(7L));
    }

    @Test
    void controls_disabled_are_noop() {
        SimulationEngine engine = new SimulationEngine();
        engine.setSimulationEnabled(false); // 生产默认 false
        engine.start(100L, 7L, T0, segments(), 10.0);
        assertNull(engine.getRun(7L)); // 未启用不建运行
    }

    @Test
    void current_segment_station_id() {
        SimulationEngine.SimRun run = run();
        assertEquals(11L, SimulationEngine.currentSegmentStationId(run, 300));
        assertEquals(12L, SimulationEngine.currentSegmentStationId(run, 800));
    }

}
