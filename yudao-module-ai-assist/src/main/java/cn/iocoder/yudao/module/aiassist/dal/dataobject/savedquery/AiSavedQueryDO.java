package cn.iocoder.yudao.module.aiassist.dal.dataobject.savedquery;

import cn.iocoder.yudao.framework.tenant.core.db.TenantBaseDO;
import com.baomidou.mybatisplus.annotation.KeySequence;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.*;

/**
 * 保存的查询条件 DO（契约 docs/AI_ASSIST_BOUNDARY.md §6 第 4 项）。
 *
 * <p>只存"结构化筛选条件"（conditions_json），不存结果快照：条件是可复核的输入，
 * 快照是滞后的事实。运营简报要的是"按这套条件再取一遍"，不是一份会过期的旧数字。
 * 本表是 AI 辅助自己的草稿，不碰任何业务表（契约 §0）。
 */
@TableName("ai_saved_query")
@KeySequence("ai_saved_query_seq")
@Data
@EqualsAndHashCode(callSuper = true)
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AiSavedQueryDO extends TenantBaseDO {

    @TableId
    private Long id;
    /** 名称 */
    private String name;
    /** 业务场景：ORDER 订单 / DISPATCH 调度 / FEEDBACK 反馈 / 其他 */
    private String scene;
    /** 结构化筛选条件（JSON 文本；本期不做服务端语义校验，只做长度与空值约束） */
    private String conditionsJson;
    /** 备注 */
    private String remark;

}
