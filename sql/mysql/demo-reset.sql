-- 演示前复位（手工执行；**默认不删除任何数据**，只把流程态订单放回"待入池"）
--
-- 用途：把上一次演示/联调留下的 已入池(1)/已分配(2)/已发车(3) 订单放回 待入池(8)，
--       让后台「一键演示（归集→调度→审核→核验）」从干净状态跑一遍；同时清掉"今天"的调度方案，
--       避免方案列表里堆着旧方案（历史方案保留，便于对比）。
--
-- 执行（服务器仓库根目录）：
--   set -a; source /opt/cargo-post-platform/.env; set +a
--   docker compose --env-file /opt/cargo-post-platform/.env -f deploy/docker-compose.yml \
--     exec -T mysql mysql --default-character-set=utf8mb4 -u root -p"${DB_PASSWORD}" "${DB_NAME}" < sql/mysql/demo-reset.sql
--
-- 注意：本脚本只影响 transport_order 的状态、transport_dispatch_plan(_item)、transport_leg、
--       transport_handover，不动会员、商品、站点、班次、司机等基础数据。

-- 1) 流程态订单 → 待入池（承运审核通过态），可被「一键演示」重新归集
UPDATE transport_order
SET status = 8, update_time = NOW()
WHERE status IN (1, 2, 3);

-- 2) 同步清理「运输段 + 换乘交接 + 方案」，避免孤儿段被下一次调度复用（P1-D）。
--    顺序必须是 交接 → 段 → 明细 → 方案（后者被前者引用）。
--    交接清理口径：属于"今天"方案，或引用的段属于"今天"方案/孤儿段；
--    段清理口径：属于"今天"方案，或已指向不存在方案的孤儿段（跨天残留的孤儿一起兜底清掉）。
DELETE FROM transport_handover
WHERE plan_id IN (SELECT id FROM transport_dispatch_plan WHERE create_time >= CURDATE())
   OR leg_from_id IN (SELECT id FROM transport_leg
                      WHERE plan_id IN (SELECT id FROM transport_dispatch_plan WHERE create_time >= CURDATE())
                         OR plan_id IS NULL
                         OR plan_id NOT IN (SELECT id FROM transport_dispatch_plan))
   OR leg_to_id   IN (SELECT id FROM transport_leg
                      WHERE plan_id IN (SELECT id FROM transport_dispatch_plan WHERE create_time >= CURDATE())
                         OR plan_id IS NULL
                         OR plan_id NOT IN (SELECT id FROM transport_dispatch_plan));

DELETE FROM transport_leg
WHERE plan_id IN (SELECT id FROM transport_dispatch_plan WHERE create_time >= CURDATE())
   OR plan_id IS NULL
   OR plan_id NOT IN (SELECT id FROM transport_dispatch_plan);

DELETE FROM transport_dispatch_plan_item
WHERE plan_id IN (SELECT id FROM transport_dispatch_plan WHERE create_time >= CURDATE());

-- 默认只清"今天"创建的方案；如需全量清理（含跨天残留的 plan 1/28/29 等），
-- 把下面两处的 CURDATE() 改成 '1970-01-01' 再执行。
DELETE FROM transport_dispatch_plan
WHERE create_time >= CURDATE();

-- 3) 只读校验（执行后可肉眼确认干净程度）
SELECT status, COUNT(*) AS cnt FROM transport_order GROUP BY status ORDER BY status;
SELECT id, status, total_distance, create_time FROM transport_dispatch_plan ORDER BY id DESC LIMIT 5;

-- 4) 体检（P1-D：执行后下面三项必须全为 0，否则演示会踩 P0-A/错位问题）
SELECT 'orphan_legs' AS check_item, COUNT(*) AS cnt FROM transport_leg l
LEFT JOIN transport_dispatch_plan p ON p.id = l.plan_id WHERE p.id IS NULL;

SELECT 'plan_leg_mismatch' AS check_item, COUNT(*) AS cnt FROM (
  SELECT p.id FROM transport_dispatch_plan p
  LEFT JOIN transport_leg l ON l.plan_id = p.id
  GROUP BY p.id, p.total_leg_count HAVING COUNT(l.id) <> p.total_leg_count
) t;

SELECT 'inflight_no_leg' AS check_item, COUNT(*) AS cnt FROM transport_order o
WHERE o.status IN (2, 3)
  AND NOT EXISTS (SELECT 1 FROM transport_leg l WHERE l.order_id = o.id);
