"""scripts/check_training_health.py 逻辑：只读 status + 新增日志，不重训。"""

from __future__ import annotations

import json
import time
from pathlib import Path


def check_training_health(base_dir: str | Path = ".") -> dict:
    base = Path(base_dir)
    status_path = base / "algorithm/learning/training/training_status.json"
    stamp_path = base / "logs/learning/last_check_timestamp.txt"
    log_dir = base / "logs/learning"
    out = {
        "checked_at": time.time(),
        "status_file_exists": status_path.exists(),
        "status": None,
        "stage": None,
        "checkpoint_age_s": None,
        "last_error": None,
        "new_log_lines": 0,
        "process_hint": "unknown",
        "healthy": False,
    }
    if not status_path.exists():
        out["last_error"] = "missing_status"
        return out
    status = json.loads(status_path.read_text(encoding="utf-8"))
    out["status"] = status.get("status")
    out["stage"] = status.get("stage")
    out["last_error"] = status.get("last_error")
    ckpt = base / "algorithm/learning/data/checkpoint.json"
    if ckpt.exists():
        out["checkpoint_age_s"] = time.time() - ckpt.stat().st_mtime

    new_lines = 0
    if log_dir.exists():
        for p in log_dir.glob("*.log"):
            text = p.read_text(encoding="utf-8", errors="replace").splitlines()
            new_lines += sum(1 for line in text if line.strip())
    out["new_log_lines"] = new_lines
    stamp_path.parent.mkdir(parents=True, exist_ok=True)
    stamp_path.write_text(str(time.time()), encoding="utf-8")

    bad = out["last_error"] or out["status"] in ("FAILED",)
    out["healthy"] = not bad and out["status"] not in (None, "INIT")
    out["process_hint"] = "ok" if out["healthy"] else ("failed" if bad else "idle")
    return out


if __name__ == "__main__":
    import sys

    root = sys.argv[1] if len(sys.argv) > 1 else "."
    print(json.dumps(check_training_health(root), indent=2, default=str))
