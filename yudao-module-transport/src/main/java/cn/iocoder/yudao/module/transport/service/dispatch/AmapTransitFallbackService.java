package cn.iocoder.yudao.module.transport.service.dispatch;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 高德公交兜底：**仅当本地公交线网+算法解不出可行方案时**才调用高德"公交路径规划"，
 * 取"不乘地铁"（strategy=5）的纯公交方案，作为换乘点/线路建议。
 *
 * 设计原则（对应"尽量用本地路网+算法、少用高德"）：
 * - 本地有解 → 完全不调高德；
 * - 高频订单场景：失败冷却 60s + 结果缓存 5 分钟，避免打爆免费配额；
 * - 只解析公交段（过滤轨道/地铁/步行），拿到的站名再映射回本地站点，映射不全则不造腿（不伪造数据）。
 */
@Service
@Slf4j
public class AmapTransitFallbackService {

    private static final String TRANSIT_URL = "https://restapi.amap.com/v3/direction/transit/integrated";
    /** 失败冷却（毫秒）：冷却期内直接返回 null，不重复打高德 */
    private static final long FAIL_COOLDOWN_MS = 60_000;
    /** 结果缓存（毫秒）：同一 O-D 5 分钟内复用（订单池常出现同片区重复线路） */
    private static final long CACHE_TTL_MS = 5 * 60_000;
    /** 非公交线路关键字 */
    private static final List<String> NON_BUS = List.of("轨道", "地铁", "号线", "轮渡", "索道", "出租");

    @Value("${yudao.transport.amap.key:${AMAP_KEY:}}")
    private String amapKey;

    private final RestTemplate restTemplate = buildRestTemplate();
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final AtomicLong unavailableUntil = new AtomicLong(0);
    private final java.util.Map<String, CachedSuggestion> cache = new java.util.concurrent.ConcurrentHashMap<>();

    /** 一条建议乘车段（真实公交线路 + 上/下车站名） */
    public record SuggestedLeg(String lineName, String fromStopName, String toStopName) {
    }

    /** 高德公交建议（纯公交） */
    public record Suggestion(List<SuggestedLeg> legs, double distanceKm, int durationMinutes, double cost) {
        public String describe() {
            StringBuilder sb = new StringBuilder();
            for (SuggestedLeg leg : legs) {
                if (sb.length() > 0) {
                    sb.append(" + ");
                }
                sb.append(leg.lineName()).append("(").append(leg.fromStopName()).append("→")
                        .append(leg.toStopName()).append(")");
            }
            return sb.append("，约 ").append(Math.round(distanceKm)).append("km / ")
                    .append(durationMinutes).append("min").toString();
        }
    }

    private record CachedSuggestion(Suggestion suggestion, long expireAt) {
    }

    private static RestTemplate buildRestTemplate() {
        RestTemplate template = new RestTemplate();
        template.setRequestFactory(new org.springframework.http.client.SimpleClientHttpRequestFactory() {{
            setConnectTimeout((int) Duration.ofSeconds(2).toMillis());
            setReadTimeout((int) Duration.ofSeconds(6).toMillis());
        }});
        return template;
    }

    /**
     * 查询"不乘地铁"的公交方案；无 key / 冷却中 / 失败 / 无公交方案 均返回 null（调用方走原有降级）。
     */
    public Suggestion suggestBusOnly(double fromLon, double fromLat, double toLon, double toLat) {
        if (amapKey == null || amapKey.isBlank()) {
            return null;
        }
        long now = System.currentTimeMillis();
        if (now < unavailableUntil.get()) {
            return null;
        }
        String cacheKey = String.format("%.4f,%.4f->%.4f,%.4f", fromLon, fromLat, toLon, toLat);
        CachedSuggestion cached = cache.get(cacheKey);
        if (cached != null && cached.expireAt() > now) {
            return cached.suggestion();
        }
        try {
            String url = TRANSIT_URL + "?key=" + amapKey
                    + "&origin=" + fromLon + "," + fromLat
                    + "&destination=" + toLon + "," + toLat
                    + "&city=" + java.net.URLEncoder.encode("重庆", java.nio.charset.StandardCharsets.UTF_8)
                    + "&cityd=" + java.net.URLEncoder.encode("重庆", java.nio.charset.StandardCharsets.UTF_8)
                    + "&strategy=5&nightflag=0&extensions=base";
            String json = restTemplate.getForObject(url, String.class);
            Suggestion suggestion = parse(json);
            if (suggestion != null) {
                cache.put(cacheKey, new CachedSuggestion(suggestion, now + CACHE_TTL_MS));
            }
            return suggestion;
        } catch (Exception ex) {
            unavailableUntil.set(System.currentTimeMillis() + FAIL_COOLDOWN_MS);
            log.warn("[amap-transit] 高德公交兜底查询失败（{}s 内不再重试）：{}", FAIL_COOLDOWN_MS / 1000, ex.getMessage());
            return null;
        }
    }

    private Suggestion parse(String json) throws Exception {
        if (json == null || json.isBlank()) {
            return null;
        }
        JsonNode root = objectMapper.readTree(json);
        if (!"1".equals(root.path("status").asText())) {
            return null;
        }
        JsonNode transits = root.path("route").path("transits");
        if (!transits.isArray() || transits.isEmpty()) {
            return null;
        }
        for (JsonNode transit : transits) {
            List<SuggestedLeg> legs = new ArrayList<>();
            for (JsonNode segment : transit.path("segments")) {
                JsonNode bus = segment.path("bus");
                JsonNode buslines = bus.path("buslines");
                if (!buslines.isArray() || buslines.isEmpty()) {
                    continue; // 步行段跳过
                }
                JsonNode line = buslines.get(0);
                String lineName = line.path("name").asText("");
                if (lineName.isBlank() || NON_BUS.stream().anyMatch(lineName::contains)) {
                    continue; // 非公交不采纳（本项目只做公交）
                }
                legs.add(new SuggestedLeg(lineName,
                        line.path("departure_stop").path("name").asText(""),
                        line.path("arrival_stop").path("name").asText("")));
            }
            if (!legs.isEmpty() && legs.size() <= 3) {
                double km = transit.path("distance").asDouble(0) / 1000.0;
                int minutes = (int) Math.round(transit.path("duration").asDouble(0) / 60.0);
                double cost = transit.path("cost").asDouble(0);
                return new Suggestion(legs, km, minutes, cost);
            }
        }
        return null;
    }

}
