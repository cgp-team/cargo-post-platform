package cn.iocoder.yudao.module.transport.service.notification;

import cn.iocoder.yudao.module.transport.dal.dataobject.dispatch.TransportUserNotificationDO;
import cn.iocoder.yudao.module.transport.dal.mysql.dispatch.TransportUserNotificationMapper;
import cn.iocoder.yudao.module.transport.enums.dispatch.TransportOrderEventTypeEnum;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * 用户通知单测：同一事件重复推送只落一条（幂等，需求 §88/§116）。
 */
@ExtendWith(MockitoExtension.class)
class UserNotificationServiceImplTest {

    @Mock private TransportUserNotificationMapper notificationMapper;

    private UserNotificationServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new UserNotificationServiceImpl();
        ReflectionTestUtils.setField(service, "notificationMapper", notificationMapper);
    }

    @Test
    void duplicate_event_is_idempotent() {
        // 第一次：查无同事件 → 落库
        when(notificationMapper.selectByEvent(any(), any(), any())).thenReturn(null);
        when(notificationMapper.insert(any(TransportUserNotificationDO.class))).thenAnswer(inv -> {
            TransportUserNotificationDO d = inv.getArgument(0);
            d.setId(100L);
            return 1;
        });
        Long first = service.send(NotificationSendDTO.builder()
                .recipientType(cn.iocoder.yudao.module.transport.enums.notification.NotificationRecipientTypeEnum.USER)
                .recipientId(1L)
                .eventType(TransportOrderEventTypeEnum.LEG_ARRIVED)
                .title("到达")
                .content("已到达")
                .orderId(9L)
                .build());
        assertEquals(100L, first);

        // 第二次：同事件已存在 → 直接返回已有编号，不再 insert
        TransportUserNotificationDO existing = TransportUserNotificationDO.builder().id(100L).build();
        when(notificationMapper.selectByEvent(any(), any(), any())).thenReturn(existing);
        Long second = service.send(NotificationSendDTO.builder()
                .recipientType(cn.iocoder.yudao.module.transport.enums.notification.NotificationRecipientTypeEnum.USER)
                .recipientId(1L)
                .eventType(TransportOrderEventTypeEnum.LEG_ARRIVED)
                .title("到达")
                .content("已到达")
                .orderId(9L)
                .build());
        assertEquals(100L, second);
        verify(notificationMapper, times(1)).insert(any(TransportUserNotificationDO.class));
    }

}
