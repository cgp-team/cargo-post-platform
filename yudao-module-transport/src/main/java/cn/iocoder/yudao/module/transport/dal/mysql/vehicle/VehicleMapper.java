package cn.iocoder.yudao.module.transport.dal.mysql.vehicle;

import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.framework.mybatis.core.mapper.BaseMapperX;
import cn.iocoder.yudao.framework.mybatis.core.query.LambdaQueryWrapperX;
import cn.iocoder.yudao.module.transport.controller.admin.transport.vehicle.vo.VehiclePageReqVO;
import cn.iocoder.yudao.module.transport.dal.dataobject.vehicle.VehicleDO;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface VehicleMapper extends BaseMapperX<VehicleDO> {
    default PageResult<VehicleDO> selectPage(VehiclePageReqVO reqVO) {
        return selectPage(reqVO, new LambdaQueryWrapperX<VehicleDO>()
                .likeIfPresent(VehicleDO::getPlateNo, reqVO.getPlateNo())
                .eqIfPresent(VehicleDO::getStatus, reqVO.getStatus())
                .betweenIfPresent(VehicleDO::getCreateTime, reqVO.getCreateTime())
                .orderByDesc(VehicleDO::getId));
    }
}
