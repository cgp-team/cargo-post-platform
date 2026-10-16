# AI 智能辅助模块 · 边界与接口契约（队列 #93）

这份文件是给**并行进行的另一条线**（AI 辅助模块）看的。里面的每条"现有能力"都是 2026-10-16 在
`ci/admin-gates-2026-10-16` 分支上实测的读数，不是设想；引用到的行号是**当时的读数**，动手前先 `awk` 复核。

---

## 0. 总则（这些不是口号，是要能被判据证伪的结构约束）

| 约束 | 落成什么才算数 |
|---|---|
| AI 是增强，不是主干 | 关掉 AI（`buspost.ai.enabled=false` 或菜单不授权）后，客货邮后台所有既有页面必须**零变化**可用；业务 Service 一律不得被 AI 包反向调用 |
| 只输出参考/摘要/草稿 | AI 出口只准返回"文本 + 草稿对象"，**不得**注入任何写口；草稿要落库必须走管理员已存在的正式接口 |
| 人工终审 | 每条建议旁边必须有"采纳并执行"的入口，且该入口调的是既有业务接口（带确认框与幂等键），不新建第二条写路径 |
| 调用留日志 | 见 §5 的 `ai_invocation_log` 表；日志写入失败不得影响主流程（旁路原则），但**必须可见**（数出来、能查） |
| 数据脱敏 | 见 §4：唯一脱敏出口 + AI 只吃聚合摘要 DTO，两道判据钉住 |

---

## 1. 归属与不许碰的东西

- 后端新增包：`yudao-module-ai-assist`（独立 module，不改 `yudao-module-transport` 的 Service）。
- 前端新增目录：`yudao-ui-admin-vue3/src/views/transport/ai/**` 与 `src/api/transport/ai/**`。
- **不许改**（这条线负责）：`service/dispatch/**`、`service/transport/**`、`controller/admin/transport/**`、
  `sql/**` 里既有的 V0xx 文件、`scripts/check_admin_*`、`.github/workflows/ci.yml` 的既有步骤。
- 需要既有接口配合改字段时：写进"依赖申请"，由这条线实现；不要顺手改（本仓已经因为"顺手改不相干文件"
  吃过 `eslint --fix` 搅乱六页排版的亏，见台账 D-134）。

## 2. 编号段分配（防撞，历史事故是真的）

- 迁移文件：增量目录现读到 **`V036` 为止**（`sql/incremental`，35 个文件）。AI 模块从 **`V050__` 起**用，
  V037–V049 留给主线，避免重演 D-133 那次 V035/V036 与菜单主键 6930/6931 抢占。
- 菜单主键：现读 6xxx 系列已密集使用，`7000/7001/7002` = 会员中心（V022）。AI 模块用 **`7100–7199`**。
  撞号不用人盯：`scripts/check_menu_id_collisions.py` 已经在拦（VALUES 与 SELECT 两种写法都认，
  K1–K7 七把刀，真树现读 64 条声明 / 61 个 id）。
- 云端执行永远走 `db-migrate-guarded.yml`：`mode=check` 只读；`mode=apply` 需要显式 `confirm=APPLY`，
  且脚本必须幂等（`WHERE NOT EXISTS` / `INSERT IGNORE`）。**任何自动写共享云库都不允许**（D-123/#69 那条线）。

## 3. 直接复用的现件（不要再引入新依赖）

| 需要 | 已有 | 实测状态 |
|---|---|---|
| 限流 | `@RateLimiter`（`yudao-spring-boot-starter-protection`） | 自动装配已注册；transport 模块已依赖该 starter ⇒ 加注解即生效 |
| 结果缓存 | Redis，`application-local.yaml` 与 `application-dev.yaml` 都配了 | dev 走 `REDIS_HOST` 环境变量，云上同栈 |
| 幂等 | `@Idempotent` + `UserIdempotentKeyResolver`（key = 方法+参数+userId+userType，异常时删键允许重试，**不需要前端配合**） | 后台写口 45 个 / 已带键 8 个，由 `scripts/check_admin_write_idempotency.py` 钉 |
| 留痕（业务侧） | 订单事件流 `transport_order_event`，`extra_data` 是 `text`、注释原文"扩展数据JSON" | #60 已用 `JSONUtil.toJsonStr(Map.of("photoUrl", …))` 往这格写过，同族做法有先例 |
| 文件 | `infra_file` + `/app-api/infra/file/upload` | 云上主配置已切"本地存储"，实测 `code=0` 能落盘 |

## 4. 数据出口与脱敏（这是必须先做的部分，不是 AI 的一部分）

**现状读数**：`DriverBaseVO` / `DriverRespVO` / `DriverSimpleRespVO` / `FeedbackRespVO` 里都有
`private String mobile` —— 后台列表接口今天就在回**明文手机号**。这有业务理由（调度要打电话），
所以不改它；要改的是"AI 拿到的那一份"。

要求：

