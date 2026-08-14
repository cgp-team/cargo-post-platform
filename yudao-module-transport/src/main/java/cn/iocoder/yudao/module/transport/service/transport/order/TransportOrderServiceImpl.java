package cn.iocoder.yudao.module.transport.service.transport.order;

import cn.hutool.core.util.RandomUtil;
import cn.iocoder.yudao.framework.common.pojo.PageParam;
import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.framework.mybatis.core.query.LambdaQueryWrapperX;
import cn.iocoder.yudao.module.transport.controller.admin.transport.order.vo.*;
import cn.iocoder.yudao.module.transport.controller.app.transport.send.vo.AppSendOrderCreateReqVO;
import cn.iocoder.yudao.module.transport.convert.transport.order.TransportOrderConvert;
import cn.iocoder.yudao.module.transport.dal.dataobject.order.*;
import cn.iocoder.yudao.module.transport.dal.mysql.order.*;
import cn.iocoder.yudao.module.transport.enums.dispatch.TransportOrderStatusEnum;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.annotation.Validated;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Objects;

import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.module.transport.enums.ErrorCodeConstants.CARGO_AUDIT_ONLY_CARGO;
import static cn.iocoder.yudao.module.transport.enums.ErrorCodeConstants.ORDER_NOT_EXISTS;
import static cn.iocoder.yudao.module.transport.enums.ErrorCodeConstants.SEND_ORDER_USER_NOT_LOGIN;

@Service
@Validated
public class TransportOrderServiceImpl implements TransportOrderService {

    @Resource private TransportOrderMapper orderMapper;
    @Resource private PassengerOrderMapper passengerOrderMapper;
    @Resource private CargoOrderMapper cargoOrderMapper;
    @Resource private PostalOrderMapper postalOrderMapper;

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
        // Update sub-orders
        Integer orderType = reqVO.getOrderType();
        if (orderType != null) {
            switch (orderType) {
                case 1 -> { deletePassengerOrder(reqVO.getId()); createPassengerOrder(reqVO.getId(), reqVO); }
                case 2 -> { deleteCargoOrder(reqVO.getId()); createCargoOrder(reqVO.getId(), reqVO); }
                case 3 -> { deletePostalOrder(reqVO.getId()); createPostalOrder(reqVO.getId(), reqVO); }
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
        // 主表：货运订单，status=0 待调度，天然可被调度员归集入池
        TransportOrderDO order = TransportOrderDO.builder()
                .orderNo(generateOrderNo())
                .orderType(2) // 货运/生鲜
                .pickupStationId(reqVO.getPickupStationId())
                .deliveryStationId(reqVO.getDeliveryStationId())
                .earliestPickupTime(reqVO.getEarliestPickupTime())
                .status(TransportOrderStatusEnum.CREATED.getStatus())
                .memberUserId(userId)
                .build();
        orderMapper.insert(order);
        // 货运子表：寄货货物信息
        CargoOrderDO sub = CargoOrderDO.builder()
                .orderId(order.getId())
                .cargoCategory("农产品")
                .freshFlag(true)
                .itemCount(1)
                .weightKg(reqVO.getGoodsWeight())
                .goodsName(reqVO.getGoodsName())
                .goodsNote(reqVO.getGoodsNote())
                .photoUrl(reqVO.getPhotoUrl())
                .receiverName(reqVO.getReceiverName())
                .receiverMobile(reqVO.getReceiverMobile())
                .receiverAddress(reqVO.getReceiverAddress())
                .build();
        cargoOrderMapper.insert(sub);
        return order.getId();
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
        CargoOrderDO upd = new CargoOrderDO();
        upd.setId(cargo.getId());
        if (Boolean.TRUE.equals(reqVO.getPass())) {
            // 通过：标记审核通过，可进调度池
            upd.setAuditStatus(1);
        } else {
            // 拒绝（危险品/违禁品等）：标记拒绝 + 原因，主表置取消，村民查件可见
            upd.setAuditStatus(2);
            upd.setRejectReason(reqVO.getRejectReason());
            TransportOrderDO orderUpd = new TransportOrderDO();
            orderUpd.setId(order.getId());
            orderUpd.setStatus(TransportOrderStatusEnum.CANCELLED.getStatus());
            orderMapper.updateById(orderUpd);
        }
        cargoOrderMapper.updateById(upd);
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
        if (reqVO.getCargoCategory() == null) return;
        CargoOrderDO sub = CargoOrderDO.builder()
                .orderId(orderId)
                .cargoCategory(reqVO.getCargoCategory())
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
