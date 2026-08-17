package cn.iocoder.yudao.module.transport.controller.app.transport.send;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.framework.common.pojo.PageParam;
import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.framework.common.util.object.BeanUtils;
import cn.iocoder.yudao.framework.mybatis.core.query.LambdaQueryWrapperX;
import cn.iocoder.yudao.module.transport.controller.admin.transport.station.vo.StationSimpleRespVO;
import cn.iocoder.yudao.module.transport.controller.app.transport.send.vo.AppSendArrangementRespVO;
import cn.iocoder.yudao.module.transport.controller.app.transport.send.vo.AppSendOrderCreateReqVO;
import cn.iocoder.yudao.module.transport.controller.app.transport.send.vo.AppSendOrderRespVO;
import cn.iocoder.yudao.module.transport.dal.dataobject.dispatch.DispatchPlanDO;
import cn.iocoder.yudao.module.transport.dal.dataobject.dispatch.DispatchPlanItemDO;
import cn.iocoder.yudao.module.transport.dal.dataobject.order.CargoOrderDO;
import cn.iocoder.yudao.module.transport.dal.dataobject.order.PostalOrderDO;
import cn.iocoder.yudao.module.transport.dal.dataobject.order.TransportOrderDO;
import cn.iocoder.yudao.module.transport.dal.dataobject.station.StationDO;
import cn.iocoder.yudao.module.transport.dal.dataobject.vehicle.VehicleDO;
import cn.iocoder.yudao.module.transport.dal.mysql.dispatch.DispatchPlanItemMapper;
import cn.iocoder.yudao.module.transport.dal.mysql.dispatch.DispatchPlanMapper;
import cn.iocoder.yudao.module.transport.dal.mysql.order.PostalOrderMapper;
import cn.iocoder.yudao.module.transport.dal.mysql.order.TransportOrderMapper;
import cn.iocoder.yudao.module.transport.dal.mysql.vehicle.VehicleMapper;
import cn.iocoder.yudao.module.transport.enums.dispatch.TransportOrderStatusEnum;
import cn.iocoder.yudao.module.transport.service.transport.order.TransportOrderService;
import cn.iocoder.yudao.module.transport.service.transport.station.StationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import jakarta.annotation.security.PermitAll;
import jakarta.validation.Valid;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

import static cn.iocoder.yudao.framework.common.pojo.CommonResult.success;
import static cn.iocoder.yudao.framework.security.core.util.SecurityFrameworkUtils.getLoginUserId;

@Tag(name = "用户 APP - 寄货/包裹")
@RestController
@RequestMapping("/transport/send")
@Validated
public class AppSendController {

    @Resource private TransportOrderService transportOrderService;
    @Resource private StationService stationService;
    @Resource private PostalOrderMapper postalOrderMapper;
    @Resource private TransportOrderMapper transportOrderMapper;
    @Resource private DispatchPlanItemMapper dispatchPlanItemMapper;
    @Resource private DispatchPlanMapper dispatchPlanMapper;
    @Resource private VehicleMapper vehicleMapper;

    @PostMapping("/create")
    @Operation(summary = "寄货创建货运订单")
    public CommonResult<AppSendOrderRespVO> create(@Valid @RequestBody AppSendOrderCreateReqVO reqVO) {
        Long orderId = transportOrderService.createSendOrder(getLoginUserId(), reqVO);
        return success(toRespVO(transportOrderService.get(orderId)));
    }

    @GetMapping("/page")
    @Operation(summary = "我的寄货记录分页")
    public CommonResult<PageResult<AppSendOrderRespVO>> page(PageParam pageParam) {
        PageResult<TransportOrderDO> pageResult = transportOrderService.getMySendPage(getLoginUserId(), pageParam);
        List<AppSendOrderRespVO> list = pageResult.getList().stream()
                .map(this::toRespVO)
                .toList();
        return success(new PageResult<>(list, pageResult.getTotal()));
    }

    @GetMapping("/track")
    @Operation(summary = "按业务订单号查询（包裹追踪）")
    @Parameter(name = "no", description = "业务订单号", required = true)
    public CommonResult<AppSendOrderRespVO> track(@RequestParam("no") String no) {
        TransportOrderDO order = transportOrderService.getByOrderNo(no);
        // 归属校验：非下单人/非收件人仅返回进度信息，防遍历单号窃取取件码与收件人 PII
        if (!transportOrderService.canViewOrderDetail(order, getLoginUserId())) {
            return success(toProgressVO(order));
        }
        return success(toRespVO(order));
    }

    @GetMapping("/stations")
    @Operation(summary = "获得寄货站点列表")
    @PermitAll
    public CommonResult<List<StationSimpleRespVO>> stations() {
        return success(BeanUtils.toBean(stationService.getSimpleList(), StationSimpleRespVO.class));
    }

