# 客货邮平台精简与性能提升方案

> 数据来源：2026-08-19 对开发服务器（1.15.29.107，腾讯云 4C/3.6G + 8G swap）的实测 + 全仓库静态分析。
> 目标：在给定硬件上降低内存占用、加快启动、收缩安全暴露面，同时清理仓库冗余资产。

## 0. 现状实测（关键数据）

### 0.1 运行现状

| 指标 | 实测值 | 说明 |
|---|---|---|
| Java 进程 RSS | **808 MB**（`-Xms512m -Xmx512m`） | 非堆占用 ~300MB，偏高 |
| JVM 线程数 | **120** | 其中 Redisson 49（32 netty + 16 worker + 1 timer）、Quartz 28（25 worker + scheduler/misfire/cluster）、Tomcat 10 |
| Metaspace | 107 MB committed，**99% 占满**（23440 个类） | classpath 臃肿 |
| 启动时间 | 20.8 s | |
| fat jar | 163 MB / 264 个依赖 jar | |
| 系统内存 | 3.6G 总量，free 仅 449MB，**swap 已用 972MB** | **mysqld 被换出 437MB**（DB 延迟隐患） |
| 容器 RSS | MinIO **245MB**（数据仅 168K、0 个文件）、MySQL 122MB、mock-algorithm 19MB、Redis 4.3MB | MinIO 纯属空转 |
| Nginx 7 天访问 | 真实业务请求 **< 20 次**（其余全是互联网扫描器） | 性能问题 = 资源占用，不是 QPS |

### 0.2 数据库现状（cargo_post_dev，总计 ~8.2MB）

- `infra_api_access_log`：**6.8MB / 13088 行，占全库 80%+**，清理 Job 未启用，无界增长。
- `infra_job`：18 行**全部是已禁用模块（pay/mall/iot/demo）的 Job 种子**（status=2 关闭），但 QRTZ_TRIGGERS 残留 11 个触发器，Quartz 以集群模式每 15s 做一次 DB checkin——零业务价值，纯开销。
- `member_*` 只有 2 张表（member_user 4 行、member_address 0 行）；`transport_*` 27 张表 ~1MB。业务数据量极小。
- `yudao_demo01~03` 5 张演示表仍在。
- `system_mail_log / system_notify_template / system_social_user* / system_operate_log` 全为 0 行（对应功能零使用）。

### 0.3 模块与功能使用结论（静态分析 + 服务器数据交叉验证）

**已确认在用**：transport 全域（订单/寄件/司机/车辆线路站点班次/调度/公告反馈/看板）、system 的 auth/user/role/menu/dept/post/dict/oauth2/sms/social（登录链路）、infra 的 file/logger/config/job 管理、member 的 auth/user/address（小程序）。算法链路 transport → mock-algorithm（真实下游接入前必须保留）。

**确认冗余**：

| 冗余项 | 证据 |
|---|---|
| Quartz 定时任务体系 | 4 个启用模块**零** `@Scheduled`/`JobHandler` 业务任务；infra_job 18 行全是已禁用模块种子 |
| ~~`wx-java-mp` / `wx-java-miniapp` starter~~（已纠正：保留） | 初判零引用，实施时验证构建推翻：`SocialClientServiceImpl` 真实使用（小程序手机号/订阅消息/公众号服务） |
| `yudao.api-encrypt` | 全仓**无任何** `@ApiEncrypt` 注解，但过滤器对**每个请求**多做一次 handler-mapping 查找 |
| Druid stat 过滤器 + 监控台 | 14 天慢 SQL 记录为 0；且 `/api/druid/` 控制台**外网无密码可访问**（安全问题） |
| springdoc/knife4j | `/api/v3/api-docs` 外网暴露 **581KB 全量 API 结构**（安全问题），首次访问后 OpenAPI 模型驻留内存 |
| application.yaml 死配置 | flowable（jar 不在 classpath）、spring.ai/yudao.ai（~170 行，含**真实 AI key**，泄漏面）、rocketmq/kafka/rabbitmq（客户端 jar 不在 classpath）、spring-boot-admin（SBA 不在 jar）、trade/iot 配置块 |
| infra demo 子域 | 5 个演示 Controller + 5 张 yudao_demo 表 |
| member 的 point/level/signin/social-user app 端点 | 小程序 `utils/api.js` 无对应调用（admin 侧有页面，删除需产品确认，本方案不删） |
| MinIO 容器 | 0 文件、168K 数据、245MB RSS；且 9000/9001 端口对公网开放 |

