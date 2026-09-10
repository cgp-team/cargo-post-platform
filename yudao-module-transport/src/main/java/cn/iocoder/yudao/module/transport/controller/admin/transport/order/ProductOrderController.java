package cn.iocoder.yudao.module.transport.controller.admin.transport.order;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.framework.common.util.object.BeanUtils;
import cn.iocoder.yudao.module.transport.controller.admin.transport.order.vo.*;
import cn.iocoder.yudao.module.transport.dal.dataobject.order.ProductOrderDO;
import cn.iocoder.yudao.module.transport.dal.dataobject.order.ProductOrderItemDO;
import cn.iocoder.yudao.module.transport.dal.dataobject.driver.DriverDO;
import cn.iocoder.yudao.module.transport.dal.dataobject.shift.ShiftDO;
import cn.iocoder.yudao.module.transport.dal.dataobject.station.StationDO;
import cn.iocoder.yudao.module.transport.dal.dataobject.vehicle.VehicleDO;
import cn.iocoder.yudao.module.transport.dal.mysql.driver.DriverMapper;
import cn.iocoder.yudao.module.transport.dal.mysql.shift.ShiftMapper;
import cn.iocoder.yudao.module.transport.dal.mysql.station.StationMapper;
import cn.iocoder.yudao.module.transport.dal.mysql.vehicle.VehicleMapper;
import cn.iocoder.yudao.module.transport.enums.transport.ProductOrderStatusEnum;
import cn.iocoder.yudao.module.transport.service.transport.order.ProductOrderService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.List;

import static cn.iocoder.yudao.framework.common.pojo.CommonResult.success;

@Tag(name = "管理后台 - 商品订单管理")
@RestController
@RequestMapping("/transport/product-order")
@Validated
public class ProductOrderController {

    @Resource private ProductOrderService productOrderService;
    @Resource private VehicleMapper vehicleMapper;
    @Resource private ShiftMapper shiftMapper;
    @Resource private DriverMapper driverMapper;
    @Resource private StationMapper stationMapper;

    @GetMapping("/page")
    @Operation(summary = "获得商城订单分页")
    @PreAuthorize("@ss.hasPermission('transport:product-order:query')")
    public CommonResult<PageResult<ProductOrderRespVO>> page(@Valid ProductOrderPageReqVO reqVO) {
        PageResult<ProductOrderDO> pageResult = productOrderService.getPage(reqVO);
        List<ProductOrderRespVO> list = pageResult.getList().stream()
                .map(this::toVO)
                .toList();
        return success(new PageResult<>(list, pageResult.getTotal()));
    }

    @GetMapping("/get")
    @Operation(summary = "获得商城订单")
    @Parameter(name = "id", description = "订单编号", required = true)
    @PreAuthorize("@ss.hasPermission('transport:product-order:query')")
    public CommonResult<ProductOrderRespVO> get(@RequestParam("id") Long id) {
        return success(toVO(productOrderService.get(id)));
    }

    @PutMapping("/ship")
    @Operation(summary = "订单发货")
    @PreAuthorize("@ss.hasPermission('transport:product-order:ship')")
    public CommonResult<Boolean> ship(@Valid @RequestBody ProductOrderShipReqVO reqVO) {
        productOrderService.ship(reqVO.getId(), reqVO.getVehicleId(), reqVO.getShiftId());
        return success(true);
    }

    @PutMapping("/complete")
    @Operation(summary = "订单完成")
    @Parameter(name = "id", description = "订单编号", required = true)
    @PreAuthorize("@ss.hasPermission('transport:product-order:complete')")
    public CommonResult<Boolean> complete(@RequestParam("id") Long id) {
        productOrderService.complete(id);
        return success(true);
    }

    private ProductOrderRespVO toVO(ProductOrderDO order) {
        ProductOrderRespVO vo = BeanUtils.toBean(order, ProductOrderRespVO.class);
        vo.setStatusName(ProductOrderStatusEnum.nameOf(order.getStatus()));
        // 司机执行闭环展示：承运车牌/班次/司机/交付站点（后台一眼看到"谁在送、送到哪、有没有装车妥投凭证"）
        if (order.getVehicleId() != null) {
            VehicleDO vehicle = vehicleMapper.selectById(order.getVehicleId());
            vo.setVehiclePlate(vehicle != null ? vehicle.getPlateNo() : null);
        }
        if (order.getShiftId() != null) {
            ShiftDO shift = shiftMapper.selectById(order.getShiftId());
            vo.setShiftCode(shift != null ? shift.getShiftCode() : null);
        }
        if (order.getDriverId() != null) {
            DriverDO driver = driverMapper.selectById(order.getDriverId());
            if (driver != null) {
                vo.setDriverName(driver.getName());
                vo.setDriverMobile(driver.getMobile());
            }
        }
        if (order.getDeliverStationId() != null) {
            StationDO station = stationMapper.selectById(order.getDeliverStationId());
            vo.setDeliverStationName(station != null ? station.getStationName() : null);
        }
        List<ProductOrderItemDO> items = productOrderService.getItemsByOrderId(order.getId());
        vo.setItems(BeanUtils.toBean(items, ProductOrderItemRespVO.class));
        return vo;
    }
}
