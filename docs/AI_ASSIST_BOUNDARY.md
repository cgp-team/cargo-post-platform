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
| 幂等 | `@Idempotent` + `UserIdempotentKeyResolver`（key = 方法+参数+userId+userType，异常时删键允许重试，**不需要前端配合**） | 后台写口 46 个 / 已带键 8 个（#92 新增的 `mark-admin-read` 按口径不钉键），由 `scripts/check_admin_write_idempotency.py` 钉 |
| 留痕（业务侧） | 订单事件流 `transport_order_event`，`extra_data` 是 `text`、注释原文"扩展数据JSON" | #60 已用 `JSONUtil.toJsonStr(Map.of("photoUrl", …))` 往这格写过，同族做法有先例 |
| 文件 | `infra_file` + `/app-api/infra/file/upload` | 云上主配置已切"本地存储"，实测 `code=0` 能落盘 |

## 4. 数据出口与脱敏（这是必须先做的部分，不是 AI 的一部分）

**现状读数**：`DriverBaseVO` / `DriverRespVO` / `DriverSimpleRespVO` / `FeedbackRespVO` 里都有
`private String mobile` —— 后台列表接口今天就在回**明文手机号**。这有业务理由（调度要打电话），
所以不改它；要改的是"AI 拿到的那一份"。

已经落地的（主线今天做完，直接用，别再各写一份）：

1. **唯一出口 = `cn.iocoder.yudao.module.transport.util.SensitiveDataMask`**：
   `mobile(String)` → `138****8001`，`tail4(String)` → `****7X23`。
   ⚠ 语义要点：**空值原样返回**（null 进 null 出）——把"后端没给手机号"掩成 `"***"`，
   读的人就分不清"没给"和"给了被屏蔽"，这与"金额缺值不许念成 0.00"是同一条纪律。
   不是手机号形状的脏数据（座机、带区号）只留首字符，不猜它的含义。
2. **判据 = `scripts/check_admin_ai_data_mask.py`**（已挂 ci.yml 第 12 个静态检查步骤）。它钉四件事：
   出口在（找不到 ⇒ rc=2 拒判）、transport 模块里没有第二处掩码实现、空值守卫恰好 2 处、
   **AI 面（`yudao-module-ai-assist/**`、`views/transport/ai/**`、`api/transport/ai/**`）里出现
   `getMobile(` / `.mobile` / `mobile:` 即红**。你现在还没有那些目录，它会打"跳过（目录尚未落地）"——
   那句跳过不等于这本闸空转，A1–A3 一直活着（自检 K6 专门钉这条）。
3. 用例 `SensitiveDataMaskTest` 四条，断的是**泄不泄**（中间四位一定不在结果里），
   不是"星号有几个"——依赖换版本就红一次的判据最后只会被人改成能过。

要你遵守的两条：

1. AI 的输入只能是**聚合摘要 DTO**（你新建 `*SummaryVO`），字段集合里不得出现 `mobile`、`idCard`、
   完整地址、原始流水列表；地址按业务需要保留到乡镇级（县域调度没有乡镇就无从推理），但**姓名与电话一律掩码**。
2. 新增 SummaryVO 时顺手加一条会红的检查：把某个 SummaryVO 塞一个 `mobile` 字段 ⇒ 上面那本闸必须红
   （自检 K4 已经证明了它咬得住，你要做的是别把新目录写成绕过它的形状）。

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
| 2 | 「赶集 / 农忙 / 节假日」事件日历 | **已由主线做完，本线不再各建一套**（见下面的 §6.1）：表 `transport_town_event_calendar`、接口 `/transport/town-event/*`、菜单 6950–6952 | 创新1 的真卡点是**没有这张表**，不是不会推演；有日历 + 既有班次统计，规则就能出"提前留舱"提示 |
| 3 | 客货空间冲突检视 | 排班保存前的确定性校验：核定载客/载货 vs 预报量，输出冲突点与缓冲建议 | 是约束校验，不是概率猜测；措辞保守反而更可信 |
| 4 | 查询构造器 + 运营简报模板导出 | 结构化筛选（存条件）+ 定时聚合 + 模板文档 | 现成分页/聚合接口够用；"自然语言"那一层将来只做"话→参数"，**永远不做"话→SQL"** |

### 6.1 事件日历只留一套（2026-10-16 补，队列 #98）

