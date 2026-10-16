package cn.iocoder.yudao.module.transport.dal.mysql.dispatch;

import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.framework.mybatis.core.mapper.BaseMapperX;
import cn.iocoder.yudao.framework.mybatis.core.query.LambdaQueryWrapperX;
import cn.iocoder.yudao.module.transport.controller.admin.transport.townevent.vo.TownEventPageReqVO;
import cn.iocoder.yudao.module.transport.dal.dataobject.dispatch.TownEventCalendarDO;
import org.apache.ibatis.annotations.Mapper;

import java.time.LocalDate;
import java.util.List;

@Mapper
public interface TownEventCalendarMapper extends BaseMapperX<TownEventCalendarDO> {

    default PageResult<TownEventCalendarDO> selectPage(TownEventPageReqVO reqVO) {
        LambdaQueryWrapperX<TownEventCalendarDO> q = new LambdaQueryWrapperX<TownEventCalendarDO>()
                .likeIfPresent(TownEventCalendarDO::getTown, reqVO.getTown())
                .eqIfPresent(TownEventCalendarDO::getEventType, reqVO.getEventType())
                .orderByDesc(TownEventCalendarDO::getStartDate);
        if (reqVO.getFrom() != null) {
            // 区间判断不能只比 startDate：一个跨月的农忙季，起点在上月但本月仍在生效
            q.and(w -> w.ge(TownEventCalendarDO::getEndDate, reqVO.getFrom())
                    .or().ge(TownEventCalendarDO::getStartDate, reqVO.getFrom()));
        }
        return selectPage(reqVO, q);
    }

    /** 同镇同日同类型是否已有登记（唯一性由服务层挡，理由见 V037 头注：软删 + DB 唯一键会在第二轮删除撞键） */
    default List<TownEventCalendarDO> selectSameDayList(String town, String eventType, LocalDate day) {
        return selectList(new LambdaQueryWrapperX<TownEventCalendarDO>()
                .eq(TownEventCalendarDO::getTown, town)
                .eq(TownEventCalendarDO::getEventType, eventType)
                .le(TownEventCalendarDO::getStartDate, day)
                .ge(TownEventCalendarDO::getEndDate, day));
    }
}
