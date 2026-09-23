"""主城 + 江津真实公交数据层（OSM discovery → snapshot → RoadGraph snap）。"""

from .region import CORE_DISTRICTS, JIANGJIN_DISTRICTS, TransitRegionConfig
from .osm_transit_discovery import OSMTransitDiscovery, TransitRoute, TransitStation
from .repository import (
    StationRoadMatch,
    StationRoadSnapper,
    TransitSnapshot,
    TransitSnapshotStore,
    TransitStationDeduplicator,
)

__all__ = [
    "CORE_DISTRICTS", "JIANGJIN_DISTRICTS", "TransitRegionConfig",
    "OSMTransitDiscovery", "TransitRoute", "TransitStation",
    "StationRoadMatch", "StationRoadSnapper", "TransitSnapshot",
    "TransitSnapshotStore", "TransitStationDeduplicator",
]
