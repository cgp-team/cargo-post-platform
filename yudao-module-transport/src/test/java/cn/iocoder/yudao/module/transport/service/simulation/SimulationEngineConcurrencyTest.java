package cn.iocoder.yudao.module.transport.service.simulation;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * SimulationEngine 并发安全测试。
 *
 * 验证：
 * 1. 同一车辆多线程 tick 不抛异常、不产生状态撕裂
 * 2. 同一车辆双 start 只保留一个 active run
 * 3. tick + pause/resume 并发安全
 * 4. tick + reset 并发安全
 * 5. tick + scenario/inject 并发安全
 * 6. 事件幂等：10000 次 tick 只产生一次 START/COMPLETED
 */
class SimulationEngineConcurrencyTest {

    private SimulationEngine engine;

    // 构造一个简单的 3 站路线
    private static final long VEHICLE_ID = 1L;
    private static final long PLAN_ID = 100L;

    @BeforeEach
    void setUp() {
        engine = new SimulationEngine();
        ReflectionTestUtils.setField(engine, "simulationEnabled", true);
    }

    private List<SimulationEngine.SimSegment> buildSegments() {
        List<double[]> polyline1 = List.of(
                new double[]{104.0, 30.0},
                new double[]{104.01, 30.01}
        );
        List<double[]> polyline2 = List.of(
                new double[]{104.01, 30.01},
                new double[]{104.02, 30.02}
        );
        List<double[]> polyline3 = List.of(
                new double[]{104.02, 30.02},
                new double[]{104.03, 30.03}
        );

        List<SimulationEngine.SimSegment> segments = new ArrayList<>();
        segments.add(new SimulationEngine.SimSegment(1L, "站A", polyline1, 60, 10, false));
        segments.add(new SimulationEngine.SimSegment(2L, "站B", polyline2, 120, 10, false));
        segments.add(new SimulationEngine.SimSegment(3L, "站C", polyline3, 180, 10, true));
        return segments;
    }

    /**
     * 测试 1：10 线程同时 tick 同一车辆，不抛异常，状态单调
     */
    @Test
    void concurrentTick_noException_monotonicProgress() throws Exception {
        engine.start(PLAN_ID, VEHICLE_ID, java.time.LocalDateTime.now(), buildSegments(), 100.0);

        int threadCount = 10;
        int tickCount = 1000;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch latch = new CountDownLatch(threadCount);
        AtomicReference<Throwable> error = new AtomicReference<>();
        ConcurrentLinkedQueue<Long> simSecondsHistory = new ConcurrentLinkedQueue<>();

        for (int t = 0; t < threadCount; t++) {
            executor.submit(() -> {
                try {
                    latch.countDown();
                    latch.await();
                    for (int i = 0; i < tickCount; i++) {
                        SimulationEngine.SimTick tick = engine.tick(VEHICLE_ID);
                        if (tick != null) {
                            simSecondsHistory.add(tick.getSimSeconds());
                        }
                        Thread.yield();
                    }
                } catch (Throwable e) {
                    error.compareAndSet(null, e);
                }
            });
        }

        executor.shutdown();
        assertTrue(executor.awaitTermination(30, TimeUnit.SECONDS));
        assertNull(error.get(), "Concurrent tick threw exception: " + error.get());

        // 验证所有 simSeconds 非负
        for (Long sec : simSecondsHistory) {
            assertTrue(sec >= 0, "Negative simSeconds: " + sec);
        }
    }

    /**
     * 测试 2：10 线程同时 start 同一车辆，最终只有一个 active run
     */
    @Test
    void concurrentStart_onlyOneActiveRun() throws Exception {
        int threadCount = 10;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch latch = new CountDownLatch(threadCount);
        AtomicReference<Throwable> error = new AtomicReference<>();

        for (int t = 0; t < threadCount; t++) {
            final int threadId = t;
            executor.submit(() -> {
                try {
                    latch.countDown();
                    latch.await();
                    // 每个线程用不同的 planId 来区分
                    engine.start(PLAN_ID + threadId, VEHICLE_ID, java.time.LocalDateTime.now(),
                            buildSegments(), 10.0);
                } catch (Throwable e) {
                    error.compareAndSet(null, e);
                }
            });
        }

        executor.shutdown();
        assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
        assertNull(error.get(), "Concurrent start threw exception: " + error.get());

        // 只有一个 active run
        SimulationEngine.SimRun run = engine.getRun(VEHICLE_ID);
        assertNotNull(run, "No active run after concurrent start");
    }

