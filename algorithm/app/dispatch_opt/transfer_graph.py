"""全网换乘图：站点—线路 二部图，搜 1–3 腿联运路径。"""
from __future__ import annotations

from dataclasses import dataclass, field
from heapq import heappop, heappush
from typing import Iterable, Sequence


@dataclass(frozen=True)
class LineSpec:
    line_id: str
    name: str
    stations: tuple[str, ...]  # 含场站的完整站序（真实线路站点）
    depot: str


@dataclass(frozen=True)
class TransferPathLeg:
    line_id: str
    line_name: str
    from_station: str
    to_station: str
    board_index: int
    alight_index: int
    stops_spanned: int


@dataclass
class TransferPath:
    legs: list[TransferPathLeg]
    transfers: int
    stops_total: int
    reason_code: str = "MULTILEG_DRIVER_HANDOVER_SAME_STATION"

    @property
    def line_chain(self) -> list[str]:
        return [leg.line_id for leg in self.legs]


class TransferGraph:
    """按「同站换乘」建边；代价 = 经过站数 + 换乘惩罚。"""

    def __init__(
        self,
        lines: Sequence[LineSpec],
        *,
        transfer_penalty_stops: float = 2.5,
        max_legs: int = 3,
        transfer_stations: Iterable[str] | None = None,
        # 联运=司机对司机同站交接；仅共享站可换乘。禁远距步行换乘。
        walk_transfers: Sequence[tuple[str, str]] | None = None,
    ):
        self.lines = {ln.line_id: ln for ln in lines}
        self.transfer_penalty = transfer_penalty_stops
        self.max_legs = max_legs
        # 步行/跨江换乘对（如 较场口↔上新街、观音桥↔两路口）
        self.walk_pairs = {tuple(sorted(p)) for p in (walk_transfers or ())}
        # 默认：出现在 ≥2 条线上的站可换乘；可显式指定
        if transfer_stations is not None:
            self.hubs = set(transfer_stations)
        else:
            count: dict[str, int] = {}
            for ln in lines:
                for s in ln.stations:
                    count[s] = count.get(s, 0) + 1
            self.hubs = {s for s, c in count.items() if c >= 2}
        # 步行/跨江桥端点必须可换乘（否则 alight 后无法上另一条线）
        self.hubs |= {s for pair in self.walk_pairs for s in pair}

    def _board_options(self, origin: str) -> list[TransferPathLeg]:
        """去程 + 返程（农村客货邮对开，双向均可承运）。"""
        out = []
        for ln in self.lines.values():
            if origin not in ln.stations:
                continue
            i = ln.stations.index(origin)
            # 去程 i → 末站
            for j in range(i + 1, len(ln.stations)):
                out.append(TransferPathLeg(
                    line_id=ln.line_id,
                    line_name=ln.name,
                    from_station=origin,
                    to_station=ln.stations[j],
                    board_index=i,
                    alight_index=j,
                    stops_spanned=j - i,
                ))
            # 返程 i → 首站
            for j in range(0, i):
                out.append(TransferPathLeg(
                    line_id=ln.line_id,
                    line_name=ln.name,
                    from_station=origin,
                    to_station=ln.stations[j],
                    board_index=i,
                    alight_index=j,
                    stops_spanned=i - j,
                ))
        return out

    def search(self, origin: str, dest: str) -> list[TransferPath]:
        """Dijkstra on (station, legs_used)；返回最优 K 条（默认按代价排序前 3）。"""
        if origin == dest:
            return []
        # state: (station, legs_used) -> (cost, path)
        start = (origin, 0)
        best: dict[tuple[str, int], float] = {start: 0.0}
        prev: dict[tuple[str, int], tuple[tuple[str, int], TransferPathLeg]] = {}
        pq: list[tuple[float, str, int]] = [(0.0, origin, 0)]
        found: list[tuple[float, TransferPath]] = []

        while pq:
            cost, st, used = heappop(pq)
            if cost > best.get((st, used), float("inf")) + 1e-9:
                continue
            if st == dest and used > 0:
                legs: list[TransferPathLeg] = []
                cur = (st, used)
                while cur in prev:
                    pstate, leg = prev[cur]
                    legs.append(leg)
                    cur = pstate
                legs.reverse()
                found.append((cost, TransferPath(
                    legs=legs,
                    transfers=len(legs) - 1,
                    stops_total=sum(l.stops_spanned for l in legs),
                )))
                continue
            if used >= self.max_legs:
                continue
            # 步行换乘：在 walk_pairs 两端之间免费/低代价跳转（不计入 leg）
            for a, b in self.walk_pairs:
                other = None
                if a == st:
                    other = b
                elif b == st:
                    other = a
                if other is None:
                    continue
                ncost = cost + 1.0  # 步行换乘等效 1 站
                key = (other, used)
                if ncost < best.get(key, float("inf")) - 1e-9:
                    best[key] = ncost
                    # 步行段不占用 leg；用特殊 prev 记录
                    prev[key] = ((st, used), TransferPathLeg(
                        line_id="WALK", line_name="步行/跨江换乘",
                        from_station=st, to_station=other,
                        board_index=-1, alight_index=-1, stops_spanned=1,
                    ))
                    heappush(pq, (ncost, other, used))
            for leg in self._board_options(st):
                # 不允许原地绕
                if leg.to_station == st:
                    continue
                # 中间换乘点必须是 hub（最后一腿可以不是）
                nxt_used = used + 1
                if nxt_used < self.max_legs and leg.to_station != dest and leg.to_station not in self.hubs:
                    continue
                ncost = cost + leg.stops_spanned
                if used > 0:
                    ncost += self.transfer_penalty
                key = (leg.to_station, nxt_used)
                if ncost < best.get(key, float("inf")) - 1e-9:
                    best[key] = ncost
                    prev[key] = ((st, used), leg)
                    heappush(pq, (ncost, leg.to_station, nxt_used))

        found.sort(key=lambda x: x[0])
        return [p for _, p in found[:3]]


# 联运交接模型：司机A 在交接站把货交给司机B（同一站点，禁止远距步行）。
# 仅当两条线共享同一站时才可能交接；步行换乘对默认关闭。
DEFAULT_WALK_TRANSFERS: tuple[tuple[str, str], ...] = ()


def suggest_multileg(
    lines: Sequence[LineSpec],
    origin: str,
    dest: str,
    *,
    max_legs: int = 3,
    walk_transfers: Sequence[tuple[str, str]] | None = None,
) -> TransferPath | None:
    # 同站司机交接；walk_transfers 仅用于同站/邻站（≤150m）由业务显式传入
    g = TransferGraph(lines, max_legs=max_legs, walk_transfers=walk_transfers or ())
    paths = g.search(origin, dest)
    return paths[0] if paths else None
