package cn.iocoder.yudao.module.transport.dal.mysql.shift;

import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.framework.mybatis.core.mapper.BaseMapperX;
import cn.iocoder.yudao.framework.mybatis.core.query.LambdaQueryWrapperX;
import cn.iocoder.yudao.module.transport.controller.admin.transport.shift.vo.ShiftPageReqVO;
import cn.iocoder.yudao.module.transport.dal.dataobject.shift.ShiftDO;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface ShiftMapper extends BaseMapperX<ShiftDO> {
    default PageResult<ShiftDO> selectPage(ShiftPageReqVO reqVO) {
        return selectPage(reqVO, new LambdaQueryWrapperX<ShiftDO>()
                .likeIfPresent(ShiftDO::getShiftCode, reqVO.getShiftCode())
                .eqIfPresent(ShiftDO::getRouteId, reqVO.getRouteId())
                .eqIfPresent(ShiftDO::getStatus, reqVO.getStatus())
                .betweenIfPresent(ShiftDO::getCreateTime, reqVO.getCreateTime())
                .orderByDesc(ShiftDO::getId));
    }
}
