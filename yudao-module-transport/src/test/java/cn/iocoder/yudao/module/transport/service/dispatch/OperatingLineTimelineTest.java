package cn.iocoder.yudao.module.transport.service.dispatch;

import cn.iocoder.yudao.module.transport.dal.dataobject.route.RouteStationDO;
import cn.iocoder.yudao.module.transport.dal.dataobject.shift.ShiftDO;
import org.junit.jupiter.api.Test;

import java.time.LocalTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 运营线路时间轴单测：核心是"车已经开过的站不能再派它去取货（不折返）"。
 *
 * <p>线路口径：悠山路(0min) → 中研所(10) → 上新街(20) → 较场口(30)，班次 08:00 发车、往返 60 分钟
 * （单程 30 分钟，与 DeterministicScheduleSimulator 同一套时间轴）。</p>
 */
class OperatingLineTimelineTest {

    private static RouteStationDO rs(int seq, Long stationId, int minutes) {
        return RouteStationDO.builder().id((long) seq).routeId(405L).stationId(stationId)
                .sequenceNo(seq).plannedMinutes(minutes).build();
    }

    /** 悠山路 → 中研所 → 上新街 → 较场口 */
    private static List<RouteStationDO> line() {
        return List.of(rs(1, 1L, 0), rs(2, 2L, 10), rs(3, 3L, 20), rs(4, 4L, 30));
    }

    private static ShiftDO shift(LocalTime departure, int durationMinutes) {
        return ShiftDO.builder().id(1L).shiftCode("SH-1").routeId(405L)
                .plannedDepartureTime(departure).plannedDurationMinutes(durationMinutes).status(0).build();
    }

    @Test
    void notDeparted_wholeForwardTripAhead_nothingPassed() {
        OperatingLineTimeline.Position pos = OperatingLineTimeline.at(
                line(), shift(LocalTime.of(8, 0), 60), LocalTime.of(7, 40));

        assertNotNull(pos);
        assertEquals(OperatingLineTimeline.Direction.FORWARD, pos.direction());
        assertEquals(1L, pos.currentStationId());
        assertEquals(List.of(1L, 2L, 3L, 4L), pos.travelOrder());
        assertTrue(pos.passedStations().isEmpty());
    }

    @Test
    void forwardMidway_stationsBehindArePassed() {
        // 08:15 在 中研所(10min)→上新街(20min) 之间：悠山路、中研所已经开过，上新街/较场口还没到
        OperatingLineTimeline.Position pos = OperatingLineTimeline.at(
                line(), shift(LocalTime.of(8, 0), 60), LocalTime.of(8, 15));

        assertEquals(OperatingLineTimeline.Direction.FORWARD, pos.direction());
        assertEquals(List.of(3L, 4L), pos.travelOrder());
        assertEquals(List.of(1L, 2L), pos.passedStations());
    }

    @Test
    void returning_servesStationsBackTowardsOrigin() {
        // 08:45 = 去程 30min 跑完 + 返程 15min：在 上新街→中研所 之间，往起点方向开
        OperatingLineTimeline.Position pos = OperatingLineTimeline.at(
                line(), shift(LocalTime.of(8, 0), 60), LocalTime.of(8, 45));

        assertEquals(OperatingLineTimeline.Direction.RETURNING, pos.direction());
        assertEquals(List.of(2L, 1L), pos.travelOrder());
        assertEquals(List.of(3L, 4L), pos.passedStations());
    }

    @Test
    void finished_noLongerServesStations() {
        OperatingLineTimeline.Position pos = OperatingLineTimeline.at(
                line(), shift(LocalTime.of(8, 0), 60), LocalTime.of(9, 30));

        assertTrue(pos.finished());
        assertTrue(pos.travelOrder().isEmpty());
        assertEquals(4, pos.passedStations().size());
    }

    @Test
    void windowStations_truncatesToTaskWindow() {
        // 任务窗口 08:00-08:25：车只跑到 上新街(20min) 附近 → 窗口内依次经过 悠山路/中研所/上新街
        List<Long> stations = OperatingLineTimeline.windowStations(line(), shift(LocalTime.of(8, 0), 60),
                LocalTime.of(8, 0), LocalTime.of(8, 25));

        assertEquals(List.of(1L, 2L, 3L), stations);
    }

    @Test
    void windowStations_wholeLineWhenWindowCoversTrip() {
        List<Long> stations = OperatingLineTimeline.windowStations(line(), shift(LocalTime.of(8, 0), 60),
                LocalTime.of(8, 0), LocalTime.of(10, 0));

        assertEquals(List.of(1L, 2L, 3L, 4L), stations);
    }

    @Test
    void windowStations_emptyWhenShiftAlreadyFinished() {
        List<Long> stations = OperatingLineTimeline.windowStations(line(), shift(LocalTime.of(8, 0), 60),
                LocalTime.of(11, 0), LocalTime.of(12, 0));

        assertTrue(stations.isEmpty());
    }

    @Test
    void missingShiftOrStations_returnsNull() {
        assertNull(OperatingLineTimeline.at(line(), null, LocalTime.of(8, 0)));
        assertNull(OperatingLineTimeline.at(List.of(), shift(LocalTime.of(8, 0), 60), LocalTime.of(8, 0)));
        assertNull(OperatingLineTimeline.windowStations(line(), null, LocalTime.of(8, 0), LocalTime.of(9, 0)));
    }

    @Test
    void stationOrderIsNormalisedBySequence() {
        List<RouteStationDO> shuffled = List.of(rs(4, 4L, 30), rs(1, 1L, 0), rs(3, 3L, 20), rs(2, 2L, 10));

        assertEquals(List.of(1L, 2L, 3L, 4L), OperatingLineTimeline.orderedStations(shuffled).stream()
                .map(RouteStationDO::getStationId).toList());
    }
}
