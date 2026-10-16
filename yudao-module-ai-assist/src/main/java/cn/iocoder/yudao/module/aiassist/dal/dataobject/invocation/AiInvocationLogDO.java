package cn.iocoder.yudao.module.aiassist.dal.dataobject.invocation;

import cn.iocoder.yudao.framework.tenant.core.db.TenantBaseDO;
import com.baomidou.mybatisplus.annotation.KeySequence;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.*;

/**
 * AI 调用日志 DO（契约 docs/AI_ASSIST_BOUNDARY.md §5）。
 *
 * <p>这是本模块唯一"需要新增"的表。三条纪律：
 * <ol>
 *   <li>{@code maskedInput} 落库前**已经过 §4 的唯一脱敏出口**，本表永不存原文；</li>
 *   <li>写入是**旁路**的：落库失败不得阻断页面（见 {@code AiInvocationLogServiceImpl#record}），
 *       但失败必须可见，不能悄悄吞掉；</li>
 *   <li>{@code errorBrief} 不许只写"失败"，要写要点（否则这张表只能证明"出过事"，证不了出了什么事）。</li>
 * </ol>
 */
@TableName("ai_invocation_log")
@KeySequence("ai_invocation_log_seq")
@Data
@EqualsAndHashCode(callSuper = true)
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AiInvocationLogDO extends TenantBaseDO {

    @TableId
    private Long id;
    /** 场景：nl-query / ticket-review / capacity-brief / risk-card / digest */
    private String scene;
    /** 登录管理员编号（是操作人，不是 AI） */
    private Long operatorId;
    /** 送出去的内容（落库前已过 §4 脱敏出口，不许存原文） */
    private String maskedInput;
    /** 模型返回的文本 */
    private String output;
    /** 消耗 token 数 */
    private Integer tokens;
    /** 耗时（毫秒） */
    private Integer latencyMs;
    /** 状态：OK / RATE_LIMITED / ERROR / EMPTY */
    private String status;
    /** 失败要点（不许只写"失败"） */
    private String errorBrief;

}
