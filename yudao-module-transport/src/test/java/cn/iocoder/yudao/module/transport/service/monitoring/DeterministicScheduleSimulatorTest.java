package cn.iocoder.yudao.module.transport.service.monitoring;

import cn.iocoder.yudao.module.transport.dal.dataobject.route.RouteDO;
import cn.iocoder.yudao.module.transport.dal.dataobject.route.RouteStationDO;
import cn.iocoder.yudao.module.transport.dal.dataobject.shift.ShiftDO;
import cn.iocoder.yudao.module.transport.dal.dataobject.station.StationDO;
import cn.iocoder.yudao.module.transport.dal.dataobject.vehicle.VehicleDO;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 确定性班次模拟器单测：**完全不依赖人工启动 SimulationEngine**，
 * 仅凭车辆 + 班次 + 线路 + 线路站点 + 当前时间就必须产出 SIMULATED 车辆位置（演示可用性的核心保障）。
 */
class DeterministicScheduleSimulatorTest {

    private static final RouteDO ROUTE = RouteDO.builder()
            .id(1L).routeCode("R001").routeName("县城—青山镇线")
            .distanceKm(BigDecimal.valueOf(20)).build();

    private static final Map<Long, StationDO> STATIONS = Map.of(
            1L, StationDO.builder().id(1L).stationName("县城客运中心")
                    .longitude(BigDecimal.valueOf(104.0)).latitude(BigDecimal.valueOf(30.0)).build(),
            4L, StationDO.builder().id(4L).stationName("青山镇站")
                    .longitude(BigDecimal.valueOf(104.1)).latitude(BigDecimal.valueOf(30.1)).build());

    private static final List<RouteStationDO> ROUTE_STATIONS = List.of(
            RouteStationDO.builder().id(1L).routeId(1L).stationId(1L).sequenceNo(1).plannedMinutes(0).build(),
            RouteStationDO.builder().id(2L).routeId(1L).stationId(4L).sequenceNo(2).plannedMinutes(60).build());

    private static final VehicleDO VEHICLE = VehicleDO.builder().id(1L).plateNo("川A12345").status(0).build();

    @Test
    void assignShifts_rotatesAvailableVehiclesByDepartureTime() {
        List<VehicleDO> vehicles = List.of(
                VehicleDO.builder().id(2L).status(0).build(),
                VehicleDO.builder().id(1L).status(0).build(),
                VehicleDO.builder().id(9L).status(1).build()); // 停用车辆不参与
        List<ShiftDO> shifts = List.of(
                ShiftDO.builder().id(2L).shiftCode("SH002").plannedDepartureTime(LocalTime.of(8, 30)).status(0).build(),
                ShiftDO.builder().id(1L).shiftCode("SH001").plannedDepartureTime(LocalTime.of(6, 30)).status(0).build(),
                ShiftDO.builder().id(3L).shiftCode("SH003").plannedDepartureTime(LocalTime.of(14, 0)).status(0).build());

        Map<Long, List<ShiftDO>> assignment = DeterministicScheduleSimulator.assignShifts(vehicles, shifts);

        // 车辆按 ID 升序（1、2），班次按发车时间升序（SH001、SH002、SH003）轮转：1→SH001/SH003，2→SH002
        assertEquals(List.of("SH001", "SH003"), assignment.get(1L).stream().map(ShiftDO::getShiftCode).toList());
        assertEquals(List.of("SH002"), assignment.get(2L).stream().map(ShiftDO::getShiftCode).toList());
        assertNull(assignment.get(9L));
    }

    @Test
    void selectCurrentShift_prefersRunningThenNextThenLast() {
        ShiftDO morning = ShiftDO.builder().id(1L).shiftCode("SH001")
                .plannedDepartureTime(LocalTime.of(6, 30)).plannedDurationMinutes(50).build();
        ShiftDO noon = ShiftDO.builder().id(2L).shiftCode("SH002")
                .plannedDepartureTime(LocalTime.of(12, 0)).plannedDurationMinutes(50).build();
        List<ShiftDO> shifts = List.of(noon, morning);

        assertEquals("SH002", DeterministicScheduleSimulator.selectCurrentShift(shifts, LocalTime.of(12, 10)).getShiftCode());
        assertEquals("SH002", DeterministicScheduleSimulator.selectCurrentShift(shifts, LocalTime.of(9, 0)).getShiftCode());
        assertEquals("SH002", DeterministicScheduleSimulator.selectCurrentShift(shifts, LocalTime.of(23, 0)).getShiftCode());
        assertNull(DeterministicScheduleSimulator.selectCurrentShift(List.of(), LocalTime.NOON));
    }

