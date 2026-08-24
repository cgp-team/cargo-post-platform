package cn.iocoder.yudao.module.transport.service.transport.send;

import cn.iocoder.yudao.module.transport.controller.app.transport.send.vo.RoutePreviewRespVO;
import cn.iocoder.yudao.module.transport.dal.dataobject.station.StationDO;
import cn.iocoder.yudao.module.transport.dal.mysql.station.StationMapper;
import cn.iocoder.yudao.module.transport.integration.algorithm.AlgorithmClient;
import cn.iocoder.yudao.module.transport.integration.algorithm.dto.AlgorithmDistancePairDTO;
import cn.iocoder.yudao.module.transport.integration.algorithm.dto.AlgorithmDistanceReqDTO;
import cn.iocoder.yudao.module.transport.integration.algorithm.dto.AlgorithmDistanceRespDTO;
import cn.iocoder.yudao.module.transport.integration.algorithm.dto.AlgorithmStationDTO;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;

import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.module.transport.enums.ErrorCodeConstants.SEND_STATIONS_SAME;
import static cn.iocoder.yudao.module.transport.enums.ErrorCodeConstants.STATION_DISABLED;
import static cn.iocoder.yudao.module.transport.enums.ErrorCodeConstants.STATION_NOT_EXISTS;

/**
 * 寄货路线预览：取货/送达站点 → 高德路网距离/时间（经算法服务），供小程序前端体验。
 *
 * - 后端按 stationId 查真实 StationDO（不信任前端坐标），并校验：站点存在、未删除（逻辑删除自动过滤）、
 *   已启用（status=0）、两站不同。
 * - 距离/时间来源与算法规划/ETA 一致（复用 {@link AmapDistanceProvider}，经算法服务 /api/v1/distance）。
 * - 高德不可用/超时/配额 → 算法侧降级直线估算并标记 provider=euclidean；本服务明确返回 provider/warning，
 *   不把 fallback 伪装成高德真实路线。算法服务整体不可用 → available=false。
 * - 全链路单位为公里（km），不做 degree 重复换算。
 */
@Service
@Slf4j
public class AppSendRouteInfoService {

    /** 站点启用状态值（0=启用，与车辆口径一致；status 为空按启用处理，兼容历史数据） */
    private static final int STATION_STATUS_ENABLED = 0;

    @Resource
    private StationMapper stationMapper;
    @Resource
    private AlgorithmClient algorithmClient;

    public RoutePreviewRespVO routePreview(Long pickupStationId, Long deliveryStationId) {
        if (pickupStationId == null || deliveryStationId == null) {
            throw exception(STATION_NOT_EXISTS);
        }
        if (Objects.equals(pickupStationId, deliveryStationId)) {
            throw exception(SEND_STATIONS_SAME);
        }
        StationDO pickup = requireEnabled(pickupStationId);
        StationDO delivery = requireEnabled(deliveryStationId);

        RoutePreviewRespVO vo = new RoutePreviewRespVO();
        AlgorithmDistanceRespDTO resp;
        try {
            resp = algorithmClient.distance(AlgorithmDistanceReqDTO.builder()
                    .stations(List.of(toAlgorithmStation(pickup), toAlgorithmStation(delivery)))
                    .build());
        } catch (Exception ex) {
            log.warn("[routePreview][{}-{} 算法服务不可用：{}]", pickupStationId, deliveryStationId, ex.getMessage());
            vo.setAvailable(false);
            vo.setProvider("amap");
            vo.setWarning("路线服务暂不可用，请稍后重试");
            return vo;
        }
        if (resp == null || resp.getPairs() == null || resp.getPairs().isEmpty()) {
            vo.setAvailable(false);
            vo.setProvider("amap");
            vo.setWarning("路线服务暂不可用，请稍后重试");
            return vo;
        }
        AlgorithmDistancePairDTO pair = resp.getPairs().get(0);
        if (pair.getAvailable() != null && !pair.getAvailable()) {
            vo.setAvailable(false);
            vo.setProvider(pair.getProvider());
            vo.setWarning("所选站点暂无法规划路线");
            return vo;
        }
        if (pair.getDistanceKm() == null) {
            vo.setAvailable(false);
            vo.setProvider(pair.getProvider());
            vo.setWarning("所选站点暂无法规划路线");
            return vo;
        }
        vo.setAvailable(true);
        vo.setDistanceKm(BigDecimal.valueOf(Math.round(pair.getDistanceKm() * 100) / 100.0));
        vo.setProvider(pair.getProvider());
        vo.setDurationMinutes(pair.getDurationSeconds() != null
                ? (int) Math.ceil(pair.getDurationSeconds() / 60.0) : null);
        if ("euclidean".equals(pair.getProvider())) {
            vo.setWarning("路网暂不可用，当前为直线估算");
        }
        return vo;
    }

    /** 站点存在 + 未删除（selectById 逻辑删除自动过滤）+ 已启用；否则抛明确业务错误 */
    private StationDO requireEnabled(Long stationId) {
        StationDO station = stationMapper.selectById(stationId);
        if (station == null) {
            throw exception(STATION_NOT_EXISTS);
        }
        if (station.getStatus() != null && station.getStatus() != STATION_STATUS_ENABLED) {
            throw exception(STATION_DISABLED);
        }
        return station;
    }

    private AlgorithmStationDTO toAlgorithmStation(StationDO station) {
        return AlgorithmStationDTO.builder()
                .stationId(String.valueOf(station.getId()))
                .longitude(station.getLongitude().doubleValue())
                .latitude(station.getLatitude().doubleValue())
                .build();
    }

}
