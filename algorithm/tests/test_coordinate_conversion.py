"""坐标系统一：GCJ-02 ⇄ WGS-84（DISPATCH_CORE_V047 section 8）。

证明：round-trip、站点吸附、GraphHopper 路由、路由输出坐标一致，且**没有重复转换**。
"""

from __future__ import annotations

from app.routing import coordinate
from app.routing.local_routing import LocalRoutingEngine, RoadEdge, RoadGraph

# 重庆主城真实附近坐标（GCJ-02，业务坐标系）
GCJ_POINTS = [
    (29.5630, 106.5516),  # 解放碑附近
    (29.6010, 106.5060),  # 磁器口附近
    (29.5320, 106.6230),  # 江北
]


def test_out_of_china_no_shift():
    lon, lat = coordinate.wgs84_to_gcj02(139.6917, 35.6895)  # 东京
    assert (lon, lat) == (139.6917, 35.6895)
    assert coordinate.out_of_china(139.6917, 35.6895) is True
    assert coordinate.out_of_china(106.55, 29.56) is False


def test_round_trip_within_tolerance():
    for lat, lon in GCJ_POINTS:
        # GCJ-02 -> WGS-84 -> GCJ-02 往返误差应 < 1m
        err = coordinate.round_trip_error_m((lat, lon))
        assert err < 1.0, f"round trip error {err} too large for {(lat, lon)}"


def test_gcj_wgs_offset_is_realistic():
    # 中国境内 GCJ-02 与 WGS-84 存在 100m~700m 量级偏移
    lat, lon = GCJ_POINTS[0]
    wlat, wlon = coordinate.to_wgs84((lat, lon))
    assert (wlat, wlon) != (lat, lon)
    dlat = abs(wlat - lat) * 111_320.0
    dlon = abs(wlon - lon) * 111_320.0 * 0.87
    assert 50.0 < (dlat * dlat + dlon * dlon) ** 0.5 < 900.0


def test_station_snap_consistent_frame():
    """站点吸附必须在同一坐标系内进行（不得混用 GCJ/WGS）。"""
    lat, lon = GCJ_POINTS[1]
    graph = RoadGraph()
    graph.add_node("n1", lat, lon)
    graph.add_node("n2", lat + 0.01, lon + 0.01)
    # GCJ-02 站点直接吸附（与 graph 同坐标系）
    node, dist = graph.nearest_node((lat, lon))
    assert node == "n1"
    assert dist < 1.0

    # 若错误地把 GCJ-02 站点当成 WGS-84 去吸附 WGS-84 路网，会得到明显偏移
    wlat, wlon = coordinate.to_wgs84((lat, lon))
    _, wrong_dist = graph.nearest_node((wlat, wlon))
    assert wrong_dist > 50.0


def test_graphhopper_boundary_single_conversion():
    """GraphHopper(OSM, WGS-84) 边界：只允许一次 GCJ→WGS 与一次 WGS→GCJ。"""
    lat, lon = GCJ_POINTS[2]
    graph = RoadGraph()
    graph.add_node("a", lat, lon)
    graph.add_node("b", lat + 0.02, lon + 0.02)
    graph.add_edge(RoadEdge("a", "b", 3000.0, 400.0, ((lat, lon), (lat + 0.02, lon + 0.02))))
    engine = LocalRoutingEngine(graph)

    origin_gcj = (lat, lon)
    dest_gcj = (lat + 0.02, lon + 0.02)
    origin_wgs = coordinate.to_wgs84(origin_gcj)
    dest_wgs = coordinate.to_wgs84(dest_gcj)

    res = engine.route(origin_wgs, dest_wgs)
    assert res.is_formal
    assert res.distance_m > 0

    # 回程：WGS-84 polyline -> GCJ-02，端点必须回到原始 GCJ-02 端点（单次往返）
    poly_gcj = coordinate.to_gcj02_polyline(res.polyline)
    back_origin = poly_gcj[0]
    back_dest = poly_gcj[-1]
    assert abs(back_origin[0] - origin_gcj[0]) < 1e-6
    assert abs(back_origin[1] - origin_gcj[1]) < 1e-6
    assert abs(back_dest[0] - dest_gcj[0]) < 1e-6
    assert abs(back_dest[1] - dest_gcj[1]) < 1e-6


def test_polyline_helpers_are_inverse():
    poly = [GCJ_POINTS[0], GCJ_POINTS[2]]
    wgs = coordinate.to_wgs84_polyline(poly)
    back = coordinate.to_gcj02_polyline(wgs)
    for (a_lat, a_lon), (b_lat, b_lon) in zip(poly, back):
        assert abs(a_lat - b_lat) < 1e-6
        assert abs(a_lon - b_lon) < 1e-6
