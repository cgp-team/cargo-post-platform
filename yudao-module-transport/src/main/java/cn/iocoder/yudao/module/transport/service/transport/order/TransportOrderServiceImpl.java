package cn.iocoder.yudao.module.transport.service.transport.order;

import cn.hutool.core.util.RandomUtil;
import cn.hutool.core.util.StrUtil;
import cn.iocoder.yudao.framework.common.pojo.PageParam;
import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.framework.mybatis.core.query.LambdaQueryWrapperX;
import cn.iocoder.yudao.module.member.api.user.MemberUserApi;
import cn.iocoder.yudao.module.member.api.user.dto.MemberUserRespDTO;
import cn.iocoder.yudao.module.transport.controller.admin.transport.order.vo.*;
import cn.iocoder.yudao.module.transport.controller.app.transport.send.vo.AppSendOrderCreateReqVO;
import cn.iocoder.yudao.module.transport.convert.transport.order.TransportOrderConvert;
import cn.iocoder.yudao.module.transport.dal.dataobject.order.*;
import cn.iocoder.yudao.module.transport.dal.dataobject.station.StationDO;
import cn.iocoder.yudao.module.transport.dal.mysql.order.*;
import cn.iocoder.yudao.module.transport.dal.mysql.station.StationMapper;
import cn.iocoder.yudao.module.transport.enums.dispatch.TransportOrderStatusEnum;
import cn.iocoder.yudao.module.transport.enums.dispatch.TransportOrderEventTypeEnum;
import cn.iocoder.yudao.module.transport.enums.order.ReviewStatusEnum;
import cn.iocoder.yudao.module.transport.service.order.CargoReviewResult;
import cn.iocoder.yudao.module.transport.service.order.CargoReviewService;
import cn.iocoder.yudao.module.transport.service.order.CargoReviewServiceImpl;
import cn.iocoder.yudao.module.transport.service.order.OrderEventService;
import cn.iocoder.yudao.module.transport.service.dispatch.CargoPricingService;
import cn.iocoder.yudao.module.transport.service.notification.UserNotificationService;
import cn.iocoder.yudao.module.transport.util.StationAccessUtil;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.annotation.Validated;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Objects;

import static cn.iocoder.yudao.framework.common.exception.enums.GlobalErrorCodeConstants.BAD_REQUEST;
import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.module.transport.enums.ErrorCodeConstants.CARGO_AUDIT_ONLY_CARGO;
import static cn.iocoder.yudao.module.transport.enums.ErrorCodeConstants.CARGO_AUDIT_STATUS_ILLEGAL;
import static cn.iocoder.yudao.module.transport.enums.ErrorCodeConstants.ORDER_NOT_EXISTS;
import static cn.iocoder.yudao.module.transport.enums.ErrorCodeConstants.SEND_ORDER_NOT_YOURS;
import static cn.iocoder.yudao.module.transport.enums.ErrorCodeConstants.SEND_ORDER_STATUS_ILLEGAL;
import static cn.iocoder.yudao.module.transport.enums.ErrorCodeConstants.SEND_ORDER_USER_NOT_LOGIN;
import static cn.iocoder.yudao.module.transport.enums.ErrorCodeConstants.SEND_STATIONS_SAME;
import static cn.iocoder.yudao.module.transport.enums.ErrorCodeConstants.STATION_DISABLED;
import static cn.iocoder.yudao.module.transport.enums.ErrorCodeConstants.STATION_NOT_EXISTS;
import static cn.iocoder.yudao.module.transport.enums.ErrorCodeConstants.STATION_NOT_USER_ACCESSIBLE;
import static cn.iocoder.yudao.module.transport.enums.ErrorCodeConstants.STATION_NOT_VEHICLE_ACCESSIBLE;

@Service
@Validated
public class TransportOrderServiceImpl implements TransportOrderService {

