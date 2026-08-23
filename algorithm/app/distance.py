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
import time
from dataclasses import dataclass
from math import hypot
from typing import Protocol

import httpx

from .models import Station

AMAP_DISTANCE_URL = "https://restapi.amap.com/v3/distance"
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

# (from_station_id, to_station_id) -> (km, seconds)
DistanceMatrix = dict[tuple[str, str], tuple[float, float]]


class AmapUnavailable(Exception):
    """高德路网距离不可用（失败/超时/配额错误）；调用方整单降级回欧氏直线。"""


class DistanceProvider(Protocol):
    """距离矩阵提供方：输入站点集合，输出全量有序站点对的 (km, seconds)。"""

    def get_matrix(self, points: list[Station]) -> DistanceMatrix: ...


class EuclideanDistanceProvider:
    """现状欧氏直线口径（单位：度）；seconds 恒为 0，行程时间由后端均速口径自估。"""

    def get_matrix(self, points: list[Station]) -> DistanceMatrix:
        return {
            (a.stationId, b.stationId): (
                hypot(a.longitude - b.longitude, a.latitude - b.latitude),
                0.0,
            )
            for a in points
            for b in points
        }


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

    @classmethod
    def from_env(cls) -> AmapDistanceProvider | None:
        key = os.environ.get("AMAP_KEY", "").strip()
        return cls(key) if key else None

    def get_matrix(self, points: list[Station]) -> DistanceMatrix:
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
        raise AmapUnavailable(f"高德距离接口请求失败（destination={destination}）: {last_error}")

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
            # 官方文档：批量与否都应按单项 info/code 判错（仅出错时返回这两个字段）
            if "info" in result or "code" in result:
                raise AmapUnavailable(
                    f"单点测距失败: origin_id={result.get('origin_id')} "
                    f"code={result.get('code')} info={result.get('info')}"
                )
            try:
                pairs.append((float(result["distance"]) / 1000.0, float(result["duration"])))
            except (KeyError, TypeError, ValueError) as exc:
                raise AmapUnavailable(f"结果项 distance/duration 缺失或不可解析: {result}") from exc
        return pairs
