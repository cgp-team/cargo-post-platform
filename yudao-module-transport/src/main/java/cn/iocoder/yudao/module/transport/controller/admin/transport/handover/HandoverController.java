package cn.iocoder.yudao.module.transport.controller.admin.transport.handover;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.framework.common.util.object.BeanUtils;
import cn.iocoder.yudao.module.transport.controller.admin.transport.handover.vo.HandoverDisputeReqVO;
import cn.iocoder.yudao.module.transport.controller.admin.transport.handover.vo.HandoverPageReqVO;
import cn.iocoder.yudao.module.transport.controller.admin.transport.handover.vo.HandoverRespVO;
import cn.iocoder.yudao.module.transport.dal.dataobject.dispatch.TransportHandoverDO;
import cn.iocoder.yudao.module.transport.dal.dataobject.order.TransportOrderDO;
import cn.iocoder.yudao.module.transport.dal.mysql.driver.DriverMapper;
import cn.iocoder.yudao.module.transport.dal.mysql.order.TransportOrderMapper;
import cn.iocoder.yudao.module.transport.dal.mysql.station.StationMapper;
import cn.iocoder.yudao.module.transport.dal.dataobject.driver.DriverDO;
import cn.iocoder.yudao.module.transport.dal.dataobject.station.StationDO;
import cn.iocoder.yudao.module.transport.enums.dispatch.TransportHandoverStatusEnum;
import cn.iocoder.yudao.module.transport.service.dispatch.HandoverService;
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

@Tag(name = "管理后台 - 货物交接记录")
@RestController
@RequestMapping("/transport/handover")
@Validated
public class HandoverController {

    @Resource
    private HandoverService handoverService;
    @Resource
    private TransportOrderMapper orderMapper;
    @Resource
    private StationMapper stationMapper;
    @Resource
    private DriverMapper driverMapper;

    @GetMapping("/page")
    @Operation(summary = "获得货物交接分页")
    @PreAuthorize("@ss.hasPermission('transport:handover:query')")
    public CommonResult<PageResult<HandoverRespVO>> page(@Valid HandoverPageReqVO reqVO) {
        PageResult<TransportHandoverDO> pageResult = handoverService.getPage(reqVO);
        PageResult<HandoverRespVO> result = BeanUtils.toBean(pageResult, HandoverRespVO.class, this::enrich);
        return success(result);
    }

    @GetMapping("/get")
    @Operation(summary = "获得货物交接")
    @Parameter(name = "id", description = "编号", required = true)
    @PreAuthorize("@ss.hasPermission('transport:handover:query')")
    public CommonResult<HandoverRespVO> get(@RequestParam("id") Long id) {
        HandoverRespVO vo = BeanUtils.toBean(handoverService.get(id), HandoverRespVO.class);
        enrich(vo);
        return success(vo);
    }

    @GetMapping("/list-by-order")
    @Operation(summary = "按订单获得交接记录")
    @Parameter(name = "orderId", description = "运输订单编号", required = true)
    @PreAuthorize("@ss.hasPermission('transport:handover:query')")
    public CommonResult<List<HandoverRespVO>> listByOrder(@RequestParam("orderId") Long orderId) {
        List<HandoverRespVO> list = BeanUtils.toBean(handoverService.getByOrder(orderId), HandoverRespVO.class);
        list.forEach(this::enrich);
        return success(list);
    }

    @PutMapping("/dispute")
    @Operation(summary = "标记交接争议（件数不符/破损）")
    @PreAuthorize("@ss.hasPermission('transport:handover:update')")
    public CommonResult<Boolean> dispute(@Valid @RequestBody HandoverDisputeReqVO reqVO) {
        handoverService.disputeHandover(reqVO.getId(), reqVO.getRemark());
        return success(true);
    }

    private void enrich(HandoverRespVO vo) {
        vo.setStatusName(TransportHandoverStatusEnum.nameOf(vo.getStatus()));
        if (vo.getOrderId() != null) {
            TransportOrderDO order = orderMapper.selectById(vo.getOrderId());
            vo.setOrderNo(order != null ? order.getOrderNo() : null);
        }
        if (vo.getStationId() != null) {
            StationDO station = stationMapper.selectById(vo.getStationId());
            vo.setStationName(station != null ? station.getStationName() : null);
        }
        if (vo.getFromDriverId() != null) {
            DriverDO driver = driverMapper.selectById(vo.getFromDriverId());
            vo.setFromDriverName(driver != null ? driver.getName() : null);
        }
        if (vo.getToDriverId() != null) {
            DriverDO driver = driverMapper.selectById(vo.getToDriverId());
            vo.setToDriverName(driver != null ? driver.getName() : null);
        }
    }

}
