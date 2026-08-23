package cn.iocoder.yudao.module.transport.dal.mysql.driver;

import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.framework.mybatis.core.mapper.BaseMapperX;
import cn.iocoder.yudao.framework.mybatis.core.query.LambdaQueryWrapperX;
import cn.iocoder.yudao.module.transport.controller.admin.transport.driver.vo.DriverPageReqVO;
import cn.iocoder.yudao.module.transport.dal.dataobject.driver.DriverDO;
import org.apache.ibatis.annotations.Mapper;

import java.time.LocalDate;
import java.util.List;

@Mapper
public interface DriverMapper extends BaseMapperX<DriverDO> {
    default PageResult<DriverDO> selectPage(DriverPageReqVO reqVO) {
        return selectPage(reqVO, new LambdaQueryWrapperX<DriverDO>()
                .likeIfPresent(DriverDO::getName, reqVO.getName())
                .likeIfPresent(DriverDO::getMobile, reqVO.getMobile())
                .betweenIfPresent(DriverDO::getCreateTime, reqVO.getCreateTime())
                .orderByDesc(DriverDO::getId));
    }

    /** 按手机号查询司机档案（小程序司机端身份识别） */
    default DriverDO selectByMobile(String mobile) {
        return selectOne(DriverDO::getMobile, mobile);
    }

    /** 查询驾驶证在 deadline 前（含已过期）到期的司机，按到期日升序 */
    default List<DriverDO> selectExpiringList(LocalDate deadline) {
        return selectList(new LambdaQueryWrapperX<DriverDO>()
                .isNotNull(DriverDO::getLicenseExpireDate)
                .le(DriverDO::getLicenseExpireDate, deadline)
                .orderByAsc(DriverDO::getLicenseExpireDate));
    }
}
