package cn.iocoder.yudao.module.transport.dal.dataobject.dispatch;

import com.baomidou.mybatisplus.annotation.KeySequence;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.*;
import java.time.LocalDateTime;

@TableName("transport_order_event")
@KeySequence("transport_order_event_seq")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TransportOrderEventDO {

    @TableId
    private Long id;
    private Long orderId;
    private String eventType;
    private LocalDateTime eventTime;
    private String operator;
    private String detail;
    private String extraData;
    private Long tenantId;
    private LocalDateTime createTime;
}
