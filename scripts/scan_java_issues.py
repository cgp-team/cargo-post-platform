from pathlib import Path
import re

root = Path("yudao-module-transport/src/main/java")
for p in root.rglob("*.java"):
    if "target" in str(p):
        continue
    t = p.read_text(encoding="utf-8", errors="replace")
    lines = t.splitlines()
    for i, l in enumerate(lines, 1):
        if "new ServiceException(" in l:
            print(f"SE {p.name}:{i}: {l.strip()[:140]}")
        if "->" in l and ("reasons" in l or "window" in l or "distanceEstimate" in l or "planHolder" in l):
            print(f"LAM {p.name}:{i}: {l.strip()[:140]}")
        if "vehicleWindows" in l:
            print(f"VW {p.name}:{i}: {l.strip()[:140]}")
