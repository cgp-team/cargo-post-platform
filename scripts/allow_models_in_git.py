from pathlib import Path

gi = Path(".gitignore")
t = gi.read_text(encoding="utf-8")
old = """# Large / generated artifacts (do not push)
*.zip
tools/graphhopper/
tools/osm-data/
algorithm/data/
algorithm/models/
algorithm/artifacts/
algorithm/_learning_zip/
algorithm/_t_q*/
algorithm/algorithm/
*.osm.pbf
*.jar
graph-cache/
**/route_search_ranker.model
**/route_search_ranker.model.txt
"""
new = """# Large / generated artifacts (do not push)
*.zip
tools/graphhopper/
tools/osm-data/
algorithm/artifacts/
algorithm/_learning_zip/
algorithm/_t_q*/
algorithm/algorithm/
*.osm.pbf
*.jar
graph-cache/

# algorithm/data：默认忽略大缓存，但模型与实验结果必须进仓库
algorithm/data/*
!algorithm/data/model_registry/
!algorithm/data/*.json
!algorithm/data/*_result.json
!algorithm/data/v0*.json
!algorithm/data/v0*.jsonl

# 训练模型必须随仓库分发（云端/CI 使用）
!algorithm/models/
algorithm/models/*
!algorithm/models/candidate_ranker.model
!algorithm/models/feature_schema.json
!algorithm/models/model_metadata.json
!algorithm/models/registry.json

# 大训练轨迹/站点快照仍不进仓
algorithm/learning/data/*.jsonl
algorithm/data/transit/
algorithm/data/v041_metric_audit_rows.jsonl
"""
if old not in t:
    raise SystemExit("gitignore block not found")
gi.write_text(t.replace(old, new), encoding="utf-8")
print("gitignore updated")
