package cn.iocoder.yudao.module.transport.service.transport.notice;

import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.module.transport.controller.admin.transport.notice.vo.*;
import cn.iocoder.yudao.module.transport.convert.transport.notice.NoticeConvert;
import cn.iocoder.yudao.module.transport.dal.dataobject.notice.NoticeDO;
import cn.iocoder.yudao.module.transport.dal.mysql.notice.NoticeMapper;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;
import org.springframework.validation.annotation.Validated;

import java.util.List;

import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.module.transport.enums.ErrorCodeConstants.NOTICE_NOT_EXISTS;

@Service @Validated
public class NoticeServiceImpl implements NoticeService {
    @Resource private NoticeMapper mapper;

    @Override public Long create(NoticeCreateReqVO reqVO) {
        NoticeDO o = NoticeConvert.INSTANCE.convert(reqVO);
        mapper.insert(o);
        return o.getId();
    }
    @Override public void update(NoticeUpdateReqVO reqVO) {
        validateExists(reqVO.getId());
        mapper.updateById(NoticeConvert.INSTANCE.convert(reqVO));
    }
    @Override public void delete(Long id) { validateExists(id); mapper.deleteById(id); }
    @Override public NoticeDO get(Long id) { return validateExists(id); }
    @Override public PageResult<NoticeDO> getPage(NoticePageReqVO reqVO) { return mapper.selectPage(reqVO); }
    @Override public List<NoticeDO> getOnShelfList() { return mapper.selectOnShelfList(); }

    private NoticeDO validateExists(Long id) {
        NoticeDO o = mapper.selectById(id);
        if (o == null) throw exception(NOTICE_NOT_EXISTS);
        return o;
    }
}