    @Resource private TransportOrderMapper orderMapper;
    @Resource private PassengerOrderMapper passengerOrderMapper;
    @Resource private CargoOrderMapper cargoOrderMapper;
    @Resource private PostalOrderMapper postalOrderMapper;
    @Resource private MemberUserApi memberUserApi;
    @Resource private StationMapper stationMapper;
    @Resource private CargoReviewService cargoReviewService;
    @Resource private OrderEventService orderEventService;
    @Resource private UserNotificationService userNotificationService;
    @Resource private CargoPricingService cargoPricingService;

    @Override
    @Transactional
    public Long create(TransportOrderCreateReqVO reqVO) {
        TransportOrderDO order = TransportOrderConvert.INSTANCE.convert(reqVO);
        order.setOrderNo(generateOrderNo());
        orderMapper.insert(order);

        Integer orderType = reqVO.getOrderType();
        if (orderType != null) {
            switch (orderType) {
                case 1: // 客运
                    createPassengerOrder(order.getId(), reqVO);
                    break;
                case 2: // 货运
                    createCargoOrder(order.getId(), reqVO);
                    break;
                case 3: // 邮快件
                    createPostalOrder(order.getId(), reqVO);
                    break;
            }
        }
        return order.getId();
    }

    @Override
    @Transactional
    public void update(TransportOrderUpdateReqVO reqVO) {
        validateExists(reqVO.getId());
        TransportOrderDO order = TransportOrderConvert.INSTANCE.convert(reqVO);
        orderMapper.updateById(order);
        // Update sub-orders：存在则按 id 更新（保留取件码/核销/审核等流程字段），不存在才插入
        Integer orderType = reqVO.getOrderType();
        if (orderType != null) {
            switch (orderType) {
                case 1 -> updatePassengerOrder(reqVO.getId(), reqVO);
                case 2 -> updateCargoOrder(reqVO.getId(), reqVO);
                case 3 -> updatePostalOrder(reqVO.getId(), reqVO);
            }
        }
    }

    @Override
    @Transactional
    public void delete(Long id) {
        validateExists(id);
        deletePassengerOrder(id);
        deleteCargoOrder(id);
        deletePostalOrder(id);
        orderMapper.deleteById(id);
    }

    @Override
    public TransportOrderDO get(Long id) {
        return validateExists(id);
    }

    @Override
    public PageResult<TransportOrderDO> getPage(TransportOrderPageReqVO reqVO) {
        return orderMapper.selectPage(reqVO);
    }