1. 全工程唯一脱敏出口 `AiDataMask.maskMobile(String)`（Hutool `DesensitizedUtil.mobilePhone` 一类实现，
   一处定义、别处不许自写正则掩码）。
2. AI 的输入只能是**聚合摘要 DTO**（新建 `*SummaryVO`），其字段集合里不得出现 `mobile`、`idCard`、
   完整地址、原始流水列表。判据形状建议：在 `yudao-module-ai-assist` 与 `views/transport/ai/**` 里
   grep 到 `getMobile(`、`mobile:`、`SELECT.*mobile` 即红。
3. 判据必须能红：把某个 SummaryVO 加一个 `mobile` 字段 ⇒ 那本闸必须报违规（同一条规则配一条会红的负控，
   否则它就是一台永远绿的空转判据）。

## 5. `ai_invocation_log`（唯一需要新增的表）

```
id            bigint PK
scene         varchar(64)    -- nl-query / ticket-review / capacity-brief / risk-card / digest
operator_id   bigint         -- 登录管理员，不是 AI
masked_input  text           -- 送出去的内容，落库前已过 §4 的出口（不许存原文）
output        text           -- 模型返回的文本
tokens / latency_ms  int     -- 成本与体验，便于限流调参
status        varchar(16)    -- OK / RATE_LIMITED / ERROR / EMPTY
error_brief   varchar(255)   -- 失败要点，不许只写"失败"
create_time   datetime
```
落库失败不得阻断页面，但**要能在页面上看见"日志没落"**（这是本仓对所有写口的同一口径：
成功语必须有后端依据，见队列 #30 那一族）。

## 6. 第一批：**不接 AI 也能做完**的四项（这条线可以直接开工）

| # | 内容 | 落在哪 | 为什么不需要模型 |
|---|---|---|---|
| 1 | 管理员待处理告警队列（底座） | `NotificationPageReqVO` 加 `recipientType`、`/admin-unread-count`、通知页三态与"标记已处理" | 告警一直写得进表（`sendToAdmin` 现读 6 处调用），缺的只是查询口径；正在做（#92） |
| 2 | 「赶集 / 农忙 / 节假日」事件日历 | 新表 `town_event_calendar(town, event_type, start, end, note)` + 维护页 | 创新1 的真卡点是**没有这张表**，不是不会推演；有日历 + 既有班次统计，规则就能出"提前留舱"提示 |
| 3 | 客货空间冲突检视 | 排班保存前的确定性校验：核定载客/载货 vs 预报量，输出冲突点与缓冲建议 | 是约束校验，不是概率猜测；措辞保守反而更可信 |
| 4 | 查询构造器 + 运营简报模板导出 | 结构化筛选（存条件）+ 定时聚合 + 模板文档 | 现成分页/聚合接口够用；"自然语言"那一层将来只做"话→参数"，**永远不做"话→SQL"** |

## 7. 需要模型的部分，以及需要你提供的东西

真正需要模型的只有：工单语义分类与照片是否支撑描述（视觉）、口语上报的同义归并、
反馈的主题聚类、以及把上面四项的输出**润色成文字**。前提条件三件，缺一不可：

1. 一个可用的推理端点（内网自建或外部服务）+ 出网策略，配在云上那台机器 —— **服务器我管不到**，
   密钥一律不进仓库、不进日志、不进台账；
2. `application-*.yaml` 里加 `buspost.ai.base-url / api-key(走环境变量) / model / timeout`；
3. 一句可验收的口径：拿不到端点时，页面显示"能力未就绪"，**不许用模板文案冒充 AI 输出**
   （本仓红线：不许演示形态、不许假能力）。

## 8. 明确不做 / 待前置

- **司机情绪/危险定性**（基础5 的一半）：连续出车时长、上报疲惫次数是统计，做得；
  "消极情绪识别"不做 —— 既需要模型又踩劳动监控合规，且你写的"不做危险司机定性"已经定了调。
  这条只出"关怀消息草稿 + 调度留意标签"，永不出处罚动作。
- **情景推演沙盘**（创新5）：本质是算法侧的对比运行（度量台已有 A/B 尺子）。⚠ 但你明令
  `algorithm/**` 不提交 ⇒ 这条路今天**进不了云端**。要做先解禁算法目录，或把沙盘限定为
  "读既有方案 + 只展示不落地"的报表层。
- **服务缺口洞察**（创新4）：先量数据再决定，几条反馈上做聚类是自欺。用这条 SQL 量（走 guarded `check` 模式）：
  `SELECT COUNT(*), AVG(LENGTH(content)), COUNT(DISTINCT user_id) FROM transport_feedback;`
- **实时抓新闻/外网爬活动预告**：不做（合规与稳定性都不划算），日历表由人工维护。

## 9. 验收件（这个模块的完成凭证长什么样）

1. `vue-tsc --noEmit` rc=0 / 0 错，且**改过 .vue 先跑 SFC 结构扫**（D-134 那条：构建只报第一个错，
   结构扫能一次列全）。
