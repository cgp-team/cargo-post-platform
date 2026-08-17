package cn.iocoder.yudao.module.transport.dal.dataobject.feedback;

import cn.iocoder.yudao.framework.tenant.core.db.TenantBaseDO;
import com.baomidou.mybatisplus.annotation.KeySequence;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.*;

import java.time.LocalDateTime;

@TableName("transport_feedback")
@KeySequence("transport_feedback_seq")
@Data
@EqualsAndHashCode(callSuper = true)
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FeedbackDO extends TenantBaseDO {
    @TableId
    private Long id;
    /** 会员编号 */
    private Long userId;
    private String name;
    private String mobile;
    private String content;
    /** 状态(0待处理 1已回复) */
    private Integer status;
    private String reply;
    private LocalDateTime replyTime;
}