    @Override
    @Transactional
    public Long createSendOrder(Long userId, AppSendOrderCreateReqVO reqVO) {
        if (userId == null) {
            throw exception(SEND_ORDER_USER_NOT_LOGIN);
        }
        // 二次校验站点（不信任小程序前端）：非空 / 不相同 / 存在 / 未删除 / 已启用
        validateSendStations(reqVO.getPickupStationId(), reqVO.getDeliveryStationId());
        // 计价前置：寄货页已按同口径显示金额，这里落库保证「小程序看到多少，后台订单就是多少」。
        // 件单价×件数 + 里程费（与方案预计收入同源，见 CargoPricingService）。
        int itemCount = reqVO.getItemCount() != null && reqVO.getItemCount() > 0 ? reqVO.getItemCount() : 1;
        CargoPricingService.CargoQuote quote = cargoPricingService.quote(
                reqVO.getPickupStationId(), reqVO.getDeliveryStationId(), itemCount);
        // 主表：货运订单，先置已创建，随后的自动承运审核立即流转（审核通过才可入池）
        TransportOrderDO order = TransportOrderDO.builder()
                .orderNo(generateOrderNo())
                .orderType(2) // 货运/生鲜
                .pickupStationId(reqVO.getPickupStationId())
                .deliveryStationId(reqVO.getDeliveryStationId())
                .earliestPickupTime(reqVO.getEarliestPickupTime())
                .status(TransportOrderStatusEnum.CREATED.getStatus())
                .memberUserId(userId)
                .totalAmount(quote.amount())
                .build();
        orderMapper.insert(order);
        // 货运子表：寄货货物信息
        // 货物类型/件数/体积/生鲜由村民在寄货页填写（缺省兼容旧客户端：农产品 / 1 件 / 0 m³ / 非生鲜）。
        // 说明：freshFlag 曾硬编码 true，导致所有寄货订单都被判为「生鲜需人工审核」，此处按客户勾选落库。
        CargoOrderDO sub = CargoOrderDO.builder()
                .orderId(order.getId())
                .cargoCategory(StrUtil.blankToDefault(reqVO.getCargoCategory(), "农产品"))
                .freshFlag(Boolean.TRUE.equals(reqVO.getFreshFlag()))
                .itemCount(itemCount)
                .weightKg(reqVO.getGoodsWeight())
                .volumeM3(reqVO.getVolumeM3() != null ? reqVO.getVolumeM3() : BigDecimal.ZERO)
                .goodsName(reqVO.getGoodsName())
                .goodsNote(reqVO.getGoodsNote())
                .photoUrl(reqVO.getPhotoUrl())
                .receiverName(reqVO.getReceiverName())
                .receiverMobile(reqVO.getReceiverMobile())
                .receiverAddress(reqVO.getReceiverAddress())
                // 用户原始位置单独留痕：服务站（pickupStationId）由可达性评估推荐，二者都不丢
                .originalAddress(reqVO.getOriginalAddress())
                .originalLatitude(reqVO.getOriginalLatitude())
                .originalLongitude(reqVO.getOriginalLongitude())
                // 取货方式由小程序可达性评估给出（车辆不能直接进入 → 最近站点交接）
                .pickupServiceMode(reqVO.getPickupServiceMode())
                .build();
        cargoOrderMapper.insert(sub);

        // Phase 2 承运审核：客户提交后先审核，结果决定生命周期（通过→待入池，需操作→待客户操作，
        // 需人工→待审核，拒运→取消）；审核结果与原因码落子表，供小程序实时展示
        applyAutoReview(order, sub);
        // 订单事件时间线 + 用户通知（事件驱动，供小程序消息中心/后台时间线展示）
        orderEventService.record(order.getId(), TransportOrderEventTypeEnum.ORDER_CREATED,
                "寄货订单创建：" + order.getOrderNo());
        userNotificationService.send(userId, TransportOrderEventTypeEnum.ORDER_CREATED,
                "寄货订单已提交", "您的订单 " + order.getOrderNo() + " 已提交，正在处理", order.getId());
        return order.getId();
    }

    /** 自动承运审核：写子表审核结果/服务方式 + 主表生命周期流转（审核结果由 reasonCode 表达） */
    private void applyAutoReview(TransportOrderDO order, CargoOrderDO sub) {
        CargoReviewResult result = cargoReviewService.review(
                sub.getGoodsName(), sub.getWeightKg(), sub.getFreshFlag(),
                sub.getGoodsNote(), order.getPickupStationId(), order.getDeliveryStationId());
        CargoOrderDO upd = new CargoOrderDO();
        upd.setId(sub.getId());
        upd.setReviewStatus(result.getReviewStatus());
        upd.setReviewReasonCodes(CargoReviewServiceImpl.joinReasonCodes(result.getReasonCodes()));
        // 取货方式：小程序可达性评估已给出（如 NEAREST_STATION 车辆进不去校园）时以客户端为准，
        // 否则用承运审核推导值（默认站到站）
        upd.setPickupServiceMode(StrUtil.isNotBlank(sub.getPickupServiceMode())
                ? sub.getPickupServiceMode() : result.getPickupServiceMode());
        upd.setDeliveryServiceMode(result.getDeliveryServiceMode());
        upd.setServicePointStationId(result.getServicePointStationId());
        // 拒运：兼容旧 auditStatus 字段 + 原因文案（村民查件页可见"审核不通过+原因"）
        if (result.isRejected()) {
            upd.setAuditStatus(2);
            upd.setRejectReason(CargoReviewServiceImpl.reasonText(result.getReasonCodes()));
        }
        cargoOrderMapper.updateById(upd);
        // 主表生命周期流转
        Integer lifecycle = resolveReviewLifecycle(result.getReviewStatus());
        TransportOrderDO orderUpd = new TransportOrderDO();
        orderUpd.setId(order.getId());
        orderUpd.setStatus(lifecycle);
        orderMapper.updateById(orderUpd);
        // 审核结果事件 + 通知（与生命周期一一对应，事件类型即用户可读的推进节点）
        recordReviewEvent(order, lifecycle, result);
    }