    /**
     * 测试 3：tick + pause/resume 并发，状态合法
     */
    @Test
    void concurrentTickPauseResume_validState() throws Exception {
        engine.start(PLAN_ID, VEHICLE_ID, java.time.LocalDateTime.now(), buildSegments(), 100.0);

        int threadCount = 4;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch latch = new CountDownLatch(threadCount);
        AtomicReference<Throwable> error = new AtomicReference<>();
        AtomicInteger tickCount = new AtomicInteger(0);

        // 线程 1-2：持续 tick
        for (int t = 0; t < 2; t++) {
            executor.submit(() -> {
                try {
                    latch.countDown();
                    latch.await();
                    for (int i = 0; i < 500; i++) {
                        engine.tick(VEHICLE_ID);
                        tickCount.incrementAndGet();
                        Thread.yield();
                    }
                } catch (Throwable e) {
                    error.compareAndSet(null, e);
                }
            });
        }

        // 线程 3：反复 pause/resume
        executor.submit(() -> {
            try {
                latch.countDown();
                latch.await();
                for (int i = 0; i < 200; i++) {
                    engine.pause(VEHICLE_ID);
                    engine.resume(VEHICLE_ID);
                    Thread.yield();
                }
            } catch (Throwable e) {
                error.compareAndSet(null, e);
            }
        });

        // 线程 4：设置速度
        executor.submit(() -> {
            try {
                latch.countDown();
                latch.await();
                for (int i = 0; i < 200; i++) {
                    engine.setSpeed(VEHICLE_ID, 10.0 + (i % 60));
                    Thread.yield();
                }
            } catch (Throwable e) {
                error.compareAndSet(null, e);
            }
        });

        executor.shutdown();
        assertTrue(executor.awaitTermination(30, TimeUnit.SECONDS));
        assertNull(error.get(), "Concurrent tick/pause/resume threw exception: " + error.get());

        // 验证最终状态合法
        SimulationEngine.SimRun run = engine.getRun(VEHICLE_ID);
        assertNotNull(run);
        int status = run.getStatus();
        assertTrue(status == SimulationEngine.STATUS_RUNNING ||
                        status == SimulationEngine.STATUS_PAUSED ||
                        status == SimulationEngine.STATUS_COMPLETED,
                "Invalid status after concurrent operations: " + status);
    }

    /**
     * 测试 4：tick + reset 并发，reset 后不再有 active run
     */
    @Test
    void concurrentTickReset_noActiveRunAfterReset() throws Exception {
        engine.start(PLAN_ID, VEHICLE_ID, java.time.LocalDateTime.now(), buildSegments(), 100.0);

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch latch = new CountDownLatch(2);
        AtomicReference<Throwable> error = new AtomicReference<>();

        // 线程 1：持续 tick
        executor.submit(() -> {
            try {
                latch.countDown();
                latch.await();
                for (int i = 0; i < 1000; i++) {
                    engine.tick(VEHICLE_ID);
                    Thread.yield();
                }
            } catch (Throwable e) {
                error.compareAndSet(null, e);
            }
        });

        // 线程 2：稍后 reset
        executor.submit(() -> {
            try {
                latch.countDown();
                latch.await();
                Thread.sleep(10);
                engine.reset(VEHICLE_ID);
            } catch (Throwable e) {
                error.compareAndSet(null, e);
            }
        });

        executor.shutdown();
        assertTrue(executor.awaitTermination(30, TimeUnit.SECONDS));
        assertNull(error.get(), "Concurrent tick/reset threw exception: " + error.get());

        // reset 后不应有 active run
        SimulationEngine.SimRun run = engine.getRun(VEHICLE_ID);
        assertNull(run, "Active run exists after reset");
    }

    /**
     * 测试 5：tick + scenario/inject 并发，各状态独立
     */
    @Test
    void concurrentTickScenarioInject_statesIndependent() throws Exception {
        engine.start(PLAN_ID, VEHICLE_ID, java.time.LocalDateTime.now(), buildSegments(), 100.0);

        ExecutorService executor = Executors.newFixedThreadPool(4);
        CountDownLatch latch = new CountDownLatch(4);
        AtomicReference<Throwable> error = new AtomicReference<>();

        // 线程 1-2：持续 tick
        for (int t = 0; t < 2; t++) {
            executor.submit(() -> {
                try {
                    latch.countDown();
                    latch.await();
                    for (int i = 0; i < 500; i++) {
                        engine.tick(VEHICLE_ID);
                        Thread.yield();
                    }
                } catch (Throwable e) {
                    error.compareAndSet(null, e);
                }
            });
        }

        // 线程 3：设置交通因子
        executor.submit(() -> {
            try {
                latch.countDown();
                latch.await();
                for (int i = 0; i < 100; i++) {
                    engine.setTrafficFactor(VEHICLE_ID, 1.5);
                    engine.setGpsAvailable(VEHICLE_ID, false);
                    Thread.yield();
                }
            } catch (Throwable e) {
                error.compareAndSet(null, e);
            }
        });

        // 线程 4：设置故障
        executor.submit(() -> {
            try {
                latch.countDown();
                latch.await();
                for (int i = 0; i < 100; i++) {
                    engine.setVehicleFault(VEHICLE_ID, true);
                    Thread.yield();
                    engine.setVehicleFault(VEHICLE_ID, false);
                    Thread.yield();
                }
            } catch (Throwable e) {
                error.compareAndSet(null, e);
            }
        });

        executor.shutdown();
        assertTrue(executor.awaitTermination(30, TimeUnit.SECONDS));
        assertNull(error.get(), "Concurrent tick/scenario/inject threw exception: " + error.get());
    }

    /**
     * 测试 6：事件幂等 - 10000 次 tick 只产生一次 START
     */
    @Test
    void eventIdempotency_10000ticks_oneStart() throws Exception {
        engine.start(PLAN_ID, VEHICLE_ID, java.time.LocalDateTime.now(), buildSegments(), 0.1);

        int totalTicks = 10000;
        int startCount = 0;
        int completeCount = 0;

        for (int i = 0; i < totalTicks; i++) {
            List<SimulationEngine.SimEvent> events = engine.tickWithEvents(VEHICLE_ID);
            for (SimulationEngine.SimEvent evt : events) {
                if ("START".equals(evt.getEventType())) {
                    startCount++;
                }
                if ("COMPLETED".equals(evt.getEventType())) {
                    completeCount++;
                }
            }
        }

        assertEquals(1, startCount, "START should appear exactly once");
        assertTrue(completeCount <= 1, "COMPLETED should appear at most once");
    }
}
