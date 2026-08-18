package cn.iocoder.yudao.module.transport.service.transport.driver;

import cn.iocoder.yudao.framework.common.enums.UserTypeEnum;
import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.framework.security.core.LoginUser;
import cn.iocoder.yudao.module.member.api.user.MemberUserApi;
import cn.iocoder.yudao.module.member.api.user.dto.MemberUserRespDTO;
import cn.iocoder.yudao.module.transport.controller.app.transport.driver.vo.AppDriverArriveReqVO;
import cn.iocoder.yudao.module.transport.controller.app.transport.driver.vo.AppDriverDepartReqVO;
import cn.iocoder.yudao.module.transport.controller.app.transport.driver.vo.AppDriverLocationReqVO;
import cn.iocoder.yudao.module.transport.controller.app.transport.driver.vo.AppDriverOrderActionReqVO;
import cn.iocoder.yudao.module.transport.controller.app.transport.driver.vo.AppDriverPickupRespVO;
import cn.iocoder.yudao.module.transport.controller.app.transport.driver.vo.AppDriverProfileRespVO;
import cn.iocoder.yudao.module.transport.dal.dataobject.dispatch.DispatchPlanDO;
import cn.iocoder.yudao.module.transport.dal.dataobject.dispatch.DispatchPlanItemDO;
import cn.iocoder.yudao.module.transport.dal.dataobject.driver.DriverDO;
import cn.iocoder.yudao.module.transport.dal.dataobject.driver.DriverVehicleDO;
import cn.iocoder.yudao.module.transport.dal.dataobject.order.PostalOrderDO;
import cn.iocoder.yudao.module.transport.dal.dataobject.order.TransportOrderDO;
import com.baomidou.mybatisplus.core.toolkit.support.SFunction;
import cn.iocoder.yudao.module.transport.dal.dataobject.route.RouteStationDO;
import cn.iocoder.yudao.module.transport.dal.dataobject.shift.ShiftDO;
import cn.iocoder.yudao.module.transport.dal.dataobject.shift.ShiftExecutionDO;
import cn.iocoder.yudao.module.transport.dal.dataobject.vehicle.VehicleDO;
import cn.iocoder.yudao.module.transport.dal.dataobject.vehicle.VehicleLocationDO;
import cn.iocoder.yudao.module.transport.dal.dataobject.vehicle.VehicleLocationTrackDO;
import cn.iocoder.yudao.module.transport.dal.mysql.dispatch.DispatchPlanItemMapper;
import cn.iocoder.yudao.module.transport.dal.mysql.dispatch.DispatchPlanMapper;
import cn.iocoder.yudao.module.transport.dal.mysql.driver.DriverMapper;
import cn.iocoder.yudao.module.transport.dal.mysql.driver.DriverVehicleMapper;
import cn.iocoder.yudao.module.transport.dal.mysql.order.CargoOrderMapper;
import cn.iocoder.yudao.module.transport.dal.mysql.order.PostalOrderMapper;
import cn.iocoder.yudao.module.transport.dal.mysql.order.TransportOrderMapper;
import cn.iocoder.yudao.module.transport.dal.mysql.route.RouteMapper;
import cn.iocoder.yudao.module.transport.dal.mysql.route.RouteStationMapper;
import cn.iocoder.yudao.module.transport.dal.mysql.shift.ShiftExecutionMapper;
import cn.iocoder.yudao.module.transport.dal.mysql.shift.ShiftMapper;
import cn.iocoder.yudao.module.transport.dal.mysql.station.StationMapper;
import cn.iocoder.yudao.module.transport.dal.mysql.vehicle.VehicleLocationMapper;
import cn.iocoder.yudao.module.transport.dal.mysql.vehicle.VehicleLocationTrackMapper;
import cn.iocoder.yudao.module.transport.dal.mysql.vehicle.VehicleMapper;
import cn.iocoder.yudao.module.transport.enums.dispatch.TransportOrderStatusEnum;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static cn.iocoder.yudao.module.transport.enums.ErrorCodeConstants.DRIVER_CARGO_FULL;
import static cn.iocoder.yudao.module.transport.enums.ErrorCodeConstants.DRIVER_IDENTITY_MISMATCH;
import static cn.iocoder.yudao.module.transport.enums.ErrorCodeConstants.DRIVER_NOT_FOUND;
import static cn.iocoder.yudao.module.transport.enums.ErrorCodeConstants.DRIVER_ORDER_NOT_ASSIGNED;
import static cn.iocoder.yudao.module.transport.enums.ErrorCodeConstants.DRIVER_ORDER_STATUS_ILLEGAL;
import static cn.iocoder.yudao.module.transport.enums.ErrorCodeConstants.DRIVER_CARGO_PHOTO_REQUIRED;
import static cn.iocoder.yudao.module.transport.enums.ErrorCodeConstants.DRIVER_STATION_NOT_IN_ROUTE;
import static cn.iocoder.yudao.module.transport.enums.ErrorCodeConstants.DRIVER_STATION_ORDER_ILLEGAL;
import static cn.iocoder.yudao.module.transport.enums.ErrorCodeConstants.POSTAL_ALREADY_PICKED;
import static cn.iocoder.yudao.module.transport.enums.ErrorCodeConstants.POSTAL_PICKUP_CODE_INVALID;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * 司机端写操作闭环纯 Mockito 单测：发车、到站、确认装车、确认送达、位置上报。
 * 身份一律从登录态解析（member_user.mobile → transport_driver.mobile），客户端 driverId 仅做一致性校验。
 */
@ExtendWith(MockitoExtension.class)
class DriverAppServiceImplTest {

    private static final Long MEMBER_ID = 100L;
    private static final String DRIVER_MOBILE = "13800138001";
    private static final Long DRIVER_ID = 1L;