    /** 记录自动审核结果事件并推送用户通知（审核通过/需客户操作/待人工/拒运） */
    private void recordReviewEvent(TransportOrderDO order, Integer lifecycle, CargoReviewResult result) {
        if (TransportOrderStatusEnum.READY_FOR_POOL.getStatus().equals(lifecycle)) {
            orderEventService.record(order.getId(), TransportOrderEventTypeEnum.REVIEW_PASSED,
                    "承运审核通过，已进入待入池（可被智能调度）");
            userNotificationService.sendToOrderUser(order.getId(), TransportOrderEventTypeEnum.REVIEW_PASSED,
                    "订单审核通过", "您的订单 " + order.getOrderNo() + " 已通过承运审核，等待调度");
        } else if (TransportOrderStatusEnum.WAITING_CUSTOMER_ACTION.getStatus().equals(lifecycle)) {
            orderEventService.record(order.getId(), TransportOrderEventTypeEnum.STATION_RECOMMENDED,
                    "需客户将货物送至就近服务站点后确认：" + CargoReviewServiceImpl.reasonText(result.getReasonCodes()));
            userNotificationService.sendToOrderUser(order.getId(), TransportOrderEventTypeEnum.STATION_RECOMMENDED,
                    "请确认送货站点", "为完成承运，请将货物送至推荐服务站点并确认");
        } else if (TransportOrderStatusEnum.PENDING_REVIEW.getStatus().equals(lifecycle)) {
            orderEventService.record(order.getId(), TransportOrderEventTypeEnum.REVIEW_PASSED,
                    "需人工审核：" + CargoReviewServiceImpl.reasonText(result.getReasonCodes()));
            userNotificationService.sendToOrderUser(order.getId(), TransportOrderEventTypeEnum.REVIEW_PASSED,
                    "订单待人工审核", "您的订单正在人工审核，请耐心等待");
        } else {
            orderEventService.record(order.getId(), TransportOrderEventTypeEnum.REVIEW_REJECTED,
                    "承运审核未通过：" + CargoReviewServiceImpl.reasonText(result.getReasonCodes()));
            userNotificationService.sendToOrderUser(order.getId(), TransportOrderEventTypeEnum.REVIEW_REJECTED,
                    "订单未能承运", "您的订单未通过承运审核：" + CargoReviewServiceImpl.reasonText(result.getReasonCodes()));
        }
    }

    /** 审核结果 → 订单生命周期状态（与 CargoReviewService 契约一致） */
    private Integer resolveReviewLifecycle(Integer reviewStatus) {
        if (ReviewStatusEnum.PASSED.getStatus().equals(reviewStatus)) {
            return TransportOrderStatusEnum.READY_FOR_POOL.getStatus();
        }
        if (ReviewStatusEnum.CONDITIONAL.getStatus().equals(reviewStatus)) {
            return TransportOrderStatusEnum.WAITING_CUSTOMER_ACTION.getStatus();
        }
        if (ReviewStatusEnum.MANUAL_REVIEW.getStatus().equals(reviewStatus)) {
            return TransportOrderStatusEnum.PENDING_REVIEW.getStatus();
        }
        // REJECTED → 明确不可运输，终态取消，不可入池
        return TransportOrderStatusEnum.CANCELLED.getStatus();
    }

    /** 寄货订单落库前二次校验站点：非空 / 不相同 / 存在（未删除）/ 已启用（status=0） */
    private void validateSendStations(Long pickupStationId, Long deliveryStationId) {
        if (pickupStationId == null || deliveryStationId == null) {
            throw exception(STATION_NOT_EXISTS);
        }
        if (Objects.equals(pickupStationId, deliveryStationId)) {
            throw exception(SEND_STATIONS_SAME);
        }
        requireEnabledStation(pickupStationId);
        requireEnabledStation(deliveryStationId);
    }

