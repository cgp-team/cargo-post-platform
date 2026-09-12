package cn.iocoder.yudao.module.transport.service.transport.route;

import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.module.transport.controller.admin.transport.route.vo.*;
import cn.iocoder.yudao.module.transport.convert.transport.route.RouteConvert;
import cn.iocoder.yudao.module.transport.dal.dataobject.route.RouteDO;
import cn.iocoder.yudao.module.transport.dal.dataobject.route.RouteStationDO;
import cn.iocoder.yudao.module.transport.dal.dataobject.station.StationDO;
import cn.iocoder.yudao.module.transport.dal.mysql.route.RouteMapper;
import cn.iocoder.yudao.module.transport.dal.mysql.route.RouteStationMapper;
import cn.iocoder.yudao.module.transport.dal.mysql.station.StationMapper;
import cn.iocoder.yudao.module.transport.util.GeoDistanceUtil;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.annotation.Validated;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.module.transport.enums.ErrorCodeConstants.ROUTE_NOT_EXISTS;

@Service @Validated
public class RouteServiceImpl implements RouteService {
    @Resource private RouteMapper mapper;
    @Resource private RouteStationMapper routeStationMapper;
    @Resource private StationMapper stationMapper;
    @Override public Long create(RouteCreateReqVO reqVO) { RouteDO o = RouteConvert.INSTANCE.convert(reqVO); mapper.insert(o); return o.getId(); }
    @Override public void update(RouteUpdateReqVO reqVO) { validateExists(reqVO.getId()); mapper.updateById(RouteConvert.INSTANCE.convert(reqVO)); }
    @Override public void delete(Long id) { validateExists(id); mapper.deleteById(id); }
    @Override public RouteDO get(Long id) { return validateExists(id); }
    @Override public PageResult<RouteDO> getPage(RoutePageReqVO reqVO) { return mapper.selectPage(reqVO); }
    @Override public List<RouteDO> getSimpleList() { return mapper.selectList(); }

    @Override
    public List<RouteStationRespVO> getRouteStations(Long routeId) {
        validateExists(routeId);
        List<RouteStationDO> rows = routeStationMapper.selectListByRouteIds(List.of(routeId));
        if (rows.isEmpty()) {
            return List.of();
        }
        Map<Long, StationDO> stationMap = stationMapper.selectBatchIds(
                        rows.stream().map(RouteStationDO::getStationId).filter(Objects::nonNull).collect(Collectors.toSet()))
                .stream().collect(Collectors.toMap(StationDO::getId, s -> s, (a, b) -> a));
        List<RouteStationRespVO> result = new ArrayList<>();
        rows.stream()
                .sorted(Comparator.comparing(RouteStationDO::getSequenceNo,
                        Comparator.nullsLast(Comparator.naturalOrder())))
                .forEach(row -> {
                    StationDO station = stationMap.get(row.getStationId());
                    RouteStationRespVO vo = new RouteStationRespVO();
                    vo.setStationId(row.getStationId());
                    vo.setSequenceNo(row.getSequenceNo());
                    vo.setPlannedMinutes(row.getPlannedMinutes());
                    if (station != null) {
                        vo.setStationName(station.getStationName());
                        vo.setAddress(station.getAddress());
                        if (station.getLongitude() != null) {
                            vo.setLongitude(station.getLongitude().doubleValue());
                        }
                        if (station.getLatitude() != null) {
                            vo.setLatitude(station.getLatitude().doubleValue());
                        }
                    }
                    result.add(vo);
                });
        return result;
    }

    /**
     * 整线覆盖保存经停站点：先删旧站序再按数组顺序写入，
     * 计划分钟未填时按站点直线里程 / 20km/h 自动估算（与真实线路班次口径一致）。
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void saveRouteStations(RouteStationSaveReqVO reqVO) {
        RouteDO route = validateExists(reqVO.getRouteId());
        routeStationMapper.delete(new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<RouteStationDO>()
                .eq(RouteStationDO::getRouteId, reqVO.getRouteId()));
        List<RouteStationSaveReqVO.Item> items = reqVO.getStations();
        if (items == null || items.isEmpty()) {
            return;
        }
        Map<Long, StationDO> stationMap = stationMapper.selectBatchIds(
                        items.stream().map(RouteStationSaveReqVO.Item::getStationId)
                                .filter(Objects::nonNull).collect(Collectors.toSet()))
                .stream().collect(Collectors.toMap(StationDO::getId, s -> s, (a, b) -> a));
        double cumKm = 0;
        StationDO prev = null;
        int seq = 1;
        for (RouteStationSaveReqVO.Item item : items) {
            if (item.getStationId() == null) {
                continue;
            }
            StationDO station = stationMap.get(item.getStationId());
            if (station == null) {
                continue;
            }
            if (prev != null && prev.getLongitude() != null && station.getLongitude() != null) {
                cumKm += GeoDistanceUtil.haversineKm(prev.getLongitude().doubleValue(),
                        prev.getLatitude().doubleValue(),
                        station.getLongitude().doubleValue(), station.getLatitude().doubleValue());
            }
            Integer minutes = item.getPlannedMinutes();
            if (minutes == null) {
                minutes = (int) Math.round(cumKm / 20.0 * 60);
            }
            routeStationMapper.insert(RouteStationDO.builder()
                    .routeId(reqVO.getRouteId())
                    .stationId(item.getStationId())
                    .sequenceNo(seq++)
                    .plannedMinutes(minutes)
                    .build());
            prev = station;
        }
        // 线路里程随站序刷新（调度/地图展示用真实里程）
        if (cumKm > 0) {
            RouteDO upd = new RouteDO();
            upd.setId(route.getId());
            upd.setDistanceKm(BigDecimal.valueOf(Math.round(cumKm * 100) / 100.0));
            mapper.updateById(upd);
        }
    }

    private RouteDO validateExists(Long id) { RouteDO o = mapper.selectById(id); if (o == null) throw exception(ROUTE_NOT_EXISTS); return o; }
}
