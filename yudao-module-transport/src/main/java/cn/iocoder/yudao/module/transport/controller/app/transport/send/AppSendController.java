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
import cn.iocoder.yudao.module.transport.controller.app.transport.send.vo.AppSendRoutePreviewReqVO;
import cn.iocoder.yudao.module.transport.controller.app.transport.send.vo.RoutePreviewRespVO;
import cn.iocoder.yudao.module.transport.dal.dataobject.dispatch.DispatchPlanDO;
import cn.iocoder.yudao.module.transport.dal.dataobject.dispatch.DispatchPlanItemDO;
import cn.iocoder.yudao.module.transport.dal.dataobject.order.CargoOrderDO;
import cn.iocoder.yudao.module.transport.dal.dataobject.order.PostalOrderDO;
import cn.iocoder.yudao.module.transport.dal.dataobject.order.TransportOrderDO;
import cn.iocoder.yudao.module.transport.dal.dataobject.shift.ShiftDO;
import cn.iocoder.yudao.module.transport.dal.dataobject.station.StationDO;
import cn.iocoder.yudao.module.transport.dal.dataobject.vehicle.VehicleDO;
import cn.iocoder.yudao.module.transport.dal.dataobject.vehicle.VehicleLocationDO;
import cn.iocoder.yudao.module.transport.dal.mysql.dispatch.DispatchPlanItemMapper;
import cn.iocoder.yudao.module.transport.dal.mysql.dispatch.DispatchPlanMapper;
import cn.iocoder.yudao.module.transport.dal.mysql.order.PostalOrderMapper;
import cn.iocoder.yudao.module.transport.dal.mysql.order.TransportOrderMapper;
import cn.iocoder.yudao.module.transport.dal.mysql.shift.ShiftMapper;
import cn.iocoder.yudao.module.transport.dal.mysql.vehicle.VehicleLocationMapper;
import cn.iocoder.yudao.module.transport.dal.mysql.vehicle.VehicleMapper;
import cn.iocoder.yudao.module.transport.enums.dispatch.TransportOrderStatusEnum;
import cn.iocoder.yudao.module.transport.service.transport.order.TransportOrderService;
import cn.iocoder.yudao.module.transport.service.transport.send.AppSendRouteInfoService;
import cn.iocoder.yudao.module.transport.util.GeoDistanceUtil;
import cn.iocoder.yudao.module.transport.service.transport.station.StationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import jakarta.annotation.security.PermitAll;
import jakarta.validation.Valid;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
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
    @Resource private AppSendRouteInfoService sendRouteInfoService;
    @Resource private PostalOrderMapper postalOrderMapper;
    @Resource private TransportOrderMapper transportOrderMapper;
    @Resource private DispatchPlanItemMapper dispatchPlanItemMapper;
    @Resource private DispatchPlanMapper dispatchPlanMapper;
    @Resource private VehicleMapper vehicleMapper;
    @Resource private ShiftMapper shiftMapper;
    @Resource private VehicleLocationMapper vehicleLocationMapper;

    /** "车来取货/送货"提醒的订单状态范围：仅在途（已分配/已发车）；已完成/已取消不提醒 */
    private static final Set<Integer> CARRIER_REMINDER_STATUSES = Set.of(
            TransportOrderStatusEnum.ASSIGNED.getStatus(), TransportOrderStatusEnum.DEPARTED.getStatus());
    /** 车辆位置新鲜度阈值（分钟）：超过视为班次已结束的残留上报，不参与提醒 */
    private static final int CARRIER_LOCATION_FRESH_MINUTES = 30;

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
        List<TransportOrderDO> orders = pageResult.getList();
        List<AppSendOrderRespVO> list = orders.stream()
                .map(this::toRespVO)
                .toList();
        fillCarrierBatch(list, orders); // 在途订单"车来取货/送货"提醒（批量，避免逐单 N+1）
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
        AppSendOrderRespVO vo = toRespVO(order);
        fillEta(vo, order);
        return success(vo);
    }

    /** 填充到达预估（仅 track 详情调用；未分配车辆/班次时字段全 null，不影响原流程） */
    private void fillEta(AppSendOrderRespVO vo, TransportOrderDO order) {
        List<DispatchPlanItemDO> items = dispatchPlanItemMapper.selectList(DispatchPlanItemDO::getOrderId, order.getId());
        if (items.isEmpty()) {
            return;
        }
        // 取送达方向（送客 2 / 派送 3）的最新一条；没有送达明细时兜底取最新一条
        DispatchPlanItemDO item = items.stream()
                .filter(i -> i.getActionType() != null
                        && (i.getActionType() == 2 || i.getActionType() == 3 || i.getActionType() == 4))
                .max(Comparator.comparing(DispatchPlanItemDO::getId))
                .orElseGet(() -> items.stream().max(Comparator.comparing(DispatchPlanItemDO::getId)).orElse(null));
        if (item == null) {
            return;
        }
        if (item.getVehicleId() != null) {
            VehicleDO vehicle = vehicleMapper.selectById(item.getVehicleId());
            if (vehicle != null) {
                vo.setVehiclePlate(vehicle.getPlateNo());
            }
        }
        if (item.getShiftId() != null) {
            ShiftDO shift = shiftMapper.selectById(item.getShiftId());
            if (shift != null) {
                vo.setShiftCode(shift.getShiftCode());
            }
        }
        if (item.getStationId() != null) {
            // 站点名仅作展示：沿用 arrangements 的精简列表 Map 取值，站点被删也不影响追踪主流程
            Map<Long, String> stationNameMap = stationService.getSimpleList().stream()
                    .collect(Collectors.toMap(StationDO::getId, StationDO::getStationName, (a, b) -> a));
            vo.setTargetStation(stationNameMap.get(item.getStationId()));
        }
        vo.setEstimatedArrivalTime(item.getEstimatedArrivalTime());
        // etaMinutes：仅预计到达时间在未来时给出分钟差，否则为 null
        LocalDateTime eta = item.getEstimatedArrivalTime();
        if (eta != null && eta.isAfter(LocalDateTime.now())) {
            vo.setEtaMinutes((int) Duration.between(LocalDateTime.now(), eta).toMinutes());
        }
        // 车来取货/送货提醒：仅在途订单填充（完成/取消的经停明细仍在，不提醒，防误导）
        if (carrierReminderEligible(order.getStatus())) {
            Map<Long, VehicleLocationDO> carrierLocMap = new HashMap<>();
            Map<Long, VehicleDO> carrierVehicleMap = new HashMap<>();
            if (item.getVehicleId() != null) {
                carrierLocMap.put(item.getVehicleId(), vehicleLocationMapper.selectByVehicleId(item.getVehicleId()));
                carrierVehicleMap.put(item.getVehicleId(), vehicleMapper.selectById(item.getVehicleId()));
            }
            fillCarrierLiveInfo(vo, item, carrierLocMap, carrierVehicleMap,
                    stationService.getSimpleList().stream()
                            .collect(Collectors.toMap(StationDO::getId, Function.identity(), (a, b) -> a)));
        }
    }

    /** 订单是否可展示"车来取货/送货"提醒：仅已分配/已发车的在途订单 */
    static boolean carrierReminderEligible(Integer status) {
        return status != null && CARRIER_REMINDER_STATUSES.contains(status);
    }

    /** 车辆位置是否新鲜：reportTime 超过阈值视为班次已结束的残留上报，不参与提醒 */
    static boolean isCarrierLocationFresh(LocalDateTime reportTime, LocalDateTime now) {
        return reportTime != null
                && reportTime.isAfter(now.minusMinutes(CARRIER_LOCATION_FRESH_MINUTES));
    }

    /** 我的寄货列表批量填充承运车辆实时位置（在途订单"车来取货/送货"提醒），一次加载避免逐单 N+1 */
    private void fillCarrierBatch(List<AppSendOrderRespVO> list, List<TransportOrderDO> orders) {
        if (list.isEmpty()) {
            return;
        }
        // 仅在途订单（已分配/已发车）展示提醒；完成/取消单的经停明细仍在，预过滤防误显示
        List<Long> orderIds = orders.stream()
                .filter(o -> carrierReminderEligible(o.getStatus()))
                .map(TransportOrderDO::getId).toList();
        if (orderIds.isEmpty()) {
            return;
        }
        // 方向经停明细：送客 2 / 派送 3 / 揽收 4（揽收@上车站，派送@下车站，送客@下车站）
        List<DispatchPlanItemDO> items = dispatchPlanItemMapper.selectList(new LambdaQueryWrapperX<DispatchPlanItemDO>()
                .in(DispatchPlanItemDO::getOrderId, orderIds)
                .in(DispatchPlanItemDO::getActionType, 2, 3, 4));
        if (items.isEmpty()) {
            return;
        }
        // 每订单取方向经停最新一条
        Map<Long, DispatchPlanItemDO> orderItemMap = items.stream()
                .collect(Collectors.toMap(DispatchPlanItemDO::getOrderId, Function.identity(),
                        (a, b) -> a.getId() > b.getId() ? a : b));
        Set<Long> vehicleIds = items.stream().map(DispatchPlanItemDO::getVehicleId)
                .filter(Objects::nonNull).collect(Collectors.toSet());
        if (vehicleIds.isEmpty()) {
            return;
        }
        // 一次批量加载：车辆最新位置 + 车辆档案 + 站点
        Map<Long, VehicleLocationDO> locMap = vehicleLocationMapper
                .selectList(new LambdaQueryWrapperX<VehicleLocationDO>()
                        .in(VehicleLocationDO::getVehicleId, vehicleIds))
                .stream().collect(Collectors.toMap(VehicleLocationDO::getVehicleId, Function.identity(), (a, b) -> a));
        Map<Long, VehicleDO> vehicleMap = vehicleMapper.selectBatchIds(vehicleIds).stream()
                .collect(Collectors.toMap(VehicleDO::getId, Function.identity(), (a, b) -> a));
        Map<Long, StationDO> stationMap = stationService.getSimpleList().stream()
                .collect(Collectors.toMap(StationDO::getId, Function.identity(), (a, b) -> a));
        for (int i = 0; i < list.size(); i++) {
            // 状态收口兜底：即使查询结果混入非在途订单也不填充（与 orderIds 预过滤同口径）
            if (!carrierReminderEligible(orders.get(i).getStatus())) {
                continue;
            }
            DispatchPlanItemDO item = orderItemMap.get(orders.get(i).getId());
            if (item != null) {
                fillCarrierLiveInfo(list.get(i), item, locMap, vehicleMap, stationMap);
            }
        }
    }

    /** 填充承运车辆实时位置 + 距目标站点距离/分钟（车来取货/送货提醒）。
     *  目标站点 = 该订单方向经停站（揽收→上车站，派送/客运送客→下车站）。
     *  车辆未发车/未上报位置，或上报已过期（班次结束残留）时字段保持 null，不影响原流程。 */
    private void fillCarrierLiveInfo(AppSendOrderRespVO vo, DispatchPlanItemDO item,
                                     Map<Long, VehicleLocationDO> locMap,
                                     Map<Long, VehicleDO> vehicleMap,
                                     Map<Long, StationDO> stationMap) {
        if (item.getVehicleId() == null || item.getStationId() == null) {
            return;
        }
        VehicleLocationDO loc = locMap.get(item.getVehicleId());
        if (loc == null || loc.getLongitude() == null || loc.getLatitude() == null) {
            return;
        }
        // 位置新鲜度：班次结束后的残留上报不参与提醒（vehicle_location 每车一行，收车后不清理）
        if (!isCarrierLocationFresh(loc.getReportTime(), LocalDateTime.now())) {
            return;
        }
        StationDO station = stationMap.get(item.getStationId());
        if (station == null || station.getLongitude() == null || station.getLatitude() == null) {
            return;
        }
        // 列表场景下补车牌/站点名（track 详情已在 fillEta 设置，非 null 不覆盖）
        VehicleDO vehicle = vehicleMap.get(item.getVehicleId());
        if (vehicle != null && vo.getVehiclePlate() == null) {
            vo.setVehiclePlate(vehicle.getPlateNo());
        }
        if (vo.getTargetStation() == null) {
            vo.setTargetStation(station.getStationName());
        }
        GeoDistanceUtil.DistanceEta distanceEta = GeoDistanceUtil.computeKmAndMinutes(
                loc.getLongitude().doubleValue(), loc.getLatitude().doubleValue(),
                station.getLongitude().doubleValue(), station.getLatitude().doubleValue(),
                GeoDistanceUtil.DEFAULT_AVG_SPEED_KMH);
        vo.setCarrierLongitude(loc.getLongitude().doubleValue());
        vo.setCarrierLatitude(loc.getLatitude().doubleValue());
        vo.setCarrierDistanceKm(BigDecimal.valueOf(Math.round(distanceEta.distKm() * 100) / 100.0));
        vo.setCarrierEtaMinutes(distanceEta.etaMinutes());
    }

    @GetMapping("/stations")
    @Operation(summary = "获得寄货站点列表")
    @PermitAll
    public CommonResult<List<StationSimpleRespVO>> stations() {
        return success(BeanUtils.toBean(stationService.getSimpleList(), StationSimpleRespVO.class));
    }

    @PostMapping("/route-preview")
    @Operation(summary = "寄货页取货/送达站点路线预览（真实道路距离 + 预计时间，后端校验站点有效性）")
    @PermitAll
    public CommonResult<RoutePreviewRespVO> routePreview(@Valid @RequestBody AppSendRoutePreviewReqVO reqVO) {
        return success(sendRouteInfoService.routePreview(reqVO.getPickupStationId(), reqVO.getDeliveryStationId()));
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