    @Mock private DriverMapper driverMapper;
    @Mock private DriverVehicleMapper driverVehicleMapper;
    @Mock private VehicleMapper vehicleMapper;
    @Mock private ShiftMapper shiftMapper;
    @Mock private RouteMapper routeMapper;
    @Mock private RouteStationMapper routeStationMapper;
    @Mock private StationMapper stationMapper;
    @Mock private TransportOrderMapper transportOrderMapper;
    @Mock private CargoOrderMapper cargoOrderMapper;
    @Mock private PostalOrderMapper postalOrderMapper;
    @Mock private DispatchPlanItemMapper dispatchPlanItemMapper;
    @Mock private DispatchPlanMapper dispatchPlanMapper;
    @Mock private ShiftExecutionMapper shiftExecutionMapper;
    @Mock private VehicleLocationMapper vehicleLocationMapper;
    @Mock private VehicleLocationTrackMapper vehicleLocationTrackMapper;
    @Mock private MemberUserApi memberUserApi;

    private DriverAppServiceImpl driverAppService;

    @BeforeEach
    void setUp() {
        driverAppService = new DriverAppServiceImpl();
        ReflectionTestUtils.setField(driverAppService, "driverMapper", driverMapper);
        ReflectionTestUtils.setField(driverAppService, "driverVehicleMapper", driverVehicleMapper);
        ReflectionTestUtils.setField(driverAppService, "vehicleMapper", vehicleMapper);
        ReflectionTestUtils.setField(driverAppService, "shiftMapper", shiftMapper);
        ReflectionTestUtils.setField(driverAppService, "routeMapper", routeMapper);
        ReflectionTestUtils.setField(driverAppService, "routeStationMapper", routeStationMapper);
        ReflectionTestUtils.setField(driverAppService, "stationMapper", stationMapper);
        ReflectionTestUtils.setField(driverAppService, "transportOrderMapper", transportOrderMapper);
        ReflectionTestUtils.setField(driverAppService, "cargoOrderMapper", cargoOrderMapper);
        ReflectionTestUtils.setField(driverAppService, "postalOrderMapper", postalOrderMapper);
        ReflectionTestUtils.setField(driverAppService, "dispatchPlanItemMapper", dispatchPlanItemMapper);
        ReflectionTestUtils.setField(driverAppService, "dispatchPlanMapper", dispatchPlanMapper);
        ReflectionTestUtils.setField(driverAppService, "shiftExecutionMapper", shiftExecutionMapper);
        ReflectionTestUtils.setField(driverAppService, "vehicleLocationMapper", vehicleLocationMapper);
        ReflectionTestUtils.setField(driverAppService, "vehicleLocationTrackMapper", vehicleLocationTrackMapper);
        ReflectionTestUtils.setField(driverAppService, "memberUserApi", memberUserApi);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    // ==================== 工具 ====================

    /** 模拟会员登录态（member 用户类型，id = MEMBER_ID） */
    private void loginMember() {
        LoginUser loginUser = new LoginUser();
        loginUser.setId(MEMBER_ID);
        loginUser.setUserType(UserTypeEnum.MEMBER.getValue());
        loginUser.setTenantId(0L);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(loginUser, null, java.util.Collections.emptyList()));
    }

    /** 登录会员匹配到司机档案（memberUserApi 取手机号 → driverMapper 按手机号匹配） */
    private void stubLoginDriver() {
        MemberUserRespDTO user = new MemberUserRespDTO();
        user.setId(MEMBER_ID);
        user.setMobile(DRIVER_MOBILE);
        when(memberUserApi.getUser(MEMBER_ID)).thenReturn(user);
        when(driverMapper.selectByMobile(DRIVER_MOBILE)).thenReturn(DriverDO.builder()
                .id(DRIVER_ID).name("张建国").mobile(DRIVER_MOBILE).build());
    }

    /** 该司机名下已下发/执行中方案（status=2）的一条派单明细 */
    private void stubAssignedPlanItem(Long orderId, Long shiftId) {
        when(dispatchPlanItemMapper.selectList(any())).thenReturn(List.of(
                DispatchPlanItemDO.builder().planId(100L).driverId(DRIVER_ID).shiftId(shiftId).orderId(orderId).build()));
        when(dispatchPlanMapper.selectList(any())).thenReturn(List.of(
                DispatchPlanDO.builder().id(100L).status(2).build()));
    }

    private ShiftExecutionDO todayExecution(Integer loadedCount) {
        return ShiftExecutionDO.builder()
                .id(50L).shiftId(10L).driverId(DRIVER_ID).vehicleId(7L)
                .execDate(LocalDate.now()).status(0).loadedCount(loadedCount).build();
    }

    // ==================== 发车 ====================

