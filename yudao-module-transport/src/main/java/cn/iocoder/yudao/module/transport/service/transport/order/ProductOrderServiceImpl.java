package cn.iocoder.yudao.module.transport.service.transport.order;

import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.framework.mybatis.core.query.LambdaQueryWrapperX;
import cn.hutool.core.util.StrUtil;
import cn.iocoder.yudao.module.transport.controller.admin.transport.order.vo.ProductOrderPageReqVO;
import cn.iocoder.yudao.module.transport.controller.app.transport.order.vo.AppProductOrderCreateReqVO;
import cn.iocoder.yudao.module.transport.controller.app.transport.order.vo.AppProductOrderTraceRespVO;
import cn.iocoder.yudao.module.transport.dal.dataobject.order.ProductOrderDO;
import cn.iocoder.yudao.module.transport.dal.dataobject.order.ProductOrderItemDO;
import cn.iocoder.yudao.module.transport.dal.dataobject.driver.DriverVehicleDO;
import cn.iocoder.yudao.module.transport.dal.dataobject.driver.DriverDO;
import cn.iocoder.yudao.module.transport.dal.dataobject.shift.ShiftExecutionDO;
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
import cn.iocoder.yudao.module.transport.dal.mysql.driver.DriverVehicleMapper;
import cn.iocoder.yudao.module.transport.dal.mysql.driver.DriverMapper;
import cn.iocoder.yudao.module.transport.dal.mysql.shift.ShiftExecutionMapper;
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
import java.util.Comparator;
import java.util.Objects;
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
    @Resource private DriverVehicleMapper driverVehicleMapper;
    @Resource private DriverMapper driverMapper;
    @Resource private ShiftExecutionMapper shiftExecutionMapper;

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
        // 司机端任务归属 + 交付站点：发货即派单给"该车绑定的司机"，交付点=班次线路终点站
        update.setDriverId(resolveDriverId(vehicleId));
        update.setDeliverStationId(resolveDeliverStationId(shiftId));
        orderMapper.updateById(update);
    }

    /** 承运司机：按人车绑定取在职司机（无绑定时为 null，司机端看不到该单，管理员可代发） */
    private Long resolveDriverId(Long vehicleId) {
        if (vehicleId == null || driverVehicleMapper == null) {
            return null;
        }
        return driverVehicleMapper.selectActiveBindings().stream()
                .filter(b -> Objects.equals(b.getVehicleId(), vehicleId))
                .map(DriverVehicleDO::getDriverId)
                .findFirst().orElse(null);
    }

    /** 交付站点：班次线路的终点站（sequence_no 最大），小程序"司机已到达"据此判定 */
    private Long resolveDeliverStationId(Long shiftId) {
        if (shiftId == null) {
            return null;
        }
        ShiftDO shift = shiftMapper.selectById(shiftId);
        if (shift == null || shift.getRouteId() == null) {
            return null;
        }
        return routeStationMapper.selectListByRouteId(shift.getRouteId()).stream()
                .max(Comparator.comparing(rs -> rs.getSequenceNo() == null ? 0 : rs.getSequenceNo()))
                .map(RouteStationDO::getStationId)
                .orElse(null);
    }

    @Override
    public List<ProductOrderDO> getDriverDeliveryTasks(Long vehicleId) {
        if (vehicleId == null) {
            return List.of();
        }
        // 已发货且尚未妥投的商城单：司机端"待装车/待妥投"任务
        return orderMapper.selectList(new LambdaQueryWrapperX<ProductOrderDO>()
                .eq(ProductOrderDO::getVehicleId, vehicleId)
                .eq(ProductOrderDO::getStatus, ProductOrderStatusEnum.DELIVERED.getStatus())
                .isNull(ProductOrderDO::getDeliverTime)
                .orderByDesc(ProductOrderDO::getId));
    }

    @Override
    @Transactional
    public void driverLoad(Long driverId, Long vehicleId, Long orderId, String photoUrl) {
        ProductOrderDO order = validateDriverTask(driverId, vehicleId, orderId);
        ProductOrderDO update = new ProductOrderDO();
        update.setId(order.getId());
        update.setLoadPhotoUrl(StrUtil.blankToDefault(photoUrl, ""));
        update.setLoadTime(LocalDateTime.now());
        orderMapper.updateById(update);
    }

    @Override
    @Transactional
    public void driverDeliver(Long driverId, Long vehicleId, Long orderId, String photoUrl) {
        ProductOrderDO order = validateDriverTask(driverId, vehicleId, orderId);
        ProductOrderDO update = new ProductOrderDO();
        update.setId(order.getId());
        update.setStatus(ProductOrderStatusEnum.COMPLETED.getStatus());
        update.setDeliverPhotoUrl(StrUtil.blankToDefault(photoUrl, ""));
        update.setDeliverTime(LocalDateTime.now());
        orderMapper.updateById(update);
    }

    /** 司机端动作前置校验：订单存在 + 承运车辆=司机绑定车辆 + 已发货 + 未妥投 */
    private ProductOrderDO validateDriverTask(Long driverId, Long vehicleId, Long orderId) {
        ProductOrderDO order = validateExists(orderId);
        if (vehicleId == null || !Objects.equals(order.getVehicleId(), vehicleId)) {
            throw exception(DRIVER_ORDER_NOT_ASSIGNED);
        }
        if (driverId != null && order.getDriverId() != null && !Objects.equals(order.getDriverId(), driverId)) {
            throw exception(DRIVER_ORDER_NOT_ASSIGNED);
        }
        if (!ProductOrderStatusEnum.DELIVERED.getStatus().equals(order.getStatus()) || order.getDeliverTime() != null) {
            throw exception(PRODUCT_ORDER_STATUS_ILLEGAL);
        }
        return order;
    }

    // ==================== 用户端展示辅助（司机信息 / 是否已到达交付站点） ====================

    private String driverNameOf(Long driverId) {
        DriverDO driver = driverId == null ? null : driverMapper.selectById(driverId);
        return driver != null ? driver.getName() : null;
    }

    private String driverMobileOf(Long driverId) {
        DriverDO driver = driverId == null ? null : driverMapper.selectById(driverId);
        return driver != null ? driver.getMobile() : null;
    }

    /**
     * 司机是否已到达交付站点：当班次执行记录（该班次+司机+今天）的当前站点 == 交付站点，
     * 或执行记录已完成。数据源是司机端"确认到达"写入的真实状态（后端为源），未发车时为 false。
     */
    private Boolean resolveDriverArrived(ProductOrderDO order) {
        if (order.getShiftId() == null || order.getDriverId() == null) {
            return Boolean.FALSE;
        }
        ShiftExecutionDO execution = shiftExecutionMapper.selectByShiftAndDriverAndDate(
                order.getShiftId(), order.getDriverId(), LocalDate.now());
        if (execution == null) {
            return Boolean.FALSE;
        }
        if (execution.getStatus() != null && execution.getStatus() == 1) {
            return Boolean.TRUE; // 执行记录已完成 = 跑完整条线路
        }
        return order.getDeliverStationId() != null
                && Objects.equals(execution.getCurrentStationId(), order.getDeliverStationId());
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
        vo.setOrderNo(order.getOrderNo());
        vo.setStatus(order.getStatus());
        vo.setStatusName(ProductOrderStatusEnum.nameOf(order.getStatus()));
        vo.setReceiverName(order.getReceiverName());
        vo.setReceiverMobile(order.getReceiverMobile());
        vo.setReceiverAddress(order.getReceiverAddress());
        // 司机作业凭证（装车拍照 / 妥投凭证）：用户端"司机已到达 + 已装车/已送达"直接展示
        vo.setDriverName(driverNameOf(order.getDriverId()));
        vo.setDriverMobile(driverMobileOf(order.getDriverId()));
        vo.setLoadTime(order.getLoadTime());
        vo.setLoadPhotoUrl(order.getLoadPhotoUrl());
        vo.setDeliverTime(order.getDeliverTime());
        vo.setDeliverPhotoUrl(order.getDeliverPhotoUrl());
        vo.setDriverArrived(resolveDriverArrived(order));
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
