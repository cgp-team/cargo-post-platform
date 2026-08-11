package cn.iocoder.yudao.module.transport.service.transport.order;

import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.module.transport.controller.admin.transport.order.vo.ProductOrderPageReqVO;
import cn.iocoder.yudao.module.transport.controller.app.transport.order.vo.AppProductOrderCreateReqVO;
import cn.iocoder.yudao.module.transport.dal.dataobject.order.ProductOrderDO;
import cn.iocoder.yudao.module.transport.dal.dataobject.order.ProductOrderItemDO;
import cn.iocoder.yudao.module.transport.dal.dataobject.product.ProductDO;
import cn.iocoder.yudao.module.transport.dal.mysql.order.ProductOrderItemMapper;
import cn.iocoder.yudao.module.transport.dal.mysql.order.ProductOrderMapper;
import cn.iocoder.yudao.module.transport.dal.mysql.product.ProductMapper;
import cn.iocoder.yudao.module.transport.enums.transport.ProductOrderStatusEnum;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.annotation.Validated;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.module.transport.enums.ErrorCodeConstants.*;

@Service
@Validated
public class ProductOrderServiceImpl implements ProductOrderService {

    @Resource private ProductOrderMapper orderMapper;
    @Resource private ProductOrderItemMapper itemMapper;
    @Resource private ProductMapper productMapper;

    @Override
    @Transactional
    public Long createOrder(Long userId, AppProductOrderCreateReqVO reqVO) {
        if (userId == null) {
            throw exception(PRODUCT_ORDER_USER_NOT_LOGIN);
        }
        // 1. 校验商品
        ProductDO product = productMapper.selectById(reqVO.getProductId());
        if (product == null) {
            throw exception(PRODUCT_NOT_EXISTS);
        }
        if (product.getStatus() != null && product.getStatus() != 0) {
            throw exception(PRODUCT_OFF_SHELF);
        }
        // 2. 扣库存（stock >= quantity 条件，返回 0 即库存不足）
        if (productMapper.deductStock(product.getId(), reqVO.getQuantity()) == 0) {
            throw exception(PRODUCT_STOCK_NOT_ENOUGH);
        }
        // 3. 金额计算
        BigDecimal price = product.getPrice() != null ? product.getPrice() : BigDecimal.ZERO;
        BigDecimal amount = price.multiply(BigDecimal.valueOf(reqVO.getQuantity()));
        // 4. 订单主表
        ProductOrderDO order = ProductOrderDO.builder()
                .orderNo(generateOrderNo())
                .userId(userId)
                .userMobile(reqVO.getUserMobile())
                .totalAmount(amount)
                .status(ProductOrderStatusEnum.PENDING_DELIVERY.getStatus())
                .receiverName(reqVO.getReceiverName())
                .receiverMobile(reqVO.getReceiverMobile())
                .receiverAddress(reqVO.getReceiverAddress())
                .remark(reqVO.getRemark())
                .build();
        orderMapper.insert(order);
        // 5. 订单明细（商品快照，防改价/下架影响历史订单）
        itemMapper.insert(ProductOrderItemDO.builder()
                .orderId(order.getId())
                .productId(product.getId())
                .productName(product.getName())
                .productImage(product.getImage())
                .productPrice(price)
                .quantity(reqVO.getQuantity())
                .amount(amount)
                .build());
        return order.getId();
    }

    @Override
    public PageResult<ProductOrderDO> getMyPage(Long userId, ProductOrderPageReqVO reqVO) {
        if (userId == null) {
            throw exception(PRODUCT_ORDER_USER_NOT_LOGIN);
        }
        return orderMapper.selectPageByUser(reqVO, userId);
    }

    @Override
    @Transactional
    public void cancel(Long userId, Long id) {
        if (userId == null) {
            throw exception(PRODUCT_ORDER_USER_NOT_LOGIN);
        }
        ProductOrderDO order = validateExists(id);
        if (!order.getUserId().equals(userId)) {
            throw exception(PRODUCT_ORDER_NOT_YOURS);
        }
        if (!ProductOrderStatusEnum.PENDING_DELIVERY.getStatus().equals(order.getStatus())) {
            throw exception(PRODUCT_ORDER_STATUS_ILLEGAL);
        }
        // 恢复库存
        for (ProductOrderItemDO item : itemMapper.selectListByOrderId(id)) {
            productMapper.restoreStock(item.getProductId(), item.getQuantity());
        }
        // 置取消
        ProductOrderDO update = new ProductOrderDO();
        update.setId(id);
        update.setStatus(ProductOrderStatusEnum.CANCELLED.getStatus());
        orderMapper.updateById(update);
    }

    @Override
    public PageResult<ProductOrderDO> getPage(ProductOrderPageReqVO reqVO) {
        return orderMapper.selectPage(reqVO);
    }

    @Override
    public ProductOrderDO get(Long id) {
        return validateExists(id);
    }

    @Override
    @Transactional
    public void ship(Long id) {
        updateStatus(id, ProductOrderStatusEnum.PENDING_DELIVERY.getStatus(), ProductOrderStatusEnum.DELIVERED.getStatus());
    }

    @Override
    @Transactional
    public void complete(Long id) {
        updateStatus(id, ProductOrderStatusEnum.DELIVERED.getStatus(), ProductOrderStatusEnum.COMPLETED.getStatus());
    }

    @Override
    public List<ProductOrderItemDO> getItemsByOrderId(Long orderId) {
        return itemMapper.selectListByOrderId(orderId);
    }

    private void updateStatus(Long id, Integer fromStatus, Integer toStatus) {
        ProductOrderDO order = validateExists(id);
        if (!fromStatus.equals(order.getStatus())) {
            throw exception(PRODUCT_ORDER_STATUS_ILLEGAL);
        }
        ProductOrderDO update = new ProductOrderDO();
        update.setId(id);
        update.setStatus(toStatus);
        orderMapper.updateById(update);
    }

    private ProductOrderDO validateExists(Long id) {
        ProductOrderDO order = orderMapper.selectById(id);
        if (order == null) {
            throw exception(PRODUCT_ORDER_NOT_EXISTS);
        }
        return order;
    }

    private String generateOrderNo() {
        return "PO" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmssSSS"));
    }
}