    @Test
    void depart_creates_execution_and_departs_cargo_orders() {
        loginMember();
        stubLoginDriver();
        when(shiftMapper.selectById(10L)).thenReturn(ShiftDO.builder().id(10L).routeId(5L).build());
        when(driverVehicleMapper.selectActiveBindings()).thenReturn(List.of(
                DriverVehicleDO.builder().driverId(DRIVER_ID).vehicleId(7L).status(1).build()));
        // 已下发/执行中派单明细 → 订单 1000
        when(dispatchPlanItemMapper.selectListByDriverId(DRIVER_ID)).thenReturn(List.of(
                DispatchPlanItemDO.builder().planId(100L).driverId(DRIVER_ID).orderId(1000L).build()));
        when(dispatchPlanMapper.selectList(any())).thenReturn(List.of(
                DispatchPlanDO.builder().id(100L).status(2).build()));

        AppDriverDepartReqVO reqVO = new AppDriverDepartReqVO();
        reqVO.setDriverId(DRIVER_ID);
        reqVO.setShiftId(10L);
        driverAppService.depart(reqVO);

        // 创建当天执行记录：在途、绑定车辆、当天日期、发车时间
        ArgumentCaptor<ShiftExecutionDO> execCaptor = ArgumentCaptor.forClass(ShiftExecutionDO.class);
        verify(shiftExecutionMapper).insert(execCaptor.capture());
        assertEquals(10L, execCaptor.getValue().getShiftId());
        assertEquals(DRIVER_ID, execCaptor.getValue().getDriverId());
        assertEquals(7L, execCaptor.getValue().getVehicleId());
        assertEquals(LocalDate.now(), execCaptor.getValue().getExecDate());
        assertNotNull(execCaptor.getValue().getDepartTime());
        assertEquals(0, execCaptor.getValue().getStatus());
        // 该司机名下已下发/执行中派单的已分配货运订单推进为已发车
        ArgumentCaptor<TransportOrderDO> orderCaptor = ArgumentCaptor.forClass(TransportOrderDO.class);
        verify(transportOrderMapper).update(orderCaptor.capture(), any());
        assertEquals(TransportOrderStatusEnum.DEPARTED.getStatus(), orderCaptor.getValue().getStatus());
    }

    @Test
    void depart_login_member_not_driver_throws() {
        loginMember();
        when(memberUserApi.getUser(MEMBER_ID)).thenReturn(null); // 会员不存在/非司机

        AppDriverDepartReqVO reqVO = new AppDriverDepartReqVO();
        reqVO.setDriverId(DRIVER_ID);
        reqVO.setShiftId(10L);
        ServiceException ex = assertThrows(ServiceException.class, () -> driverAppService.depart(reqVO));

        assertEquals(DRIVER_NOT_FOUND.getCode(), ex.getCode());
        verifyNoInteractions(shiftExecutionMapper, transportOrderMapper);
    }

    @Test
    void depart_driverId_mismatch_throws() {
        loginMember();
        stubLoginDriver();

        AppDriverDepartReqVO reqVO = new AppDriverDepartReqVO();
        reqVO.setDriverId(99L); // 与登录态解析的司机不一致
        reqVO.setShiftId(10L);
        ServiceException ex = assertThrows(ServiceException.class, () -> driverAppService.depart(reqVO));

        assertEquals(DRIVER_IDENTITY_MISMATCH.getCode(), ex.getCode());
        verifyNoInteractions(shiftMapper, shiftExecutionMapper);
    }

    // ==================== 到站 ====================

    @Test
    void arrive_mid_station_updates_current_station() {
        loginMember();
        stubLoginDriver();
        when(shiftMapper.selectById(10L)).thenReturn(ShiftDO.builder().id(10L).routeId(5L).build());
        when(shiftExecutionMapper.selectByShiftAndDriverAndDate(10L, DRIVER_ID, LocalDate.now()))
                .thenReturn(todayExecution(0));
        when(routeStationMapper.selectListByRouteIds(List.of(5L))).thenReturn(List.of(
                RouteStationDO.builder().routeId(5L).stationId(11L).sequenceNo(1).build(),
                RouteStationDO.builder().routeId(5L).stationId(22L).sequenceNo(2).build()));

        AppDriverArriveReqVO reqVO = new AppDriverArriveReqVO();
        reqVO.setDriverId(DRIVER_ID);
        reqVO.setShiftId(10L);
        reqVO.setStationId(11L);
        driverAppService.arrive(reqVO);

        // 中途站：仅更新当前站点，仍在途、无到达时间
        ArgumentCaptor<ShiftExecutionDO> captor = ArgumentCaptor.forClass(ShiftExecutionDO.class);
        verify(shiftExecutionMapper).updateById(captor.capture());
        assertEquals(11L, captor.getValue().getCurrentStationId());
        assertEquals(0, captor.getValue().getStatus());
        assertNull(captor.getValue().getArriveTime());
    }

    @Test
    void arrive_terminal_station_completes_execution() {
        loginMember();
        stubLoginDriver();
        when(shiftMapper.selectById(10L)).thenReturn(ShiftDO.builder().id(10L).routeId(5L).build());
        when(shiftExecutionMapper.selectByShiftAndDriverAndDate(10L, DRIVER_ID, LocalDate.now()))
                .thenReturn(todayExecution(0));
        when(routeStationMapper.selectListByRouteIds(List.of(5L))).thenReturn(List.of(
                RouteStationDO.builder().routeId(5L).stationId(11L).sequenceNo(1).build(),
                RouteStationDO.builder().routeId(5L).stationId(22L).sequenceNo(2).build()));

        // 防跳站：需按 sequence 顺序到站，先到中途站 11(seq1)，再到终点 22(seq2)
        AppDriverArriveReqVO mid = new AppDriverArriveReqVO();
        mid.setDriverId(DRIVER_ID);
        mid.setShiftId(10L);
        mid.setStationId(11L);
        driverAppService.arrive(mid);
        AppDriverArriveReqVO terminal = new AppDriverArriveReqVO();
        terminal.setDriverId(DRIVER_ID);
        terminal.setShiftId(10L);
        terminal.setStationId(22L); // 最大 sequence_no 的终点站
        driverAppService.arrive(terminal);

        // 终点站：写到达时间，执行记录置已完成
        ArgumentCaptor<ShiftExecutionDO> captor = ArgumentCaptor.forClass(ShiftExecutionDO.class);
        verify(shiftExecutionMapper, times(2)).updateById(captor.capture());
        ShiftExecutionDO finalUpdate = captor.getAllValues().get(1);
        assertEquals(22L, finalUpdate.getCurrentStationId());
        assertEquals(1, finalUpdate.getStatus());
        assertNotNull(finalUpdate.getArriveTime());
    }

