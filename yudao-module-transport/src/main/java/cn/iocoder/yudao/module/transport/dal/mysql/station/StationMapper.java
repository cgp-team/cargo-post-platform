package cn.iocoder.yudao.module.transport.dal.mysql.station;

import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.framework.mybatis.core.mapper.BaseMapperX;
import cn.iocoder.yudao.framework.mybatis.core.query.LambdaQueryWrapperX;
import cn.iocoder.yudao.module.transport.controller.admin.transport.station.vo.StationPageReqVO;
import cn.iocoder.yudao.module.transport.dal.dataobject.station.StationDO;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface StationMapper extends BaseMapperX<StationDO> {
    default PageResult<StationDO> selectPage(StationPageReqVO reqVO) {
        return selectPage(reqVO, new LambdaQueryWrapperX<StationDO>()
                .likeIfPresent(StationDO::getStationCode, reqVO.getStationCode())
                .likeIfPresent(StationDO::getStationName, reqVO.getStationName())
                .betweenIfPresent(StationDO::getCreateTime, reqVO.getCreateTime())
                .orderByDesc(StationDO::getId));
    }
}
