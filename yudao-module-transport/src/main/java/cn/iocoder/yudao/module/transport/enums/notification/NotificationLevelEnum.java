package cn.iocoder.yudao.module.transport.enums.notification;

/**
 * 通知级别（前端用于配色/是否强提醒）。
 */
public enum NotificationLevelEnum {

    /** 普通信息 */
    INFO,
    /** 成功/完成 */
    SUCCESS,
    /** 需要用户/司机操作（强提醒） */
    ACTION_REQUIRED,
    /** 警告 */
    WARNING,
    /** 异常 */
    EXCEPTION

}
