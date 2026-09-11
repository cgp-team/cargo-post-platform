package cn.iocoder.yudao.module.transport.service.transport.driver;

import cn.iocoder.yudao.framework.common.enums.UserTypeEnum;
import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.framework.security.core.LoginUser;
import cn.iocoder.yudao.module.member.api.user.MemberUserApi;
import cn.iocoder.yudao.module.member.api.user.dto.MemberUserRespDTO;
import cn.iocoder.yudao.module.transport.controller.app.transport.driver.vo.AppDriverLegActionReqVO;
import cn.iocoder.yudao.module.transport.dal.dataobject.dispatch.TransportLegDO;
import cn.iocoder.yudao.module.transport.dal.dataobject.driver.DriverDO;
import cn.iocoder.yudao.module.transport.dal.mysql.dispatch.TransportLegMapper;
import cn.iocoder.yudao.module.transport.dal.mysql.driver.DriverMapper;
import cn.iocoder.yudao.module.transport.dal.mysql.station.StationMapper;
import cn.iocoder.yudao.module.transport.dal.mysql.vehicle.VehicleMapper;
import cn.iocoder.yudao.module.transport.dal.mysql.order.TransportOrderMapper;
import cn.iocoder.yudao.module.transport.enums.dispatch.TransportLegStatusEnum;
import cn.iocoder.yudao.module.transport.service.dispatch.HandoverService;
import cn.iocoder.yudao.module.transport.service.dispatch.MultiLegService;
import cn.iocoder.yudao.module.transport.service.notification.UserNotificationService;
import cn.iocoder.yudao.module.transport.service.order.OrderEventService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * 司机运输段任务单测（需求 §55~§58/§72）：
 * 司机只能看到/操作**自己的**运输段，别人的段返回 DRIVER_ORDER_NOT_ASSIGNED / LEG_NOT_ASSIGNED。
 */
@ExtendWith(MockitoExtension.class)
class DriverLegTaskTest {

    private static final Long MEMBER_ID = 100L;
    private static final Long DRIVER_ID = 1L;
    private static final String DRIVER_MOBILE = "13900001001";

    @Mock private DriverMapper driverMapper;
    @Mock private TransportLegMapper transportLegMapper;
    @Mock private MemberUserApi memberUserApi;
    @Mock private StationMapper stationMapper;
    @Mock private VehicleMapper vehicleMapper;
    @Mock private TransportOrderMapper transportOrderMapper;
    @Mock private MultiLegService multiLegService;
    @Mock private HandoverService handoverService;
    @Mock private OrderEventService orderEventService;
    @Mock private UserNotificationService userNotificationService;

    private DriverAppServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new DriverAppServiceImpl();
        ReflectionTestUtils.setField(service, "driverMapper", driverMapper);
        ReflectionTestUtils.setField(service, "transportLegMapper", transportLegMapper);
        ReflectionTestUtils.setField(service, "memberUserApi", memberUserApi);
        ReflectionTestUtils.setField(service, "stationMapper", stationMapper);
        ReflectionTestUtils.setField(service, "vehicleMapper", vehicleMapper);
        ReflectionTestUtils.setField(service, "transportOrderMapper", transportOrderMapper);
        ReflectionTestUtils.setField(service, "multiLegService", multiLegService);
        ReflectionTestUtils.setField(service, "handoverService", handoverService);
        ReflectionTestUtils.setField(service, "orderEventService", orderEventService);
        ReflectionTestUtils.setField(service, "userNotificationService", userNotificationService);
        login();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private void login() {
        LoginUser loginUser = new LoginUser();
        loginUser.setId(MEMBER_ID);
        loginUser.setUserType(UserTypeEnum.MEMBER.getValue());
        loginUser.setTenantId(0L);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(loginUser, null, java.util.Collections.emptyList()));
        MemberUserRespDTO user = new MemberUserRespDTO();
        user.setId(MEMBER_ID);
        user.setMobile(DRIVER_MOBILE);
        when(memberUserApi.getUser(MEMBER_ID)).thenReturn(user);
        when(driverMapper.selectByMobile(DRIVER_MOBILE)).thenReturn(
                DriverDO.builder().id(DRIVER_ID).name("张建国").mobile(DRIVER_MOBILE).build());
    }

    @Test
    void current_leg_returns_own_active_leg_only() {
        when(transportLegMapper.selectActiveByDriverId(DRIVER_ID)).thenReturn(List.of(
                TransportLegDO.builder().id(7L).orderId(100L)
                        .driverId(DRIVER_ID).fromStationId(201L).toStationId(202L).legSequence(1)
                        .vehicleId(11L).status(TransportLegStatusEnum.IN_TRANSIT.getStatus()).build()));
        when(stationMapper.selectBatchIds(any())).thenReturn(List.of());
        when(vehicleMapper.selectBatchIds(any())).thenReturn(List.of());
        when(transportOrderMapper.selectBatchIds(any())).thenReturn(List.of());

        var vo = service.currentLeg(null);

        assertEquals(7L, vo.getId());
        assertEquals(1, vo.getLegSequence());
        verify(transportLegMapper).selectActiveByDriverId(DRIVER_ID);
    }

    @Test
    void cannot_operate_other_drivers_leg() {
        when(multiLegService.getLeg(9L)).thenReturn(TransportLegDO.builder()
                .id(9L).orderId(100L).legSequence(2).driverId(99L)  // 别人的段
                .status(TransportLegStatusEnum.ASSIGNED.getStatus()).build());
        AppDriverLegActionReqVO reqVO = new AppDriverLegActionReqVO();
        reqVO.setLegId(9L);

        assertThrows(ServiceException.class, () -> service.legAction("accept", reqVO));
        verify(multiLegService, never()).advanceLegStatus(any(), any());
    }

    @Test
    void accept_own_leg_advances_to_driver_accepted() {
        when(multiLegService.getLeg(7L)).thenReturn(TransportLegDO.builder()
                .id(7L).orderId(100L).legSequence(1).driverId(DRIVER_ID)
                .status(TransportLegStatusEnum.ASSIGNED.getStatus()).build());
        AppDriverLegActionReqVO reqVO = new AppDriverLegActionReqVO();
        reqVO.setLegId(7L);

        service.legAction("accept", reqVO);

        verify(multiLegService).advanceLegStatus(eq(7L), eq(TransportLegStatusEnum.DRIVER_ACCEPTED));
    }

}