    @Test
    void arrive_skip_station_throws() {
        loginMember();
        stubLoginDriver();
        when(shiftMapper.selectById(10L)).thenReturn(ShiftDO.builder().id(10L).routeId(5L).build());
        when(shiftExecutionMapper.selectByShiftAndDriverAndDate(10L, DRIVER_ID, LocalDate.now()))
                .thenReturn(todayExecution(0)); // currentStationId 为空，当前应为首站(seq 1)
        when(routeStationMapper.selectListByRouteIds(List.of(5L))).thenReturn(List.of(
                RouteStationDO.builder().routeId(5L).stationId(11L).sequenceNo(1).build(),
                RouteStationDO.builder().routeId(5L).stationId(22L).sequenceNo(2).build()));

        AppDriverArriveReqVO reqVO = new AppDriverArriveReqVO();
        reqVO.setDriverId(DRIVER_ID);
        reqVO.setShiftId(10L);
        reqVO.setStationId(22L); // 跳过首站直达终点
        ServiceException ex = assertThrows(ServiceException.class, () -> driverAppService.arrive(reqVO));

        assertEquals(DRIVER_STATION_ORDER_ILLEGAL.getCode(), ex.getCode());
        verify(shiftExecutionMapper, never()).updateById(any(ShiftExecutionDO.class));
    }

    @Test
    void arrive_station_not_in_route_throws() {
        loginMember();
        stubLoginDriver();
        when(shiftMapper.selectById(10L)).thenReturn(ShiftDO.builder().id(10L).routeId(5L).build());
        when(shiftExecutionMapper.selectByShiftAndDriverAndDate(10L, DRIVER_ID, LocalDate.now()))
                .thenReturn(todayExecution(0));
        when(routeStationMapper.selectListByRouteIds(List.of(5L))).thenReturn(List.of(
                RouteStationDO.builder().routeId(5L).stationId(11L).sequenceNo(1).build()));

        AppDriverArriveReqVO reqVO = new AppDriverArriveReqVO();
        reqVO.setDriverId(DRIVER_ID);
        reqVO.setShiftId(10L);
        reqVO.setStationId(99L); // 不属于该线路
        ServiceException ex = assertThrows(ServiceException.class, () -> driverAppService.arrive(reqVO));

        assertEquals(DRIVER_STATION_NOT_IN_ROUTE.getCode(), ex.getCode());
        verify(shiftExecutionMapper, never()).updateById(any(ShiftExecutionDO.class));
    }

    // ==================== 装车 ====================

    @Test
    void pickupConfirm_pooled_order_departs_and_increments_loaded() {
        loginMember();
        stubLoginDriver();
        when(transportOrderMapper.selectById(1000L)).thenReturn(TransportOrderDO.builder()
                .id(1000L).orderType(2).status(TransportOrderStatusEnum.POOLED.getStatus()).build());
        stubAssignedPlanItem(1000L, 10L);
        when(shiftExecutionMapper.selectByShiftAndDriverAndDate(10L, DRIVER_ID, LocalDate.now()))
                .thenReturn(todayExecution(0));
        when(vehicleMapper.selectById(7L)).thenReturn(VehicleDO.builder().id(7L).cargoCapacity(4).build());
        when(transportOrderMapper.update(any(), any())).thenReturn(1);

        AppDriverOrderActionReqVO reqVO = new AppDriverOrderActionReqVO();
        reqVO.setDriverId(DRIVER_ID);
        reqVO.setOrderId(1000L);
        reqVO.setDriverPhotoUrl("http://example.com/photo.jpg"); // 货运强制收件照片
        driverAppService.pickupConfirm(reqVO);

        // 订单状态推进已发车 + 执行记录已装件数 +1
        ArgumentCaptor<ShiftExecutionDO> loadedCaptor = ArgumentCaptor.forClass(ShiftExecutionDO.class);
        verify(shiftExecutionMapper).updateById(loadedCaptor.capture());
        assertEquals(1, loadedCaptor.getValue().getLoadedCount());
    }

    @Test
    void pickupConfirm_departed_order_allowed() {
        loginMember();
        stubLoginDriver();
        // depart 已把司机名下已分配订单推进为已发车(3)，真实流程「先发车→到站扫码装车」必须放行已发车
        when(transportOrderMapper.selectById(1000L)).thenReturn(TransportOrderDO.builder()
                .id(1000L).orderType(2).status(TransportOrderStatusEnum.DEPARTED.getStatus()).build());
        stubAssignedPlanItem(1000L, 10L);
        when(shiftExecutionMapper.selectByShiftAndDriverAndDate(10L, DRIVER_ID, LocalDate.now()))
                .thenReturn(todayExecution(0));
        when(vehicleMapper.selectById(7L)).thenReturn(VehicleDO.builder().id(7L).cargoCapacity(4).build());
        when(transportOrderMapper.update(any(), any())).thenReturn(1);

        AppDriverOrderActionReqVO reqVO = new AppDriverOrderActionReqVO();
        reqVO.setDriverId(DRIVER_ID);
        reqVO.setOrderId(1000L);
        reqVO.setDriverPhotoUrl("http://example.com/photo.jpg"); // 货运强制收件照片
        driverAppService.pickupConfirm(reqVO);

        // 已发车订单装车后仍保持已发车（loaded 记录 +1），deliver 仍可 3→4
        ArgumentCaptor<ShiftExecutionDO> loadedCaptor = ArgumentCaptor.forClass(ShiftExecutionDO.class);
        verify(shiftExecutionMapper).updateById(loadedCaptor.capture());
        assertEquals(1, loadedCaptor.getValue().getLoadedCount());
    }

