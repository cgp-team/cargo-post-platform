package cn.iocoder.yudao.module.transport.controller.admin.transport.order;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.framework.common.util.object.BeanUtils;
import cn.iocoder.yudao.module.transport.controller.admin.transport.order.vo.*;
import cn.iocoder.yudao.module.transport.dal.dataobject.order.CargoOrderDO;
import cn.iocoder.yudao.module.transport.dal.dataobject.order.PostalOrderDO;
import cn.iocoder.yudao.module.transport.dal.dataobject.order.TransportOrderDO;
import cn.iocoder.yudao.module.transport.dal.dataobject.station.StationDO;
import cn.iocoder.yudao.module.transport.dal.mysql.order.PostalOrderMapper;
import cn.iocoder.yudao.module.transport.dal.mysql.station.StationMapper;
import cn.iocoder.yudao.module.transport.service.transport.order.TransportOrderService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

import static cn.iocoder.yudao.framework.common.pojo.CommonResult.success;

@Tag(name = "管理后台 - 订单管理")
@RestController
@RequestMapping("/transport/order")
@Validated
public class TransportOrderController {
    @Resource private TransportOrderService orderService;
    @Resource private PostalOrderMapper postalOrderMapper;
    @Resource private StationMapper stationMapper;

    @PostMapping("/create")
    @Operation(summary = "创建订单")
    @PreAuthorize("@ss.hasPermission('transport:order:create')")
    public CommonResult<Long> create(@Valid @RequestBody TransportOrderCreateReqVO reqVO) {
        return success(orderService.create(reqVO));
    }

    @PostMapping("/audit")
    @Operation(summary = "审核货运订单（通过/拒绝，危险品/违禁品拒绝运输）")
    @PreAuthorize("@ss.hasPermission('transport:order:update')")
    public CommonResult<Boolean> audit(@Valid @RequestBody OrderAuditReqVO reqVO) {
        orderService.audit(reqVO);
        return success(true);
    }

    @PutMapping("/update")
    @Operation(summary = "更新订单")
    @PreAuthorize("@ss.hasPermission('transport:order:update')")
    public CommonResult<Boolean> update(@Valid @RequestBody TransportOrderUpdateReqVO reqVO) {
        orderService.update(reqVO); return success(true);
    }

    @DeleteMapping("/delete")
    @Operation(summary = "删除订单")
    @Parameter(name = "id", description = "编号", required = true)
    @PreAuthorize("@ss.hasPermission('transport:order:delete')")
    public CommonResult<Boolean> delete(@RequestParam("id") Long id) {
        orderService.delete(id); return success(true);
    }

    @GetMapping("/get")
    @Operation(summary = "获得订单")
    @Parameter(name = "id", description = "编号", required = true)
    @PreAuthorize("@ss.hasPermission('transport:order:query')")
    public CommonResult<TransportOrderRespVO> get(@RequestParam("id") Long id) {
        return success(toVO(orderService.get(id)));
    }

    @GetMapping("/page")
    @Operation(summary = "获得订单分页")
    @PreAuthorize("@ss.hasPermission('transport:order:query')")
    public CommonResult<PageResult<TransportOrderRespVO>> page(@Valid TransportOrderPageReqVO reqVO) {
        PageResult<TransportOrderDO> pageResult = orderService.getPage(reqVO);
        // 站点名/交接站名一次性查出来做映射，避免逐行查询（N+1）
        Map<Long, StationDO> stationMap = stationMapper.selectList().stream()
                .filter(s -> s.getId() != null)
                .collect(Collectors.toMap(StationDO::getId, Function.identity(), (a, b) -> a));
        List<TransportOrderRespVO> list = pageResult.getList().stream()
                .map(order -> toVO(order, stationMap))
                .toList();
        return success(new PageResult<>(list, pageResult.getTotal()));
    }

    /** 组装订单与货运/邮快件子表信息 */
    private TransportOrderRespVO toVO(TransportOrderDO order) {
        return toVO(order, null);
    }

    /** 组装订单 + 货运/邮快件子表信息 + 站点展示名（stationMap 为空时按需回查） */
    private TransportOrderRespVO toVO(TransportOrderDO order, Map<Long, StationDO> stationMap) {
        TransportOrderRespVO vo = BeanUtils.toBean(order, TransportOrderRespVO.class);
        // 站点名：取货站 / 送达站 / 交接服务站（后台列表不显示裸 ID）
        vo.setPickupStationName(stationName(order.getPickupStationId(), stationMap));
        vo.setDeliveryStationName(stationName(order.getDeliveryStationId(), stationMap));
        if (Objects.equals(order.getOrderType(), 3)) {
            // 邮快件：快递单号/取件码/核销状态/收件人
            PostalOrderDO postal = postalOrderMapper.selectOne(PostalOrderDO::getOrderId, order.getId());
            if (postal != null) {
                vo.setMailNo(postal.getMailNo());
                vo.setPickupCode(postal.getPickupCode());
                vo.setPickupStatus(postal.getPickupStatus());
                vo.setReceiverName(postal.getReceiverName());
                vo.setReceiverMobile(postal.getReceiverMobile());
                vo.setReceiverAddress(postal.getReceiverAddress());
            }
        } else {
            CargoOrderDO cargo = orderService.getCargoOrder(order.getId());
            if (cargo != null) {
                vo.setGoodsName(cargo.getGoodsName());
                vo.setGoodsNote(cargo.getGoodsNote());
                vo.setCargoWeightKg(cargo.getWeightKg());
                vo.setPhotoUrl(cargo.getPhotoUrl());
                vo.setDriverPhotoUrl(cargo.getDriverPhotoUrl());
                vo.setAuditStatus(cargo.getAuditStatus());
                vo.setRejectReason(cargo.getRejectReason());
                vo.setReceiverName(cargo.getReceiverName());
                vo.setReceiverMobile(cargo.getReceiverMobile());
                vo.setReceiverAddress(cargo.getReceiverAddress());
                // 寄货服务链路：用户原始位置 + 服务方式 + 交接站点
                vo.setOriginalAddress(cargo.getOriginalAddress());
                vo.setOriginalLatitude(cargo.getOriginalLatitude());
                vo.setOriginalLongitude(cargo.getOriginalLongitude());
                vo.setPickupServiceMode(cargo.getPickupServiceMode());
                vo.setDeliveryServiceMode(cargo.getDeliveryServiceMode());
                vo.setServicePointStationId(cargo.getServicePointStationId());
                // 交接点：显式服务点优先；没有显式服务点时就是取货站点本身（货物在该站交接）
                vo.setServicePointStationName(cargo.getServicePointStationId() != null
                        ? stationName(cargo.getServicePointStationId(), stationMap)
                        : vo.getPickupStationName());
                vo.setReviewStatus(cargo.getReviewStatus());
                vo.setReviewReasonCodes(cargo.getReviewReasonCodes());
                vo.setCargoCategory(cargo.getCargoCategory());
                vo.setFreshFlag(cargo.getFreshFlag());
                vo.setCargoItemCount(cargo.getItemCount());
                vo.setCargoVolumeM3(cargo.getVolumeM3());
            }
        }
        return vo;
    }

    /** 站点名解析：优先用调用方传入的站点映射；未命中时回查单条（get 接口场景） */
    private String stationName(Long stationId, Map<Long, StationDO> stationMap) {
        if (stationId == null) {
            return null;
        }
        StationDO station = stationMap != null ? stationMap.get(stationId) : stationMapper.selectById(stationId);
        return station != null ? station.getStationName() : null;
    }
}
