package cn.iocoder.yudao.module.transport.convert.transport.order;

import cn.iocoder.yudao.module.transport.dal.dataobject.order.ProductOrderDO;
import org.mapstruct.Mapper;
import org.mapstruct.factory.Mappers;

/**
 * 农产品商城订单 Convert。
 * <p>
 * 订单创建与明细构建走 {@link cn.iocoder.yudao.module.transport.service.transport.order.ProductOrderServiceImpl}
 * 手动组装（含商品快照），此处仅保留 DO 复制的通用转换入口，便于后续扩展。
 */
@Mapper
public interface ProductOrderConvert {
    ProductOrderConvert INSTANCE = Mappers.getMapper(ProductOrderConvert.class);

    ProductOrderDO convert(ProductOrderDO bean);
}
