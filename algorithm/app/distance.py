"""距离提供方：欧氏直线（默认兜底）与高德驾车路网距离（配置 AMAP_KEY 启用）。

单位语义（与后端约定，全链路不双重换算）：
- 欧氏路径输出"度"，PlanResult.distanceUnit="degree"，后端按 Haversine 换算公里；
- 高德路径输出真实公里，distanceUnit="km"，后端直通使用。

高德「距离测量」API（GET https://restapi.amap.com/v3/distance，type=1 驾车导航距离）：
- origins 单请求最多 100 个坐标对（"|" 分隔，"lon,lat"），destination 单个；
  本服务规模上限 30 站点 + 1 场站，一次调用覆盖全量 origins。
- 判错（官方文档）：status != "1"（含配额类 infocode，如 10003/10004/10044）；
  且"由于此接口支持批量请求，建议不论批量与否用 results[].info 字段判断请求是否成功"——
  result 带 info/code（1 无可行车道路 / 2 起终点离道路过远 / 3 不在中国境内）即判失败。
- 降级：任一 destination 失败/超时/配额错误 → 抛 AmapUnavailable，调用方整单降级回
  欧氏直线，保证单次求解的矩阵口径一致（不做部分对降级）。
"""

from __future__ import annotations

import hashlib
import os
import threading
import time
from dataclasses import dataclass
from math import asin, cos, hypot, radians, sin, sqrt
from typing import Protocol

import httpx

from .models import RoutePoint, Station

AMAP_DISTANCE_URL = "https://restapi.amap.com/v3/distance"
AMAP_DRIVING_URL = "https://restapi.amap.com/v3/direction/driving"
AMAP_TIMEOUT_SECONDS = 5.0
AMAP_MAX_ATTEMPTS = 2  # 首次 + 失败重试 1 次
# 高德个人开发者 key 的 QPS 限制很低（实测 ~3/s 即报 CUQPS_HAS_EXCEEDED_THE_LIMIT）：
# 目的地请求之间强制限速间隔；命中限流时按更长的退避序列重试
AMAP_PACE_SECONDS = 0.35
AMAP_THROTTLE_MAX_ATTEMPTS = 4
AMAP_THROTTLE_BACKOFF = (0.5, 1.0, 2.0)
AMAP_THROTTLE_MARKERS = ("HAS_EXCEEDED_THE_LIMIT", "QPS")
CACHE_TTL_SECONDS = 24 * 3600
# 高德 origins 单请求上限 100 个坐标对；本服务上限 31 点，一次调用覆盖全量
AMAP_MAX_ORIGINS = 100
# 直线估算默认均速（km/h），与业务后端 PricingRule 默认一致；直线降级时估算秒
EUCLIDEAN_AVG_SPEED_KMH = 25.0

# (from_station_id, to_station_id) -> (km, seconds)；seconds 为 None 表示无时长数据（欧氏路径）
DistanceMatrix = dict[tuple[str, str], tuple[float, float | None]]


@dataclass
class RouteResult:
    """单路线结果（Route Preview / 真实道路 polyline 用）。

    - available=True：有距离/时长；provider 标识来源（amap=高德路网 / euclidean=直线估算 fallback）。
    - available=False：该点对明确不可达（高德无可行车道路），无估算值。
    - 距离恒为公里（km），不做 degree 换算。
    - polyline：真实道路坐标点序列（GCJ-02）；euclidean 兜底时仅起终点两点（明确标注，不伪装真实道路）。
    """

    available: bool
    distanceKm: float | None
    durationSeconds: float | None
    provider: str
    polyline: list[RoutePoint] | None = None


def haversine_km(lon1: float, lat1: float, lon2: float, lat2: float) -> float:
    """Haversine 大圆距离（km），欧氏直线降级时换算真实公里用。"""
    d_lat = radians(lat2 - lat1)
    d_lon = radians(lon2 - lon1)
    a = sin(d_lat / 2) ** 2 + cos(radians(lat1)) * cos(radians(lat2)) * sin(d_lon / 2) ** 2
    return 2 * 6371.0 * asin(sqrt(a))


class AmapUnavailable(Exception):
    """高德路网距离不可用（失败/超时/配额错误）；调用方整单降级回欧氏直线。

    unreachable=True 表示该点对明确无可行车道路（不可达，非降级可覆盖的失败）。
    """

    def __init__(self, message: str, unreachable: bool = False):
        super().__init__(message)
        self.unreachable = unreachable