    private void requireEnabledStation(Long stationId) {
        StationDO station = stationMapper.selectById(stationId);
        if (station == null) {
            throw exception(STATION_NOT_EXISTS);
        }
        if (station.getStatus() != null && station.getStatus() != 0) {
            throw exception(STATION_DISABLED);
        }
        // 站点启用 ≠ 用户可达 ≠ 车辆可达：下单即校验（不相信前端），校园禁行区站点不能作为取/送站
        if (!StationAccessUtil.userAccessible(station)) {
            throw exception(STATION_NOT_USER_ACCESSIBLE);
        }
        if (!StationAccessUtil.vehicleAccessible(station)) {
            throw exception(STATION_NOT_VEHICLE_ACCESSIBLE);
        }
    }

    @Override
    public PageResult<TransportOrderDO> getMySendPage(Long userId, PageParam pageParam) {
        if (userId == null) {
            throw exception(SEND_ORDER_USER_NOT_LOGIN);
        }
        return orderMapper.selectPageByMemberUser(pageParam, userId);
    }

    @Override
    public TransportOrderDO getByOrderNo(String orderNo) {
        TransportOrderDO order = orderMapper.selectByOrderNo(orderNo);
        if (order == null) {
            throw exception(ORDER_NOT_EXISTS);
        }
        return order;
    }

    @Override
    public CargoOrderDO getCargoOrder(Long orderId) {
        return cargoOrderMapper.selectOne(CargoOrderDO::getOrderId, orderId);
    }

    @Override
    public boolean canViewOrderDetail(TransportOrderDO order, Long loginUserId) {
        if (order == null || loginUserId == null) {
            return false;
        }
        // 下单人本人
        if (Objects.equals(order.getMemberUserId(), loginUserId)) {
            return true;
        }
        // 邮快件：管理端录入的订单 memberUserId 为空，收件人凭手机号匹配查件（需展示取件码供司机核销）
        if (Objects.equals(order.getOrderType(), 3)) {
            PostalOrderDO postal = postalOrderMapper.selectOne(PostalOrderDO::getOrderId, order.getId());
            MemberUserRespDTO user = memberUserApi.getUser(loginUserId);
            return postal != null && StrUtil.isNotBlank(postal.getReceiverMobile())
                    && user != null && StrUtil.isNotBlank(user.getMobile())
                    && Objects.equals(postal.getReceiverMobile().trim(), user.getMobile().trim());
        }
        return false;
    }