这一格是**我这份契约自己造成的重复**：原来这行写的是"新表 `town_event_calendar(town, event_type, start, end, note)`"，
本线照这句建了 `V050__ai_event_calendar.sql`；同一天主线又按同一个队列项建了 `V037__town_event_calendar.sql`。
两边表名不同、菜单主键不同（6950–6952 vs 7100–7104），所以 `check_menu_id_collisions.py` 判绿 ——
它认的是户口不是业务身份。**两张表都进云上，代价落在运营身上**：同一个集日要填两遍，后台读一套、提示读另一套。

留在仓里的那一套以**有周期规则**的那张为准（`transport_town_event_calendar`）：县域场期是周期的（多数场镇逢 1/4/7 或 2/5/8 日赶场），
只能存单日就要运营替每个镇一年填几十行，填不出来的表等于没有表。所以要做的不是删功能，是把本线那一套的读法接到主线上：

- 表：`transport_town_event_calendar`，列含 `recurrence`（`NONE` / `EVERY_N_DAYS`）+ `interval_days` + `start_date` / `end_date`。
- 接口（都已带权限位）：`GET /transport/town-event/page`、`GET /transport/town-event/get`、`POST /transport/town-event/create`（服务端幂等键在册）、`PUT /transport/town-event/update`、`DELETE /transport/town-event/delete`。
  本线做情景提示要读的是 **`GET /transport/town-event/effective-on?day=YYYY-MM-DD`**：它把 `recurrence` 展开成"这一天生效的事件"，
  所以本线**不要自己再算周期**。⚠ 它回空列表时的正确读法是"这一格还没登记"，不是"那天没有集"——把缺值当结论是本仓反复记过的那一种病。
- 权限位：`transport:town-event:query|save|delete`；菜单 6950/6951/6952。
- 本线的 AI 侧要读日历时，**给 `yudao-module-ai-assist` 加 `yudao-module-transport` 依赖**后调 `TownEventCalendarService`，
  或者直接读这张表；不要再建第三张表、第三个同名 DO —— `scripts/check_business_table_single_owner.py`（第 14 本闸，已挂 CI）会红：
  它认"剥掉模块前缀后同名的两张表"和"落在两个顶层模块的同名 DO"，K1–K6 六把刀配着。
- `V050__ai_event_calendar.sql` 与 `yudao-module-ai-assist` 里那套 `eventcalendar` 该退场；**它还没进过任何一次云上执行**（`mode=apply` 要人显式 `confirm=APPLY`），所以现在收手不需要 DROP 迁移。


## 7. 需要模型的部分，以及需要你提供的东西

> **2026-10-16 更新：这一节的代码部分已经做完**（落点见 13.1 的 `AiGateway` / `AiTasks` / `AiTaskService` 三行）。
> 现在只剩一件事需要人给：**一个可用的推理端点**。配置口只有一处 ——
> `yudao-server/src/main/resources/application.yaml` 末尾的 `buspost.ai.*`（默认 `enabled: false`、密钥留空
> ⇒ 全系统天然"未就绪"，后台其余页面零变化）。

真正需要模型的只有：工单语义分类与照片是否支撑描述（视觉）、口语上报的同义归并、
反馈的主题聚类、以及把上面四项的输出**润色成文字**。前提条件三件，缺一不可：

1. 一个可用的推理端点（内网自建或外部服务）+ 出网策略，配在云上那台机器 —— **服务器我管不到**，
   密钥一律不进仓库、不进日志、不进台账；
2. `application.yaml` 里的 `buspost.ai.*`。请求体走 **OpenAI 兼容的 `/chat/completions`**、不引任何厂商 SDK，
   所以**云上 API 与自部署只改三个值**（`base-url` / `api-key` / `model`）：

   | 端点形态 | `base-url` | `model` | `vision-model` |
   |---|---|---|---|
   | 云上 · DashScope 兼容模式 | `https://dashscope.aliyuncs.com/compatible-mode/v1` | `qwen-plus` | `qwen-vl-plus` |
   | 云上 · OpenAI 兼容 | `https://api.openai.com/v1` | 自定 | 自定 |
   | 自部署 · vLLM / 内网网关 | `http://<内网IP>:8000/v1` | 部署时给的模型名 | 有视觉模型才填 |
   | 自部署 · Ollama openai 层 | `http://127.0.0.1:11434/v1` | `qwen2.5:7b-instruct` | `qwen2.5vl:7b` |

   `api-key` **只从环境变量 `BUSPOST_AI_API_KEY` 注入**（yaml 里写的是 `${BUSPOST_AI_API_KEY:}`，仓库里永远看不到真值）；
   `timeout-ms`（默认 8000）真的传给 HTTP 客户端（connect + request 两处），`max-input-chars`（默认 4000）
   是送出去之前的截断上限，截了会在提示里写明"原长 N 字"。