2. 每条新判据配一条**会红**的负控；静态闸一律"一条 fixture 一个目录"（同目录放多条会互相顶包）。
3. 静态闸在 GBK 控制台也必须能跑：文件头要有输出流 UTF-8 段（今天 12 本全崩在打印中文上，
   崩溃的 rc=1 会被误读成"判红了"）。
4. "关掉 AI 系统仍完整可用"要有一条检查：禁用开关后，既有 transport 页面集合与判据读数**不变**。
5. 界面改动必须真机或浏览器点验；做不到的登记 `NOT VERIFIED IN BROWSER`，不许用"看起来对"交差。

## 10. 两件等人拍板的事（与本模块相关，但不由我定）

- PR #195 合进 `master` = 触发云端部署 + guarded 迁移，目前 6 个提交只在 PR 分支。
- 后台"手动调整余额"要不要开：它的两条技术拦阻（无幂等键、0 元谎报"调整成功"）已补齐，
  钱包页文案已改成对今天后端的事实；开不开是运营决定（要开得先定：操作人留痕与是否二次审批）。

---

## 11. 给并行 agent 的开工须知（今天在这个仓里真咬过人的，不是通用建议）

**先核前提再动手**
1. 没有推理端点之前，不要写任何会显示"AI 生成中"的界面 —— 那正是本仓红线里的"演示形态"。
   先做 §6 那四项（不接 AI 就能交付），AI 侧只做外壳与开关。
2. 分支基线用 `ci/admin-gates-2026-10-16`（现 `8a7aec94`），否则你看不见本文件与 `recipientType` 口径；
   PR 另开一个，描述里写明"依赖 #195 先合"。`ci.yml` 与 `scripts/` 两边都会改 —— 那是最容易撞的两个文件。
3. 别动 §1 列的那批文件；要既有接口配合改动，写"依赖申请"给我，别顺手改（`eslint --fix` 扫目录搅乱过六页排版，见 D-134）。

**Windows / Git-Bash（这条线上一定会撞）**
4. 工作树是 **CRLF**（`core.autocrlf=true`）：批量插行必须按目标文件自己的换行符插，否则整文件变差异。
   台账 `DECISION_LOG.md` 是 LF，别混。
5. 控制台是 **GBK**：任何新写的 python 判据文件头必须加 `sys.stdout.reconfigure(encoding="utf-8")`，
   不然打印中文时抛异常，退出码 1 会被误读成"判据判红了"（今天 12 本全中这一枪）。
6. 中文/引号/反引号/反斜杠不要塞进 shell 字面量 —— 一律 Write 成 .py 再跑；`/tmp` Windows 版 python 看不见（用 `cygpath -w`）。
7. 多行注解/多行签名是解析陷阱：按"注解块归属"或"从 @XxxMapping 往下找签名"，别按行号邻近配对
   （同一个 bug 今天犯了三次：多行 `@Idempotent` 读成 0、邻近行号造成假阳性、多行方法签名把动词顶给下一个方法）。

**什么才算凭证**
8. 本机**没有 Maven 也没有 wrapper**：Java 侧只能靠 CI 的 backend job 出数，不要说"我改完能编译"；
   读数以 CI 为准，并且要**数用例名与类级条数**（367→369→372 那种），只看 rc 不算。
9. 前端：改过 .vue 先跑 SFC 结构扫（`@vue/compiler-sfc` 是传递依赖，从工程根 `node <script>` 直接 import 不到），
   再跑 `vue-tsc --noEmit` —— 它只报每文件第一个错。`vitest`/`eslint` 收尾一起跑。
10. 每条新判据配一条**会红**的负控；"数到 0"一律非零退出（fail-closed）；fixture 一条一个目录；
    锚点/钉值改动要同轮改表并写原因（口径修正 ≠ 进展）。
11. 界面改动必须真机或浏览器点验；做不到就在台账里登记 `NOT VERIFIED IN BROWSER`，别用"看起来对"交差。

**环境与时序**
12. 云端执行永远走 `db-migrate-guarded.yml`：`mode=check` 只读，`mode=apply` 需要人给 `confirm=APPLY`，脚本必须幂等。
    **服务器由用户管，谁都不要直接连库**。密钥不进仓库、不进日志、不进文档。
13. App 仓 `cargo-post-android` 里 51 本负控正在串行跑（约 15:00–17:30），期间**不要改那棵树**；
    要跑负控必须走 `tools/negctl_lock.sh` 的锁，两批并行踩过（角色书被记成 rc=2 假红，见 D-039）。
14. `app/build/test-results/` 会被任何带 `--tests` 过滤的 gradle 调用覆盖 —— 依赖产物读数的检查要自己确保产物新鲜，
    不要拿快照还原冒充证据（已立案 #96）。

## 12. 只由用户拍板、不要代他决定的两件事

- PR #195 合进 `master` 会触发云端部署与迁移执行；合并在浏览器侧由他点。
- 后台"手动调整余额"开不开口：两条技术拦阻已补齐，剩下是运营与审批口径问题。