    @Test
    void pickupConfirm_completed_order_throws() {
        loginMember();
        stubLoginDriver();
        when(transportOrderMapper.selectById(1000L)).thenReturn(TransportOrderDO.builder()
                .id(1000L).orderType(2).status(TransportOrderStatusEnum.COMPLETED.getStatus()).build());

        AppDriverOrderActionReqVO reqVO = new AppDriverOrderActionReqVO();
        reqVO.setDriverId(DRIVER_ID);
        reqVO.setOrderId(1000L);
        ServiceException ex = assertThrows(ServiceException.class, () -> driverAppService.pickupConfirm(reqVO));

        assertEquals(DRIVER_ORDER_STATUS_ILLEGAL.getCode(), ex.getCode());
        verify(transportOrderMapper, never()).update(any(), any());
        verify(shiftExecutionMapper, never()).updateById(any(ShiftExecutionDO.class));
    }

    @Test
    void pickupConfirm_not_assigned_order_throws() {
        loginMember();
        stubLoginDriver();
        when(transportOrderMapper.selectById(1000L)).thenReturn(TransportOrderDO.builder()
                .id(1000L).orderType(2).status(TransportOrderStatusEnum.POOLED.getStatus()).build());
        when(dispatchPlanItemMapper.selectList(any())).thenReturn(List.of()); // 非本司机派单

        AppDriverOrderActionReqVO reqVO = new AppDriverOrderActionReqVO();
        reqVO.setDriverId(DRIVER_ID);
        reqVO.setOrderId(1000L);
        ServiceException ex = assertThrows(ServiceException.class, () -> driverAppService.pickupConfirm(reqVO));

        assertEquals(DRIVER_ORDER_NOT_ASSIGNED.getCode(), ex.getCode());
        verify(transportOrderMapper, never()).update(any(), any());
    }

    @Test
    void pickupConfirm_cargo_full_throws() {
        loginMember();
        stubLoginDriver();
        when(transportOrderMapper.selectById(1000L)).thenReturn(TransportOrderDO.builder()
                .id(1000L).orderType(2).status(TransportOrderStatusEnum.POOLED.getStatus()).build());
        stubAssignedPlanItem(1000L, 10L);
        when(shiftExecutionMapper.selectByShiftAndDriverAndDate(10L, DRIVER_ID, LocalDate.now()))
                .thenReturn(todayExecution(4)); // 已装 4 件
        when(vehicleMapper.selectById(7L)).thenReturn(VehicleDO.builder().id(7L).cargoCapacity(4).build());

        AppDriverOrderActionReqVO reqVO = new AppDriverOrderActionReqVO();
        reqVO.setDriverId(DRIVER_ID);
        reqVO.setOrderId(1000L);
        reqVO.setDriverPhotoUrl("http://example.com/photo.jpg"); // 货运强制收件照片
        ServiceException ex = assertThrows(ServiceException.class, () -> driverAppService.pickupConfirm(reqVO));

        assertEquals(DRIVER_CARGO_FULL.getCode(), ex.getCode());
        verify(transportOrderMapper, never()).update(any(), any());
        verify(shiftExecutionMapper, never()).updateById(any(ShiftExecutionDO.class));
    }

    @Test
    void pickupConfirm_cas_zero_rows_throws() {
        loginMember();
        stubLoginDriver();
        when(transportOrderMapper.selectById(1000L)).thenReturn(TransportOrderDO.builder()
                .id(1000L).orderType(2).status(TransportOrderStatusEnum.POOLED.getStatus()).build());
        stubAssignedPlanItem(1000L, 10L);
        when(shiftExecutionMapper.selectByShiftAndDriverAndDate(10L, DRIVER_ID, LocalDate.now()))
                .thenReturn(todayExecution(0));
        when(vehicleMapper.selectById(7L)).thenReturn(VehicleDO.builder().id(7L).cargoCapacity(4).build());
        when(transportOrderMapper.update(any(), any())).thenReturn(0); // 并发下状态已被推进

        AppDriverOrderActionReqVO reqVO = new AppDriverOrderActionReqVO();
        reqVO.setDriverId(DRIVER_ID);
        reqVO.setOrderId(1000L);
        reqVO.setDriverPhotoUrl("http://example.com/photo.jpg"); // 货运强制收件照片
        ServiceException ex = assertThrows(ServiceException.class, () -> driverAppService.pickupConfirm(reqVO));

        assertEquals(DRIVER_ORDER_STATUS_ILLEGAL.getCode(), ex.getCode());
        verify(shiftExecutionMapper, never()).updateById(any(ShiftExecutionDO.class));
    }

    @Test
    void pickupConfirm_cargo_missing_photo_throws() {
        loginMember();
        stubLoginDriver();
        when(transportOrderMapper.selectById(1000L)).thenReturn(TransportOrderDO.builder()
                .id(1000L).orderType(2).status(TransportOrderStatusEnum.POOLED.getStatus()).build());
        stubAssignedPlanItem(1000L, 10L);

        AppDriverOrderActionReqVO reqVO = new AppDriverOrderActionReqVO();
        reqVO.setDriverId(DRIVER_ID);
        reqVO.setOrderId(1000L);
        // 不传 driverPhotoUrl：货运散件强制收件照片，缺照片不能装车
        ServiceException ex = assertThrows(ServiceException.class, () -> driverAppService.pickupConfirm(reqVO));

        assertEquals(DRIVER_CARGO_PHOTO_REQUIRED.getCode(), ex.getCode());
        verify(transportOrderMapper, never()).update(any(), any());
    }

    // ==================== 取件核销（邮快件） ====================

