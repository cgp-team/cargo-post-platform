package cn.iocoder.yudao.module.transport.service.transport.order;

import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.module.transport.controller.admin.transport.order.vo.ProductOrderPageReqVO;
import cn.iocoder.yudao.module.transport.controller.app.transport.order.vo.AppProductOrderCreateReqVO;
import cn.iocoder.yudao.module.transport.controller.app.transport.order.vo.AppProductOrderTraceRespVO;
import cn.iocoder.yudao.module.transport.dal.dataobject.order.ProductOrderDO;
import cn.iocoder.yudao.module.transport.dal.dataobject.order.ProductOrderItemDO;
import cn.iocoder.yudao.module.transport.dal.dataobject.product.ProductDO;
import cn.iocoder.yudao.module.transport.dal.dataobject.route.RouteDO;
import cn.iocoder.yudao.module.transport.dal.dataobject.route.RouteStationDO;
import cn.iocoder.yudao.module.transport.dal.dataobject.shift.ShiftDO;
import cn.iocoder.yudao.module.transport.dal.dataobject.station.StationDO;
import cn.iocoder.yudao.module.transport.dal.dataobject.vehicle.VehicleDO;
import cn.iocoder.yudao.module.transport.dal.dataobject.vehicle.VehicleLocationDO;
import cn.iocoder.yudao.module.transport.dal.dataobject.vehicle.VehicleLocationTrackDO;
import cn.iocoder.yudao.module.transport.dal.mysql.order.ProductOrderItemMapper;
import cn.iocoder.yudao.module.transport.dal.mysql.order.ProductOrderMapper;
import cn.iocoder.yudao.module.transport.dal.mysql.product.ProductMapper;
import cn.iocoder.yudao.module.transport.dal.mysql.route.RouteMapper;
import cn.iocoder.yudao.module.transport.dal.mysql.route.RouteStationMapper;
import cn.iocoder.yudao.module.transport.dal.mysql.shift.ShiftMapper;
import cn.iocoder.yudao.module.transport.dal.mysql.station.StationMapper;
import cn.iocoder.yudao.module.transport.dal.mysql.vehicle.VehicleLocationMapper;
import cn.iocoder.yudao.module.transport.dal.mysql.vehicle.VehicleLocationTrackMapper;
import cn.iocoder.yudao.module.transport.dal.mysql.vehicle.VehicleMapper;
import cn.iocoder.yudao.module.transport.enums.transport.ProductOrderStatusEnum;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.annotation.Validated;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.module.transport.enums.ErrorCodeConstants.*;

@Service
@Validated
public class ProductOrderServiceImpl implements ProductOrderService {

