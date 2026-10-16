package cn.iocoder.yudao.module.aiassist.service.invocation;

import cn.iocoder.yudao.module.aiassist.dal.dataobject.invocation.AiInvocationLogDO;
import cn.iocoder.yudao.module.aiassist.dal.mysql.invocation.AiInvocationLogMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * AI 调用留痕的旁路契约（契约 §5）。
 *
 * <p>这张表的存在意义是"可见"，所以它自己的失败方式也必须是可见的、且**不伤主流程**。
 */
@ExtendWith(MockitoExtension.class)
class AiInvocationLogServiceImplTest {

    @Mock private AiInvocationLogMapper mapper;
    @InjectMocks private AiInvocationLogServiceImpl service;

    private static AiInvocationLogDO sample() {
        return AiInvocationLogDO.builder()
                .scene("digest").operatorId(1L).status("OK").maskedInput("未分配订单 12 单").build();
    }

    @Test
    void recordInsertsAndReturnsTrue() {
        when(mapper.insert(any(AiInvocationLogDO.class))).thenReturn(1);

        assertTrue(service.record(sample()));

        ArgumentCaptor<AiInvocationLogDO> cap = ArgumentCaptor.forClass(AiInvocationLogDO.class);
        verify(mapper).insert(cap.capture());
        assertEquals("digest", cap.getValue().getScene());
    }

    /**
     * 关键负控：落库抛异常时，record 必须**不抛出**（否则留痕本身会砸了业务页面），
     * 但也要如实返回 false（否则"失败被吞"）。
     */
    @Test
    void recordSwallowsFailureAndReturnsFalse() {
        when(mapper.insert(any(AiInvocationLogDO.class))).thenThrow(new RuntimeException("db down"));

        assertFalse(service.record(sample()));
    }

}