    @Override
    @Transactional
    public void audit(OrderAuditReqVO reqVO) {
        TransportOrderDO order = validateExists(reqVO.getOrderId());
        // 仅货运（村民寄货散件）需要审核
        if (!Objects.equals(order.getOrderType(), 2)) {
            throw exception(CARGO_AUDIT_ONLY_CARGO);
        }
        CargoOrderDO cargo = cargoOrderMapper.selectOne(CargoOrderDO::getOrderId, order.getId());
        if (cargo == null) {
            throw exception(ORDER_NOT_EXISTS);
        }
        // 状态机：仅 已创建(0)/已入池(1)/待审核(6，自动审核转人工) 且 审核未决（PENDING/MANUAL_REVIEW）可审
        Integer status = order.getStatus();
        Integer reviewStatus = cargo.getReviewStatus();
        // 管理员复核「自动审核已通过」的订单（正常货物提交后即自动通过 → 待入池/已入池）：
        // 通过 = 幂等确认（只记录审核结论，不改变生命周期，绝不把订单从池里打回）；
        // 拒绝 = 复核不通过 → 取消该单，避免已经把货备好的订单被静默运走。
        if (ReviewStatusEnum.PASSED.getStatus().equals(reviewStatus)
                && (Objects.equals(status, TransportOrderStatusEnum.READY_FOR_POOL.getStatus())
                    || Objects.equals(status, TransportOrderStatusEnum.POOLED.getStatus()))) {
            CargoOrderDO upd = new CargoOrderDO();
            upd.setId(cargo.getId());
            if (Boolean.TRUE.equals(reqVO.getPass())) {
                upd.setAuditStatus(1);
                cargoOrderMapper.updateById(upd);
                return;
            }
            if (StrUtil.isBlank(reqVO.getRejectReason())) {
                throw exception(BAD_REQUEST);
            }
            upd.setAuditStatus(2);
            upd.setRejectReason(reqVO.getRejectReason());
            upd.setReviewStatus(ReviewStatusEnum.REJECTED.getStatus());
            TransportOrderDO orderUpd = new TransportOrderDO();
            orderUpd.setId(order.getId());
            orderUpd.setStatus(TransportOrderStatusEnum.CANCELLED.getStatus());
            orderMapper.updateById(orderUpd);
            // CAS：仅当审核状态仍为 PASSED（读取时）时落库，防并发复核互相覆盖
            cargoOrderMapper.updateById(upd);
            return;
        }
        boolean reviewGate = Objects.equals(status, TransportOrderStatusEnum.CREATED.getStatus())
                || Objects.equals(status, TransportOrderStatusEnum.POOLED.getStatus())
                || Objects.equals(status, TransportOrderStatusEnum.PENDING_REVIEW.getStatus());
        if (!reviewGate) {
            throw exception(CARGO_AUDIT_STATUS_ILLEGAL);
        }
        boolean undecided = reviewStatus == null
                || ReviewStatusEnum.PENDING.getStatus().equals(reviewStatus)
                || ReviewStatusEnum.MANUAL_REVIEW.getStatus().equals(reviewStatus);
        if (!undecided) {
            throw exception(CARGO_AUDIT_STATUS_ILLEGAL);
        }
        CargoOrderDO upd = new CargoOrderDO();
        upd.setId(cargo.getId());
        if (Boolean.TRUE.equals(reqVO.getPass())) {
            // 通过：审核通过 → 可进调度池（生命周期转 READY_FOR_POOL）
            upd.setAuditStatus(1);
            upd.setReviewStatus(ReviewStatusEnum.PASSED.getStatus());
            TransportOrderDO orderUpd = new TransportOrderDO();
            orderUpd.setId(order.getId());
            orderUpd.setStatus(TransportOrderStatusEnum.READY_FOR_POOL.getStatus());
            orderMapper.updateById(orderUpd);
        } else {
            // 拒绝（危险品/违禁品等）：原因必填，标记拒绝 + 原因，主表置取消，村民查件可见
            if (StrUtil.isBlank(reqVO.getRejectReason())) {
                throw exception(BAD_REQUEST);
            }
            upd.setAuditStatus(2);
            upd.setRejectReason(reqVO.getRejectReason());
            upd.setReviewStatus(ReviewStatusEnum.REJECTED.getStatus());
            TransportOrderDO orderUpd = new TransportOrderDO();
            orderUpd.setId(order.getId());
            orderUpd.setStatus(TransportOrderStatusEnum.CANCELLED.getStatus());
            orderMapper.updateById(orderUpd);
        }
        // CAS：仅当审核状态仍为读取时的 undecided 值时落库，防并发两次 audit（一通过一拒绝）互相覆盖
        LambdaQueryWrapperX<CargoOrderDO> auditCas = new LambdaQueryWrapperX<CargoOrderDO>()
                .eq(CargoOrderDO::getId, cargo.getId());
        if (reviewStatus == null) {
            auditCas.isNull(CargoOrderDO::getReviewStatus);
        } else {
            auditCas.eq(CargoOrderDO::getReviewStatus, reviewStatus);
        }
        if (cargoOrderMapper.update(upd, auditCas) == 0) {
            throw exception(CARGO_AUDIT_STATUS_ILLEGAL);
        }
    }

