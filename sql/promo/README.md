# 宣传片演示数据（sql/promo）

面向宣传片拍摄的整套造数：重庆真实公交线网 + 自建客货邮线路 + 45 天历史运营 + 拍摄当日的"正在运行"实况。

## 执行顺序（首次全量导入）

在**服务器**上按编号执行（01 → 22）：

```bash
set -a; source /opt/cargo-post-platform/.env; set +a
M="docker compose --env-file /opt/cargo-post-platform/.env -f /opt/cargo-post-platform/deploy/docker-compose.yml"
for f in 01-base-fleet 02-base-cqupt-stations 03-base-cqupt-vehicles 04-base-multi-leg \
         05-base-real-bus-network 06-base-real-bus-shifts 07-base-demo-orders 08-base-shaping-fix \
         09-base-fleet-final 10-members-products 20-history-orders 21-today-live 22-extras 23-polish; do
  echo "=== $f ==="
  $M exec -T mysql mysql --default-character-set=utf8mb4 -u root -p"${DB_PASSWORD}" "${DB_NAME}" \
    < "sql/promo/${f}.sql"
done
```

## 拍摄前刷新（重要）

车辆实时位置（15 分钟窗口）、班次执行、方案时间轴都相对"执行时刻"生成。
**开拍前 5 分钟在本机跑一遍**：

```bash
bash sql/promo/90-refresh-live.sh   # 走 SSH 重跑 21-today-live.sql
```

建议拍摄时段：**上午 10 点后、下午 17 点前**——此时大屏同时有"已完成/在途/待发"三种班次，
早班方案已完结、执行中方案正在跨车交接、下午方案待审核，流水线全状态齐活。

## 演示账号

| 角色 | 账号 | 密码 | 说明 |
|---|---|---|---|
| 后台管理员 | admin | admin123 | 管理端/大屏 |
| 小程序用户 | 13800000000 | 123456 | 「我的寄货」有今日+历史订单、消息中心有通知 |
| 司机端 | 13800138004（赵德柱）/ 13800138006（刘明轩）/ 13800138009（吴海涛） | 123456 | 执行中方案 910002 的三位承运司机 |
| 自建线司机 | 13900001001~3 | 123456 | CQUPT 车辆 |

## 数据分层与 ID 段

| 层 | 文件 | ID 段 |
|---|---|---|
| 基础（站点/线路/车辆/司机/班次/订单池） | 01~09 | 历史脚本原生 ID（1~999、40x、62xxx 等） |
| 会员/商品 | 10 | mobile 唯一键 / 商品 1~12 |
| 历史订单（45 天，已完成） | 20 | 900000~909999 |
| 当日实况（订单/方案/段/交接/位置/轨迹/通知/事件） | 21 | 910000~919999（自清理） |
| 公告/反馈/商城订单 | 22 | 920000+（自清理） |
| 细节层（核验/日志/时间线/待审核单/系统公告） | 23 | 913700+、914000+、920000+、930000+（自清理，须在 21 之后跑） |

## 拍完清理

```bash
$M exec -T mysql mysql --default-character-set=utf8mb4 -u root -p"${DB_PASSWORD}" "${DB_NAME}" \
  < sql/promo/99-purge.sql
```

## 注意

- 商品 `status=1` 为上架（V026 新语义），与旧 demo 脚本相反，勿混抄。
- 07 里的演示池订单时间窗为「当天全天」，隔天拍摄直接重跑 07+21 即可。
- 若只需刷新地图车辆位置而不动订单/方案：重跑 21 即可（它自清理 910000+ 段）。

## 去演示化（2026-10-11 补充）

- 订单池单号已改为日期流水（TPyyyymmddP01…），货物信息里的 "Demo/演示" 字样已清洗（23 号脚本第 8 节，随刷新重跑）。
- 管理端「站点/线路管理」列表的「来源」列已从前端移除；新增表单选项改为「客货邮驿站/公交站点」「客货邮专线/公交线路」（`yudao-ui/.../transport/station|route`），已重新构建发布。
- 12 个商品全部有实拍图：`/opt/cargo-post-web/images/products/*.jpg`（本机 nginx 托管，不依赖外网）；
  图源 Wikimedia Commons / themealdb，已压缩至 800px。如需换图：替换同名文件即可，数据库不用动。
- 前端改动未提交 git（本地 dist 直接 rsync 到服务器）；回滚点在服务器 `/tmp/cargo-post-web.bak-promo`。