3. 一句可验收的口径：拿不到端点时，页面显示"能力未就绪"，**不许用模板文案冒充 AI 输出**
   （本仓红线：不许演示形态、不许假能力）。这条现在**有用例钉着**：`AiTaskServiceTest` 第 1 个用例
   断言开关关掉时五个任务的 `ready/parsed=false`、`suggestion/basis/rawText` 全为 null，
   并与第 4 个用例（端点齐备时真发一次、解析成草稿、明文手机号没出网）配成一对正负对照。

⚠ `vision-model` 留空 ⇒ "照片是否支撑描述"这一项**直接报未就绪且一次外呼都不发**，
不退化成"只看文字猜照片"（`AiTaskService#checkPhotoSupport`，同文件第 6 个用例钉着）。

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

## 13. 交接（给下一个 agent）：本线做完了什么、什么没做、需要谁配合

> 本节由 AI 辅助线（队列 #93）在实现 §6 时追加。上面 §0–§12 是主线写的契约，**不改动**；
> 这一节只登记"落地读数 / 没做的 / 依赖申请"，让接手的人不用重新考古。
>
> ⚠ **本条规矩在 2026-10-16 破了一次，破在哪要说清**：§7 被就地改写了（原文那三件前提里的第 2 件
> "需要你在 `application-*.yaml` 里加键"已经做完了，留着就是假账）。改动只做了两件事：
> 把"要你提供什么"改成**一个配置口 + 云上/自部署各改哪三个值**，并把"不许用模板文案冒充 AI 输出"
> 那条口径指到钉它的用例上。§0–§6、§8–§12 一字未动。

### 13.1 已落地（均可被仓内判据复核）