    @Override
    @Transactional
    public void confirmStationAction(Long userId, Long orderId) {
        if (userId == null) {
            throw exception(SEND_ORDER_USER_NOT_LOGIN);
        }
        TransportOrderDO order = validateExists(orderId);
        // 归属校验：仅下单人本人可确认（防越权替他人确认）
        if (!Objects.equals(order.getMemberUserId(), userId)) {
            throw exception(SEND_ORDER_NOT_YOURS);
        }
        // 仅「待客户操作」状态可确认（防重复确认 / 状态不符）
        if (!Objects.equals(order.getStatus(), TransportOrderStatusEnum.WAITING_CUSTOMER_ACTION.getStatus())) {
            throw exception(SEND_ORDER_STATUS_ILLEGAL);
        }
        // CAS 条件更新：仅待客户操作 → 待入池，防并发重复确认
        TransportOrderDO orderUpd = new TransportOrderDO();
        orderUpd.setId(orderId);
        orderUpd.setStatus(TransportOrderStatusEnum.READY_FOR_POOL.getStatus());
        orderMapper.update(orderUpd, new LambdaQueryWrapperX<TransportOrderDO>()
                .eq(TransportOrderDO::getId, orderId)
                .eq(TransportOrderDO::getStatus, TransportOrderStatusEnum.WAITING_CUSTOMER_ACTION.getStatus()));
    }

    private TransportOrderDO validateExists(Long id) {
        TransportOrderDO o = orderMapper.selectById(id);
        if (o == null) throw exception(ORDER_NOT_EXISTS);
        return o;
    }

    private String generateOrderNo() {
        return "TP" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmssSSS"));
    }

    private void createPassengerOrder(Long orderId, TransportOrderCreateReqVO reqVO) {
        if (reqVO.getPassengerCount() == null) return;
        PassengerOrderDO sub = PassengerOrderDO.builder()
                .orderId(orderId)
                .passengerCount(reqVO.getPassengerCount())
                .contactName(reqVO.getContactName())
                .contactMobile(reqVO.getContactMobile())
                .build();
        passengerOrderMapper.insert(sub);
    }

    private void createCargoOrder(Long orderId, TransportOrderCreateReqVO reqVO) {
        // 管理端/小程序建单时没填"货物类别"也要落一条货运子单：
        // 子单缺失会让后续「承运审核」直接报"订单不存在"，或让调度拿不到件数/重量，
        // 现场表现就是"我随便填的单，审核/调度都过不去"。这里给个默认类别兜底。
        CargoOrderDO sub = CargoOrderDO.builder()
                .orderId(orderId)
                .cargoCategory(reqVO.getCargoCategory() != null ? reqVO.getCargoCategory() : "普通货物")
                .freshFlag(reqVO.getFreshFlag() != null && reqVO.getFreshFlag())
                .itemCount(reqVO.getCargoItemCount() != null ? reqVO.getCargoItemCount() : 1)
                .weightKg(reqVO.getCargoWeightKg())
                .volumeM3(reqVO.getCargoVolumeM3())
                .goodsName(reqVO.getGoodsName())
                .goodsNote(reqVO.getGoodsNote())
                .photoUrl(reqVO.getPhotoUrl())
                .receiverName(reqVO.getReceiverName())
                .receiverMobile(reqVO.getReceiverMobile())
                .receiverAddress(reqVO.getReceiverAddress())
                .auditStatus(1) // 管理端录入即视为已审核通过
                .build();
        cargoOrderMapper.insert(sub);
    }

    private void createPostalOrder(Long orderId, TransportOrderCreateReqVO reqVO) {
        if (reqVO.getMailNo() == null) return;
        PostalOrderDO sub = PostalOrderDO.builder()
                .orderId(orderId)
                .mailNo(reqVO.getMailNo())
                .carrierCode(reqVO.getCarrierCode())
                .itemCount(reqVO.getPostalItemCount() != null ? reqVO.getPostalItemCount() : 1)
                .weightKg(reqVO.getPostalWeightKg())
                .receiverName(reqVO.getReceiverName())
                .receiverMobile(reqVO.getReceiverMobile())
                .receiverAddress(reqVO.getReceiverAddress())
                .pickupCode(RandomUtil.randomNumbers(6)) // 6 位数字取件码，收件人凭码取件
                .pickupStatus(0)
                .build();
        postalOrderMapper.insert(sub);
    }