    @Test
    void pickupVerify_postal_order_success() {
        loginMember();
        stubLoginDriver();
        when(transportOrderMapper.selectById(1000L)).thenReturn(TransportOrderDO.builder()
                .id(1000L).orderType(3).status(TransportOrderStatusEnum.DEPARTED.getStatus()).build());
        stubAssignedPlanItem(1000L, 10L);
        when(postalOrderMapper.selectOne(any(SFunction.class), any())).thenReturn(
                PostalOrderDO.builder().id(9L).orderId(1000L).pickupCode("123456").pickupStatus(0).build());
        when(transportOrderMapper.update(any(), any())).thenReturn(1);

        AppDriverOrderActionReqVO reqVO = new AppDriverOrderActionReqVO();
        reqVO.setDriverId(DRIVER_ID);
        reqVO.setOrderId(1000L);
        reqVO.setPickupCode("123456");
        driverAppService.pickupVerify(reqVO);

        // 主表已发车→已完成 + 子表已取件
        ArgumentCaptor<TransportOrderDO> orderCaptor = ArgumentCaptor.forClass(TransportOrderDO.class);
        verify(transportOrderMapper).update(orderCaptor.capture(), any());
        assertEquals(TransportOrderStatusEnum.COMPLETED.getStatus(), orderCaptor.getValue().getStatus());
        ArgumentCaptor<PostalOrderDO> postalCaptor = ArgumentCaptor.forClass(PostalOrderDO.class);
        verify(postalOrderMapper).updateById(postalCaptor.capture());
        assertEquals(1, postalCaptor.getValue().getPickupStatus());
    }

    @Test
    void pickupVerify_decrements_loaded_count() {
        loginMember();
        stubLoginDriver();
        when(transportOrderMapper.selectById(1000L)).thenReturn(TransportOrderDO.builder()
                .id(1000L).orderType(3).status(TransportOrderStatusEnum.DEPARTED.getStatus()).build());
        stubAssignedPlanItem(1000L, 10L);
        when(postalOrderMapper.selectOne(any(SFunction.class), any())).thenReturn(
                PostalOrderDO.builder().id(9L).orderId(1000L).pickupCode("123456").pickupStatus(0).build());
        when(transportOrderMapper.update(any(), any())).thenReturn(1);
        when(shiftExecutionMapper.selectByShiftAndDriverAndDate(10L, DRIVER_ID, LocalDate.now()))
                .thenReturn(todayExecution(2)); // 已装 2 件

        AppDriverOrderActionReqVO reqVO = new AppDriverOrderActionReqVO();
        reqVO.setDriverId(DRIVER_ID);
        reqVO.setOrderId(1000L);
        reqVO.setPickupCode("123456");
        driverAppService.pickupVerify(reqVO);

        // 核销成功后已装件数 -1（与 deliver 回减口径一致）
        ArgumentCaptor<ShiftExecutionDO> loadedCaptor = ArgumentCaptor.forClass(ShiftExecutionDO.class);
        verify(shiftExecutionMapper).updateById(loadedCaptor.capture());
        assertEquals(1, loadedCaptor.getValue().getLoadedCount());
    }

    @Test
    void pickupVerify_wrong_code_throws() {
        loginMember();
        stubLoginDriver();
        when(transportOrderMapper.selectById(1000L)).thenReturn(TransportOrderDO.builder()
                .id(1000L).orderType(3).status(TransportOrderStatusEnum.DEPARTED.getStatus()).build());
        stubAssignedPlanItem(1000L, 10L);
        when(postalOrderMapper.selectOne(any(SFunction.class), any())).thenReturn(
                PostalOrderDO.builder().id(9L).orderId(1000L).pickupCode("123456").pickupStatus(0).build());

        AppDriverOrderActionReqVO reqVO = new AppDriverOrderActionReqVO();
        reqVO.setDriverId(DRIVER_ID);
        reqVO.setOrderId(1000L);
        reqVO.setPickupCode("999999"); // 错误取件码
        ServiceException ex = assertThrows(ServiceException.class, () -> driverAppService.pickupVerify(reqVO));

        assertEquals(POSTAL_PICKUP_CODE_INVALID.getCode(), ex.getCode());
        verify(transportOrderMapper, never()).update(any(), any());
        verify(postalOrderMapper, never()).updateById(any(PostalOrderDO.class));
    }

    @Test
    void pickupVerify_already_picked_throws() {
        loginMember();
        stubLoginDriver();
        when(transportOrderMapper.selectById(1000L)).thenReturn(TransportOrderDO.builder()
                .id(1000L).orderType(3).status(TransportOrderStatusEnum.DEPARTED.getStatus()).build());
        stubAssignedPlanItem(1000L, 10L);
        when(postalOrderMapper.selectOne(any(SFunction.class), any())).thenReturn(
                PostalOrderDO.builder().id(9L).orderId(1000L).pickupCode("123456").pickupStatus(1).build());

        AppDriverOrderActionReqVO reqVO = new AppDriverOrderActionReqVO();
        reqVO.setDriverId(DRIVER_ID);
        reqVO.setOrderId(1000L);
        reqVO.setPickupCode("123456");
        ServiceException ex = assertThrows(ServiceException.class, () -> driverAppService.pickupVerify(reqVO));

        assertEquals(POSTAL_ALREADY_PICKED.getCode(), ex.getCode());
        verify(transportOrderMapper, never()).update(any(), any());
    }

    // ==================== 妥投 ====================