## 1. 第一阶段：服务器配置层精简（零代码改动，立即生效，可秒级回滚）

改动只发生在服务器 `/opt/cargo-post/config/application-dev.yaml`（Spring Boot 外部配置，优先级高于 jar 内配置）。回滚 = 删掉对应配置行 + `systemctl restart cargo-post`。

### 1.1 关闭 Quartz（省 28 线程 + 每 15s 的 DB 集群轮询）

```yaml
spring:
  autoconfigure:
    exclude: # 与 application-local.yaml 相同的关闭方式
      - org.springframework.boot.autoconfigure.quartz.QuartzAutoConfiguration
      # 保留 dev 原有的两个 vectorstore 排除项（列表属性是整体覆盖，不是合并）：
      - org.springframework.ai.vectorstore.qdrant.autoconfigure.QdrantVectorStoreAutoConfiguration
      - org.springframework.ai.vectorstore.milvus.autoconfigure.MilvusVectorStoreAutoConfiguration
```

- 依据：4 个启用模块无任何业务定时任务；现有 18 个 Job 全部是已禁用模块的种子残留。
- **实施修正**：最初用 `spring.quartz.auto-startup: false`，实测发现 SimpleThreadPool 在 Scheduler 实例化时就创建 25 个 worker 线程（不 start 也常驻），故改为整体排除自动配置（本地 local profile 长期使用此方式，已验证安全）。
- 影响：管理后台「定时任务」页的增删操作会报错（无 Scheduler bean），页面数据已随 §3 清空；日志清理由 §3.3 的 cron 脚本承担。

### 1.2 收缩 Redisson 线程（49 → ~12）

```yaml
spring:
  redis:
    redisson:
      config: |
        threads: 4
        nettyThreads: 8
        singleServerConfig:
          address: "redis://127.0.0.1:6379"
          database: 1
          password: "<与现有 REDIS_PASSWORD 相同>"
```

- 依据：当前 Redisson 默认 32 netty + 16 worker 线程，而 Redis db1 中 **key 数量为 0**、7 天业务请求 < 20 次。
- 注意：配置键前缀是 `spring.redis.redisson`（已从部署 jar 中 `RedissonProperties` 的 `@ConfigurationProperties(prefix="spring.redis.redisson")` 核实，**不是** `spring.data.redis.redisson`）；`config` 一旦提供会取代 `spring.data.redis.*` 连接参数的自动装配，address/password/database 必须内联写全。
- **实施记录**：密码未重复落盘，实际写为 `password: "${spring.data.redis.password}"`（Spring 占位符引用同文件第一文档的数据源密码，绑定期解析）。

### 1.3 关闭 Druid 统计与监控台（消除外网无密码控制台）

```yaml
spring:
  datasource:
    druid:
      web-stat-filter:
        enabled: false
      stat-view-servlet:
        enabled: false
      filter:
        stat:
          enabled: false   # 仅关闭 stat；wall 过滤器本来就没启用（未设 enabled），无需动
```

- 依据：14 天慢 SQL 为 0，stat 过滤器对每条 SQL 的统计包装（含 merge-sql 的常驻 SQL 映射表）是纯开销；web-stat-filter 对每个 HTTP 请求做 URI/session 统计；`/api/druid/` 当前外网可达且无登录保护。

### 1.4 关闭 API 文档（消除 581KB API 结构外泄 + 省驻留内存）

```yaml
springdoc:
  api-docs:
    enabled: false
  swagger-ui:
    enabled: false
knife4j:
  enable: false
```

