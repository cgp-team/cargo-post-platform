package cn.iocoder.yudao.module.transport.service.geo;

import java.util.Map;

/**
 * 路网来源白名单：只有这里列出的 provider 字符串才算"真实道路"，其余（含未知写法）一律不采信。
 *
 * <p>为什么做成白名单而不是黑名单：几何一旦来源不明就会被画成一条不存在的路。
 * 判断"不是 euclidean"是不够的 —— {@code ""}、{@code "estimated"}、{@code null}、
 * 以及将来某个 Provider 新起的名字，全都是"未知来源"，未知即不可信。</p>
 *
 * <p>与 Android 端 {@code core/map/RouteSource} 是同一份口径，两边必须同步改；
 * 这里输出的标签（{@link #AMAP} 等）用 Android 侧的枚举名，保证客户端能原样解析。</p>
 */
public final class RealRoadProviders {

    public static final String AMAP = "AMAP";
    public static final String OSM_GRAPHHOPPER = "OSM_GRAPHHOPPER";
    public static final String OSM_ROAD_GRAPH = "OSM_ROAD_GRAPH";
    public static final String VALHALLA = "VALHALLA";
    public static final String CACHE_REAL_ROAD = "CACHE_REAL_ROAD";

    /** 整条折线由多个真实路网来源逐段拼成（每一段都在白名单内），不是"来源不明" */
    public static final String MIXED = "MIXED";

    /** 键为归一化（小写、{@code -}→{@code _}、去空格）后的写法 */
    private static final Map<String, String> REAL = Map.ofEntries(
            Map.entry("amap", AMAP),
            Map.entry("gaode", AMAP),
            Map.entry("高德", AMAP),
            Map.entry("graphhopper", OSM_GRAPHHOPPER),
            Map.entry("osm_graphhopper", OSM_GRAPHHOPPER),
            Map.entry("osm", OSM_GRAPHHOPPER),
            Map.entry("local_graph", OSM_ROAD_GRAPH),
            Map.entry("osm_road_graph", OSM_ROAD_GRAPH),
            Map.entry("roadgraph", OSM_ROAD_GRAPH),
            Map.entry("localgraph", OSM_ROAD_GRAPH),
            Map.entry("valhalla", VALHALLA),
            Map.entry("cache", CACHE_REAL_ROAD),
            Map.entry("offline_cache", CACHE_REAL_ROAD));

    private RealRoadProviders() {
    }

    /** 归一化：与 Android {@code RouteSource.parse} 的 key 规则一致 */
    private static String normalize(String provider) {
        if (provider == null) {
            return "";
        }
        return provider.trim().toLowerCase().replace('-', '_').replace(" ", "");
    }

    /** 这一段的几何是否来自真实路网 */
    public static boolean isRealRoad(String provider) {
        return REAL.containsKey(normalize(provider));
    }

    /**
     * 归一化后的标准标签（下发给客户端）；来源不可信返回 {@code null}，
     * 由调用方按"没有路线"处理，而不是保留原始字符串让前端自己去猜。
     */
    public static String labelOf(String provider) {
        return REAL.get(normalize(provider));
    }
}
