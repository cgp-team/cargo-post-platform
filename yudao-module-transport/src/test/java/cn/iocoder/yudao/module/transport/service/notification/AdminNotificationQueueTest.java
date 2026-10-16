package cn.iocoder.yudao.module.transport.service.notification;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.module.transport.dal.dataobject.dispatch.TransportUserNotificationDO;
import cn.iocoder.yudao.module.transport.dal.mysql.dispatch.TransportUserNotificationMapper;
import cn.iocoder.yudao.module.transport.enums.notification.NotificationLevelEnum;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static cn.iocoder.yudao.module.transport.enums.ErrorCodeConstants.NOTIFICATION_NOT_YOURS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 管理员告警队列那两格（队列 #92 / 台账 D-136）。
 *
 * 钉的是两件今天会静默出错的事：
 * · 这张表 USER / DRIVER / ADMIN **共用编号空间**（P2-L 的老病）：后台的"已处理"如果只比 id，
 *   运营点一下就能把某个用户或司机的通知标成已读——被标的那方从此看不见这条，且没有任何痕迹。
 * · 分档计数必须**各档之和等于总数**：如果总数是一条 count、各档是另外三条 count，
 *   中间任何一次改写查询条件都会让面板上的数字自己跟自己打架，而界面看不出来。
 */
@ExtendWith(MockitoExtension.class)
class AdminNotificationQueueTest {

    @Mock private TransportUserNotificationMapper notificationMapper;
    @InjectMocks private UserNotificationServiceImpl service;

    private static TransportUserNotificationDO admin(Long id, NotificationLevelEnum level, int readStatus) {
        return TransportUserNotificationDO.builder()
                .id(id).userId(0L).recipientType("ADMIN").level(level.name())
                .readStatus(readStatus).title("段异常").build();
    }

    @Test
    void unreadCountSumsLevelsIntoTotal() {
        when(notificationMapper.selectListUnreadByAdmin()).thenReturn(List.of(
                admin(1L, NotificationLevelEnum.EXCEPTION, 0),
                admin(2L, NotificationLevelEnum.EXCEPTION, 0),
                admin(3L, NotificationLevelEnum.WARNING, 0)));

        Map<String, Long> out = service.getAdminUnreadCount();

        assertEquals(3L, out.get("total"));
        assertEquals(2L, out.get("EXCEPTION"));
        assertEquals(1L, out.get("WARNING"));
        assertEquals(0L, out.get("INFO"));
        long sumOfLevels = java.util.Arrays.stream(NotificationLevelEnum.values())
                .mapToLong(lv -> out.get(lv.name())).sum();
        assertEquals(out.get("total"), sumOfLevels);
    }

    @Test
    void markingAUserRowAsProcessedIsRefusedAndWritesNothing() {
        when(notificationMapper.selectById(9L)).thenReturn(
                TransportUserNotificationDO.builder().id(9L).userId(13900001003L)
                        .recipientType("USER").readStatus(0).build());

        ServiceException ex = assertThrows(ServiceException.class, () -> service.markAdminRead(9L));

        assertEquals(NOTIFICATION_NOT_YOURS.getCode(), ex.getCode());
        verify(notificationMapper, never()).updateById(any(TransportUserNotificationDO.class));
    }

    @Test
    void markingAnAdminRowRecordsTheTimeAndReadStatus() {
        when(notificationMapper.selectById(7L)).thenReturn(admin(7L, NotificationLevelEnum.EXCEPTION, 0));

        service.markAdminRead(7L);

        ArgumentCaptor<TransportUserNotificationDO> cap = ArgumentCaptor.forClass(TransportUserNotificationDO.class);
        verify(notificationMapper).updateById(cap.capture());
        assertEquals(7L, cap.getValue().getId());
        assertEquals(1, cap.getValue().getReadStatus());
        assertNotNull(cap.getValue().getReadTime());
    }

    /** 重复点「已处理」不该再写一次库：已读就直接返回（这一格与幂等键是两层，各管各的） */
    @Test
    void secondAckIsANoOp() {
        when(notificationMapper.selectById(8L)).thenReturn(admin(8L, NotificationLevelEnum.WARNING, 1));

        service.markAdminRead(8L);

        verify(notificationMapper, never()).updateById(any(TransportUserNotificationDO.class));
    }
}