- 本地开发不受影响（`application-local.yaml` 不携带此覆盖）。
- 说明：springdoc 的 OpenAPI 模型是**首次访问 `/v3/api-docs` 时才构建**的（之后驻留内存），所以本项的主要收益是**收攻击面 + 省驻留内存**，启动时间收益有限。

### 1.4b 可选：彻底关闭 API 访问日志（默认不关，用 §3.3 的周清理替代）

`ApiAccessLogFilter` 默认开启（`matchIfMissing=true`），对每个 admin-api 请求做 body 读取+JSON 脱敏解析+异步 DB 插入——这正是 `infra_api_access_log`（占全库 80%）的来源。

- 保守做法（推荐）：保留开关不动，靠 §3.3 的周清理控制体积，审计能力保留。
- 极致做法：`yudao.access-log.enable: false`，每请求省 body 解析 + 一次异步 insert；代价是丢失请求级审计（`system_operate_log` 操作日志是另一套，不受影响）。

### 1.5 关闭 API 加密过滤器（无业务使用，去掉每请求一次的多余 handler 查找）

```yaml
yudao:
  api-encrypt:
    enable: false
```

- 依据：全仓零 `@ApiEncrypt` 注解；`ApiEncryptFilter.getApiEncrypt()` 对每个请求都执行一次 `RequestMappingHandlerMapping.getHandler()` 二次查找。

### 1.6 本阶段收益与验证

预计：线程 120 → **~55**，Java RSS 808MB → **~700MB**，启动 20.8s → **~15~17s**（Quartz 启动 + Druid stat + 死 bean 消失），关闭 2 处外网暴露。

验证：

```bash
systemctl restart cargo-post
curl -s http://127.0.0.1:48080/actuator/health          # {"status":"UP"}
sudo -u deploy jcmd <pid> Thread.print | grep -c '^"'    # 线程数
curl -s -o /dev/null -w '%{http_code}\n' http://1.15.29.107/api/druid/index.html   # 404
```

## 2. 第二阶段：容器与系统层（省 ~325MB RSS + ~2.3GB 磁盘）

### 2.1 停掉空转的 MinIO（省 245MB，同时关闭 9000/9001 公网暴露）

文件存储 master 当前是 S3 型配置（infra_file_config id=35），但 **0 个文件** 被上传过。把 master 切到数据库存储，小程序上传功能（`/app-api/infra/file/upload`）不依赖 MinIO 仍可用：

```sql
-- 在 cargo_post_dev 库执行
UPDATE infra_file_config SET master = 1 WHERE id = 4;   -- 数据库（示例）
UPDATE infra_file_config SET master = 0 WHERE id = 35;
```

然后停容器并注释 `deploy/docker-compose.yml` 的 `minio` 服务（保留配置块注释，将来接对象存储时一行恢复）：

```bash
docker compose --env-file /opt/cargo-post-platform/.env -f deploy/docker-compose.yml stop minio
```

### 2.2 MySQL 关闭 Performance Schema（省 ~80MB RSS）

`deploy/docker-compose.yml` 的 mysql command 追加：

```yaml
command: ["--character-set-server=utf8mb4", "--collation-server=utf8mb4_unicode_ci", "--performance-schema=OFF"]
```

- 依据：全库数据 8.2MB、无 DBA 在线诊断需求；PFS 在小内存机器上是 MySQL 最大固定开销之一。`innodb_buffer_pool_size` 128MB 保持不变（数据量远小于此）。

### 2.3 端口收敛：3306/6379/18080 仅绑定本机（安全加固，零功能损失）

后端以宿主机进程（systemd java）运行，连接的是 `127.0.0.1`，因此 compose 端口可全部改为本机绑定：

```yaml
# mysql:  "127.0.0.1:${DB_PORT:-3306}:3306"
# redis:  "127.0.0.1:${REDIS_PORT:-6379}:6379"
# mock-algorithm: "127.0.0.1:${MOCK_ALGORITHM_PORT:-18080}:8000"
```

当前这三个端口对公网全开，是扫描器的实际攻击面（nginx 日志已见大量 `.env` 探测）。

### 2.4 抑制 swap 对 MySQL 的伤害

