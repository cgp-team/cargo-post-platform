package cn.iocoder.yudao.module.transport.dal.dataobject.dispatch;

import com.baomidou.mybatisplus.annotation.KeySequence;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 乡镇事件日历（赶集/农忙/节假日/活动）。
 *
 * <p>它是"情景运力"那类提示的**唯一数据来源**：平台原来只有订单历史，而订单是滞后量
 * （乡镇通常先赶集、才产生寄件），所以"看见订单再准备"永远慢一拍。
 */
@TableName("transport_town_event_calendar")
@KeySequence("transport_town_event_calendar_seq")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TownEventCalendarDO {

    @TableId
    private Long id;

    /** 乡镇/场镇名：本期是自由文本，不与站点建外键（站点表里没有"场镇"这一层） */
    private String town;

    /** MARKET / FARMING / HOLIDAY / EVENT */
    private String eventType;

    /** 起始日（含） */
    private LocalDate startDate;

    /** 结束日（含）；单日事件与 startDate 相同 */
    private LocalDate endDate;

    /** NONE / EVERY_N_DAYS —— 见 {@code TownEventCalendarServiceImpl} 的理由 */
    private String recurrence;

    /** recurrence=EVERY_N_DAYS 时的间隔天数；其余为 0 */
    private Integer intervalDays;

    private String note;

    private Long tenantId;
    private String creator;
    private LocalDateTime createTime;
    private String updater;
    private LocalDateTime updateTime;
    private Boolean deleted;
}
