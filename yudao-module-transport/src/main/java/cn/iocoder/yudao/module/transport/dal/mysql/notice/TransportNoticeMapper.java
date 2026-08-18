package cn.iocoder.yudao.module.transport.dal.mysql.notice;

import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.framework.mybatis.core.mapper.BaseMapperX;
import cn.iocoder.yudao.framework.mybatis.core.query.LambdaQueryWrapperX;
import cn.iocoder.yudao.module.transport.controller.admin.transport.notice.vo.NoticePageReqVO;
import cn.iocoder.yudao.module.transport.dal.dataobject.notice.NoticeDO;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

@Mapper
public interface TransportNoticeMapper extends BaseMapperX<NoticeDO> {

    default PageResult<NoticeDO> selectPage(NoticePageReqVO reqVO) {
        return selectPage(reqVO, new LambdaQueryWrapperX<NoticeDO>()
                .likeIfPresent(NoticeDO::getTitle, reqVO.getTitle())
                .eqIfPresent(NoticeDO::getStatus, reqVO.getStatus())
                .orderByAsc(NoticeDO::getSort).orderByDesc(NoticeDO::getId));
    }

    /** 查询上架公告（sort 升序、id 倒序，最多 20 条） */
    default List<NoticeDO> selectOnShelfList() {
        return selectList(new LambdaQueryWrapperX<NoticeDO>()
                .eq(NoticeDO::getStatus, 1)
                .orderByAsc(NoticeDO::getSort).orderByDesc(NoticeDO::getId)
                .last("LIMIT 20"));
    }
}
