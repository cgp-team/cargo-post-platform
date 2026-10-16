package cn.iocoder.yudao.module.transport.service.dispatch;

import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.module.transport.controller.admin.transport.townevent.vo.TownEventPageReqVO;
import cn.iocoder.yudao.module.transport.controller.admin.transport.townevent.vo.TownEventSaveReqVO;
import cn.iocoder.yudao.module.transport.dal.dataobject.dispatch.TownEventCalendarDO;
import jakarta.validation.Valid;

import java.time.LocalDate;
import java.util.List;

/**
 * 乡镇事件日历（队列 #93 / 契约 §6 第 2 项）。
 */
public interface TownEventCalendarService {

    Long create(@Valid TownEventSaveReqVO reqVO);

    void update(@Valid TownEventSaveReqVO reqVO);

    void delete(Long id);

    TownEventCalendarDO get(Long id);

    PageResult<TownEventCalendarDO> getPage(TownEventPageReqVO reqVO);

    /**
     * 某一天哪些乡镇处于事件生效期（含周期规则展开）。
     * 这是"赶集日提前留货舱"那类提示的取数入口 —— 只有这张表能回答"还没下单但很快要来人/来货"。
     */
    List<TownEventCalendarDO> listEffectiveOn(LocalDate day);
}
