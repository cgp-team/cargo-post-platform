package cn.iocoder.yudao.module.transport.service.transport.order;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.module.member.api.user.MemberUserApi;
import cn.iocoder.yudao.module.member.api.user.dto.MemberUserRespDTO;
import cn.iocoder.yudao.module.transport.controller.admin.transport.order.vo.OrderAuditReqVO;
import cn.iocoder.yudao.module.transport.controller.admin.transport.order.vo.TransportOrderUpdateReqVO;
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
import cn.iocoder.yudao.module.transport.enums.order.ReviewStatusEnum;
import cn.iocoder.yudao.module.transport.service.order.CargoReviewResult;
import cn.iocoder.yudao.module.transport.service.order.CargoReviewService;
import com.baomidou.mybatisplus.core.toolkit.support.SFunction;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.List;

import static cn.iocoder.yudao.framework.common.exception.enums.GlobalErrorCodeConstants.BAD_REQUEST;
import static cn.iocoder.yudao.module.transport.enums.ErrorCodeConstants.CARGO_AUDIT_STATUS_ILLEGAL;
import static cn.iocoder.yudao.module.transport.enums.ErrorCodeConstants.SEND_ORDER_NOT_YOURS;
import static cn.iocoder.yudao.module.transport.enums.ErrorCodeConstants.SEND_ORDER_STATUS_ILLEGAL;
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
    @Mock private CargoReviewService cargoReviewService;
    @Mock private cn.iocoder.yudao.module.transport.service.order.OrderEventService orderEventService;
    @Mock private cn.iocoder.yudao.module.transport.service.notification.UserNotificationService userNotificationService;
    @Mock private cn.iocoder.yudao.module.transport.service.dispatch.CargoPricingService cargoPricingService;

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
        ReflectionTestUtils.setField(orderService, "cargoReviewService", cargoReviewService);
        ReflectionTestUtils.setField(orderService, "orderEventService", orderEventService);
        ReflectionTestUtils.setField(orderService, "userNotificationService", userNotificationService);
        // 寄货计价：金额由 CargoPricingService 统一试算（件单价×件数 + 里程费），测试里给固定值
        ReflectionTestUtils.setField(orderService, "cargoPricingService", cargoPricingService);
        // lenient：不是每个用例都会走到计价（严格 stub 会因"未使用"报 UnnecessaryStubbing）
        org.mockito.Mockito.lenient().when(cargoPricingService.quote(any(), any(), any())).thenReturn(
                new cn.iocoder.yudao.module.transport.service.dispatch.CargoPricingService.CargoQuote(
                        new BigDecimal("28.21"), new BigDecimal("10.00"), new BigDecimal("18.21"),
                        new BigDecimal("18.21"), 2, new BigDecimal("5.00"), new BigDecimal("1.00")));
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
    void createSendOrder_valid_stations_inserts_order_and_auto_reviews() {
        // 审核引擎默认通过 → 生命周期流转为待入池(READY_FOR_POOL)，审核结果写子表
        when(stationMapper.selectById(1L)).thenReturn(StationDO.builder().id(1L).status(0).build());
        when(stationMapper.selectById(2L)).thenReturn(StationDO.builder().id(2L).status(0).build());
        when(cargoReviewService.review(any(), any(), any(), any(), any(), any()))
                .thenReturn(CargoReviewResult.builder()
                        .reviewStatus(ReviewStatusEnum.PASSED.getStatus())
                        .reasonCodes(List.of())
                        .pickupServiceMode("STATION_TO_STATION")
                        .deliveryServiceMode("STATION_TO_STATION")
                        .message("审核通过")
                        .build());

        orderService.createSendOrder(100L, sendReqVO(1L, 2L));

        verify(orderMapper).insert(any(TransportOrderDO.class));
        verify(cargoOrderMapper).insert(any(CargoOrderDO.class));
        // 子表回写审核结果
        ArgumentCaptor<CargoOrderDO> cargoCaptor = ArgumentCaptor.forClass(CargoOrderDO.class);
        verify(cargoOrderMapper).updateById(cargoCaptor.capture());
        assertEquals(ReviewStatusEnum.PASSED.getStatus(), cargoCaptor.getValue().getReviewStatus());
        // 主表流转为待入池（READY_FOR_POOL，唯一可归集入池状态）
        ArgumentCaptor<TransportOrderDO> orderCaptor = ArgumentCaptor.forClass(TransportOrderDO.class);
        verify(orderMapper).updateById(orderCaptor.capture());
        assertEquals(TransportOrderStatusEnum.READY_FOR_POOL.getStatus(), orderCaptor.getValue().getStatus());
    }

    @Test
    void createSendOrder_rejected_cargo_moves_to_cancelled() {
        // 审核引擎判定危险品 → 拒运 → 生命周期终态取消，子表记录原因码 + 兼容 rejectReason
        when(stationMapper.selectById(1L)).thenReturn(StationDO.builder().id(1L).status(0).build());
        when(stationMapper.selectById(2L)).thenReturn(StationDO.builder().id(2L).status(0).build());
        when(cargoReviewService.review(any(), any(), any(), any(), any(), any()))
                .thenReturn(CargoReviewResult.builder()
                        .reviewStatus(ReviewStatusEnum.REJECTED.getStatus())
                        .reasonCodes(List.of("DANGEROUS_GOODS"))
                        .pickupServiceMode("STATION_TO_STATION")
                        .deliveryServiceMode("STATION_TO_STATION")
                        .message("货物不符合运输条件")
                        .build());

        orderService.createSendOrder(100L, sendReqVO(1L, 2L));

        ArgumentCaptor<CargoOrderDO> cargoCaptor = ArgumentCaptor.forClass(CargoOrderDO.class);
        verify(cargoOrderMapper).updateById(cargoCaptor.capture());
        assertEquals(ReviewStatusEnum.REJECTED.getStatus(), cargoCaptor.getValue().getReviewStatus());
        assertEquals("DANGEROUS_GOODS", cargoCaptor.getValue().getReviewReasonCodes());
        // 主表终态取消，不可入池
        ArgumentCaptor<TransportOrderDO> orderCaptor = ArgumentCaptor.forClass(TransportOrderDO.class);
        verify(orderMapper).updateById(orderCaptor.capture());
        assertEquals(TransportOrderStatusEnum.CANCELLED.getStatus(), orderCaptor.getValue().getStatus());
    }

    // ==================== 货运审核 ====================

    @Test
    void audit_repeated_audit_throws() {
        // 已审核通过(review_status=PASSED)的订单重复审核 → 拒绝
        when(orderMapper.selectById(1L)).thenReturn(TransportOrderDO.builder()
                .id(1L).orderType(2).status(TransportOrderStatusEnum.CREATED.getStatus()).build());
        when(cargoOrderMapper.selectOne(any(SFunction.class), any()))
                .thenReturn(CargoOrderDO.builder().id(9L).orderId(1L).auditStatus(1)
                        .reviewStatus(ReviewStatusEnum.PASSED.getStatus()).build());

        OrderAuditReqVO reqVO = new OrderAuditReqVO();
        reqVO.setOrderId(1L);
        reqVO.setPass(true);
        ServiceException ex = assertThrows(ServiceException.class, () -> orderService.audit(reqVO));

        assertEquals(CARGO_AUDIT_STATUS_ILLEGAL.getCode(), ex.getCode());
        verify(cargoOrderMapper, never()).updateById(any(CargoOrderDO.class));
    }

    @Test
    void audit_autoPassed_order_isIdempotentConfirm() {
        // 村民提交后自动审核通过 → 订单直接是「待入池(8)」；管理员在订单管理里点「审核通过」
        // 属于复核确认：只记录审核结论，必须成功且**不改变生命周期**（不能把订单打回/报错中断演示）
        when(orderMapper.selectById(1L)).thenReturn(TransportOrderDO.builder()
                .id(1L).orderType(2).status(TransportOrderStatusEnum.READY_FOR_POOL.getStatus()).build());
        when(cargoOrderMapper.selectOne(any(SFunction.class), any()))
                .thenReturn(CargoOrderDO.builder().id(9L).orderId(1L).auditStatus(0)
                        .reviewStatus(ReviewStatusEnum.PASSED.getStatus()).build());

        OrderAuditReqVO reqVO = new OrderAuditReqVO();
        reqVO.setOrderId(1L);
        reqVO.setPass(true);
        orderService.audit(reqVO);

        ArgumentCaptor<CargoOrderDO> captor = ArgumentCaptor.forClass(CargoOrderDO.class);
        verify(cargoOrderMapper).updateById(captor.capture());
        assertEquals(1, captor.getValue().getAuditStatus());
        assertNull(captor.getValue().getReviewStatus()); // 只写审核结论，不动审核结果
        verify(orderMapper, never()).updateById(any(TransportOrderDO.class)); // 生命周期不变
    }

    @Test
    void audit_autoPassed_order_rejectCancelsOrder() {
        // 复核不通过（如现场发现违禁品）：必须能把订单取消，不能因为"自动审核已通过"就锁死
        when(orderMapper.selectById(1L)).thenReturn(TransportOrderDO.builder()
                .id(1L).orderType(2).status(TransportOrderStatusEnum.READY_FOR_POOL.getStatus()).build());
        when(cargoOrderMapper.selectOne(any(SFunction.class), any()))
                .thenReturn(CargoOrderDO.builder().id(9L).orderId(1L).auditStatus(0)
                        .reviewStatus(ReviewStatusEnum.PASSED.getStatus()).build());
        // CAS 落库：Mockito 默认 update 返回 0 会误判并发失败
        when(cargoOrderMapper.update(any(CargoOrderDO.class), any())).thenReturn(1);

        OrderAuditReqVO reqVO = new OrderAuditReqVO();
        reqVO.setOrderId(1L);
        reqVO.setPass(false);
        reqVO.setRejectReason("现场复核发现违禁品");
        orderService.audit(reqVO);

        ArgumentCaptor<TransportOrderDO> orderCaptor = ArgumentCaptor.forClass(TransportOrderDO.class);
        verify(orderMapper).updateById(orderCaptor.capture());
        assertEquals(TransportOrderStatusEnum.CANCELLED.getStatus(), orderCaptor.getValue().getStatus());
    }

    @Test
    void confirmStationAction_waiting_action_to_ready_for_pool() {
        // 待客户操作(status=7) → 确认送站 → 待入池(status=8)
        when(orderMapper.selectById(1L)).thenReturn(TransportOrderDO.builder()
                .id(1L).orderType(2).memberUserId(100L)
                .status(TransportOrderStatusEnum.WAITING_CUSTOMER_ACTION.getStatus()).build());
        when(orderMapper.update(any(TransportOrderDO.class), any())).thenReturn(1);

        orderService.confirmStationAction(100L, 1L);

        ArgumentCaptor<TransportOrderDO> captor = ArgumentCaptor.forClass(TransportOrderDO.class);
        verify(orderMapper).update(captor.capture(), any());
        assertEquals(TransportOrderStatusEnum.READY_FOR_POOL.getStatus(), captor.getValue().getStatus());
    }

    @Test
    void confirmStationAction_not_owner_throws() {
        // 非下单人 → 无权操作
        when(orderMapper.selectById(1L)).thenReturn(TransportOrderDO.builder()
                .id(1L).orderType(2).memberUserId(200L)
                .status(TransportOrderStatusEnum.WAITING_CUSTOMER_ACTION.getStatus()).build());

        ServiceException ex = assertThrows(ServiceException.class,
                () -> orderService.confirmStationAction(100L, 1L));

        assertEquals(SEND_ORDER_NOT_YOURS.getCode(), ex.getCode());
        verify(orderMapper, never()).update(any(TransportOrderDO.class), any());
    }

    @Test
    void confirmStationAction_wrong_status_throws() {
        // 已入池(status=1)状态确认 → 拒绝（仅待客户操作可确认）
        when(orderMapper.selectById(1L)).thenReturn(TransportOrderDO.builder()
                .id(1L).orderType(2).memberUserId(100L)
                .status(TransportOrderStatusEnum.POOLED.getStatus()).build());

        ServiceException ex = assertThrows(ServiceException.class,
                () -> orderService.confirmStationAction(100L, 1L));

        assertEquals(SEND_ORDER_STATUS_ILLEGAL.getCode(), ex.getCode());
        verify(orderMapper, never()).update(any(TransportOrderDO.class), any());
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


    @Test
    void update_ignores_incoming_status_bypass_state_machine_guard() {
        // BE-04：更新接口传入 status 也不允许改变库中订单状态（状态机守卫）
        when(orderMapper.selectById(1L)).thenReturn(
                TransportOrderDO.builder().id(1L).orderType(2).status(1).build());
        cn.iocoder.yudao.module.transport.controller.admin.transport.order.vo.TransportOrderUpdateReqVO reqVO =
                new cn.iocoder.yudao.module.transport.controller.admin.transport.order.vo.TransportOrderUpdateReqVO();
        reqVO.setId(1L);
        reqVO.setOrderType(2);
        reqVO.setStatus(4); // 试图把状态改成已完成

        orderService.update(reqVO);

        ArgumentCaptor<TransportOrderDO> captor = ArgumentCaptor.forClass(TransportOrderDO.class);
        verify(orderMapper).updateById(captor.capture());
        assertNull(captor.getValue().getStatus(), "update 传入的 status 必须被忽略（置 null 不落库）");
    }

    /**
     * D-090：后台编辑一单邮快件时，报文里没带件数/承运商（管理端表单当时根本没有回读这两项），
     * 旧写法会把库里的件数**改写成 1**（`postalItemCount != null ? : 1` 这个默认值只该出现在创建路径）。
     * 这条用例在修之前是红的：captor.getValue().getItemCount() == 1。
     */
    @Test
    void update_postal_order_without_item_count_keeps_stored_count() {
        when(orderMapper.selectById(1L)).thenReturn(
                TransportOrderDO.builder().id(1L).orderType(3).status(1).build());
        PostalOrderDO exist = new PostalOrderDO();
        exist.setId(77L);
        exist.setOrderId(1L);
        exist.setMailNo("SF123");
        exist.setCarrierCode("SF");
        exist.setItemCount(5);
        exist.setWeightKg(new BigDecimal("3.2"));
        when(postalOrderMapper.selectOne(any(SFunction.class), eq(1L))).thenReturn(exist);

        TransportOrderUpdateReqVO reqVO = new TransportOrderUpdateReqVO();
        reqVO.setId(1L);
        reqVO.setOrderType(3);
        reqVO.setMailNo("SF123");        // 守卫看的是 mailNo 非 null，所以这一支会真的走到 updateById
        // 刻意不设 carrierCode / postalItemCount / postalWeightKg：等价于"报文没带"

        orderService.update(reqVO);

        ArgumentCaptor<PostalOrderDO> captor = ArgumentCaptor.forClass(PostalOrderDO.class);
        verify(postalOrderMapper).updateById(captor.capture());
        PostalOrderDO upd = captor.getValue();
        assertNull(upd.getItemCount(), "报文没带件数 ⇒ 不许写 1，null 才会被 MyBatis 当成不改（库里保持 5）");
        assertNull(upd.getCarrierCode(), "报文没带承运商 ⇒ 不许落任何值，旧写法在这里会写空串把 SF 抹掉");
        assertNull(upd.getWeightKg(), "报文没带重量 ⇒ 不改");
    }

    /**
     * D-090 同族：货运更新里 `freshFlag == null` 被旧写法判成 false ⇒ 一张生鲜单（承运审核走人工的开关）
     * 会因为"报文没带这个键"被静默降级；`cargoItemCount == null` 同样被兜成 1。
     */
    @Test
    void update_cargo_order_without_fresh_flag_does_not_downgrade_it() {
        when(orderMapper.selectById(2L)).thenReturn(
                TransportOrderDO.builder().id(2L).orderType(2).status(1).build());
        CargoOrderDO exist = new CargoOrderDO();
        exist.setId(88L);
        exist.setOrderId(2L);
        exist.setFreshFlag(true);
        exist.setItemCount(3);
        when(cargoOrderMapper.selectOne(any(SFunction.class), eq(2L))).thenReturn(exist);

        TransportOrderUpdateReqVO reqVO = new TransportOrderUpdateReqVO();
        reqVO.setId(2L);
        reqVO.setOrderType(2);
        reqVO.setCargoCategory("生鲜果蔬");   // 守卫：cargoCategory 非 null 才会走到 updateById
        // 刻意不设 freshFlag / cargoItemCount

        orderService.update(reqVO);

        ArgumentCaptor<CargoOrderDO> captor = ArgumentCaptor.forClass(CargoOrderDO.class);
        verify(cargoOrderMapper).updateById(captor.capture());
        CargoOrderDO upd = captor.getValue();
        assertNull(upd.getFreshFlag(), "报文没带生鲜旗标 ⇒ 不许写 false 把生鲜单降级（null = 不改）");
        assertNull(upd.getItemCount(), "报文没带件数 ⇒ 不许兜成 1");
    }

}
