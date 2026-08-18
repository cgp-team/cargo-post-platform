package cn.iocoder.yudao.module.transport.dal.mysql.notice;

import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.framework.mybatis.core.mapper.BaseMapperX;
import cn.iocoder.yudao.framework.mybatis.core.query.LambdaQueryWrapperX;
import cn.iocoder.yudao.module.transport.controller.admin.transport.notice.vo.NoticePageReqVO;
import cn.iocoder.yudao.module.transport.dal.dataobject.notice.TransportNoticeDO;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

@Mapper
public interface TransportNoticeMapper extends BaseMapperX<TransportNoticeDO> {

    default PageResult<TransportNoticeDO> selectPage(NoticePageReqVO reqVO) {
        return selectPage(reqVO, new LambdaQueryWrapperX<TransportNoticeDO>()
                .likeIfPresent(TransportNoticeDO::getTitle, reqVO.getTitle())
                .eqIfPresent(TransportNoticeDO::getStatus, reqVO.getStatus())
                .orderByAsc(TransportNoticeDO::getSort).orderByDesc(TransportNoticeDO::getId));
    }

    /** 查询上架公告（sort 升序、id 倒序，最多 20 条） */
    default List<TransportNoticeDO> selectOnShelfList() {
        return selectList(new LambdaQueryWrapperX<TransportNoticeDO>()
                .eq(TransportNoticeDO::getStatus, 1)
                .orderByAsc(TransportNoticeDO::getSort).orderByDesc(TransportNoticeDO::getId)
                .last("LIMIT 20"));
    }
}
