-- V029：真实执行回流（任务 #34）—— 明细级实际时刻 + 本批未派发货运单留痕。
--
-- 为什么要这两处列，而不是"从别处推"：
-- 1) `transport_dispatch_plan_item` 此前只有 `estimated_arrival_time / planned_departure_time`
--    （都是计划值），司机上报只改 `status`（`DriverAppServiceImpl.syncTaskItemStatusOnArrive`）。
--    ⇒ 算法承诺的时效（#32 刚把 deliveryDeadline 送进算法）在真实世界里没有任何"实际值"可对账，
--    迟到率、每站实际停留、绕行代价校准全都无法测量。
-- 2) 算法返回的 `unassignedOrderIds` 此前只被用来判空 + 拼一句解释文案，**没有落库**
--    （`DispatchServiceImpl` 里 unassignedFromAlgo 只出现在 isEmpty 判断）。
--    ⇒ "这批为什么少带了两单"事后查不到，也无法回流成度量台的题（#33 的语料缺口）。
--
-- 只加可空列，不动既有语义、不回填历史行：历史方案没有"实际上报"这回事，
-- 用计划值或当前时间冒充实际值正是要避免的那种假数据。
--
-- 新库直接 SOURCE sql/mysql/transport-schema.sql 即可（两边列已对齐）；
-- 已存在的库请人工执行下面两段 ALTER。

ALTER TABLE `transport_dispatch_plan_item`
  ADD COLUMN `actual_arrival_time` datetime DEFAULT NULL
      COMMENT '实际上报时刻：PICKUP=司机完成装车、DELIVERY=司机完成妥投，其余经停=司机到站；没上报就是 NULL（不拿计划冒充实际）' AFTER `estimated_arrival_time`,
  ADD COLUMN `actual_finish_time` datetime DEFAULT NULL
      COMMENT '明细完成时刻（状态被推进到已完成/失败时落地；未完成为 NULL）' AFTER `actual_arrival_time`;

ALTER TABLE `transport_dispatch_plan`
  ADD COLUMN `unassigned_orders` text
      COMMENT '本批未派发的货运单与原因码（JSON 数组 [{"orderId":12,"reasonCode":"CAPACITY_OVERFLOW"}]）。乘客单受业务底线保护永不舍弃，故不在此列。NULL=本批没有未派单' AFTER `plan_reason`;