    @Test
    void deliver_departed_order_completes_and_decrements_loaded() {
        loginMember();
        stubLoginDriver();
        when(transportOrderMapper.selectById(1000L)).thenReturn(TransportOrderDO.builder()
                .id(1000L).orderType(2).status(TransportOrderStatusEnum.DEPARTED.getStatus()).build());
        stubAssignedPlanItem(1000L, 10L);
        when(transportOrderMapper.update(any(), any())).thenReturn(1);
        when(shiftExecutionMapper.selectByShiftAndDriverAndDate(10L, DRIVER_ID, LocalDate.now()))
                .thenReturn(todayExecution(2)); // 已装 2 件

        AppDriverOrderActionReqVO reqVO = new AppDriverOrderActionReqVO();
        reqVO.setDriverId(DRIVER_ID);
        reqVO.setOrderId(1000L);
        driverAppService.deliver(reqVO);

        // 订单已完成 + 执行记录已装件数 -1
        ArgumentCaptor<TransportOrderDO> orderCaptor = ArgumentCaptor.forClass(TransportOrderDO.class);
        verify(transportOrderMapper).update(orderCaptor.capture(), any());
        assertEquals(TransportOrderStatusEnum.COMPLETED.getStatus(), orderCaptor.getValue().getStatus());
        ArgumentCaptor<ShiftExecutionDO> loadedCaptor = ArgumentCaptor.forClass(ShiftExecutionDO.class);
        verify(shiftExecutionMapper).updateById(loadedCaptor.capture());
        assertEquals(1, loadedCaptor.getValue().getLoadedCount());
    }

    @Test
    void deliver_postal_order_throws() {
        loginMember();
        stubLoginDriver();
        // 邮快件不走妥投：由收件人凭取件码核销（pickup-verify）
        when(transportOrderMapper.selectById(1000L)).thenReturn(TransportOrderDO.builder()
                .id(1000L).orderType(3).status(TransportOrderStatusEnum.DEPARTED.getStatus()).build());

        AppDriverOrderActionReqVO reqVO = new AppDriverOrderActionReqVO();
        reqVO.setDriverId(DRIVER_ID);
        reqVO.setOrderId(1000L);
        ServiceException ex = assertThrows(ServiceException.class, () -> driverAppService.deliver(reqVO));

        assertEquals(DRIVER_ORDER_STATUS_ILLEGAL.getCode(), ex.getCode());
        verify(transportOrderMapper, never()).update(any(), any());
    }

    @Test
    void deliver_pooled_order_throws() {
        loginMember();
        stubLoginDriver();
        when(transportOrderMapper.selectById(1000L)).thenReturn(TransportOrderDO.builder()
                .id(1000L).orderType(2).status(TransportOrderStatusEnum.POOLED.getStatus()).build());

        AppDriverOrderActionReqVO reqVO = new AppDriverOrderActionReqVO();
        reqVO.setDriverId(DRIVER_ID);
        reqVO.setOrderId(1000L);
        ServiceException ex = assertThrows(ServiceException.class, () -> driverAppService.deliver(reqVO));

        assertEquals(DRIVER_ORDER_STATUS_ILLEGAL.getCode(), ex.getCode());
        verify(transportOrderMapper, never()).update(any(), any());
    }

    @Test
    void deliver_not_assigned_order_throws() {
        loginMember();
        stubLoginDriver();
        when(transportOrderMapper.selectById(1000L)).thenReturn(TransportOrderDO.builder()
                .id(1000L).orderType(2).status(TransportOrderStatusEnum.DEPARTED.getStatus()).build());
        when(dispatchPlanItemMapper.selectList(any())).thenReturn(List.of()); // 非本司机派单

        AppDriverOrderActionReqVO reqVO = new AppDriverOrderActionReqVO();
        reqVO.setDriverId(DRIVER_ID);
        reqVO.setOrderId(1000L);
        ServiceException ex = assertThrows(ServiceException.class, () -> driverAppService.deliver(reqVO));

        assertEquals(DRIVER_ORDER_NOT_ASSIGNED.getCode(), ex.getCode());
        verify(transportOrderMapper, never()).update(any(), any());
    }

    @Test
    void deliver_cas_zero_rows_throws_duplicate() {
        loginMember();
        stubLoginDriver();
        when(transportOrderMapper.selectById(1000L)).thenReturn(TransportOrderDO.builder()
                .id(1000L).orderType(2).status(TransportOrderStatusEnum.DEPARTED.getStatus()).build());
        stubAssignedPlanItem(1000L, 10L);
        when(transportOrderMapper.update(any(), any())).thenReturn(0); // 已妥投/并发重复

        AppDriverOrderActionReqVO reqVO = new AppDriverOrderActionReqVO();
        reqVO.setDriverId(DRIVER_ID);
        reqVO.setOrderId(1000L);
        ServiceException ex = assertThrows(ServiceException.class, () -> driverAppService.deliver(reqVO));

        assertEquals(DRIVER_ORDER_STATUS_ILLEGAL.getCode(), ex.getCode());
        verify(shiftExecutionMapper, never()).updateById(any(ShiftExecutionDO.class));
    }

    // ==================== 位置上报 ====================

