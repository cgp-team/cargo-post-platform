"""坐标系统一转换边界（GCJ-02 ⇄ WGS-84）。

项目约定（2026-09 统一，见 JVM 侧 `GeoCoordUtil`，本模块是其在算法侧的**唯一**对应实现）：

- 前端 / 小程序 `wx.getLocation(type=gcj02)` / 高德（AMap）/ 库内 `transport_station` 均为 **GCJ-02**；
- 车载 GPS / 北斗上报为 **WGS-84**，进入算法 / 落库前必须先 `wgs84_to_gcj02`；
- OSM / GraphHopper 路网为 **WGS-84**，调用 GraphHopper 前 `gcj02_to_wgs84`，返回 polyline 后再 `wgs84_to_gcj02`。

禁止：页面 / 服务各自手写偏移算法（会重复转换）。算法侧只允许 `import coordinate`，
且**同一次业务坐标只允许跨越边界一次**。
"""

from __future__ import annotations

import math
from enum import Enum
from typing import Iterable, Sequence

# WGS-84 椭球（克拉索夫斯基，与 JVM GeoCoordUtil 保持一致）
_A = 6378245.0
_EE = 0.00669342162296594323


class CoordinateFrame(str, Enum):
    GCJ02 = "GCJ02"
    WGS84 = "WGS84"


def out_of_china(lon: float, lat: float) -> bool:
    """粗略国境判定：境外不做偏移（与 JVM 实现一致）。"""
    if not (72.004 <= lon <= 137.8347):
        return True
    if not (0.8293 <= lat <= 55.8271):
        return True
    return False


def _transform_lat(x: float, y: float) -> float:
    ret = (
        -100.0
        + 2.0 * x
        + 3.0 * y
        + 0.2 * y * y
        + 0.1 * x * y
        + 0.2 * (abs(x) ** 0.5)
    )
    ret += (20.0 * math.sin(6.0 * x) + 20.0 * math.sin(2.0 * x)) * 2.0 / 3.0
    ret += (20.0 * math.sin(y) + 40.0 * math.sin(y / 3.0)) * 2.0 / 3.0
    ret += (160.0 * math.sin(y / 12.0) + 320.0 * math.sin(y * math.pi / 30.0)) * 2.0 / 3.0
    return ret


def _transform_lon(x: float, y: float) -> float:
    ret = (
        300.0
        + x
        + 2.0 * y
        + 0.1 * x * x
        + 0.1 * x * y
        + 0.1 * (abs(x) ** 0.5)
    )
    ret += (20.0 * math.sin(6.0 * x) + 20.0 * math.sin(2.0 * x)) * 2.0 / 3.0
    ret += (20.0 * math.sin(x) + 40.0 * math.sin(x / 3.0)) * 2.0 / 3.0
    ret += (150.0 * math.sin(x / 12.0) + 300.0 * math.sin(x / 30.0)) * 2.0 / 3.0
    return ret


def wgs84_to_gcj02(lon: float, lat: float) -> tuple[float, float]:
    """WGS-84 → GCJ-02，返回 `(lon, lat)`；境外原样返回。"""
    if out_of_china(lon, lat):
        return lon, lat
    d_lat = _transform_lat(lon - 105.0, lat - 35.0)
    d_lon = _transform_lon(lon - 105.0, lat - 35.0)
    rad_lat = lat / 180.0 * math.pi
    magic = math.sin(rad_lat)
    magic = 1 - _EE * magic * magic
    sqrt_magic = math.sqrt(magic)
    d_lat = (d_lat * 180.0) / ((_A * (1 - _EE)) / (magic * sqrt_magic) * math.pi)
    d_lon = (d_lon * 180.0) / (_A / sqrt_magic * math.cos(rad_lat) * math.pi)
    return lon + d_lon, lat + d_lat


def gcj02_to_wgs84(lon: float, lat: float, iterations: int = 2) -> tuple[float, float]:
    """GCJ-02 → WGS-84，返回 `(lon, lat)`；境外原样返回。

    用加偏移后的偏差反向迭代修正（2 次迭代误差 < 1e-6 度 ≈ 0.1m，与 JVM 实现一致）。
    """
    if out_of_china(lon, lat):
        return lon, lat
    wgs_lon, wgs_lat = lon, lat
    for _ in range(max(1, iterations)):
        gcj_lon, gcj_lat = wgs84_to_gcj02(wgs_lon, wgs_lat)
        wgs_lon += lon - gcj_lon
        wgs_lat += lat - gcj_lat
    return wgs_lon, wgs_lat


def convert_point(point: Sequence[float], target: CoordinateFrame) -> tuple[float, float]:
    """`(lon, lat)` 坐标跨边界转换，返回 `(lon, lat)`。"""
    lon, lat = float(point[0]), float(point[1])
    if target == CoordinateFrame.WGS84:
        return gcj02_to_wgs84(lon, lat)
    return wgs84_to_gcj02(lon, lat)


def to_wgs84(point_lat_lon: tuple[float, float]) -> tuple[float, float]:
    """内部 `(lat, lon)`（GCJ-02）→ 内部 `(lat, lon)`（WGS-84）。"""
    lon, lat = gcj02_to_wgs84(point_lat_lon[1], point_lat_lon[0])
    return lat, lon


def to_gcj02(point_lat_lon: tuple[float, float]) -> tuple[float, float]:
    """内部 `(lat, lon)`（WGS-84）→ 内部 `(lat, lon)`（GCJ-02）。"""
    lon, lat = wgs84_to_gcj02(point_lat_lon[1], point_lat_lon[0])
    return lat, lon


def to_wgs84_polyline(points: Iterable[tuple[float, float]]) -> list[tuple[float, float]]:
    """内部 polyline `(lat, lon)` GCJ-02 → WGS-84。"""
    return [to_wgs84(p) for p in points]


def to_gcj02_polyline(points: Iterable[tuple[float, float]]) -> list[tuple[float, float]]:
    """内部 polyline `(lat, lon)` WGS-84 → GCJ-02。"""
    return [to_gcj02(p) for p in points]


def round_trip_error_m(point_lat_lon: tuple[float, float]) -> float:
    """GCJ→WGS→GCJ 往返误差（米），用于测试证明「无重复转换」。"""
    lat, lon = point_lat_lon
    back = to_gcj02(to_wgs84((lat, lon)))
    d_lat = math.radians(back[0] - lat)
    d_lon = math.radians(back[1] - lon)
    r = 6_371_000.0
    x = d_lon * math.cos(math.radians(lat)) * r
    y = d_lat * r
    return (x * x + y * y) ** 0.5


__all__ = [
    "CoordinateFrame",
    "out_of_china",
    "wgs84_to_gcj02",
    "gcj02_to_wgs84",
    "convert_point",
    "to_wgs84",
    "to_gcj02",
    "to_wgs84_polyline",
    "to_gcj02_polyline",
    "round_trip_error_m",
]
