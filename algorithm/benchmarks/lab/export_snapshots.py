"""把「算法请求留痕表」导成度量台可读的 JSONL —— 只读库，不写任何东西。

数据出处（不是设想，是已核实的现网通路）：
- 表 `transport_algorithm_request`（`sql/mysql/transport-schema.sql:356-374`），
  由 `AlgorithmAdapter.plan()` 每次真实派单落一行；
- `request_json` 就是发给算法服务的 PlanRequest 原文
  （序列化用 NON_NULL：`AlgorithmAdapterConfiguration.java:34`，所以"没配的字段不出现"是常态，
   读入侧必须容忍缺字段而不是要求全字段）；
- `status` 是后端校验通过的终态（0 处理中 1 可行 2 无解 3 失败），`response_json` 是当次方案。
  ⇒ 留痕表天然带"线上真实规模/真实字段覆盖"，这就是自优化的题源（任务 #33）。

PII 口径（逐 DTO 核过 `AlgorithmStationDTO/OrderDTO/ShipmentDTO/VehicleDTO`）：
请求里只有站点 ID + 经纬度、车辆 ID + 容量 + 骨架站序、订单 ID + 件数/重量/体积，
**没有**姓名、手机号、地址、金额等个人字段。仍然做两件事再落文件：
1. 订单号/请求号不带出仓库 —— 只保留 `snapshot_hash` 前 12 位作为样本主键；
2. 站点经纬度保留（这是算法输入的本体，去掉就没法重算 OD），但它只到"村点"粒度，
   不含任何收件人位置。

用法（凭据走环境变量，不在仓库里留口令）：
  set LAB_MYSQL_HOST=127.0.0.1
  set LAB_MYSQL_USER=root
  set LAB_MYSQL_PASSWORD=...
  set LAB_MYSQL_DB=ruoyi-vue-pro
  cd algorithm && PYTHONPATH=. python -m benchmarks.lab.export_snapshots --limit 200

导出物默认落在 `benchmarks/lab/online/snapshots.jsonl`（该目录已 gitignore，
真实运营数据不入库；测试用的极小样本以 fixture 形式提交，见 tests/fixtures/）。
"""
from __future__ import annotations

import argparse
import json
import os
import sys

import pymysql

HERE = os.path.dirname(os.path.abspath(__file__))
OUT_DIR = os.path.join(HERE, "online")
DEFAULT_OUT = os.path.join(OUT_DIR, "snapshots.jsonl")

# 留痕表里我们要的列（顺序即导出顺序）
_SQL = """
SELECT snapshot_hash,
       create_time,
       status,
       algorithm_version,
       parameter_version,
       request_json
  FROM transport_algorithm_request
 WHERE deleted = 0
   AND status IN (1, 2)          -- 处理中(0)/失败(3)的请求没有可复核的终态，导出来只是噪声
 ORDER BY create_time DESC
 LIMIT %s
"""


def _conn():
    host = os.environ.get("LAB_MYSQL_HOST", "127.0.0.1")
    user = os.environ.get("LAB_MYSQL_USER")
    password = os.environ.get("LAB_MYSQL_PASSWORD")
    db = os.environ.get("LAB_MYSQL_DB")
    if not (user and password and db):
        raise SystemExit(
            "缺少凭据：请设置 LAB_MYSQL_USER / LAB_MYSQL_PASSWORD / LAB_MYSQL_DB"
            "（故意不在仓库里留口令；本机开发库的值见 yudao-server 的 application-local.yaml）"
        )
    return pymysql.connect(
        host=host, port=int(os.environ.get("LAB_MYSQL_PORT", "3306")),
        user=user, password=password, database=db, charset="utf8mb4",
        cursorclass=pymysql.cursors.DictCursor,
    )


def export(out_path: str, limit: int) -> dict:
    conn = _conn()
    try:
        with conn.cursor() as cur:
            cur.execute(_SQL, (limit,))
            rows = cur.fetchall()
    finally:
        conn.close()

    os.makedirs(os.path.dirname(out_path), exist_ok=True)
    written = skipped = 0
    seen: set[str] = set()
    with open(out_path, "w", encoding="utf-8") as fh:
        for r in rows:
            h = (r["snapshot_hash"] or "")[:12]
            if not h or h in seen:
                skipped += 1
                continue
            try:
                req = json.loads(r["request_json"])
            except (TypeError, ValueError):
                skipped += 1
                continue
            seen.add(h)
            fh.write(json.dumps({
                "snapshot_hash": h,
                "created_at": r["create_time"].isoformat(sep=" ", timespec="seconds"),
                "db_status": int(r["status"]),
                "algorithm_version": r["algorithm_version"],
                "parameter_version": r["parameter_version"],
                "request": req,
            }, ensure_ascii=False, sort_keys=True) + "\n")
            written += 1
    return {"fetched": len(rows), "written": written, "skipped": skipped, "out": out_path}


def main(argv=None) -> int:
    ap = argparse.ArgumentParser(description="导出算法请求留痕快照（只读）")
    ap.add_argument("--out", default=DEFAULT_OUT)
    ap.add_argument("--limit", type=int, default=200)
    args = ap.parse_args(argv)
    info = export(args.out, args.limit)
    print(json.dumps(info, ensure_ascii=False))
    return 0


if __name__ == "__main__":
    sys.exit(main())