| 项 | 落点 | 证据 |
|---|---|---|
| §6 第 3 项 客货空间检视 | 只读判定 `POST /transport/ai/capacity-conflict/check`（菜单 7110，`V051`）；`CapacityConflictServiceImpl`（未录≠0、decimal 比载重） | 同上 |
| §6 第 4 项 查询构造器+简报 | 表 `ai_saved_query`（`V052`，菜单 7120–7123）+ CRUD `/transport/ai/saved-query/*`；模板排版 `POST /transport/ai/briefing/render`；`views/transport/ai/query-builder/**` | 同上 |
| §7 AI 外壳（开关 + 能力未就绪） | `buspost.ai.*` 用 `@Value` 读（**默认 off/空 ⇒ 天然"未就绪"，不改任何 yaml**）；`GET /transport/ai/capability/status`（菜单 7130，`V053`）；`views/transport/ai/capability/**` | 见 13.5 读数 |
| §5 调用日志（契约里唯一新增的表） | 表 `ai_invocation_log`（`V054`，菜单 7140，**只读页**）；`AiInvocationLogServiceImpl#record` 为**旁路**（落库失败不抛、但记 error 级日志）；`GET /transport/ai/invocation-log/page`；`views/transport/ai/invocation-log/**` | 同上 |
| 云端迁移通道 | `V050`–`V055` 已进 `db-migrate-guarded.yml` 的 apply 白名单（`V050` 现仅建所有 AI 页共用的顶级目录 7100；白名单共 **15 支** = `V028`、`V030`–`V037`、`V050`–`V055`，`V029` 明确排除），并补了探针（check 只读、apply 需 `confirm=APPLY`；`probe`/`probe_strict` 各 **32 格**，含主线 V037 那 3 格） | YAML 可解析；**白名单在本机 MySQL 上跑两遍零漂移、32 格探针对得上**（见 13.5 第 16 本） |
| **§7 五个模型任务 + 一条规则提示**（2026-10-16 补） | `core/AiGateway`（**唯一外呼口**：JDK `HttpClient` 打 OpenAI 兼容 `/chat/completions`，非 2xx→`http_error`、2xx 无正文→`empty`、未就绪→`not_ready`，**每次都写 `ai_invocation_log`** 且留痕是旁路）；`core/AiTasks`（纯函数：提示词 + 严格解析 + "数字没被改"的集合比对）；`service/task/AiTaskService`（5 个模型任务 + **不依赖模型**的 `scenarioHints`，只读 transport 那份日历）；`POST /transport/ai/task/{classify,photo-support,merge,cluster,polish}` + `GET /transport/ai/task/scenario-hints`；`views/transport/ai/assist/index.vue` | 见 13.5 读数 |
| **§7 配置口（云上 / 自部署只改三个值）**（2026-10-16 补） | `application.yaml` 末尾新增 `buspost.ai.*` 一段（纯 CRLF 追加，9 条配置行；文件 4 个 YAML 文档均无 profile 守卫 ⇒ 默认生效）；`AiAssistProperties#endpointReady/visionReady/chatCompletionsUrl` | `yaml.safe_load_all` 4 文档、doc3 读到该段；`enabled:false` + 密钥空 ⇒ 能力页念未就绪 |
| **菜单 `V055`**（2026-10-16 补） | 7150「AI 辅助工作台」（`transport:ai:scenario:query`，组件 `transport/ai/assist/index`）+ 7151 按钮权限（`transport:ai:task:run`）；按权限串判存在 + `INSERT IGNORE` 授角色 ⇒ 幂等 | 第 1 本闸（menu_id_collisions）76 条主键 / 73 个不同 id，`rc=0` |
| **§4 脱敏收进唯一出口**（2026-10-16 补） | `transport/util/SensitiveDataMask#maskEmbeddedMobiles`（**出口内部再自检一次**，掩不干净就抛）；AI 侧不再自写手机号正则 —— `AiTaskService#mask` 调出口 + `AiTasks#assertNoPlainMobile` 二次断言 | 第 13 本闸"AI 面命中 0 处"；出口自测 +2 用例（transport 387→389） |
| **第 15 本静态闸**（2026-10-16 补） | `scripts/check_ai_assist_boundaries.py`：A1 任务目录里不许出现业务写口 / A2 只有 `AiGateway` 能做 HTTP / A3 日志生产者 ≥1 / A4 密钥只与 `Authorization` 同现 / A5 返回只能是 `Draft`；K1–K6 刀口 + K7 真树读数 | `rc=0`；`--self-test rc=0`（K6 证明"密钥出现在没有 Authorization 的文件里"会红） |
| **第 12 本闸的扫描根补齐**（2026-10-16 补，队列 #99） | `check_admin_write_idempotency.py`：`MODULE_ADMIN` 加 ai-assist=(10,1)，并加 `MUST_COVER_OWNER` + `demand` 过滤 ⇒ **模块还没落地时大声跳过，而不是把"端点被删了"念成合规**；K8/K9 两把刀专门钉这本闸自己的覆盖面 | `rc=0`（写口 10 / 在册 10 / 自带幂等键 1）；`--self-test rc=0` |

三条纪律落在代码里，接手时别破坏：**未录容量 ≠ 0 座**（`CapacityConflictServiceImpl`）；**缺值不折叠成 0**（简报数值用文字承载）；**只读不写**（两个新接口都不落库、不注入写口）。

### 13.2 没做 / 本线做不到的（需要下一个 agent 或主线配合）

1. **界面点验**：~~五条页面未点验~~ **2026-10-16 晚已在本机栈（一次性库 + 本机后端 + vite dev）逐页点过**，
   读数见 **13.7**：菜单「AI 辅助」下五页 + 乡镇事件日历维护页全部打开、真点、且**正向路径也走了**
   （日历页 UI 建一条 → assist 情景提示读到它）。三处"只有真跑才现形"的缺陷就是这么抓到的（见 D-144）。
   ⚠ 仍要登记的部分：**云上部署形态未点验**（PR #195 未合、云上没装 V050–V055）⇒ 对云上那份**仍按 §9.5 记 `NOT VERIFIED IN BROWSER`**。