    @GetMapping("/arrangements")
    @Operation(summary = "我的乘车安排（客运订单已分配/已发车/已完成，含承运车辆与方案，供村民到站通知）")
    public CommonResult<List<AppSendArrangementRespVO>> arrangements() {
        Long userId = getLoginUserId();
        List<TransportOrderDO> orders = transportOrderMapper.selectList(new LambdaQueryWrapperX<TransportOrderDO>()
                .eq(TransportOrderDO::getMemberUserId, userId)
                .eq(TransportOrderDO::getOrderType, 1)
                .in(TransportOrderDO::getStatus, TransportOrderStatusEnum.ASSIGNED.getStatus(),
                        TransportOrderStatusEnum.DEPARTED.getStatus(), TransportOrderStatusEnum.COMPLETED.getStatus())
                .orderByDesc(TransportOrderDO::getId));
        if (orders.isEmpty()) {
            return success(List.of());
        }
        Map<Long, String> stationNameMap = stationService.getSimpleList().stream()
                .collect(Collectors.toMap(StationDO::getId, StationDO::getStationName, (a, b) -> a));
        return success(orders.stream().map(order -> {
            AppSendArrangementRespVO vo = new AppSendArrangementRespVO();
            vo.setOrderId(order.getId());
            vo.setOrderNo(order.getOrderNo());
            vo.setStatus(order.getStatus());
            vo.setStatusName(TransportOrderStatusEnum.nameOf(order.getStatus()));
            vo.setBoardingStationId(order.getPickupStationId());
            vo.setBoardingStationName(stationNameMap.get(order.getPickupStationId()));
            vo.setAlightingStationId(order.getDeliveryStationId());
            vo.setAlightingStationName(stationNameMap.get(order.getDeliveryStationId()));
            // 承运车辆与方案：取该订单在调度方案明细中的首条（方案下发后村民可见）
            DispatchPlanItemDO item = dispatchPlanItemMapper.selectList(DispatchPlanItemDO::getOrderId, order.getId())
                    .stream().findFirst().orElse(null);
            if (item != null) {
                vo.setPlanId(item.getPlanId());
                DispatchPlanDO plan = dispatchPlanMapper.selectById(item.getPlanId());
                if (plan != null) {
                    vo.setPlanStatus(plan.getStatus());
                }
                if (item.getVehicleId() != null) {
                    VehicleDO vehicle = vehicleMapper.selectById(item.getVehicleId());
                    if (vehicle != null) {
                        vo.setVehiclePlateNo(vehicle.getPlateNo());
                    }
                }
            }
            return vo;
        }).toList());
    }

    /** 无权查看明细时的进度信息（不含取件码、收件人 PII、货物明细） */
    private AppSendOrderRespVO toProgressVO(TransportOrderDO order) {
        AppSendOrderRespVO vo = new AppSendOrderRespVO();
        vo.setOrderNo(order.getOrderNo());
        vo.setOrderType(order.getOrderType());
        vo.setStatus(order.getStatus());
        vo.setStatusName(TransportOrderStatusEnum.nameOf(order.getStatus()));
        vo.setCreateTime(order.getCreateTime());
        return vo;
    }

    private AppSendOrderRespVO toRespVO(TransportOrderDO order) {
        AppSendOrderRespVO vo = BeanUtils.toBean(order, AppSendOrderRespVO.class);
        vo.setStatusName(TransportOrderStatusEnum.nameOf(order.getStatus()));
        vo.setOrderType(order.getOrderType());
        if (Objects.equals(order.getOrderType(), 3)) {
            // 邮快件：快递单号/取件码/收件人（包裹查询展示取件码核销）
            PostalOrderDO postal = postalOrderMapper.selectOne(PostalOrderDO::getOrderId, order.getId());
            if (postal != null) {
                vo.setGoodsName(postal.getMailNo());
                vo.setGoodsWeight(postal.getWeightKg());
                vo.setMailNo(postal.getMailNo());
                vo.setPickupCode(postal.getPickupCode());
                vo.setReceiverName(postal.getReceiverName());
                vo.setReceiverMobile(postal.getReceiverMobile());
                vo.setReceiverAddress(postal.getReceiverAddress());
            }
        } else {
            CargoOrderDO cargo = transportOrderService.getCargoOrder(order.getId());
            if (cargo != null) {
                vo.setGoodsName(cargo.getGoodsName());
                vo.setGoodsWeight(cargo.getWeightKg());
                vo.setGoodsNote(cargo.getGoodsNote());
                vo.setPhotoUrl(cargo.getPhotoUrl());
                vo.setAuditStatus(cargo.getAuditStatus());
                vo.setRejectReason(cargo.getRejectReason());
                vo.setReceiverName(cargo.getReceiverName());
                vo.setReceiverMobile(cargo.getReceiverMobile());
                vo.setReceiverAddress(cargo.getReceiverAddress());
            }
        }
        return vo;
    }
}
