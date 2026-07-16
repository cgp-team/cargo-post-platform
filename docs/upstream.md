# 上游仓库管理

- `origin`：客货邮运营管理平台的项目远程，用于团队协作与项目交付。
- `upstream`：`https://github.com/YunaiV/ruoyi-vue-pro.git`，只用于读取上游更新，不承载本项目代码。
- 改造前基线：`c356e768bd`，标签 `upstream-baseline-20260716`。
- 版本特征：原始基线为 RuoYi-Vue-Pro `2026.06-jdk8-SNAPSHOT`、Spring Boot 2.7.18；按项目决定已对齐同发布线 `v2026.06(jdk17/21)` 的 Boot 3 兼容实现，并将编译基线设为 JDK 21、Spring Boot 3.5.15。

不得直接在 `main`、`master` 或 `develop` 上合并 upstream。同步上游时先更新远程引用，再创建 `chore/sync-upstream-YYYYMM` 分支；在该分支完成冲突处理、数据库脚本评审、安全检查、后端与前端回归后，才通过项目评审流程合入。上游业务示例模块不得在同步中被意外重新启用。