2. **§6.3 接真实数据（依赖申请）**：平台里**没有"预报量"的存储字段**——真实数据是"车辆核定容量（`transport_vehicle`）+ 订单池/计划量（`transport_order` 系列、`transport_dispatch_plan_item.quantity`）"。要自动带出这两头，需要在 `yudao-module-transport` 新增一个**只读 `api` 包**（该模块目前没有 `api` 包，也没有任何模块依赖它的先例）。当前实现是"页面手工录入 + 服务端确定性判定"，功能闭环但不自动。
3. **§6.4 定时聚合**：需要"读既有聚合 + 调度器 + 落库位置"，本期只做了**存条件 + 模板排版**。"自然语言"那一层（话→参数）未做，且契约已定**永远不做话→SQL**。
4. **§7 AI 功能（2026-10-16 已补齐代码，只剩端点）**：开关 + `GET /transport/ai/capability/status` + 「能力未就绪」页 + **五个模型任务与一条规则提示**都已落地；`application.yaml` 里的 `buspost.ai.*` 也由本线补上了（原先是"依赖申请"）。**仍缺的只有一件：一个真实可用的推理端点**（`base-url` + `BUSPOST_AI_API_KEY` + `model`）—— 这要配在云上那台机器上，**服务器由用户管，谁都不要直接连**。在端点配好之前，assist 页六格一律念「未就绪」，这是设计行为不是缺陷。红线不变：不许用模板文案冒充 AI 输出。
5. **§9 第 4 条「关掉 AI 仍完整可用」检查**：现在有了真消费者，因此这条不再是"恒真"。已落地的部分是**用例级**的：`AiTaskServiceTest` 断言开关关掉时五个任务只回未就绪（`suggestion/basis/rawText` 全 null、恰好 4 条 `not_ready` 留痕），而不依赖模型的 `scenarioHints` 照样 `ready=true` 且 0 次外呼。**仍缺"页面集合级"的对照**：把 `enabled` 在 true/false 之间切换后，比对既有 transport 页面的路由集合与 15 本闸读数是否一字不变 —— 这一步需要在浏览器里点，与第 1 条一起做。

### 13.3 并行写入者：事件日历重复已了结

- 另一条线在 `yudao-module-transport` 里自带一份乡镇事件日历：表 `transport_town_event_calendar`（`sql/incremental/V037__town_event_calendar.sql`，带 recurrence）、菜单 6950–6952、`/transport/town-event`。
- 本线原先也建了一份（`town_event_calendar` / 原 `V050__ai_event_calendar.sql` / 菜单 7100–7104）。**经拍板：保留 transport 那份，本线删除重复** —— 已删掉本线的表、`eventcalendar` 模块、前端 `event-calendar` 页；`V050` 改为只建所有 AI 页共用的顶级目录「AI 辅助」(7100)（文件改名 `V050__ai_menu_dir.sql`）。
- 该线提交的第 14 本静态闸 `scripts/check_business_table_single_owner.py`（`5abba2f9`，队列 #98）因此**已转绿（`rc=0`）**。若将来 AI 侧要用日历，**只读 transport 那份，别再建第二张表**（这正是那本闸要防的）。
- 该线仍在继续提交（如 `c9b11736`、`064d66b8`）：接手前先 `git log` 复核，别把该线的文件当成自己的改动提交。

### 13.4 台账与编号（接手前先看这段，防撞）

- 台账 `DECISION_LOG.md` 在 `android-app` **子模块**里（本仓未检出，且 §11.13 时段内禁碰该树）。本线的决定（含 `NOT VERIFIED IN BROWSER`）**只写在提交信息里**，由主线并入台账。
- 本线已占号：**迁移 `V050`（仅目录菜单）–`V055`**；**菜单 7100 / 7110 / 7120–7123 / 7130 / 7140 / 7150–7151**；**错误码 `1_006_002_xxx` / `1_006_003_xxx`**（事件日历已删，故 `1_006_001_xxx` 不再占用）。**`V056` 起、菜单 7160 起仍空闲。**

### 13.5 本轮验证读数（2026-10-16）

- 14 本静态闸（两条线合并后的面，2026-10-16 接手时重跑）：全部 `rc=0`；且各本 `--self-test` 全部 `rc=0`（含 `check_admin_ai_data_mask` 现在真扫到 AI 面三目录）。
  - 第 14 本 `check_business_table_single_owner.py`（该线 `5abba2f9` 提交）：删掉本线重复后**已转绿（`rc=0`）**（见 13.3）。
