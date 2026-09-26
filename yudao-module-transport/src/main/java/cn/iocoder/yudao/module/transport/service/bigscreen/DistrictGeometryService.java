package cn.iocoder.yudao.module.transport.service.bigscreen;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 重庆区县边界几何服务（大屏「划片区查看」专用）。
 *
 * 数据源：仓库 tools/osm-data/extract_districts.py 从本地 OSM PBF 抽取，
 * 资源文件 src/main/resources/bigscreen/chongqing-districts.json。
 * 坐标系为 GCJ-02（与站点/订单坐标一致，抽取脚本已把 WGS-84 顶点转成 GCJ-02），
 * 因此点包含判断无需运行时纠偏。
 *
 * 判定采用射线法；数据懒加载（首次调用解析一次，约 300KB）。
 */
@Slf4j
@Component
public class DistrictGeometryService {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String RESOURCE = "bigscreen/chongqing-districts.json";
    /**
     * 功能区（OSM 中与真实区县多边形重叠，如两江新区覆盖江北观音桥一带）。
     * 点包含命中多个时优先返回真实行政区，因此功能区条目排到判定队列末尾。
     */
    private static final java.util.Set<String> FUNCTIONAL_ZONES =
            java.util.Set.of("两江新区", "重庆高新区", "万盛经济技术开发区");

    /** 单个区县条目（同名飞地会拆成多个条目，各自独立判定） */
    private static final class DistrictEntry {
        final String name;
        /** rings.get(0) 为外环，其后为洞 */
        final List<double[][]> rings = new ArrayList<>();
        double minLng = Double.MAX_VALUE, maxLng = -Double.MAX_VALUE;
        double minLat = Double.MAX_VALUE, maxLat = -Double.MAX_VALUE;

        DistrictEntry(String name) {
            this.name = name;
        }

        void addRing(double[][] ring) {
            rings.add(ring);
            for (double[] p : ring) {
                minLng = Math.min(minLng, p[0]);
                maxLng = Math.max(maxLng, p[0]);
                minLat = Math.min(minLat, p[1]);
                maxLat = Math.max(maxLat, p[1]);
            }
        }
    }

    private volatile List<DistrictEntry> entries;
    private volatile Map<String, Object> rawGeo;
    private volatile List<String> names;

    @PostConstruct
    public void warmUp() {
        // 启动即加载：避免大屏首次请求时才读盘；失败不阻断启动（findDistrict 降级为 null）
        load();
    }

    private synchronized void load() {
        if (entries != null) {
            return;
        }
        List<DistrictEntry> loaded = new ArrayList<>();
        Map<String, Object> raw = new LinkedHashMap<>();
        Set<String> nameSet = new LinkedHashSet<>();
        try (InputStream in = new ClassPathResource(RESOURCE).getInputStream()) {
            JsonNode root = MAPPER.readTree(in);
            raw = MAPPER.convertValue(root, Map.class);
            for (JsonNode d : root.path("districts")) {
                String name = d.path("name").asText();
                if (name.isEmpty()) {
                    continue;
                }
                DistrictEntry entry = new DistrictEntry(name);
                for (JsonNode ringNode : d.path("rings")) {
                    double[][] ring = new double[ringNode.size()][2];
                    for (int i = 0; i < ringNode.size(); i++) {
                        ring[i][0] = ringNode.get(i).get(0).asDouble();
                        ring[i][1] = ringNode.get(i).get(1).asDouble();
                    }
                    if (ring.length >= 3) {
                        entry.addRing(ring);
                    }
                }
                if (!entry.rings.isEmpty()) {
                    loaded.add(entry);
                    nameSet.add(name);
                }
            }
            log.info("[DistrictGeometryService] 加载区县边界完成: {} 个条目, {} 个区县", loaded.size(), nameSet.size());
        } catch (Exception ex) {
            log.error("[DistrictGeometryService] 区县边界资源加载失败，划片区过滤将不可用: {}", ex.getMessage());
            loaded = new ArrayList<>();
        }
        entries = loaded;
        // 真实行政区优先判定，功能区（两江新区等，与真实区县重叠）排到末尾
        entries.sort((a, b) -> Boolean.compare(FUNCTIONAL_ZONES.contains(a.name), FUNCTIONAL_ZONES.contains(b.name)));
        rawGeo = raw;
        names = new ArrayList<>(nameSet);
    }

    /**
     * 点所在区县名（GCJ-02 坐标）；不在任何区县内（含洞/无数据）返回 null。
     */
    public String findDistrict(double lng, double lat) {
        List<DistrictEntry> list = entries;
        if (list == null) {
            load();
            list = entries;
        }
        for (DistrictEntry e : list) {
            if (lng < e.minLng || lng > e.maxLng || lat < e.minLat || lat > e.maxLat) {
                continue;
            }
            if (!inRing(e.rings.get(0), lng, lat)) {
                continue;
            }
            boolean inHole = false;
            for (int i = 1; i < e.rings.size(); i++) {
                if (inRing(e.rings.get(i), lng, lat)) {
                    inHole = true;
                    break;
                }
            }
            if (!inHole) {
                return e.name;
            }
        }
        return null;
    }

    /** 射线法点包含判断 */
    private static boolean inRing(double[][] ring, double x, double y) {
        boolean inside = false;
        for (int i = 0, j = ring.length - 1; i < ring.length; j = i++) {
            double xi = ring[i][0], yi = ring[i][1];
            double xj = ring[j][0], yj = ring[j][1];
            boolean intersect = ((yi > y) != (yj > y))
                    && (x < (xj - xi) * (y - yi) / (yj - yi + Double.MIN_VALUE) + xi);
            if (intersect) {
                inside = !inside;
            }
        }
        return inside;
    }

    public List<String> getDistrictNames() {
        if (names == null) {
            load();
        }
        return names;
    }

    /** 供 /transport/bigscreen/districts 接口返回的完整边界数据（含 source/coordinateSystem/districts） */
    public Map<String, Object> getGeoData() {
        if (rawGeo == null) {
            load();
        }
        return rawGeo;
    }
}
