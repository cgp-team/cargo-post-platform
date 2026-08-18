package cn.iocoder.yudao.module.transport.service.transport.notice;

import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.module.transport.controller.admin.transport.notice.vo.*;
import cn.iocoder.yudao.module.transport.dal.dataobject.notice.NoticeDO;
import jakarta.validation.Valid;

import java.util.List;

public interface TransportNoticeService {
    Long create(@Valid NoticeCreateReqVO reqVO);
    void update(@Valid NoticeUpdateReqVO reqVO);
    void delete(Long id);
    NoticeDO get(Long id);
    PageResult<NoticeDO> getPage(NoticePageReqVO reqVO);
    /** 上架公告列表（小程序展示，最多 20 条） */
    List<NoticeDO> getOnShelfList();
}