class DistanceProvider(Protocol):
    """距离矩阵提供方：输入站点集合，输出全量有序站点对的 (km, seconds)。"""

    def get_matrix(self, points: list[Station]) -> DistanceMatrix: ...


class EuclideanDistanceProvider:
    """欧氏直线口径（单位：度）+ Haversine 均速估算秒数。"""

    def get_matrix(self, points: list[Station]) -> DistanceMatrix:
        result: DistanceMatrix = {}
        for a in points:
            for b in points:
                dist_deg = hypot(a.longitude - b.longitude, a.latitude - b.latitude)
                km = haversine_km(a.longitude, a.latitude, b.longitude, b.latitude)
                seconds = round(km / EUCLIDEAN_AVG_SPEED_KMH * 3600) if km > 0 else 0.0
                result[(a.stationId, b.stationId)] = (dist_deg, seconds)
        return result


@dataclass
class _CacheEntry:
    matrix: DistanceMatrix
    created_at: float


class AmapDistanceProvider:
    """高德路网距离提供方。

    - 配置：AMAP_KEY 环境变量；from_env() 在 key 为空时返回 None（服务整体走欧氏路径）。
    - 调用：每个 destination 一次请求（origins 全量 ≤100），超时 5s，失败重试 1 次。
    - 缓存：进程内站点级缓存，key = 站点集合坐标哈希，TTL 24h；站点坐标基本不变，
      命中缓存 0 次外部调用。duration（秒）随矩阵缓存，留作后续 ETA 改进（本期只用 km）。
    """

    def __init__(self, key: str, client: httpx.Client | None = None, cache: dict[str, _CacheEntry] | None = None):
        self._key = key
        self._client = client or httpx.Client(timeout=AMAP_TIMEOUT_SECONDS)
        self._cache: dict[str, _CacheEntry] = cache if cache is not None else {}
        # 单路线（含 polyline）缓存：key = "lon,lat→lon,lat"，TTL 24h（RoadSegment 缓存，禁止每车每 5s 打高德）
        self._route_cache: dict[str, tuple[RouteResult, float]] = {}
        # 串行化高德请求：key QPS 低（实测 ~3/s）+ 共享连接池，多线程并发会触发限流/缓存复合操作竞态
        self._lock = threading.Lock()

    def get_route_with_polyline(self, origin: Station, destination: Station) -> RouteResult:
        """单点对路网路径（含真实道路 polyline），供车辆沿真实道路运行。

        高德驾车路径 API 成功 → available=True, provider=amap, polyline=真实道路坐标；
        明确不可达 → available=False；高德失败/超时/配额 → 直线兜底（provider=euclidean，
        polyline 仅起终点两点，明确标注不伪装真实道路）。
        缓存：按起终点坐标（RoadSegment 口径），TTL 24h，命中 0 次外部调用。
        """
        with self._lock:
            cache_key = f"{self._coord(origin)}→{self._coord(destination)}"
            entry = self._route_cache.get(cache_key)
            if entry is not None and entry[1] > time.time():
                return entry[0]
            try:
                km, seconds, polyline = self._fetch_driving_route(origin, destination)
                result = RouteResult(available=True, distanceKm=round(km, 2), durationSeconds=seconds,
                                     provider="amap", polyline=polyline)
            except AmapUnavailable as exc:
                if exc.unreachable:
                    result = RouteResult(available=False, distanceKm=None, durationSeconds=None, provider="amap")
                else:
                    result = self._euclidean_route_with_polyline(origin, destination)
            self._route_cache[cache_key] = (result, time.time() + CACHE_TTL_SECONDS)
            return result

    def _fetch_driving_route(self, origin: Station, destination: Station) -> tuple[float, float, list[RoutePoint]]:
        """高德驾车路径：返回 (公里, 秒, 坐标点序列)。单点不可达抛 unreachable=True。"""
        params = {
            "key": self._key,
            "origin": self._coord(origin),
            "destination": self._coord(destination),
            # 高德驾车路径：extensions=all 才返回 paths[].polyline（真实道路坐标序列）；
            # extensions=base 只给 distance/duration，polyline 恒为空 → 前端只能画直线。
            "extensions": "all",
        }
        try:
            response = self._client.get(AMAP_DRIVING_URL, params=params)
            response.raise_for_status()
        except httpx.HTTPError as exc:
            raise AmapUnavailable(f"高德驾车路径请求失败: {exc}") from exc
        payload = response.json()
        if payload.get("status") != "1":
            raise AmapUnavailable(f"高德驾车路径返回错误: infocode={payload.get('infocode')} info={payload.get('info')}")
        paths = (payload.get("route") or {}).get("paths") or []
        if not paths:
            raise AmapUnavailable("高德驾车路径无可行路线", unreachable=True)
        path = paths[0]
        distance_m = float(path.get("distance") or 0)
        duration_s = float(path.get("duration") or 0)
        polyline = self._parse_polyline(path.get("polyline") or "")
        if not polyline:
            # 高德 v3 驾车 + extensions=all：path 级不返回 polyline，路径点分散在 steps[].polyline，
            # 逐段拼接后再去重（相邻 step 首尾点重复），否则前端只能画直线。
            raw = ";".join(str(step.get("polyline") or "") for step in (path.get("steps") or []))
            polyline = self._dedupe_points(self._parse_polyline(raw))
        return distance_m / 1000.0, duration_s, polyline

    @staticmethod
    def _dedupe_points(points: list[RoutePoint]) -> list[RoutePoint]:
        """去掉相邻重复点（相邻 step 的衔接点会重复出现）。"""
        result: list[RoutePoint] = []
        for point in points:
            if result and result[-1].longitude == point.longitude and result[-1].latitude == point.latitude:
                continue
            result.append(point)
        return result

    @staticmethod
    def _parse_polyline(raw: str) -> list[RoutePoint]:
        """解析高德 polyline（"lon,lat;lon,lat;..."）→ 坐标点序列（GCJ-02）。"""
        points: list[RoutePoint] = []
        for segment in raw.split(";"):
            if not segment or "," not in segment:
                continue
            lon, lat = segment.split(",")
            points.append(RoutePoint(longitude=float(lon), latitude=float(lat)))
        return points

    def _euclidean_route_with_polyline(self, origin: Station, destination: Station) -> RouteResult:
        """直线兜底：Haversine 公里 + 均速秒 + 仅起终点两点的直线 polyline（明确 provider=euclidean）。"""
        km = haversine_km(origin.longitude, origin.latitude, destination.longitude, destination.latitude)
        seconds = round(km / EUCLIDEAN_AVG_SPEED_KMH * 3600)
        polyline = [RoutePoint(longitude=origin.longitude, latitude=origin.latitude),
                    RoutePoint(longitude=destination.longitude, latitude=destination.latitude)]
        return RouteResult(available=True, distanceKm=round(km, 2), durationSeconds=seconds,
                           provider="euclidean", polyline=polyline)

    @classmethod
    def from_env(cls) -> AmapDistanceProvider | None:
        key = os.environ.get("AMAP_KEY", "").strip()
        return cls(key) if key else None

    def get_matrix(self, points: list[Station]) -> DistanceMatrix:
        with self._lock:
            self._evict_expired()
            cache_key = self._cache_key(points)
            entry = self._cache.get(cache_key)
            if entry is not None:
                return entry.matrix
            if len(points) > AMAP_MAX_ORIGINS:
                raise AmapUnavailable(f"坐标对 {len(points)} 超过高德单请求上限 {AMAP_MAX_ORIGINS}")
            coords = [self._coord(point) for point in points]
            matrix: DistanceMatrix = {}
            for index, destination in enumerate(coords):
                if index > 0:
                    # 限速间隔：高德 key QPS 很低（实测 ~3/s），连续请求会触发 10021 限流
                    time.sleep(AMAP_PACE_SECONDS)
                pairs = self._fetch_to_destination(coords, destination)
                for origin_index, (km, seconds) in enumerate(pairs):
                    matrix[(points[origin_index].stationId, points[index].stationId)] = (km, seconds)
            self._cache[cache_key] = _CacheEntry(matrix=matrix, created_at=time.time())
            return matrix

    def get_route(self, origin: Station, destination: Station) -> RouteResult:
        """单点对路网距离/时长（Route Preview 用），薄封装 get_matrix，恒返回 km。

        高德成功 → available=True, provider=amap（真实路网公里+秒）；
        明确不可达（高德无可行车道路）→ available=False；
        高德失败/超时/配额 → 直线估算，available=True, provider=euclidean。
        """
        try:
            matrix = self.get_matrix([origin, destination])
        except AmapUnavailable as exc:
            if exc.unreachable:
                return RouteResult(available=False, distanceKm=None, durationSeconds=None, provider="amap")
            return self._euclidean_route(origin, destination)
        km, seconds = matrix.get((origin.stationId, destination.stationId), (None, None))
        if km is None:
            return RouteResult(available=False, distanceKm=None, durationSeconds=None, provider="amap")
        return RouteResult(available=True, distanceKm=km, durationSeconds=seconds, provider="amap")

    def _euclidean_route(self, origin: Station, destination: Station) -> RouteResult:
        """直线估算降级：Haversine 真实公里 + 按均速估算秒（provider=euclidean）。"""
        km = haversine_km(origin.longitude, origin.latitude, destination.longitude, destination.latitude)
        seconds = round(km / EUCLIDEAN_AVG_SPEED_KMH * 3600)
        return RouteResult(available=True, distanceKm=round(km, 2), durationSeconds=seconds, provider="euclidean")

    @staticmethod
    def _coord(point: Station) -> str:
        return f"{point.longitude},{point.latitude}"

    @classmethod
    def _cache_key(cls, points: list[Station]) -> str:
        coords = "|".join(sorted({cls._coord(point) for point in points}))
        return hashlib.sha1(coords.encode()).hexdigest()

    def _evict_expired(self) -> None:
        cutoff = time.time() - CACHE_TTL_SECONDS
        expired = [key for key, entry in self._cache.items() if entry.created_at < cutoff]
        for key in expired:
            del self._cache[key]

    def _fetch_to_destination(self, origins: list[str], destination: str) -> list[tuple[float, float]]:
        params = {
            "key": self._key,
            "origins": "|".join(origins),
            "destination": destination,
            "type": "1",
        }
        last_error: Exception | None = None
        attempt = 0
        while True:
            attempt += 1
            try:
                response = self._client.get(AMAP_DISTANCE_URL, params=params)
                response.raise_for_status()
                return self._parse(response.json(), expected=len(origins))
            except (httpx.HTTPError, AmapUnavailable) as exc:
                last_error = exc
                if self._is_throttle(exc):
                    # 限流（QPS 超限）：按退避序列重试；达到尝试上限按不可用降级
                    if attempt >= AMAP_THROTTLE_MAX_ATTEMPTS:
                        break
                    time.sleep(AMAP_THROTTLE_BACKOFF[min(attempt - 1, len(AMAP_THROTTLE_BACKOFF) - 1)])
                elif attempt >= AMAP_MAX_ATTEMPTS:
                    break
        # 重试后最终失败：保留底层的"不可达"标记（单点测距失败=该点对无道路），供 get_route 区分
        raise AmapUnavailable(
            f"高德距离接口请求失败（destination={destination}）: {last_error}",
            unreachable=getattr(last_error, "unreachable", False),
        )

    @staticmethod
    def _is_throttle(exc: Exception) -> bool:
        return any(marker in str(exc) for marker in AMAP_THROTTLE_MARKERS)

    @staticmethod
    def _parse(payload: dict, expected: int) -> list[tuple[float, float]]:
        if payload.get("status") != "1":
            raise AmapUnavailable(
                f"高德距离接口返回错误: infocode={payload.get('infocode')} info={payload.get('info')}"
            )
        results = payload.get("results") or []
        if len(results) != expected:
            raise AmapUnavailable(f"结果数 {len(results)} 与 origins 数 {expected} 不一致")
        pairs: list[tuple[float, float]] = []
        for result in results:
            # 官方文档：批量与否都应按单项 info/code 判错（仅出错时返回这两个字段）。
            # 单点错误（1 无道路 / 2 离道路过远 / 3 不在中国境内）视为该点对"不可达"，非降级可覆盖的失败。
            if "info" in result or "code" in result:
                raise AmapUnavailable(
                    f"单点测距失败: origin_id={result.get('origin_id')} "
                    f"code={result.get('code')} info={result.get('info')}",
                    unreachable=True,
                )
            try:
                pairs.append((float(result["distance"]) / 1000.0, float(result["duration"])))
            except (KeyError, TypeError, ValueError) as exc:
                raise AmapUnavailable(f"结果项 distance/duration 缺失或不可解析: {result}") from exc
        return pairs
