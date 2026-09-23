from pathlib import Path

# 1) extend .gitignore
gi = Path(r".gitignore")
t = gi.read_text(encoding="utf-8")
extra = """
# Large / generated artifacts (do not push)
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
full_test_results.txt
more_test_results.txt
test_output.txt
test_feasibility*.txt
.w/
.workbuddy/
.mimosa/
.impeccable/
.pytest_cache/
**/.pytest_cache/
"""
if "tools/graphhopper/" not in t:
    t = t.rstrip() + "\n" + extra
    gi.write_text(t, encoding="utf-8")
    print("gitignore updated")
else:
    print("gitignore already")

print("done")
