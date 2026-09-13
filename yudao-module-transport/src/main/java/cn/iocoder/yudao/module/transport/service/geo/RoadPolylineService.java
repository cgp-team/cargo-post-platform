package cn.iocoder.yudao.module.transport.service.geo;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 真实道路轨迹服务（GCJ-02）。
 *
 * <p>为什么要独立成服务：项目里"画线路"的地方（调度可视化 / 模拟运营 / 司机导航 / 小程序线路详情）
 * 过去都要绕一圈算法服务的 {@code /api/v1/route}，算法服务不可用时就退化成"两点直线"，
 * 演示里看起来就是"路线还是直线"。这里直接用后端已配置的高德 Web key 调「驾车路径规划」，
 * 返回真实道路点序列，并做内存缓存（路网几何稳定，10 分钟内复用）。</p>
 *
 * <p>坐标系：高德与站点表、小程序 wx.getLocation(gcj02) 同为 GCJ-02，直接使用不换算。</p>
 *
 * <p>失败策略：未配置 key / 超时 / 限额 / 解析异常一律返回 {@code null}，
 * <b>由调用方决定是虚线直线兜底还是干脆不画</b>，本服务绝不伪造"真实道路"。</p>
 */
@Slf4j
@Service
public class RoadPolylineService {

    /** 高德驾车路径规划（返回 steps[].polyline 真实道路点） */
    private static final String DRIVING_URL = "https://restapi.amap.com/v3/direction/driving";
    /** 轨迹缓存 TTL：10 分钟（路网几何稳定，不必频繁重算） */
    private static final long CACHE_TTL_MS = 10 * 60 * 1000L;
    /** 单次调用超时 */
    private static final Duration TIMEOUT = Duration.ofMillis(4000);
    /** 缓存条目上限（超过后清理过期项，防止长跑内存膨胀） */
    private static final int CACHE_MAX_ENTRIES = 4000;
    /** 高德驾车规划单次最多 16 个途经点（origin + 16 途经点 + destination = 18 个点） */
    private static final int MAX_WAYPOINTS = 16;
    /**
     * 相邻两次高德调用的最小间隔（毫秒）：个人 key 的驾车规划 QPS 很低（约 3 次/秒），
     * 实测 120ms 间隔批量取 9 段会触发限流并把部分段退化成直线；放宽到 350ms 串行节流。
     */
    private static final long MIN_INTERVAL_MS = 350L;
    /** 失败重试次数（限流/网络抖动时再试一次，仍失败才返回 null 让调用方兜底） */
    private static final int FETCH_RETRIES = 1;
    /** 重试前等待（毫秒） */
    private static final long RETRY_WAIT_MS = 1200L;

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Value("${yudao.transport.amap.key:${AMAP_KEY:}}")
    private String amapKey;

    private final RestTemplate restTemplate = new RestTemplateBuilder()
            .connectTimeout(TIMEOUT)
            .readTimeout(TIMEOUT)
            .build();

    private final Map<String, Cached> cache = new ConcurrentHashMap<>();
    private final Object throttleLock = new Object();
    private long lastCallAt = 0L;

    private record Cached(List<double[]> points, long expireAt) {
    }

    /** key 是否已配置（未配置时所有方法返回 null，调用方走兜底） */
    public boolean available() {
        return amapKey != null && !amapKey.isBlank();
    }

