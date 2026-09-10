package cn.iocoder.yudao.module.transport.service.transport.transit;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 现实公交数据源（REAL_TRANSIT）：高德 Web 服务「周边搜索」查询公交站点。
 *
 * - 仅在配置了 key（`yudao.transport.amap.key` 或环境变量 `AMAP_KEY`）时可用；
 *   未配置 → {@link #available()} 返回 false，调用方自动降级到项目线路层，**不伪造现实公交**。
 * - 坐标：高德与站点表、wx.getLocation(gcj02) 同为 GCJ-02，直接比较距离，不需要转换。
 * - 容错：超时/限额/返回异常一律记日志 + 返回空列表，绝不影响附近公交主流程。
 * - 结果缓存 60s：附近公交首页 15s 刷新一次，避免打爆高德配额。
 *
 * 说明（已知能力边界）：高德 Web 服务没有"按坐标查公交线路"的接口，本实现只提供**站点**；
 * 线路层由项目自建线路（{@link ProjectTransitProvider}）提供，前端按来源分层展示不混淆。
 */
@Slf4j
@Service
public class AmapTransitProvider implements TransitProvider {

    /** 高德周边搜索接口 */
    private static final String PLACE_AROUND_URL = "https://restapi.amap.com/v3/place/around";
    /** POI 分类码：150700 = 公交车站 */
    private static final String TYPE_BUS_STATION = "150700";
    /** 结果缓存 TTL（毫秒） */
    private static final long CACHE_TTL_MS = 60_000L;
    /** 单次调用超时 */
    private static final Duration TIMEOUT = Duration.ofMillis(2500);
    /** 单次返回站点数上限 */
    private static final int MAX_POIS = 20;

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Value("${yudao.transport.amap.key:${AMAP_KEY:}}")
    private String amapKey;

    private final RestTemplate restTemplate = new RestTemplateBuilder()
            .connectTimeout(TIMEOUT)
            .readTimeout(TIMEOUT)
            .build();

    /** key = 坐标+半径（4 位小数桶），value = 站点列表 + 过期时间 */
    private final Map<String, Cached> cache = new ConcurrentHashMap<>();

    private record Cached(List<TransitStation> stations, long expireAt) {
    }

    @Override
    public String name() {
        return "AMAP";
    }

    @Override
    public String dataSource() {
        return REAL_TRANSIT;
    }

    @Override
    public boolean available() {
        return amapKey != null && !amapKey.isBlank();
    }

    @Override
    public List<TransitStation> searchNearbyStations(double latitude, double longitude, double radiusMeters) {
        if (!available()) {
            return List.of();
        }
        String cacheKey = String.format("%.4f,%.4f,%.0f", latitude, longitude, radiusMeters);
        Cached cached = cache.get(cacheKey);
        if (cached != null && cached.expireAt() > System.currentTimeMillis()) {
            return cached.stations();
        }
        List<TransitStation> stations = doSearch(latitude, longitude, radiusMeters);
        cache.put(cacheKey, new Cached(stations, System.currentTimeMillis() + CACHE_TTL_MS));
        return stations;
    }

    private List<TransitStation> doSearch(double latitude, double longitude, double radiusMeters) {
        String url = PLACE_AROUND_URL + "?key=" + amapKey
                + "&location=" + longitude + "," + latitude // 高德：经度在前
                + "&types=" + TYPE_BUS_STATION
                + "&radius=" + (long) radiusMeters
                + "&offset=" + MAX_POIS + "&page=1&extensions=base";
        try {
            String body = restTemplate.getForObject(url, String.class);
            return parseStations(body);
        } catch (Exception ex) {
            log.warn("[AmapTransit] 附近公交站点查询失败（自动降级项目线路层）：{}", ex.getMessage());
            return List.of();
        }
    }

    /**
     * 解析高德周边搜索响应（纯函数，便于单测）。
     * 响应形如：{"status":"1","pois":[{"name":"XX站","location":"104.123,30.456","distance":"120"}]}
     * status != "1"、无 pois、单条缺坐标 → 跳过；distance 单位米 → 公里（2 位小数）。
     */
    @SuppressWarnings("unchecked")
    static List<TransitStation> parseStations(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        Map<String, Object> root;
        try {
            root = OBJECT_MAPPER.readValue(json, Map.class);
        } catch (Exception ex) {
            return List.of();
        }
        if (!"1".equals(String.valueOf(root.get("status")))) {
            return List.of();
        }
        Object poisObj = root.get("pois");
        if (!(poisObj instanceof List<?> pois)) {
            return List.of();
        }
        List<TransitStation> result = new ArrayList<>();
        for (Object poiObj : pois) {
            if (!(poiObj instanceof Map<?, ?> poi)) {
                continue;
            }
            String name = poi.get("name") == null ? null : String.valueOf(poi.get("name"));
            String location = poi.get("location") == null ? null : String.valueOf(poi.get("location"));
            if (name == null || name.isBlank() || location == null || location.indexOf(',') <= 0) {
                continue;
            }
            String[] xy = location.split(",");
            double lng;
            double lat;
            try {
                lng = Double.parseDouble(xy[0].trim());
                lat = Double.parseDouble(xy[1].trim());
            } catch (NumberFormatException ex) {
                continue;
            }
            Double distanceKm = null;
            try {
                distanceKm = Math.round(Double.parseDouble(String.valueOf(poi.get("distance"))) / 10.0) / 100.0;
            } catch (Exception ignored) {
                // 无 distance 字段：保持 null，由调用方按坐标计算
            }
            String address = poi.get("address") == null ? null : String.valueOf(poi.get("address"));
            if (address != null && ("[]".equals(address.trim()) || address.isBlank())) {
                address = null;
            }
            // 公交站 POI 的 address 实际是"途经线路"（如 "125路" / "184路;801路;K13线"），拆成线路名供展示
            result.add(new TransitStation(name, lng, lat, distanceKm, parseLines(address),
                    address, REAL_TRANSIT));
        }
        return dedupe(result);
    }

    /**
     * 站点去重：同名同坐标（5 位小数）合并为一条，线路取并集。
     * 高德同一公交站常有多条 POI 记录（每条对应部分线路），不去重会出现「曾家岩(公交站)」两张卡片。
     */
    static List<TransitStation> dedupe(List<TransitStation> stations) {
        Map<String, TransitStation> map = new LinkedHashMap<>();
        for (TransitStation station : stations) {
            String key = normalizeName(station.name()) + "|"
                    + String.format("%.5f", station.longitude()) + "|" + String.format("%.5f", station.latitude());
            TransitStation exist = map.get(key);
            if (exist == null) {
                map.put(key, station);
                continue;
            }
            Set<String> lines = new LinkedHashSet<>(exist.lines() == null ? List.of() : exist.lines());
            if (station.lines() != null) {
                lines.addAll(station.lines());
            }
            Double distance = exist.distanceKm() == null ? station.distanceKm()
                    : (station.distanceKm() == null ? exist.distanceKm()
                    : Math.min(exist.distanceKm(), station.distanceKm()));
            String name = exist.name() != null && station.name() != null && station.name().length() < exist.name().length()
                    ? station.name() : exist.name();
            map.put(key, new TransitStation(name, exist.longitude(), exist.latitude(), distance,
                    new ArrayList<>(lines), exist.address() != null ? exist.address() : station.address(),
                    exist.dataSource() != null ? exist.dataSource() : station.dataSource()));
        }
        return new ArrayList<>(map.values());
    }

    /** 名称规范化：委托 {@link TransitProvider#normalizeStationName}，保证前后端同一套键规则 */
    static String normalizeName(String name) {
        return TransitProvider.normalizeStationName(name);
    }

    /**
     * 高德公交站 POI address → 途经线路名数组（过滤 "XX路1号" / "XX路站" 这类地址片段，最多 8 条）。
     * 暴露为包级静态方法便于单测。
     */
    static List<String> parseLines(String address) {
        if (address == null || address.isBlank()) {
            return List.of();
        }
        List<String> lines = new ArrayList<>();
        for (String part : address.split("[;；]")) {
            String line = part.trim();
            if (line.isEmpty() || line.length() > 24
                    || !(line.contains("路") || line.contains("线") || line.contains("快巴") || line.contains("BRT"))
                    || line.endsWith("号") || line.endsWith("站")) {
                continue;
            }
            lines.add(line);
            if (lines.size() >= 8) {
                break;
            }
        }
        return lines;
    }
}
