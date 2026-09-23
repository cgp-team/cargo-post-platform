"""TransitRegionConfig：主城 + 江津；区外 EXCLUDED。"""

from __future__ import annotations

from dataclasses import dataclass, field


CORE_DISTRICTS = (
    "渝中区", "江北区", "沙坪坝区", "九龙坡区", "南岸区",
    "大渡口区", "渝北区", "巴南区", "北碚区",
)
JIANGJIN_DISTRICTS = ("江津区",)


@dataclass
class TransitRegionConfig:
    core_id: str = "chongqing_core"
    jiangjin_id: str = "jiangjin"
    districts: dict[str, tuple[str, ...]] = field(default_factory=lambda: {
        "chongqing_core": CORE_DISTRICTS,
        "jiangjin": JIANGJIN_DISTRICTS,
    })
    max_route_hops: int = 2
    refresh_transit_data: bool = False  # 训练期间冻结 snapshot

    def is_in_scope(self, region_id: str | None) -> bool:
        return region_id in (self.core_id, self.jiangjin_id)
