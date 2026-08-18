package cn.iocoder.yudao.module.transport.service.transport.notice;

import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.module.transport.controller.admin.transport.notice.vo.*;
import cn.iocoder.yudao.module.transport.convert.transport.notice.NoticeConvert;
import cn.iocoder.yudao.module.transport.dal.dataobject.notice.TransportNoticeDO;
import cn.iocoder.yudao.module.transport.dal.mysql.notice.TransportNoticeMapper;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;
import org.springframework.validation.annotation.Validated;

import java.util.List;

import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.module.transport.enums.ErrorCodeConstants.NOTICE_NOT_EXISTS;

@Service @Validated
public class TransportNoticeServiceImpl implements TransportNoticeService {
    @Resource private TransportNoticeMapper mapper;

    @Override public Long create(NoticeCreateReqVO reqVO) {
        TransportNoticeDO o = NoticeConvert.INSTANCE.convert(reqVO);
        mapper.insert(o);
        return o.getId();
    }
    @Override public void update(NoticeUpdateReqVO reqVO) {
        validateExists(reqVO.getId());
        mapper.updateById(NoticeConvert.INSTANCE.convert(reqVO));
    }
    @Override public void delete(Long id) { validateExists(id); mapper.deleteById(id); }
    @Override public TransportNoticeDO get(Long id) { return validateExists(id); }
    @Override public PageResult<TransportNoticeDO> getPage(NoticePageReqVO reqVO) { return mapper.selectPage(reqVO); }
    @Override public List<TransportNoticeDO> getOnShelfList() { return mapper.selectOnShelfList(); }

    private TransportNoticeDO validateExists(Long id) {
        TransportNoticeDO o = mapper.selectById(id);
        if (o == null) throw exception(NOTICE_NOT_EXISTS);
        return o;
    }
}