- 前端：SFC 结构扫 5 个 `.vue` 全绿 → `vue-tsc --noEmit` `rc=0`（0 错）→ `eslint src/views/transport src/api/transport` `rc=0`（0 error；6 条 warning 全在既有 handover/order/pricingRule，非本线）→ `vitest` 4 文件 / **54** 用例通过。
- Java：这条已还掉——本机**确实有 Maven**（`/c/Users/袁/apache-maven-3.9.16/bin`）。接手后本地跑 `mvn -B -pl yudao-server -am clean test`：
  **BUILD SUCCESS**，`yudao-module-ai-assist` 聚合 **Tests run: 19, Failures: 0, Errors: 0**，`yudao-module-transport` 387 条同轮 0 红。
  ⚠ 另一条读数值得留着：**不带 `clean`** 跑同一条命令时 `OAuth2TokenServiceImplTest.testGetAccessTokenPage` 会报 `expected <1> but was <2>`
  —— 那是旧 target 状态造成的假失败（上游模块，与本线无关），**归因要用 CI 的确切命令，不能凭手感**。
- 迁移脚本本身：只做了静态/idempotent 复核，**未连库执行**（走 guarded `check` 只读、`apply` 需人给 `confirm=APPLY`）。

### 13.6 第二轮验证读数（2026-10-16 晚：§7 五个模型任务 + 配置口落地之后）

- **Java**：`mvn -B -pl yudao-module-ai-assist -am clean test` ⇒ `MVN_RC=0` / `BUILD SUCCESS`；
  `yudao-module-ai-assist` 聚合 **19 → 47**（新增 `AiTasksTest` 13 + `AiGatewayTest` 7 + **`AiTaskServiceTest` 8**），
  `yudao-module-transport` **387 → 389**（脱敏出口那两个新用例），同轮 0 红 0 错。
  ⚠ 这一轮我自己踩的一枪：`AiTaskService` 里遗留了一行 `private static final Pattern MOBILE`
  （掩码收进唯一出口之后它就没人用了）⇒ 编译 `找不到符号 Pattern`。**处理是删掉那行死码，不是补 import**。
- **前端**（照 CI frontend job 的步骤名与顺序跑，逐步读 rc）：
  `build:prod` **rc=0**，产物 **`dist-prod/` 373 个文件**（`.env.prod: VITE_OUT_DIR=dist-prod`），
  其中 `dist-prod/assets/assist-qeKOAw9D.js` 同时命中 `TransportAiAssist` 与页面里那句"这一格没有产生任何 AI 输出"
  ⇒ 新页真进了产物。⚠ 我第一遍按 `dist/` 数，数到 **0** —— **探错目录会被读成"构建没产出"**，
  已在跑手脚本里改成读 `VITE_OUT_DIR`；`vue-tsc --noEmit`（不带 `--incremental`，比 CI 严）**rc=0 / 0 错**；
  `vitest run` **4 文件 / 54 用例**全过；`eslint src/views/transport` **rc=0**（0 error / 7 warning，均在既有页）；
  SFC 结构扫（`vue/compiler-sfc` 的 parse + compileTemplate + compileScript）：`views/transport` **43 只全绿**，
  其中 `views/transport/ai/**` **6 只**（含新的 `assist/index.vue`）。
- **15 本静态闸**：全部 `rc=0`。本轮相关的四本 `--self-test` 全绿（`check_ai_assist_boundaries` K1–K7、
  `check_admin_write_idempotency` K0–K9、`check_admin_ai_data_mask` K1–K7、`check_business_table_single_owner` K1–K7）。
  一处**读数变化要留档**：`check_sql_function_arity` 107 → **108**（V037 新增的 `IF(@idx_exists=0,…)`）；
  这本闸验的是每个 IF 的实参数目、不是总数，所以仍是绿 —— 记在这里免得下一个人把它当漂移。
- **第 16 本判据（本机专用，不进 CI）**：`scripts/check_migration_idempotency.py` ⇒ `rc=0`，
  **白名单 15 支跑两遍、逐表零漂移、32 格 `probe_strict` 全对**；`--self-test` K1–K7 全绿
  （K4 非幂等 fixture 必须被数出来、K5 幂等 fixture 是配对正对照、K7 找不到 mysql 客户端 ⇒ `rc=2` 拒判）。
  **改之前在同一台机器上的实测**：第 2 遍 `V037` 直接 `ERROR 1061 Duplicate key name 'idx_town_lookup'` 中断
  （它后面那三条菜单授权因此根本跑不到），`system_role_menu` **466 → 481（+15 行）**，
  **6 格探针从 1 变 2**（钱包 / V050 / V052 / V053 / V054 / V055）。
  根因：`system_role_menu` 只有自增主键、**没有 `(role_id, menu_id)` 唯一键**（`sql/mysql/ruoyi-vue-pro.sql:3695`），
  所以基线那种 `INSERT IGNORE` 挡不住重复；而 guarded 的 apply **每次都重跑整份白名单**。
  ⇒ 10 支脚本的授权改成 `NOT EXISTS` 判重、V037 的裸 `CREATE INDEX` 换成 V024 那套 `information_schema + PREPARE`；
  **同行替换**（行数与行尾一律不动 ⇒ 台账/工作流里按行号的锚点不受影响），
  并把 10 处还在声称"INSERT IGNORE 可安全重复执行"的注释一并改掉（跑手最后会把这些行全打出来核，剩 0 条）。
  ⚠ 这批脚本在改之前**从没被任何库执行过一次**（V037/V050–V055 都没上过云），
  所以修掉的是"用户第一次点 apply 就会踩"的坑，不是历史脏数据。
