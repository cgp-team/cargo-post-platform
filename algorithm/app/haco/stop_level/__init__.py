"""HACO-CPS Stop-Level Representation 实验模块。

核心创新：从 task-level 原子升级为 stop-event 原子。

Task-level: P0 = BOARD@S1 + ALIGHT@S3（不可拆分）
Stop-level: BOARD@S1(P0), ALIGHT@S3(P0)（可独立排列）

这允许乘客 ride-through 中间站：
S1 BOARD P0 → S2 BOARD P1 → S3 ALIGHT P0 → S4 ALIGHT P1
"""
