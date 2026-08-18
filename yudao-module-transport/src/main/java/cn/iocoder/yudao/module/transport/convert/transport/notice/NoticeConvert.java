package cn.iocoder.yudao.module.transport.convert.transport.notice;

import cn.iocoder.yudao.module.transport.controller.admin.transport.notice.vo.*;
import cn.iocoder.yudao.module.transport.dal.dataobject.notice.TransportNoticeDO;
import org.mapstruct.Mapper;
import org.mapstruct.factory.Mappers;

@Mapper
public interface NoticeConvert {
    NoticeConvert INSTANCE = Mappers.getMapper(NoticeConvert.class);
    TransportNoticeDO convert(NoticeCreateReqVO bean);
    TransportNoticeDO convert(NoticeUpdateReqVO bean);
}
