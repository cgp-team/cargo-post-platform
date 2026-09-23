# THIRD_PARTY_NOTICES

本项目为非商业课程/科研/实验项目。以下开源组件按其许可证复用/借鉴。

## GraphHopper

- 来源：https://github.com/graphhopper/graphhopper
- 许可证：Apache License 2.0（官方开源仓库）
- 用途：CH / LM / Flexible 真实道路路由；生产 Routing Teacher 首选
- 版本：8.0 jar（尝试下载未完整）；HTTP adapter 对接本地服务
- 本仓库修改：`algorithm/app/routing/graphhopper_routing.py`（调用适配，未内嵌 GH 源码）
- 版权声明：保留 GraphHopper 原作者版权声明，不删除

## LightGBM

- 来源：https://github.com/microsoft/LightGBM
- 许可证：MIT
- 用途：LambdaRank（CandidateRanker / RouteSearchRanker）
- 版本：4.7.0（pip）
- 本仓库修改：无源码复制，仅 API 调用

## scikit-learn / numpy

- 许可证：BSD-3-Clause
- 用途：评估与数值计算

## OR-Tools（既有 baseline）

- 来源：Google OR-Tools
- 许可证：Apache-2.0
- 用途：既有 ortools_solver baseline（仓库原有，未替换）

## L2R（仅借鉴思想，不复制代码）

- 说明：L2R 官方仓库明确仅限非商业用途且限制二次分发
- 处理：**只借鉴** learning-based search space reduction / candidate prioritization 思想
- **不复制**任何 L2R 实现代码

## jsprit / LNS/ALNS 成熟实现

- 说明：本项目 HACO-CPS + ALNS 为自有算法（第一阶段已正确性修复）
- 参考思想：destroy/repair、SA 接受准则（教科书标准算法）
- **不复制**特定仓库源码

## OSM / OpenStreetMap 数据

- 来源：`chongqing-260921.osm.pbf`
- 许可证：Open Database License (ODbL)
- 用途：真实道路图 / 公交站点发现
- 要求：署名 © OpenStreetMap contributors

## osmium / OSM PBF 解析

- 说明：本仓库 `osm_import.py` / `osm_transit_discovery.py` 为自研最小 protobuf wire 解析
- 未复制 osmium 源码；格式规范来自 OSM PBF 公开文档

---

原则：许可证不明确或不符合本非商业项目的代码，不直接复制；可借鉴算法思想。