    @Resource private ProductOrderMapper orderMapper;
    @Resource private ProductOrderItemMapper itemMapper;
    @Resource private ProductMapper productMapper;
    @Resource private VehicleMapper vehicleMapper;
    @Resource private ShiftMapper shiftMapper;
    @Resource private RouteMapper routeMapper;
    @Resource private RouteStationMapper routeStationMapper;
    @Resource private StationMapper stationMapper;
    @Resource private VehicleLocationMapper vehicleLocationMapper;
    @Resource private VehicleLocationTrackMapper vehicleLocationTrackMapper;

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
    public void ship(Long id, Long vehicleId, Long shiftId) {
        ProductOrderDO order = validateExists(id);
        if (!ProductOrderStatusEnum.PENDING_DELIVERY.getStatus().equals(order.getStatus())) {
            throw exception(PRODUCT_ORDER_STATUS_ILLEGAL);
        }
        ProductOrderDO update = new ProductOrderDO();
        update.setId(id);
        update.setStatus(ProductOrderStatusEnum.DELIVERED.getStatus());
        update.setVehicleId(vehicleId);
        update.setShiftId(shiftId);
        orderMapper.updateById(update);
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

    @Override
    public AppProductOrderTraceRespVO getTrace(Long userId, Long id) {
        if (userId == null) {
            throw exception(PRODUCT_ORDER_USER_NOT_LOGIN);
        }
        ProductOrderDO order = validateExists(id);
        if (!order.getUserId().equals(userId)) {
            throw exception(PRODUCT_ORDER_NOT_YOURS);
        }
        AppProductOrderTraceRespVO vo = new AppProductOrderTraceRespVO();
        vo.setOrderId(order.getId());
        vo.setPoints(List.of());
        vo.setTrack(List.of());
        List<ProductOrderItemDO> items = itemMapper.selectListByOrderId(id);
        if (!items.isEmpty()) {
            vo.setProductName(items.get(0).getProductName());
        }
        // 未关联承运车辆（未发货/发货未填承运）：返回空语义，小程序据此显示「商品还未发车」
        if (order.getVehicleId() == null) {
            return vo;
        }
        VehicleDO vehicle = vehicleMapper.selectById(order.getVehicleId());
        if (vehicle != null) {
            vo.setVehiclePlate(vehicle.getPlateNo());
        }
        // 班次 → 线路 → 线路站点（points 组装参照 AppBusServiceImpl.getLines）
        ShiftDO shift = order.getShiftId() != null ? shiftMapper.selectById(order.getShiftId()) : null;
        if (shift != null) {
            vo.setShiftCode(shift.getShiftCode());
            RouteDO route = shift.getRouteId() != null ? routeMapper.selectById(shift.getRouteId()) : null;
            if (route != null) {
                vo.setRouteName(route.getRouteName());
                fillRoutePoints(vo, route.getId());
            }
        }
        // 当天轨迹（时间升序，最多 2000 点防大包）
        List<VehicleLocationTrackDO> tracks = vehicleLocationTrackMapper.selectByVehicleIdSince(
                order.getVehicleId(), LocalDate.now().atStartOfDay(), 2000);
        vo.setTrack(tracks.stream().map(t -> {
            AppProductOrderTraceRespVO.TrackPoint point = new AppProductOrderTraceRespVO.TrackPoint();
            point.setLongitude(t.getLongitude());
            point.setLatitude(t.getLatitude());
            point.setSpeedKmh(t.getSpeedKmh());
            point.setReportTime(t.getReportTime());
            return point;
        }).toList());
        // 最新位置（每车一行）
        VehicleLocationDO location = vehicleLocationMapper.selectByVehicleId(order.getVehicleId());
        if (location != null) {
            vo.setCurrentLongitude(location.getLongitude());
            vo.setCurrentLatitude(location.getLatitude());
            vo.setLastReportTime(location.getReportTime());
        }
        return vo;
    }

    /** 线路站点序列 → points，并取首末站点名为起终点站（同 AppBusServiceImpl 口径） */
    private void fillRoutePoints(AppProductOrderTraceRespVO vo, Long routeId) {
        List<RouteStationDO> routeStations = routeStationMapper.selectListByRouteId(routeId);
        if (routeStations.isEmpty()) {
            return;
        }
        Map<Long, StationDO> stationMap = stationMapper.selectList(StationDO::getId,
                        routeStations.stream().map(RouteStationDO::getStationId).toList())
                .stream().collect(Collectors.toMap(StationDO::getId, Function.identity()));
        vo.setPoints(routeStations.stream().map(rs -> {
            AppProductOrderTraceRespVO.Point point = new AppProductOrderTraceRespVO.Point();
            point.setSequenceNo(rs.getSequenceNo());
            point.setPlannedMinutes(rs.getPlannedMinutes());
            StationDO station = stationMap.get(rs.getStationId());
            if (station != null) {
                point.setStationName(station.getStationName());
                point.setLongitude(station.getLongitude());
                point.setLatitude(station.getLatitude());
            }
            return point;
        }).toList());
        vo.setStartStation(vo.getPoints().get(0).getStationName());
        vo.setEndStation(vo.getPoints().get(vo.getPoints().size() - 1).getStationName());
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