```bash
sudo sysctl -w vm.swappiness=10
echo 'vm.swappiness=10' | sudo tee /etc/sysctl.d/99-cargo-post.conf
```

当前 mysqld 已被换出 437MB；§1+§2.1-2.2 释放 ~400MB 后内存压力解除，此设置防止复发。

### 2.5 磁盘清理

```bash
sudo journalctl --vacuum-size=200M        # journald 现占 1.1GB
docker image prune -a --filter "until=720h"   # 1.1GB 可回收镜像
```

- **实施记录**：两项实际均释放 0B——journald 的 1.1G 几乎全是未归档的活动段（rotate+vacuum 后仍 0B，留待自然轮转）；docker 可回收镜像均不足 30 天、被 until 过滤器跳过。磁盘 53% 非瓶颈，不再深究。

可选（云主机无用服务，合计几十 MB）：`ModemManager`、`multipathd`、`udisks2` 停用；1Panel（~100MB）与 mihomo（代理，runner 依赖）按需决策，本方案不动。

## 3. 第三阶段：数据库一次性清理

```sql
-- 1) 删除失效定时任务种子（18 行全是已禁用模块的 Job）
DELETE FROM infra_job;
TRUNCATE infra_job_log;

-- 2) QRTZ_* 表：**实施时决定保留**（11 张合计仅 0.5MB，Quartz 排除后零运行时开销；保留可零成本恢复定时任务能力，建表脚本可从上游 ruoyi-vue-pro 仓库找回）

-- 3) 访问日志瘦身（6.8MB 开发期数据）
TRUNCATE infra_api_access_log;
TRUNCATE infra_api_error_log;

-- 4) 演示表
DROP TABLE IF EXISTS yudao_demo01_contact, yudao_demo02_category,
  yudao_demo03_course, yudao_demo03_grade, yudao_demo03_student;

-- 5) 过期 OAuth2 token（TokenCleanJob 从未注册过调度）
DELETE FROM system_oauth2_access_token WHERE expires_time < NOW();
DELETE FROM system_oauth2_refresh_token WHERE expires_time < NOW();
```

### 3.3 日志保留策略（替代被关闭的清理 Job）

新增 `deploy/scripts/cleanup.sql`（每周保留）+ 服务器 crontab：

```sql
DELETE FROM infra_api_access_log WHERE create_time < DATE_SUB(NOW(), INTERVAL 7 DAY);
DELETE FROM infra_api_error_log  WHERE create_time < DATE_SUB(NOW(), INTERVAL 30 DAY);
DELETE FROM system_login_log     WHERE create_time < DATE_SUB(NOW(), INTERVAL 30 DAY);
```

```cron
# crontab -e（deploy 用户）
17 3 * * 0  docker exec cargo-post-platform-mysql-1 sh -c 'mysql -uroot -p"$MYSQL_ROOT_PASSWORD"' < /opt/cargo-post-platform/deploy/scripts/cleanup.sql
```

## 4. 第四阶段：代码与依赖精简（走 PR + CI 部署）

### 4.1 删除 jar 内死配置（`yudao-server/src/main/resources/application*.yaml`）

| 位置 | 删除内容 | 依据 |
|---|---|---|
| application.yaml:55-64 | `flowable:` 块 | bpm 模块未引入，flowable 不在 classpath |
| application.yaml:120-144 | `rocketmq:`/`kafka:` 块 | mq starter 虽被 system 引入，但无任何 MQ 客户端 jar 打包（已验证 jar 内无 kafka/rocketmq/rabbit 类库），配置永不生效 |
| application.yaml:146-256 | `spring.ai:` + `yudao.ai:` 整段（~110 行） | ai 模块未引入；且含**多个真实 AI 服务商 key**，属凭据泄漏，删除后建议轮换 |
| application-dev.yaml:100-113 | `rocketmq:`/`rabbitmq:`/`kafka:` 连接配置 | 同上 |
| application-dev.yaml:132-144 | `spring.boot.admin:` 块 | SBA server/client 均不在 jar 中（已验证），纯死配置 |
| application.yaml:339-356 | `trade:`/`iot:` 块 | 对应模块未引入 |
| application-dev.yaml:117-120 | `lock4j:` 块 | lock4j-core 不在 jar（`YudaoLock4jConfiguration` 因缺类不装配），配置永不生效 |