    /** 编辑保留子表流程字段：客运仅更新联系人与人数 */
    private void updatePassengerOrder(Long orderId, TransportOrderUpdateReqVO reqVO) {
        if (reqVO.getPassengerCount() == null) return;
        PassengerOrderDO exist = passengerOrderMapper.selectOne(PassengerOrderDO::getOrderId, orderId);
        if (exist == null) {
            createPassengerOrder(orderId, reqVO);
            return;
        }
        PassengerOrderDO upd = new PassengerOrderDO();
        upd.setId(exist.getId());
        upd.setPassengerCount(reqVO.getPassengerCount());
        upd.setContactName(reqVO.getContactName());
        upd.setContactMobile(reqVO.getContactMobile());
        passengerOrderMapper.updateById(upd);
    }

    /** 编辑保留子表流程字段：货运不覆盖 auditStatus/rejectReason/driverPhotoUrl（审核与装车凭证） */
    private void updateCargoOrder(Long orderId, TransportOrderUpdateReqVO reqVO) {
        if (reqVO.getCargoCategory() == null) return;
        CargoOrderDO exist = cargoOrderMapper.selectOne(CargoOrderDO::getOrderId, orderId);
        if (exist == null) {
            createCargoOrder(orderId, reqVO);
            return;
        }
        CargoOrderDO upd = new CargoOrderDO();
        upd.setId(exist.getId());
        upd.setCargoCategory(reqVO.getCargoCategory());
        upd.setFreshFlag(reqVO.getFreshFlag() != null && reqVO.getFreshFlag());
        upd.setItemCount(reqVO.getCargoItemCount() != null ? reqVO.getCargoItemCount() : 1);
        upd.setWeightKg(reqVO.getCargoWeightKg());
        upd.setVolumeM3(reqVO.getCargoVolumeM3());
        upd.setGoodsName(reqVO.getGoodsName());
        upd.setGoodsNote(reqVO.getGoodsNote());
        upd.setPhotoUrl(reqVO.getPhotoUrl());
        upd.setReceiverName(reqVO.getReceiverName());
        upd.setReceiverMobile(reqVO.getReceiverMobile());
        upd.setReceiverAddress(reqVO.getReceiverAddress());
        cargoOrderMapper.updateById(upd);
    }

    /** 编辑保留子表流程字段：邮快件不覆盖 pickupCode/pickupStatus/pickedUpTime/pickerMemberUserId（取件码与核销记录） */
    private void updatePostalOrder(Long orderId, TransportOrderUpdateReqVO reqVO) {
        if (reqVO.getMailNo() == null) return;
        PostalOrderDO exist = postalOrderMapper.selectOne(PostalOrderDO::getOrderId, orderId);
        if (exist == null) {
            createPostalOrder(orderId, reqVO);
            return;
        }
        PostalOrderDO upd = new PostalOrderDO();
        upd.setId(exist.getId());
        upd.setMailNo(reqVO.getMailNo());
        upd.setCarrierCode(reqVO.getCarrierCode());
        upd.setItemCount(reqVO.getPostalItemCount() != null ? reqVO.getPostalItemCount() : 1);
        upd.setWeightKg(reqVO.getPostalWeightKg());
        upd.setReceiverName(reqVO.getReceiverName());
        upd.setReceiverMobile(reqVO.getReceiverMobile());
        upd.setReceiverAddress(reqVO.getReceiverAddress());
        postalOrderMapper.updateById(upd);
    }

    private void deletePassengerOrder(Long orderId) {
        passengerOrderMapper.delete(new LambdaQueryWrapperX<PassengerOrderDO>()
                .eq(PassengerOrderDO::getOrderId, orderId));
    }

    private void deleteCargoOrder(Long orderId) {
        cargoOrderMapper.delete(new LambdaQueryWrapperX<CargoOrderDO>()
                .eq(CargoOrderDO::getOrderId, orderId));
    }

    private void deletePostalOrder(Long orderId) {
        postalOrderMapper.delete(new LambdaQueryWrapperX<PostalOrderDO>()
                .eq(PostalOrderDO::getOrderId, orderId));
    }
}
