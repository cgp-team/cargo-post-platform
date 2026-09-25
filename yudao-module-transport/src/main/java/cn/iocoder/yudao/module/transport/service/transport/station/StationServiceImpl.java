package cn.iocoder.yudao.module.transport.service.transport.station;

import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.module.transport.controller.admin.transport.station.vo.*;
import cn.iocoder.yudao.module.transport.convert.transport.station.StationConvert;
import cn.iocoder.yudao.module.transport.dal.dataobject.station.StationDO;
import cn.iocoder.yudao.module.transport.dal.mysql.station.StationMapper;
import jakarta.annotation.Resource;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.validation.annotation.Validated;

import java.util.List;

import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.module.transport.enums.ErrorCodeConstants.STATION_NOT_EXISTS;

@Service @Validated
public class StationServiceImpl implements StationService {
    @Resource private StationMapper mapper;
    /**
     * 新增站点：**默认不开放车辆权限与调度资格**（需求 §34：新站点不能自动加入线路/开放车辆/成为调度站）。
     * 用户可达默认开（用户能走到站点），车辆可达/可调度由管理员显式打开。
     */
    /** BE-16：站点列表几乎不变且被每单调用，走 Redis 缓存；写操作全量失效（TTL 走全局 1h 兜底） */
    @Override @CacheEvict(cacheNames = CACHE_STATION_SIMPLE_LIST, allEntries = true) public Long create(StationCreateReqVO reqVO) {
        StationDO o = StationConvert.INSTANCE.convert(reqVO);
        if (o.getUserAccess() == null) {
            o.setUserAccess(true);
        }
        if (o.getVehicleAccess() == null) {
            o.setVehicleAccess(false);
        }
        if (o.getDispatchEnabled() == null) {
            o.setDispatchEnabled(false);
        }
        mapper.insert(o);
        return o.getId();
    }
    @Override @CacheEvict(cacheNames = CACHE_STATION_SIMPLE_LIST, allEntries = true) public void update(StationUpdateReqVO reqVO) {
        validateExists(reqVO.getId());
        mapper.updateById(StationConvert.INSTANCE.convert(reqVO));
    }
    @Override @CacheEvict(cacheNames = CACHE_STATION_SIMPLE_LIST, allEntries = true) public void delete(Long id) { validateExists(id); mapper.deleteById(id); }
    @Override public StationDO get(Long id) { return validateExists(id); }
    @Override public PageResult<StationDO> getPage(StationPageReqVO reqVO) { return mapper.selectPage(reqVO); }
    @Override @Cacheable(cacheNames = CACHE_STATION_SIMPLE_LIST, key = "'all'", unless = "#result == null || #result.isEmpty()")
    public List<StationDO> getSimpleList() { return mapper.selectList(); }

    /** BE-16：缓存名常量（key 固定 'all'：站点为小表全量缓存） */
    public static final String CACHE_STATION_SIMPLE_LIST = "transport:station:simpleList";
    private StationDO validateExists(Long id) { StationDO o = mapper.selectById(id); if (o == null) throw exception(STATION_NOT_EXISTS); return o; }
}
