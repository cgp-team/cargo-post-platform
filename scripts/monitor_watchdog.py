"""只读监控 watchdog：训练状态 + 算法服务 + GraphHopper + 新增日志。

纪律：正常时只读、不改代码、不重训；异常仅告警。
"""

from __future__ import annotations

import json
import os
import time
import urllib.error
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
STATUS = ROOT / "algorithm/learning/training/training_status.json"
LOG_DIR = ROOT / "logs/learning"
STAMP = LOG_DIR / "last_check_timestamp.txt"
OUT = ROOT / "logs/learning/watchdog_report.json"


def _probe(url: str, timeout: float = 2.0) -> dict:
    try:
        with urllib.request.urlopen(url, timeout=timeout) as r:
            return {"ok": True, "status": getattr(r, "status", 200)}
    except urllib.error.HTTPError as e:
        # 405/404 仍说明端口有服务
        return {"ok": e.code < 500, "status": e.code}
    except Exception as e:  # noqa: BLE001
        return {"ok": False, "error": type(e).__name__}


def check_once() -> dict:
    report: dict = {
        "ts": time.strftime("%Y-%m-%dT%H:%M:%S"),
        "training": {},
        "services": {},
        "new_log_lines": 0,
        "healthy": True,
        "alerts": [],
    }

    # 训练只读
    if STATUS.exists():
        try:
            st = json.loads(STATUS.read_text(encoding="utf-8"))
            report["training"] = {
                "status": st.get("status"),
                "stage": st.get("stage"),
                "last_error": st.get("last_error"),
            }
            if st.get("last_error") or st.get("status") in ("FAILED", "ERROR"):
                report["healthy"] = False
                report["alerts"].append("TRAINING_ERROR")
        except Exception as ex:  # noqa: BLE001
            report["healthy"] = False
            report["alerts"].append(f"STATUS_PARSE:{type(ex).__name__}")
    else:
        report["training"] = {"status": "NO_STATUS_FILE"}
        # 无长跑任务属正常

    # 服务探测（云算法优先；未配置 ALGORITHM_BASE_URL 时本地 8000 仅告警一次说明）
    algo = os.environ.get("ALGORITHM_BASE_URL", "http://127.0.0.1:8000").rstrip("/")
    gh = os.environ.get("GRAPHHOPPER_URL", "http://127.0.0.1:8080").rstrip("/")
    report["services"]["algorithm"] = {"url": algo, **_probe(f"{algo}/openapi.json")}
    report["services"]["graphhopper"] = {"url": gh, **_probe(f"{gh}/health")}
    if not os.environ.get("ALGORITHM_BASE_URL"):
        report["services"]["algorithm"]["note"] = "ALGORITHM_BASE_URL 未设置；算法在云服务器时请配置后再判定"
        # 未配置云地址时不把本地 8000 打成致命故障
        if not report["services"]["algorithm"].get("ok"):
            report["alerts"].append("SVC_NOTE:algorithm_local_unconfigured")
        else:
            pass
    elif not report["services"]["algorithm"].get("ok"):
        report["healthy"] = False
        report["alerts"].append("SVC_DOWN:algorithm")
    if not report["services"]["graphhopper"].get("ok"):
        report["healthy"] = False
        report["alerts"].append("SVC_DOWN:graphhopper")

    # 新增日志行数
    if LOG_DIR.exists():
        last = 0
        if STAMP.exists():
            try:
                last = int(STAMP.read_text(encoding="utf-8").strip() or 0)
            except Exception:  # noqa: BLE001
                last = 0
        total = 0
        for p in LOG_DIR.glob("*.log"):
            try:
                total += sum(1 for _ in p.open(encoding="utf-8", errors="replace"))
            except Exception:  # noqa: BLE001
                continue
        report["new_log_lines"] = max(0, total - last)
        STAMP.parent.mkdir(parents=True, exist_ok=True)
        STAMP.write_text(str(total), encoding="utf-8")

    OUT.parent.mkdir(parents=True, exist_ok=True)
    OUT.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
    return report


def main(interval_s: float = 0, rounds: int = 1) -> None:
    if interval_s <= 0:
        print(json.dumps(check_once(), ensure_ascii=False, indent=2))
        return
    for i in range(rounds if rounds > 0 else 10**9):
        r = check_once()
        flag = "OK " if r["healthy"] else "ALERT"
        print(f"[{r['ts']}] {flag} alerts={r['alerts']} algo={r['services']['algorithm'].get('ok')} gh={r['services']['graphhopper'].get('ok')}")
        if rounds > 0 and i + 1 >= rounds:
            break
        time.sleep(interval_s)


if __name__ == "__main__":
    import argparse

    ap = argparse.ArgumentParser(description="read-only watchdog")
    ap.add_argument("--interval", type=float, default=0)
    ap.add_argument("--rounds", type=int, default=1)
    args = ap.parse_args()
    main(interval_s=args.interval, rounds=args.rounds)
