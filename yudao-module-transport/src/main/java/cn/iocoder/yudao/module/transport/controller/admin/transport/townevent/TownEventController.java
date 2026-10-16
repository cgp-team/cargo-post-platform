package cn.iocoder.yudao.module.transport.controller.admin.transport.townevent;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.framework.common.util.object.BeanUtils;
import cn.iocoder.yudao.framework.idempotent.core.annotation.Idempotent;
import cn.iocoder.yudao.framework.idempotent.core.keyresolver.impl.UserIdempotentKeyResolver;
import cn.iocoder.yudao.module.transport.controller.admin.transport.townevent.vo.TownEventPageReqVO;
import cn.iocoder.yudao.module.transport.controller.admin.transport.townevent.vo.TownEventRespVO;
import cn.iocoder.yudao.module.transport.controller.admin.transport.townevent.vo.TownEventSaveReqVO;
import cn.iocoder.yudao.module.transport.dal.dataobject.dispatch.TownEventCalendarDO;
import cn.iocoder.yudao.module.transport.service.dispatch.TownEventCalendarService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import jakarta.validation.Valid;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static cn.iocoder.yudao.framework.common.pojo.CommonResult.success;

@Tag(name = "管理后台 - 乡镇事件日历")
@RestController
@RequestMapping("/transport/town-event")
@Validated
public class TownEventController {

    @Resource
    private TownEventCalendarService townEventCalendarService;

    @GetMapping("/page")
    @Operation(summary = "事件日历分页")
    @PreAuthorize("@ss.hasPermission('transport:town-event:query')")
    public CommonResult<PageResult<TownEventRespVO>> page(@Valid TownEventPageReqVO reqVO) {
        return success(BeanUtils.toBean(townEventCalendarService.getPage(reqVO), TownEventRespVO.class));
    }

    @GetMapping("/get")
    @Operation(summary = "事件日历单条")
    @Parameter(name = "id", description = "编号", required = true)
    @PreAuthorize("@ss.hasPermission('transport:town-event:query')")
    public CommonResult<TownEventRespVO> get(@RequestParam("id") Long id) {
        return success(BeanUtils.toBean(townEventCalendarService.get(id), TownEventRespVO.class));
    }

    /**
     * 某一天生效的事件（含周期展开）—— 情景提示的取数口。
     * ⚠ 这个口**不做权限位**以外的解释：它只回"日历上写了什么"，不推断、不补数据；
     *   日历为空时它回空列表，页面必须念"这一格还没登记"，不许把空当成"那天没有集"（缺值不是结论）。
     */
    @GetMapping("/effective-on")
    @Operation(summary = "某天生效的乡镇事件")
    @Parameter(name = "day", description = "日期", required = true, example = "2026-10-17")
    @PreAuthorize("@ss.hasPermission('transport:town-event:query')")
    public CommonResult<List<TownEventRespVO>> effectiveOn(
            @RequestParam("day") @DateTimeFormat(pattern = "yyyy-MM-dd") LocalDate day) {
        List<TownEventCalendarDO> rows = townEventCalendarService.listEffectiveOn(day);
        return success(BeanUtils.toBean(rows, TownEventRespVO.class));
    }

    @PostMapping("/create")
    @Operation(summary = "新增事件日历")
    @PreAuthorize("@ss.hasPermission('transport:town-event:save')")
    @Idempotent(timeout = 10, timeUnit = TimeUnit.SECONDS, keyResolver = UserIdempotentKeyResolver.class,
            message = "这条事件正在登记中，请勿重复提交（重复提交会让情景提示把同一个集日数两遍）")
    public CommonResult<Long> create(@Valid @RequestBody TownEventSaveReqVO reqVO) {
        return success(townEventCalendarService.create(reqVO));
    }

    @PutMapping("/update")
    @Operation(summary = "修改事件日历")
    @PreAuthorize("@ss.hasPermission('transport:town-event:save')")
    public CommonResult<Boolean> update(@Valid @RequestBody TownEventSaveReqVO reqVO) {
        townEventCalendarService.update(reqVO);
        return success(true);
    }

    @DeleteMapping("/delete")
    @Operation(summary = "删除事件日历")
    @Parameter(name = "id", description = "编号", required = true)
    @PreAuthorize("@ss.hasPermission('transport:town-event:delete')")
    public CommonResult<Boolean> delete(@RequestParam("id") Long id) {
        townEventCalendarService.delete(id);
        return success(true);
    }
}