> **实施修正**：`wx:` 配置块**保留不删**——`application-dev.yaml`/`application-local.yaml` 的 justauth 块通过 `${wx.miniapp.appid}` 等占位符引用它（微信登录凭据来源），删除会导致占位符无法解析、启动失败。wx-java starter 依赖仍按 §4.2 移除（starter 消失后 `wx:` 块退化为纯数据，由 justauth 继续使用）。

### 4.2 移除无用依赖

- ~~`yudao-module-system/pom.xml` 删除 wx-java starter~~ **已撤销**：实施时验证构建发现 `SocialClientServiceImpl` 真实使用 wx-java（小程序手机号 `WxMaPhoneNumberInfo`、订阅消息 `WxMaSubscribeMessage`、`WxMpService` 公众号服务），且 `YudaoWxClientConfiguration` 配置其 Redis 存储。**wx-java 两个 starter 保留**（初始分析时 glob 锚定写法导致 grep 假阴性，教训：引用检查必须用 path 参数限定而非路径锚定 glob）。
- `yudao-module-infra`：删除 `demo` 子域（`controller/admin/demo/` 5 个 Controller + service/dal 配套 + `ErrorCodeConstants` 中 11 个 demo 错误码），vue3 对应 `views/infra/demo/`、`api/infra/demo/` 一并删除；菜单 1070（代码生成案例）及其 5 个子菜单的清理已并入 `sql/mysql/cleanup-upstream-menus.sql` 第四步（每次部署自动执行）。
  - 收益：消除演示接口暴露面，配合 §3 删表。
- **jar 残留核查（已执行）**：`ojdbc11`/`ucp`/`oracle-spring-boot-starter-ucp` 的来源已定位为 `dynamic-datasource-spring-boot3-starter:4.5.0` 的 compile 级传递依赖（非旧构建残留），已在 `yudao-spring-boot-starter-mybatis/pom.xml` 排除 starter 与 ucp 两项；验证结果：依赖 jar 264 → **261 个**，fat jar 163MB → **155MB**。

### 4.2b 可选项（有收益但有行为变化，单独评估）

- **部门数据权限**：`DeptDataPermissionRule` 生效中，每条 SQL 都经 jsqlparser 解析改写（有 5s caffeine 缓存缓解）。本项目 21 个用户/16 个部门，大概率用不到数据范围。无配置开关，只能通过 `spring.autoconfigure.exclude` 排除 `YudaoDataPermissionAutoConfiguration`——排除后「数据范围」功能失效，**需产品确认后执行**。
- **Tomcat 线程上限**：默认 max=200，对当前业务量（日请求 < 百）可收 `server.tomcat.threads.max: 100`，峰值内存更可控。
- **登录验证码**：`yudao.captcha.enable=false` 可省滑块底图加载与登录时的 Redis 往返，但管理端登录失去人机校验、前端登录页需配合，不建议在公网环境关闭。

### 4.3 不做什么（明确边界）

- **不删** member 的 point/level/signin/tag/group 端点：admin 有对应页面，需产品确认后再删，本方案只标记。
- **不动** `yudao.websocket`：system 公告推送（NoticeController → WebSocketSenderApi）是其唯一业务使用点，local sender 空闲时开销可忽略，关闭会导致 NoticeController 注入失败，收益风险不成比例。
- **不动** `yudao-spring-boot-starter-mq` 依赖声明：mq starter 本身是空壳（客户端 jar 均未传递进来），删除 pom 行对运行时无收益，留给将来接 MQ。
- **不动** POI/excel、easy-trans、captcha、JustAuth：admin 导出、登录验证码、微信登录均真实使用。

## 5. 第五阶段：仓库资产精简（-18.7MB，可选 -45MB）

直接可删（已验证零引用）：

