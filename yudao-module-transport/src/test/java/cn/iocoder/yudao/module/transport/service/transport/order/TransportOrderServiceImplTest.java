package cn.iocoder.yudao.module.transport.service.transport.order;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.module.member.api.user.MemberUserApi;
import cn.iocoder.yudao.module.member.api.user.dto.MemberUserRespDTO;
import cn.iocoder.yudao.module.transport.controller.admin.transport.order.vo.OrderAuditReqVO;
import cn.iocoder.yudao.module.transport.controller.app.transport.send.vo.AppSendOrderCreateReqVO;
import cn.iocoder.yudao.module.transport.dal.dataobject.order.CargoOrderDO;
import cn.iocoder.yudao.module.transport.dal.dataobject.order.PostalOrderDO;
import cn.iocoder.yudao.module.transport.dal.dataobject.order.TransportOrderDO;
import cn.iocoder.yudao.module.transport.dal.dataobject.station.StationDO;
import cn.iocoder.yudao.module.transport.dal.mysql.order.CargoOrderMapper;
import cn.iocoder.yudao.module.transport.dal.mysql.order.PassengerOrderMapper;
import cn.iocoder.yudao.module.transport.dal.mysql.order.PostalOrderMapper;
import cn.iocoder.yudao.module.transport.dal.mysql.order.TransportOrderMapper;
import cn.iocoder.yudao.module.transport.dal.mysql.station.StationMapper;
import cn.iocoder.yudao.module.transport.enums.dispatch.TransportOrderStatusEnum;
import com.baomidou.mybatisplus.core.toolkit.support.SFunction;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;

import static cn.iocoder.yudao.framework.common.exception.enums.GlobalErrorCodeConstants.BAD_REQUEST;
import static cn.iocoder.yudao.module.transport.enums.ErrorCodeConstants.CARGO_AUDIT_STATUS_ILLEGAL;
import static cn.iocoder.yudao.module.transport.enums.ErrorCodeConstants.SEND_STATIONS_SAME;
import static cn.iocoder.yudao.module.transport.enums.ErrorCodeConstants.STATION_DISABLED;
import static cn.iocoder.yudao.module.transport.enums.ErrorCodeConstants.STATION_NOT_EXISTS;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * 货运审核状态机与订单明细归属判定纯 Mockito 单测。
 */
@ExtendWith(MockitoExtension.class)
class TransportOrderServiceImplTest {

    @Mock private TransportOrderMapper orderMapper;
    @Mock private PassengerOrderMapper passengerOrderMapper;
    @Mock private CargoOrderMapper cargoOrderMapper;
    @Mock private PostalOrderMapper postalOrderMapper;
    @Mock private MemberUserApi memberUserApi;
    @Mock private StationMapper stationMapper;

    private TransportOrderServiceImpl orderService;

    @BeforeEach
    void setUp() {
        orderService = new TransportOrderServiceImpl();
        ReflectionTestUtils.setField(orderService, "orderMapper", orderMapper);
        ReflectionTestUtils.setField(orderService, "passengerOrderMapper", passengerOrderMapper);
        ReflectionTestUtils.setField(orderService, "cargoOrderMapper", cargoOrderMapper);
        ReflectionTestUtils.setField(orderService, "postalOrderMapper", postalOrderMapper);
        ReflectionTestUtils.setField(orderService, "memberUserApi", memberUserApi);
        ReflectionTestUtils.setField(orderService, "stationMapper", stationMapper);
    }

    private AppSendOrderCreateReqVO sendReqVO(Long pickup, Long delivery) {
        AppSendOrderCreateReqVO reqVO = new AppSendOrderCreateReqVO();
        reqVO.setPickupStationId(pickup);
        reqVO.setDeliveryStationId(delivery);
        reqVO.setGoodsName("土鸡蛋");
        reqVO.setGoodsWeight(new BigDecimal("2.5"));
        return reqVO;
    }

    // ==================== 寄货订单站点二次校验（不信任前端） ====================

    @Test
    void createSendOrder_same_station_blocked() {
        ServiceException ex = assertThrows(ServiceException.class,
                () -> orderService.createSendOrder(100L, sendReqVO(1L, 1L)));
        assertEquals(SEND_STATIONS_SAME.getCode(), ex.getCode());
        verify(orderMapper, never()).insert(any(TransportOrderDO.class));
    }

    @Test
    void createSendOrder_missing_station_blocked() {
        when(stationMapper.selectById(1L)).thenReturn(null);
        ServiceException ex = assertThrows(ServiceException.class,
                () -> orderService.createSendOrder(100L, sendReqVO(1L, 2L)));
        assertEquals(STATION_NOT_EXISTS.getCode(), ex.getCode());
        verify(orderMapper, never()).insert(any(TransportOrderDO.class));
    }

    @Test
    void createSendOrder_disabled_station_blocked() {
        // 取货站停用 → 校验即抛，无需再查询送达站
        when(stationMapper.selectById(1L)).thenReturn(StationDO.builder().id(1L).status(1).build());
        ServiceException ex = assertThrows(ServiceException.class,
                () -> orderService.createSendOrder(100L, sendReqVO(1L, 2L)));
        assertEquals(STATION_DISABLED.getCode(), ex.getCode());
        verify(orderMapper, never()).insert(any(TransportOrderDO.class));
    }

