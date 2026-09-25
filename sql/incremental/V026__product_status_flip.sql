-- V026: WEB-08 商品上下架枚举统一为 1=上架（与公告一致，原商品侧为 0=上架）
-- 存量数据翻转：0(上架)→1，1(下架)→0；新语义下 0=下架、1=上架
-- 幂等性：不可重复执行（翻转两次会还原）。仅作为一次性迁移；全新环境直接用最新 transport-schema.sql（已含新语义与 DEFAULT 1）。
UPDATE `transport_product` SET `status` = 1 - `status` WHERE `deleted` = 0;

-- 列注释与默认值对齐新语义
ALTER TABLE `transport_product`
  MODIFY COLUMN `status` tinyint NOT NULL DEFAULT 1 COMMENT '状态(0下架 1上架，WEB-08 与公告统一)';
