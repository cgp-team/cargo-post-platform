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
-- 注意：本脚本只影响 transport_order 的状态与"今天"的调度方案，不动会员、商品、站点、班次、司机等基础数据。

-- 1) 流程态订单 → 待入池（承运审核通过态），可被「一键演示」重新归集
UPDATE transport_order
SET status = 8, update_time = NOW()
WHERE status IN (1, 2, 3);

-- 2) 清掉"今天"生成的调度方案、运输段/交接与经停明细（方案状态：0待审核 1已下发 2执行中 3已完成）
--    默认只清创建于今天的记录；如需全清，把 CURDATE() 改成 '1970-01-01'。
--    顺序很重要：先清运输段/交接，再清方案，否则会留下"孤儿段"（方案没了、段还在），
--    下一次演示的可视化按 plan 查不到方案，段数却还在，口径就对不上了。
DELETE FROM transport_handover
WHERE plan_id IN (SELECT id FROM transport_dispatch_plan WHERE create_time >= CURDATE());

DELETE FROM transport_leg
WHERE plan_id IN (SELECT id FROM transport_dispatch_plan WHERE create_time >= CURDATE());

DELETE FROM transport_dispatch_plan_item
WHERE plan_id IN (SELECT id FROM transport_dispatch_plan WHERE create_time >= CURDATE());

DELETE FROM transport_dispatch_plan
WHERE create_time >= CURDATE();

-- 2.1) 历史遗留的孤儿段/孤儿交接（方案已被删除）一并清掉，保证体检口径为 0
DELETE l FROM transport_leg l
LEFT JOIN transport_dispatch_plan p ON p.id = l.plan_id
WHERE p.id IS NULL;

DELETE h FROM transport_handover h
LEFT JOIN transport_dispatch_plan p ON p.id = h.plan_id
WHERE p.id IS NULL;

-- 3) 只读校验（执行后可肉眼确认干净程度）
SELECT status, COUNT(*) AS cnt FROM transport_order GROUP BY status ORDER BY status;
SELECT id, status, total_distance, create_time FROM transport_dispatch_plan ORDER BY id DESC LIMIT 5;
