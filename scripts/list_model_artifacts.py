from pathlib import Path

root = Path(".")
for rel in [
    "algorithm/data/model_registry",
    "algorithm/models",
    "algorithm/learning/data",
    "algorithm/data",
]:
    p = root / rel
    print("====", rel, p.exists())
    if not p.exists():
        continue
    total = 0
    for f in sorted(p.rglob("*")):
        if f.is_file():
            sz = f.stat().st_size
            total += sz
            print(f"  {f} {sz/1024:.1f} KB")
    print(f"  TOTAL {total/1024/1024:.2f} MB")