    @Test
    void compute_inTransit_interpolatesBetweenStationsWithFullContext() {
        LocalTime now = LocalTime.now();
        ShiftDO shift = ShiftDO.builder().id(1L).shiftCode("SH001").routeId(1L)
                .plannedDepartureTime(now.minusMinutes(30)).plannedDurationMinutes(60).status(0).build();

        VehicleLocationSnapshot snapshot =
                DeterministicScheduleSimulator.compute(VEHICLE, shift, ROUTE, ROUTE_STATIONS, STATIONS, now);

        assertNotNull(snapshot);
        // 完整业务上下文：班次/线路/当前站/下一站/进度/速度（下游按线路聚合车辆依赖这些字段）
        assertEquals("SIMULATED", snapshot.getSource());
        assertEquals(1, snapshot.getStatus());
        assertEquals(1L, snapshot.getShiftId());
        assertEquals("SH001", snapshot.getShiftCode());
        assertEquals(1L, snapshot.getRouteId());
        assertEquals("R001", snapshot.getRouteCode());
        assertEquals("县城—青山镇线", snapshot.getRouteName());
        assertEquals("县城客运中心", snapshot.getCurrentStationName());
        assertEquals("青山镇站", snapshot.getNextStationName());
        assertEquals(50, snapshot.getProgress().intValue());
        assertEquals(20.0, snapshot.getSpeedKmh(), 0.01); // 20km / 60min
        // 半程位置：104.05 / 30.05（允许 0.01 度误差，避免用例跨分钟抖动）
        assertEquals(104.05, snapshot.getLongitude(), 0.01);
        assertEquals(30.05, snapshot.getLatitude(), 0.01);
    }

    @Test
    void compute_beforeDeparture_parksAtFirstStation() {
        LocalTime now = LocalTime.now();
        ShiftDO shift = ShiftDO.builder().id(1L).shiftCode("SH001").routeId(1L)
                .plannedDepartureTime(now.plusMinutes(10)).plannedDurationMinutes(60).build();

        VehicleLocationSnapshot snapshot =
                DeterministicScheduleSimulator.compute(VEHICLE, shift, ROUTE, ROUTE_STATIONS, STATIONS, now);

        assertNotNull(snapshot);
        assertEquals(0, snapshot.getStatus());
        assertEquals(104.0, snapshot.getLongitude());
        assertEquals(30.0, snapshot.getLatitude());
        assertEquals("县城客运中心", snapshot.getNextStationName());
    }

    @Test
    void compute_afterSchedule_parksAtLastStationWithoutFakeDriving() {
        LocalTime now = LocalTime.now();
        ShiftDO shift = ShiftDO.builder().id(1L).shiftCode("SH001").routeId(1L)
                .plannedDepartureTime(now.minusMinutes(120)).plannedDurationMinutes(60).build();

        VehicleLocationSnapshot snapshot =
                DeterministicScheduleSimulator.compute(VEHICLE, shift, ROUTE, ROUTE_STATIONS, STATIONS, now);

        assertNotNull(snapshot);
        assertEquals(0, snapshot.getStatus()); // 收车空闲，不继续伪造行驶
        assertEquals(100, snapshot.getProgress().intValue());
        assertEquals(104.1, snapshot.getLongitude());
        assertEquals(30.1, snapshot.getLatitude());
        assertNull(snapshot.getNextStationName());
    }

    @Test
    void compute_withoutRouteOrStations_returnsNull() {
        LocalTime now = LocalTime.now();
        ShiftDO shift = ShiftDO.builder().id(1L).shiftCode("SH001").routeId(1L)
                .plannedDepartureTime(now).plannedDurationMinutes(60).build();
        assertNull(DeterministicScheduleSimulator.compute(VEHICLE, shift, null, ROUTE_STATIONS, STATIONS, now));
        assertNull(DeterministicScheduleSimulator.compute(VEHICLE, shift, ROUTE, List.of(), STATIONS, now));
    }
}