- **仍未做**：连**云**库执行（只能走 guarded `check` 只读 / `apply` 需人给 `confirm=APPLY`）；
  以及 13.2 第 1 条那五条页面的浏览器点验（含新的 assist 页）。

### 13.7 浏览器点验（2026-10-16 晚 · 台账 D-144）：六页真点过了，抓到三处真缺陷

**怎么搭的**（不碰在用的库与服务，收工即停）：
```
# 1) 一次性库（六份基线 + guarded 15 支白名单；--drop 可清）
python3 tmp/build_check_db.py
# 2) 临时 Redis（本机 6379 要口令，别去改它）
"/c/Program Files/Redis/redis-server.exe" --port 6390 --save '""' --appendonly no &
# 3) 后端（只覆盖两项：一次性库 + redis 键空间 9）
cd cargo-post-platform-m195
MSYS_NO_PATHCONV=1 java -jar yudao-server/target/yudao-server.jar --spring.profiles.active=local \
  --spring.data.redis.port=6390 --spring.data.redis.database=9 \
  '--spring.datasource.dynamic.datasource.master.url=jdbc:mysql://127.0.0.1:3306/buspost_ai_check?useSSL=false&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true&nullCatalogMeansCurrent=true&rewriteBatchedStatements=true' &
# 4) 前端（VITE_BASE_URL 已指向 48080）
cd yudao-ui/yudao-ui-admin-vue3 && node ./node_modules/vite/bin/vite.js --mode env.local --port 5173 --host 127.0.0.1 &
# 登录 admin（本地 yudao.captcha.enable=false）→ 五页路由 /ai/{assist,capability,invocation-log,capacity-conflict,query-builder}，日历页 /transport/town-event
```

**三处只有真跑才现形的缺陷**（细节在 D-144）：
1. **调用日志页整页 500** —— `output` 是 JSqlParser 保留字，MP 分页拦截器解析原句就炸；
   **我第一版只改列名没修好**（MP 会补 `AS output` 别名，别名里还是那个词）⇒ 字段/列/JSON 一起改 `output_text`。
   范围是**量出来的**：93 个 DO / 930 列全过一遍 JSqlParser，只有这一列炸；既有单测抓不到是因为它们走 mock 映射器、
   **从没生成过真 SQL** ⇒ 补 `DoColumnJsSqlParserTest`（真树全过 + 反证 `SELECT output` 必须解析不过）。
2. **assist 页三格共用一个输入**（在第 1 格打字会填满第 3 格的"简报底稿"）⇒ 拆成三个独立输入。
3. **空日历那格标题念"依据不足"**，而真实状态是"没读到生效事件" ⇒ 按场景分词改措辞。

**六页读数**：assist（6 格 + 未就绪文案 + 正向情景提示）/ capability（三行条件逐条列）/
invocation-log（**500 → 4 行**，送入栏显示 `138****8000`）/ capacity-conflict（19·300·500 vs 25·400·600 ⇒ 3 处 BLOCK、缺口 6/100/100）/
query-builder（必填校验拦空标题；**create `data=1`、同条件 10 秒内再打 ⇒ `code=900`、`total=1`**）/
town-event（**UI 建成**：杨柳镇/赶集/2026-10-17）。
跨页正向：日历那条被 assist 读到 ⇒ `杨柳镇：赶集（登记的那一天）→ 建议提前与调度确认留舱量`，
依据明写 `规则展开，未经模型` —— **"关掉 AI 整套后台仍完整可用"第一次有页面级证据**。