    /**
     * 取两点之间的真实道路轨迹。
     *
     * @return {@code [lng,lat]} 点序列（至少 2 个点，含起终点）；不可用/失败返回 {@code null}
     */
    public List<double[]> route(double fromLongitude, double fromLatitude,
                                double toLongitude, double toLatitude) {
        if (!available() || !valid(fromLongitude, fromLatitude) || !valid(toLongitude, toLatitude)) {
            return null;
        }
        if (samePoint(fromLongitude, fromLatitude, toLongitude, toLatitude)) {
            return List.of(new double[]{fromLongitude, fromLatitude}, new double[]{toLongitude, toLatitude});
        }
        String cacheKey = round5(fromLongitude) + "," + round5(fromLatitude)
                + "->" + round5(toLongitude) + "," + round5(toLatitude);
        Cached cached = cache.get(cacheKey);
        long now = System.currentTimeMillis();
        if (cached != null && cached.expireAt() > now) {
            return cached.points();
        }
        List<double[]> points = fetch(fromLongitude, fromLatitude, toLongitude, toLatitude);
        for (int attempt = 0; (points == null || points.size() < 2) && attempt < FETCH_RETRIES; attempt++) {
            try {
                Thread.sleep(RETRY_WAIT_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
            points = fetch(fromLongitude, fromLatitude, toLongitude, toLatitude);
        }
        if (points != null && points.size() >= 2) {
            if (cache.size() > CACHE_MAX_ENTRIES) {
                cache.entrySet().removeIf(e -> e.getValue().expireAt() <= now);
            }
            cache.put(cacheKey, new Cached(points, now + CACHE_TTL_MS));
            return points;
        }
        return null;
    }

    /** 两点真实道路轨迹；失败返回 null */
    private List<double[]> fetch(double fromLongitude, double fromLatitude,
                                 double toLongitude, double toLatitude) {
        return fetch(fromLongitude, fromLatitude, toLongitude, toLatitude, null);
    }

    /**
     * 沿**已有线路走廊**取两站之间的真实道路轨迹（切片）。
     *
     * <p>为什么需要：站间若用"点对点驾车规划"，高德只按最快/最短给一条自由路径——
     * 站牌在分隔带另一侧、或该点需要下穿/上桥时，就会规划出"进隧道 → 绕远 → 掉头"的走法，
     * 与公交实际走向（例如 文峰公社 → 文峰正街路口 → 吉祥路口）不符。
     * 而线路走廊（{@code transport_route.navigation_polyline}，按逐站 waypoints 取的整条线几何）
     * 本身就是这条线路的真实走向：只要在走廊折线里找到离起讫站最近的顶点，取两者之间的那段即可。</p>
     *
     * @param corridor 整条线路的真实道路折线（{@code [lng,lat]} 序列，按线路走向）
     * @return 起讫站之间的走廊片段；走廊不可用 / 两站重合时返回 {@code null}（调用方回退点对点）
     */
    public List<double[]> sliceAlong(List<double[]> corridor,
                                     double fromLongitude, double fromLatitude,
                                     double toLongitude, double toLatitude) {
        if (corridor == null || corridor.size() < 2) {
            return null;
        }
        int start = nearestIndex(corridor, fromLongitude, fromLatitude);
        int end = nearestIndex(corridor, toLongitude, toLatitude);
        if (start < 0 || end < 0 || start == end) {
            return null;
        }
        List<double[]> slice = new ArrayList<>();
        int step = start <= end ? 1 : -1;
        for (int i = start; ; i += step) {
            slice.add(corridor.get(i));
            if (i == end) {
                break;
            }
        }
        return slice.size() >= 2 ? slice : null;
    }

    /** 走廊折线里离目标点最近的顶点下标（按球面距离）；空/非法返回 -1 */
    private static int nearestIndex(List<double[]> corridor, double longitude, double latitude) {
        int best = -1;
        double bestKm = Double.MAX_VALUE;
        for (int i = 0; i < corridor.size(); i++) {
            double[] p = corridor.get(i);
            if (p == null || p.length < 2) {
                continue;
            }
            double km = cn.iocoder.yudao.module.transport.util.GeoDistanceUtil
                    .haversineKm(p[0], p[1], longitude, latitude);
            if (km < bestKm) {
                bestKm = km;
                best = i;
            }
        }
        return best;
    }

    /**
     * 多点串联的真实道路轨迹（一次请求可带最多 {@link #MAX_WAYPOINTS} 个途经点）。
     *
     * <p>用途：公交线路 / 调度方案有几十个站，逐段请求既慢（小程序 10s 超时）又费配额；
     * 按 18 个点一组切分，整条线路通常 1~2 次请求即可拿全。</p>
     *
     * @return {@code [lng,lat]} 点序列；任一组失败返回 {@code null}（调用方兜底）
     */
    public List<double[]> routeThrough(List<double[]> stops) {
        if (!available() || stops == null || stops.size() < 2) {
            return null;
        }
        StringBuilder key = new StringBuilder("through:");
        for (double[] stop : stops) {
            if (stop == null || stop.length < 2 || !valid(stop[0], stop[1])) {
                return null;
            }
            key.append(round5(stop[0])).append(',').append(round5(stop[1])).append(';');
        }
        String cacheKey = key.toString();
        long now = System.currentTimeMillis();
        Cached cached = cache.get(cacheKey);
        if (cached != null && cached.expireAt() > now) {
            return cached.points();
        }
        List<double[]> merged = new ArrayList<>();
        int index = 0;
        while (index < stops.size() - 1) {
            int last = Math.min(index + MAX_WAYPOINTS + 1, stops.size() - 1);
            StringBuilder waypoints = new StringBuilder();
            for (int i = index + 1; i < last; i++) {
                if (waypoints.length() > 0) {
                    waypoints.append(';');
                }
                waypoints.append(stops.get(i)[0]).append(',').append(stops.get(i)[1]);
            }
            List<double[]> part = fetch(stops.get(index)[0], stops.get(index)[1],
                    stops.get(last)[0], stops.get(last)[1],
                    waypoints.length() == 0 ? null : waypoints.toString());
            if (part == null || part.size() < 2) {
                return null;
            }
            part.forEach(p -> appendPoint(merged, p[0], p[1]));
            index = last;
        }
        if (merged.size() < 2) {
            return null;
        }
        if (cache.size() > CACHE_MAX_ENTRIES) {
            cache.entrySet().removeIf(e -> e.getValue().expireAt() <= now);
        }
        cache.put(cacheKey, new Cached(merged, now + CACHE_TTL_MS));
        return merged;
    }

    /** 真实道路轨迹请求（可带途经点）；失败返回 null */
    private List<double[]> fetch(double fromLongitude, double fromLatitude,
                                 double toLongitude, double toLatitude, String waypoints) {
        throttle();
        try {
            String url = DRIVING_URL
                    + "?origin=" + fromLongitude + "," + fromLatitude
                    + "&destination=" + toLongitude + "," + toLatitude
                    + (waypoints == null || waypoints.isBlank() ? "" : "&waypoints=" + waypoints)
                    + "&extensions=all&strategy=0&key=" + amapKey;
            String body = restTemplate.getForObject(url, String.class);
            if (body == null || body.isBlank()) {
                return null;
            }
            JsonNode root = OBJECT_MAPPER.readTree(body);
            if (!"1".equals(root.path("status").asText())) {
                log.debug("[road-polyline] 高德路径规划失败：status={} info={}",
                        root.path("status").asText(), root.path("info").asText());
                return null;
            }
            JsonNode paths = root.path("route").path("paths");
            if (!paths.isArray() || paths.isEmpty()) {
                return null;
            }
            List<double[]> points = new ArrayList<>();
            appendPoint(points, fromLongitude, fromLatitude);
            for (JsonNode step : paths.get(0).path("steps")) {
                for (String segment : step.path("polyline").asText("").split(";")) {
                    int comma = segment.indexOf(',');
                    if (comma <= 0) {
                        continue;
                    }
                    try {
                        appendPoint(points, Double.parseDouble(segment.substring(0, comma).trim()),
                                Double.parseDouble(segment.substring(comma + 1).trim()));
                    } catch (NumberFormatException ignored) {
                        // 单点解析失败不影响整条轨迹
                    }
                }
            }
            appendPoint(points, toLongitude, toLatitude);
            return points.size() >= 2 ? points : null;
        } catch (Exception ex) {
            log.debug("[road-polyline] 高德路径规划异常：{}", ex.getMessage());
            return null;
        }
    }

    /** 串行节流：个人 key QPS 低，连续分段请求之间留最小间隔 */
    private void throttle() {
        synchronized (throttleLock) {
            long now = System.currentTimeMillis();
            long wait = MIN_INTERVAL_MS - (now - lastCallAt);
            if (wait > 0) {
                try {
                    Thread.sleep(wait);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            lastCallAt = System.currentTimeMillis();
        }
    }

    private static void appendPoint(List<double[]> points, double longitude, double latitude) {
        if (!points.isEmpty()) {
            double[] last = points.get(points.size() - 1);
            if (Math.abs(last[0] - longitude) < 1e-7 && Math.abs(last[1] - latitude) < 1e-7) {
                return;
            }
        }
        points.add(new double[]{longitude, latitude});
    }

    private static boolean valid(double longitude, double latitude) {
        return !Double.isNaN(longitude) && !Double.isNaN(latitude)
                && Math.abs(longitude) <= 180 && Math.abs(latitude) <= 90;
    }

    private static boolean samePoint(double fromLongitude, double fromLatitude,
                                     double toLongitude, double toLatitude) {
        return Math.abs(fromLongitude - toLongitude) < 1e-7 && Math.abs(fromLatitude - toLatitude) < 1e-7;
    }

    private static String round5(double value) {
        return String.format(java.util.Locale.ROOT, "%.5f", value);
    }
}
