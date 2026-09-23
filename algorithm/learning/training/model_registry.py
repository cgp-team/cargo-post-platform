"""模型 Registry：CANDIDATE → VALIDATED → ACTIVE / FAILED / RETIRED。"""

from __future__ import annotations

import json
import shutil
import time
from dataclasses import dataclass, field
from pathlib import Path

VALID_STATES = {"CANDIDATE", "VALIDATED", "ACTIVE", "FAILED", "RETIRED"}


@dataclass
class ModelRegistry:
    root: str | Path = "algorithm/models"
    models: dict[str, dict] = field(default_factory=dict)

    def __post_init__(self):
        self.root = Path(self.root)
        self.root.mkdir(parents=True, exist_ok=True)
        self._index = self.root / "registry.json"
        if self._index.exists():
            self.models = json.loads(self._index.read_text(encoding="utf-8"))

    def _save(self) -> None:
        self._index.write_text(json.dumps(self.models, indent=2, default=str), encoding="utf-8")

    def register(self, model_id: str, *, model_version: str, metrics: dict, state: str = "CANDIDATE") -> None:
        assert state in VALID_STATES
        self.models[model_id] = {
            "state": state,
            "model_version": model_version,
            "metrics": metrics,
            "created_at": time.time(),
        }
        self._save()

    def promote(self, model_id: str) -> bool:
        """通过安全门才 ACTIVE；失败保留旧 ACTIVE。"""
        m = self.models.get(model_id)
        if not m or m["state"] != "VALIDATED":
            return False
        m["state"] = "ACTIVE"
        for mid, rec in self.models.items():
            if mid != model_id and rec.get("state") == "ACTIVE":
                rec["state"] = "RETIRED"
        self._save()
        return True

    def mark(self, model_id: str, state: str) -> None:
        assert state in VALID_STATES
        if model_id in self.models:
            self.models[model_id]["state"] = state
            self._save()

    def active(self) -> dict | None:
        for mid, rec in self.models.items():
            if rec.get("state") == "ACTIVE":
                return {"id": mid, **rec}
        return None

    def deploy_artifact(self, model_id: str, src_dir: str | Path, dest: str | Path) -> Path | None:
        m = self.models.get(model_id)
        if not m or m.get("state") != "ACTIVE":
            return None
        dest = Path(dest)
        dest.mkdir(parents=True, exist_ok=True)
        for name in ("candidate_ranker.model", "feature_schema.json", "model_metadata.json"):
            src = Path(src_dir) / name
            if src.exists():
                shutil.copy2(src, dest / name)
        return dest