    @Test
    void reportLocation_upserts_by_vehicle() {
        loginMember();
        stubLoginDriver();
        when(driverVehicleMapper.selectActiveBindings()).thenReturn(List.of(
                DriverVehicleDO.builder().driverId(DRIVER_ID).vehicleId(7L).status(1).build()));
        // 首次无位置记录，再次调用命中已有记录
        VehicleLocationDO existing = VehicleLocationDO.builder().id(60L).vehicleId(7L).build();
        when(vehicleLocationMapper.selectByVehicleId(7L)).thenReturn(null, existing);

        AppDriverLocationReqVO reqVO = new AppDriverLocationReqVO();
        reqVO.setDriverId(DRIVER_ID);
        reqVO.setShiftId(10L);
        reqVO.setLongitude(new BigDecimal("120.1234567"));
        reqVO.setLatitude(new BigDecimal("30.1234567"));
        reqVO.setSpeedKmh(new BigDecimal("42.5"));
        driverAppService.reportLocation(reqVO);
        driverAppService.reportLocation(reqVO);

        // 每车一行：首次插入，再次更新，幂等
        ArgumentCaptor<VehicleLocationDO> insertCaptor = ArgumentCaptor.forClass(VehicleLocationDO.class);
        verify(vehicleLocationMapper, times(1)).insert(insertCaptor.capture());
        assertEquals(7L, insertCaptor.getValue().getVehicleId());
        assertEquals(10L, insertCaptor.getValue().getShiftId());
        assertEquals(0, insertCaptor.getValue().getLongitude().compareTo(new BigDecimal("120.1234567")));
        assertNotNull(insertCaptor.getValue().getReportTime());
        ArgumentCaptor<VehicleLocationDO> updateCaptor = ArgumentCaptor.forClass(VehicleLocationDO.class);
        verify(vehicleLocationMapper, times(1)).updateById(updateCaptor.capture());
        assertEquals(60L, updateCaptor.getValue().getId());
        assertNotNull(updateCaptor.getValue().getReportTime());
        // 班次在途（shiftId 非空）：两次上报均落历史轨迹
        verify(vehicleLocationTrackMapper, times(2)).insert(any(VehicleLocationTrackDO.class));
    }

    @Test
    void reportLocation_no_shift_skips_track() {
        loginMember();
        stubLoginDriver();
        when(driverVehicleMapper.selectActiveBindings()).thenReturn(List.of(
                DriverVehicleDO.builder().driverId(DRIVER_ID).vehicleId(7L).status(1).build()));
        when(vehicleLocationMapper.selectByVehicleId(7L)).thenReturn(null);

        AppDriverLocationReqVO reqVO = new AppDriverLocationReqVO();
        reqVO.setDriverId(DRIVER_ID);
        reqVO.setShiftId(null); // 未在班次中
        reqVO.setLongitude(new BigDecimal("120.1234567"));
        reqVO.setLatitude(new BigDecimal("30.1234567"));
        driverAppService.reportLocation(reqVO);

        verify(vehicleLocationMapper, times(1)).insert(any(VehicleLocationDO.class));
        // 无班次：不落历史轨迹
        verify(vehicleLocationTrackMapper, never()).insert(any(VehicleLocationTrackDO.class));
    }

    // ==================== 档案 / 待装车（登录态） ====================

    @Test
    void profile_resolves_from_login() {
        loginMember();
        stubLoginDriver();
        when(driverVehicleMapper.selectActiveBindings()).thenReturn(List.of(
                DriverVehicleDO.builder().driverId(DRIVER_ID).vehicleId(7L).status(1).build()));
        when(vehicleMapper.selectById(7L)).thenReturn(VehicleDO.builder().id(7L).plateNo("川A·1").cargoCapacity(4).build());

        AppDriverProfileRespVO vo = driverAppService.profile();

        assertNotNull(vo);
        assertEquals(DRIVER_ID, vo.getDriverId());
        assertEquals("川A·1", vo.getPlateNo());
    }

    @Test
    void profile_login_not_driver_returns_null() {
        loginMember();
        when(memberUserApi.getUser(MEMBER_ID)).thenReturn(null);

        assertNull(driverAppService.profile());
    }

    @Test
    void pickups_filters_by_driver_issued_plan() {
        loginMember();
        stubLoginDriver();
        // 司机名下已下发/执行中派单明细 → 订单 1000
        when(dispatchPlanItemMapper.selectListByDriverId(DRIVER_ID)).thenReturn(List.of(
                DispatchPlanItemDO.builder().planId(100L).driverId(DRIVER_ID).orderId(1000L).build()));
        when(dispatchPlanMapper.selectList(any())).thenReturn(List.of(
                DispatchPlanDO.builder().id(100L).status(2).build()));
        when(transportOrderMapper.selectList(any())).thenReturn(List.of(
                TransportOrderDO.builder().id(1000L).orderNo("TP1000").orderType(2)
                        .status(TransportOrderStatusEnum.ASSIGNED.getStatus()).build()));

        List<AppDriverPickupRespVO> pickups = driverAppService.pickups();

        assertEquals(1, pickups.size());
        assertEquals(1000L, pickups.get(0).getOrderId());
    }

    @Test
    void pickups_returns_departed_order() {
        loginMember();
        stubLoginDriver();
        // 司机发车(depart)后订单已为已发车(3)，待装车列表仍须可见（否则前端扫码装车无入口）
        when(dispatchPlanItemMapper.selectListByDriverId(DRIVER_ID)).thenReturn(List.of(
                DispatchPlanItemDO.builder().planId(100L).driverId(DRIVER_ID).orderId(1000L).build()));
        when(dispatchPlanMapper.selectList(any())).thenReturn(List.of(
                DispatchPlanDO.builder().id(100L).status(2).build()));
        when(transportOrderMapper.selectList(any())).thenReturn(List.of(
                TransportOrderDO.builder().id(1000L).orderNo("TP1000").orderType(2)
                        .status(TransportOrderStatusEnum.DEPARTED.getStatus()).build()));

        List<AppDriverPickupRespVO> pickups = driverAppService.pickups();

        assertEquals(1, pickups.size());
        assertEquals(1000L, pickups.get(0).getOrderId());
    }

    @Test
    void pickups_no_driver_returns_empty() {
        loginMember();
        when(memberUserApi.getUser(MEMBER_ID)).thenReturn(null);

        assertTrue(driverAppService.pickups().isEmpty());
    }
}
