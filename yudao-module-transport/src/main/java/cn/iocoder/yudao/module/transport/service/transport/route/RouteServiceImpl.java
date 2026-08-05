package cn.iocoder.yudao.module.transport.service.transport.route;

import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.module.transport.controller.admin.transport.route.vo.*;
import cn.iocoder.yudao.module.transport.convert.transport.route.RouteConvert;
import cn.iocoder.yudao.module.transport.dal.dataobject.route.RouteDO;
import cn.iocoder.yudao.module.transport.dal.mysql.route.RouteMapper;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;
import org.springframework.validation.annotation.Validated;

import java.util.List;

import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.module.transport.enums.ErrorCodeConstants.ROUTE_NOT_EXISTS;

@Service @Validated
public class RouteServiceImpl implements RouteService {
    @Resource private RouteMapper mapper;
    @Override public Long create(RouteCreateReqVO reqVO) { RouteDO o = RouteConvert.INSTANCE.convert(reqVO); mapper.insert(o); return o.getId(); }
    @Override public void update(RouteUpdateReqVO reqVO) { validateExists(reqVO.getId()); mapper.updateById(RouteConvert.INSTANCE.convert(reqVO)); }
    @Override public void delete(Long id) { validateExists(id); mapper.deleteById(id); }
    @Override public RouteDO get(Long id) { return validateExists(id); }
    @Override public PageResult<RouteDO> getPage(RoutePageReqVO reqVO) { return mapper.selectPage(reqVO); }
    @Override public List<RouteDO> getSimpleList() { return mapper.selectList(); }
    private RouteDO validateExists(Long id) { RouteDO o = mapper.selectById(id); if (o == null) throw exception(ROUTE_NOT_EXISTS); return o; }
}
