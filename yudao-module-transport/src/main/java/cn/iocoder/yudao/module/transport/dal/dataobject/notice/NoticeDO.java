package cn.iocoder.yudao.module.transport.dal.dataobject.notice;

import cn.iocoder.yudao.framework.tenant.core.db.TenantBaseDO;
import com.baomidou.mybatisplus.annotation.KeySequence;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.*;

@TableName("transport_notice")
@KeySequence("transport_notice_seq")
@Data
@EqualsAndHashCode(callSuper = true)
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class NoticeDO extends TenantBaseDO {
    @TableId
    private Long id;
    private String title;
    private String content;
    /** 状态(0下架 1上架) */
    private Integer status;
    /** 排序(小的在前) */
    private Integer sort;
}
