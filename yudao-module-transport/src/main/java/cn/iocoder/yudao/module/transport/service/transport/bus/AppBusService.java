package cn.iocoder.yudao.module.transport.service.transport.bus;

import cn.iocoder.yudao.module.transport.controller.app.transport.bus.vo.AppBusLineRespVO;
import cn.iocoder.yudao.module.transport.controller.app.transport.bus.vo.AppBusRespVO;

import java.util.List;

/**
 * 小程序端实时公交 Service。
 * 复用监控中心实时车辆位置（司机真实上报优先，否则按班次时刻插值），
 * 聚合线路起终点 / 下一站 / ETA 供小程序「附近公交 · 实时到站」展示。
 */
public interface AppBusService {

    /** 实时公交列表（有班次的车辆；在途为行驶中，空闲为已到站/待发） */
    List<AppBusRespVO> getRealtimeBuses();

    /** 实时公交线路（含经停点与该线在线车辆，供「车来了式」地图+列表页） */
    List<AppBusLineRespVO> getLines();

}