    @Test
    void createSendOrder_valid_stations_inserts_order() {
        when(stationMapper.selectById(1L)).thenReturn(StationDO.builder().id(1L).status(0).build());
        when(stationMapper.selectById(2L)).thenReturn(StationDO.builder().id(2L).status(0).build());
        orderService.createSendOrder(100L, sendReqVO(1L, 2L));
        verify(orderMapper).insert(any(TransportOrderDO.class));
        verify(cargoOrderMapper).insert(any(CargoOrderDO.class));
    }

    // ==================== 货运审核 ====================

    @Test
    void audit_repeated_audit_throws() {
        // 已审核通过(audit_status=1)的订单重复审核 → 拒绝
        when(orderMapper.selectById(1L)).thenReturn(TransportOrderDO.builder()
                .id(1L).orderType(2).status(TransportOrderStatusEnum.CREATED.getStatus()).build());
        when(cargoOrderMapper.selectOne(any(SFunction.class), any()))
                .thenReturn(CargoOrderDO.builder().id(9L).orderId(1L).auditStatus(1).build());

        OrderAuditReqVO reqVO = new OrderAuditReqVO();
        reqVO.setOrderId(1L);
        reqVO.setPass(true);
        ServiceException ex = assertThrows(ServiceException.class, () -> orderService.audit(reqVO));

        assertEquals(CARGO_AUDIT_STATUS_ILLEGAL.getCode(), ex.getCode());
        verify(cargoOrderMapper, never()).updateById(any(CargoOrderDO.class));
    }

    @Test
    void audit_in_transit_order_throws() {
        // 在途(已发车=3)订单审核 → 拒绝（主表状态不在 待调度/已入池）
        when(orderMapper.selectById(1L)).thenReturn(TransportOrderDO.builder()
                .id(1L).orderType(2).status(TransportOrderStatusEnum.DEPARTED.getStatus()).build());
        when(cargoOrderMapper.selectOne(any(SFunction.class), any()))
                .thenReturn(CargoOrderDO.builder().id(9L).orderId(1L).auditStatus(0).build());

        OrderAuditReqVO reqVO = new OrderAuditReqVO();
        reqVO.setOrderId(1L);
        reqVO.setPass(true);
        ServiceException ex = assertThrows(ServiceException.class, () -> orderService.audit(reqVO));

        assertEquals(CARGO_AUDIT_STATUS_ILLEGAL.getCode(), ex.getCode());
        verify(cargoOrderMapper, never()).updateById(any(CargoOrderDO.class));
    }

    @Test
    void audit_reject_without_reason_throws() {
        // 审核拒绝但缺拒绝原因 → 参数错误
        when(orderMapper.selectById(1L)).thenReturn(TransportOrderDO.builder()
                .id(1L).orderType(2).status(TransportOrderStatusEnum.CREATED.getStatus()).build());
        when(cargoOrderMapper.selectOne(any(SFunction.class), any()))
                .thenReturn(CargoOrderDO.builder().id(9L).orderId(1L).auditStatus(0).build());

        OrderAuditReqVO reqVO = new OrderAuditReqVO();
        reqVO.setOrderId(1L);
        reqVO.setPass(false);
        reqVO.setRejectReason(null);
        ServiceException ex = assertThrows(ServiceException.class, () -> orderService.audit(reqVO));

        assertEquals(BAD_REQUEST.getCode(), ex.getCode());
        verify(cargoOrderMapper, never()).updateById(any(CargoOrderDO.class));
        verify(orderMapper, never()).updateById(any(TransportOrderDO.class));
    }

    // ==================== 订单明细归属判定（包裹追踪分层返回） ====================

    @Test
    void canViewOrderDetail_owner_returns_true() {
        TransportOrderDO order = TransportOrderDO.builder()
                .id(1L).orderType(2).memberUserId(100L).build();

        assertTrue(orderService.canViewOrderDetail(order, 100L));
    }

    @Test
    void canViewOrderDetail_postal_receiver_mobile_returns_true() {
        // 管理端录入的邮快件 memberUserId 为空，收件人凭手机号匹配查件
        TransportOrderDO order = TransportOrderDO.builder()
                .id(1L).orderType(3).memberUserId(null).build();
        when(postalOrderMapper.selectOne(any(SFunction.class), any()))
                .thenReturn(PostalOrderDO.builder().id(9L).orderId(1L).receiverMobile("13800138000").build());
        MemberUserRespDTO user = new MemberUserRespDTO();
        user.setId(200L);
        user.setMobile("13800138000");
        when(memberUserApi.getUser(200L)).thenReturn(user);

        assertTrue(orderService.canViewOrderDetail(order, 200L));
    }

    @Test
    void canViewOrderDetail_stranger_returns_false() {
        // 无关第三方：非下单人，手机号也不匹配收件人
        TransportOrderDO order = TransportOrderDO.builder()
                .id(1L).orderType(3).memberUserId(100L).build();
        when(postalOrderMapper.selectOne(any(SFunction.class), any()))
                .thenReturn(PostalOrderDO.builder().id(9L).orderId(1L).receiverMobile("13800138000").build());
        MemberUserRespDTO user = new MemberUserRespDTO();
        user.setId(200L);
        user.setMobile("13900139000");
        when(memberUserApi.getUser(200L)).thenReturn(user);

        assertFalse(orderService.canViewOrderDetail(order, 200L));
    }

}
