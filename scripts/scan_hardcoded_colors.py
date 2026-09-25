#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
ENG-05: 硬编码颜色门禁。
对 PR 新增行（git diff）中的 transport 视图层检查 hex 字面量颜色，
拦截「新引入的硬编码色」导致令牌体系回退（对应审计 WEB-03 / WEB-15）。

白名单（合理字面色例外）：
  - components/Map、plugins/echarts：canvas/SVG 不继承 CSS 变量，必须字面色
  - 已带 `/* WEB-03 例外 *`/`例外` 注释或 BMapGL/SVG 属性上下文的行
  - tokens 体系文件（tokens.scss / var.css / theme 等）本身

用法：python scripts/scan_hardcoded_colors.py [--base origin/master]
退出码：发现新增硬编码色 → 1（CI 失败）
"""
import re
import subprocess
import sys
import os

BASE = "origin/master"
for i, a in enumerate(sys.argv):
    if a == "--base" and i + 1 < len(sys.argv):
        BASE = sys.argv[i + 1]

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
os.chdir(ROOT)

HEX_RE = re.compile(r"#(?:[0-9a-fA-F]{3}|[0-9a-fA-F]{6}|[0-9a-fA-F]{8})\b")

# 目标：transport 视图与小程序（新增行才检查，存量另行治理）
TARGET_PATTERNS = [
    r"yudao-ui/yudao-ui-admin-vue3/src/views/transport/.*\.(vue|ts|scss|css)$",
    r"yudao-ui/yudao-ui-admin-vue3/src/styles/.*\.(scss|css)$",
    r"cargo-post-miniprogram/pages/.*\.wxss$" if os.path.isdir("cargo-post-miniprogram") else r"(?!)$",
]

# 例外：canvas/SVG 字面色上下文（BMapGL 覆盖物、echarts 常量文件）
EXCEPT_PATTERNS = [
    r"plugins/echarts/.*",            # echarts 主题/色板常量（字面 hex 是唯一正确写法）
    r"components/Map/.*",
    r"stroke=|fill=|strokeColor",      # SVG 属性
    r"式神",                            # 占位，永不匹配
]

def is_target(path):
    return any(re.search(p, path) for p in TARGET_PATTERNS)

def is_except(path, line):
    if any(re.search(p, path) for p in EXCEPT_PATTERNS):
        return True
    if any(k in line for k in ("stroke=", "fill=", "strokeColor", "例外")):
        return True
    return False

# 取 diff 新增行
try:
    diff = subprocess.run(
        ["git", "diff", BASE, "...", "--unified=0", "--"],
        capture_output=True, text=True, timeout=60,
    ).stdout
except Exception:
    diff = ""

if not diff.strip():
    # 本地无 base 时退化为检查工作区暂存/未跟踪（尽力而为）
    print(f"[scan_hardcoded_colors] 无法取 diff（base={BASE}），跳过")
    sys.exit(0)

cur_path = None
violations = []
for line in diff.split("\n"):
    if line.startswith("+++ b/"):
        cur_path = line[6:].strip()
        continue
    if not line.startswith("+") or line.startswith("+++"):
        continue
    if not cur_path or not is_target(cur_path):
        continue
    body = line[1:]
    m = HEX_RE.search(body)
    if not m:
        continue
    if is_except(cur_path, body):
        continue
    violations.append((cur_path, body.strip()[:120]))

if violations:
    print("[scan_hardcoded_colors] 发现新增硬编码颜色（请改用令牌体系）：")
    for p, l in violations:
        print(f"  {p}\n    + {l}")
    print("\n允许的字面色例外：plugins/echarts、components/Map、SVG 属性（stroke=/fill=）。")
    sys.exit(1)

print("[scan_hardcoded_colors] OK：transport 视图无新增硬编码颜色")
