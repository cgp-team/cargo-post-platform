package cn.iocoder.yudao.module.transport.service.bigscreen;

import java.util.Map;

/**
 * 智慧大屏聚合 Service 接口
 *
 * 目的：把大屏「N 个观看者 × 5 个接口」收敛为「1 个接口 + 1 次聚合查询」，
 * 并以 Redis 缓存挡住并发（TTL = 刷新间隔 × 0.8，即 60s 刷新 → 48s 缓存）。
 */
public interface BigScreenService {

    /**
     * 大屏 T2 层一次聚合：摘要 KPI + 今日班次 + 返程结算 + 订单趋势（24h）+ 类型/状态分布
     *
     * @return 聚合数据（含 generatedAt 数据生成时间，前端"最后更新"用它）
     */
    Map<String, Object> getOverview();

}