| 目标 | 体积 | 证据 |
|---|---|---|
| `.image/` | 9.3MB | 100 张上游功能截图，全仓零引用 |
| `sql/{db2,dm,highgo,kingbase,opengauss,oracle,postgresql,sqlserver}/` + `sql/tools/` | ~9.3MB | 上游 convertor.py 产物，仅 tools 自引用；项目生产 MySQL 8.4 |
| `script/` 全目录 | 48K | CI/deploy/docs 零引用（jenkins/livekit-poc/idea/上游 docker 与 shell） |
| `sql/mysql/quartz.sql` | 42K | 零引用；Quartz 关闭后更无意义 |
| `yudao-ui/` 4 个空壳前端（vben/vue2/uniapp/mall-uniapp） | 16K | 每个目录只有一个指向 上游的 README |
| `.gitee/` | 4K | 仓库托管在 GitHub |

可选（单独决策）：12 个已注释的后端模块源码（`yudao-module-{ai,bpm,crm,erp,im,iot,mall,mes,mp,pay,report,wms}`，~26MB）。不参与构建、不影响运行时，但删除后：

- 部署 tarball 下载量从 ~85MB 级降到 ~40MB 级，**直接缩短 deploy-dev.yml 的 8 流竞速下载时间**；
- 代价：今后从上游合并对应功能需重新引入。若不打算启用这些模块，建议删。

**保留**：`sql/mysql/` 其余 6 个文件、`sql/incremental/`、`docs/` 全部、`mock-algorithm/`（真实算法接入前是调度链路的必需下游）、vue3 全部。

## 6. 收益汇总

| 维度 | 现状 | 实施后（预计） | 手段 |
|---|---|---|---|
| 系统可用内存 | free 449MB / swap 用 972MB | **free ≥ 1.3GB，swap 回落** | 停 MinIO +245MB、MySQL PFS off +80MB、Java 瘦身 ~100MB |
| Java 线程 | 120 | **~55** | Quartz off（-28）、Redisson 收缩（-37） |
| Java RSS | 808MB | **~700MB** | 线程/metaspace/bean 收缩 |
| MySQL 稳定性 | mysqld 被 swap 出 437MB | 常驻内存，无换出 | 内存释放 + swappiness=10 |
| 启动时间 | 20.8s | **~15~17s** | Quartz 启动消失/Druid stat 关闭/死 bean 减少 |
| 每请求开销 | druid stat 包装 + api-encrypt 二次 handler 查找 | 去除 | §1.3/§1.5 |
| 安全暴露面 | Druid 控制台、581KB api-docs、3306/6379/9000-9001/18080 公网开放 | 全部收敛 | §1.3/§1.4/§2.1/§2.3 |
| 磁盘 | 21G/40G | **-2.3GB** | journald vacuum + docker prune |
| 仓库体积 | ~85MB（工作区） | **-18.7MB**（可选再 -26MB） | §5 |
| 数据库 | 8.2MB（80% 是无界日志） | ~1.5MB + 周清理策略 | §3 |

## 7. 实施顺序（每步独立、可回滚）

1. **§1 服务器配置**（10 分钟）：改 `/opt/cargo-post/config/application-dev.yaml` → `systemctl restart cargo-post` → 验证 health/线程数/暴露面。回滚 = 还原文件重启。
2. **§3 数据库清理**（5 分钟，先 `deploy/scripts/backup.sh` 备份）。
3. **§2 容器与系统**（15 分钟）：改 compose（minio 注释、PFS off、端口绑定）→ `deploy/scripts/deploy.sh` → crontab 清理任务。
4. **§4 代码 PR**：yaml 死配置 + wx-java 依赖 + infra demo 删除 → CI（H2 单测 92 例）→ 部署。
5. **§5 资产 PR**：删除清单执行 → CI drift-check 通过即可。

冒烟验证清单（每阶段后）：小程序登录（短信 + 微信）、寄件下单、调度算法调用（transport → mock-algorithm）、管理后台登录 + 车辆监控页、文件上传（验证 DB 存储 master 生效）。

## 9. 执行记录（2026-08-19，全部完成）

### 已上线生效（开发服务器，均通过验证）

