# GRAPHHOPPER_DEPLOYMENT

OSM → GraphHopper Graph → Routing Query → Real Road Route

## 1. 下载 OSM（必须构图，不能只读 POI）

```bash
wget https://download.geofabrik.de/asia/china/chongqing-latest.osm.pbf
```

记录 GRAPH_DATA_VERSION（osm 日期/哈希）→ 配置 `graph_version`。

## 2. 构图并启动

```bash
java -Xmx2g -jar graphhopper-web-*.jar \
  graph.datareader.file=chongqing-latest.osm.pbf \
  graph.location=./graph-cache \
  graph.profiles=car,bus
# 默认 http://127.0.0.1:8989
curl "http://127.0.0.1:8989/route?point=29.50,106.50&point=29.52,106.50&profile=bus&points_encoded=false"
```

## 3. 配置接入

```yaml
local-routing:
  enabled: true
  provider: graphhopper
  profile: bus
graphhopper:
  base-url: http://127.0.0.1:8989
  osm-file: /data/chongqing-latest.osm.pbf
  graph-dir: /data/graph-cache
  timeout-ms: 3000
graph_version: osm-cq-v1
```

Python：`LocalRoutingConfig.from_mapping` → `LocalRoutingProviderFactory().create()`

## 4. 测试

```bash
pytest -q algorithm/tests/test_graphhopper_provider.py algorithm/tests/test_local_routing_engine.py
```

## 5. Cache / AMap

- Cache key 含 profile + graph_version
- AMap 仅 Top-K / 最终校验
- GH 失败 → LOCAL_ROUTING_UNAVAILABLE，禁止 Haversine 假成功

## 6. 重庆演示

邮电大学 / 工商大学周边：Gap 绕行、MultiLeg 分段 geometry。
