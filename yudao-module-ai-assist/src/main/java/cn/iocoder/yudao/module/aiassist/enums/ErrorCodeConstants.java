package cn.iocoder.yudao.module.aiassist.enums;

import cn.iocoder.yudao.framework.common.exception.ErrorCode;

/**
 * AI 辅助模块错误码（1-006-xxx-xxx）。
 *
 * <p>与 transport(1-005-xxx-xxx)、member(1-004-xxx-xxx) 分段，避免与既有模块撞码。
 */
public interface ErrorCodeConstants {

    // ========== 客货空间冲突检视 1-006-002-000 ==========
    ErrorCode CAPACITY_CONFLICT_INPUT_EMPTY = new ErrorCode(1_006_002_000,
            "检视输入不完整：至少给一辆带核定容量的车，以及至少一段预报量（没有这两样就无从判定）");
    ErrorCode CAPACITY_CONFLICT_NEGATIVE = new ErrorCode(1_006_002_001, "检视输入含负数：{}");

    // ========== 查询构造器（存条件）1-006-003-000 ==========
    ErrorCode SAVED_QUERY_NOT_EXISTS = new ErrorCode(1_006_003_000, "保存的查询条件不存在");

}