| 项 | 结果 |
|---|---|
| §1 配置精简 | 写入 `/opt/cargo-post/config/application-dev.yaml`（原文件备份 `.bak-20260819`）。线程 **120 → 58**，启动 **20.8s → 17.7s**，`/druid`、`/v3/api-docs` 已 404，captcha 与 `/app-api/transport/notice/list` 冒烟通过。Quartz 用 `spring.autoconfigure.exclude` 整体排除（`auto-startup: false` 实测杀不掉 25 个 worker，见 §1.1） |
| §3 数据库 | 备份 `backups/cargo_post_dev-20260819-114033.sql.gz` 后执行：infra_job 删 18 行、3 张日志表 TRUNCATE、5 张 demo 表 DROP、过期 token 删 145 行、文件存储 master 切 DB（id=4）、demo 菜单 1070+5 子菜单删除（含角色关联 21 行）。QRTZ 表保留 |
| §2 容器/系统 | MinIO 容器已停并随 compose 移除（-245MB RSS，9000/9001 公网口关闭）；MySQL `performance_schema=OFF`；3306/6379/18080 仅绑 127.0.0.1；`vm.swappiness=10` 已持久化；deploy 用户 crontab 每周日 03:17 跑 `deploy/scripts/cleanup.sql`（已实测执行成功）；系统 available 内存 1.9G → 2.0G（java RSS 808→711MB） |
| §2.5 磁盘 | journald/docker prune 实际释放 0B（原因见 §2.5），磁盘 53% 非瓶颈 |

### 代码改动（本地工作区，已验证未提交）

- `application.yaml` 13.0KB→7.4KB（删 flowable/MQ/AI/trade/iot 死配置，**消除泄漏的 AI key**）；`application-dev.yaml` 7.9KB→6.8KB（删 MQ/lock4j/SBA 死配置；`wx:` 块因 justauth 占位符引用而保留）
- 删除 infra demo 子域：后端 46 文件 + 前端 25 文件 + 11 个错误码；`cleanup-upstream-menus.sql` 增加第四步（每次部署自动清 demo 菜单）
- `yudao-spring-boot-starter-mybatis/pom.xml` 排除 dynamic-datasource 4.5.x 传递引入的 `oracle-spring-boot-starter-ucp` 与 `ucp`
- 仓库资产删除 143 文件（`git rm` 已暂存）：`.image/`、`.gitee/`、`script/`、`sql/` 8 个厂商目录 + `tools/`、`sql/mysql/quartz.sql`、4 个空壳前端
- **wx-java 两个 starter 保留**：验证构建推翻零引用初判（`SocialClientServiceImpl` 真实使用，见 §4.2）
- 验证：服务器隔离环境 `mvn clean package` **BUILD SUCCESS**，新 jar 隔离启动 18.2s、health UP；依赖 264→261 个、163MB→155MB
- **后续追加（2026-08-19 第二轮）**：12 个已停用后端模块源码（ai/bpm/crm/erp/im/iot/mall/mes/mp/pay/report/wms，4366 文件）已物理删除，根 pom 与 yudao-server pom 的注释占位一并清理；前端同步摘除 views/api 下 13 个上游模块死目录、4 个仅服务死页面的共享组件（DiyEditor/bpmnProcessDesigner/SimpleProcessDesignerV2/AppLinkInput）、2 个死 store、`remaining.ts` 静态路由 581 行死路由及 main.ts 的 bpm wangEditor 插件注册

### 提交后服务器协调（一次性）

1. 提交并 push 后 `deploy-dev.yml` 自动构建部署（本次含后端变更会触发全量构建）。
2. 服务器持久检出 `/opt/cargo-post-platform` 有本次手工改动（compose 与 cleanup.sql），push 后需在其根目录执行：
   ```bash
   git restore deploy/docker-compose.yml && rm -f deploy/scripts/cleanup.sql && git pull
   ```
   （手工内容与提交一致，restore/rm 仅为给 git 让路。）
3. `.env` 无需变更（`MINIO_*` 变量保留无害，恢复 MinIO 时仍需用到）。
