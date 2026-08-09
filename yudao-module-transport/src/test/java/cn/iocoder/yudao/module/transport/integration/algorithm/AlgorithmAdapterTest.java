package cn.iocoder.yudao.module.transport.integration.algorithm;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.module.transport.dal.dataobject.algorithm.AlgorithmRequestDO;
import cn.iocoder.yudao.module.transport.dal.mysql.algorithm.AlgorithmRequestMapper;
import cn.iocoder.yudao.module.transport.enums.algorithm.AlgorithmRequestStatusEnum;
import cn.iocoder.yudao.module.transport.integration.algorithm.dto.AlgorithmPlanReqDTO;
import cn.iocoder.yudao.module.transport.integration.algorithm.dto.AlgorithmPlanRespDTO;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static cn.iocoder.yudao.module.transport.enums.ErrorCodeConstants.ALGORITHM_SERVICE_UNAVAILABLE;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * 幂等用例：重复快照不重复创建业务任务、不覆盖已有结果（docs/test-cases.md）。
 */
@ExtendWith(MockitoExtension.class)
class AlgorithmAdapterTest {

    @Mock
    private AlgorithmClient algorithmClient;
    @Mock
    private AlgorithmRequestMapper algorithmRequestMapper;

    private final ObjectMapper objectMapper = new AlgorithmAdapterConfiguration().algorithmObjectMapper();
    private AlgorithmAdapter adapter;

    @BeforeEach
    void setUp() {
        AlgorithmProperties properties = new AlgorithmProperties();
        adapter = new AlgorithmAdapter(algorithmClient, algorithmRequestMapper, objectMapper, properties);
    }

    @Test
    void plan_first_call_persists_request_and_response() {
        when(algorithmRequestMapper.selectRecentBySnapshotHash(anyString(), any())).thenReturn(null);
        when(algorithmClient.plan(any())).thenAnswer(invocation -> {
            AlgorithmPlanReqDTO req = invocation.getArgument(0);
            return AlgorithmResultValidatorTest.feasibleResult(req);
        });

        AlgorithmPlanReqDTO request = AlgorithmClientTest.request();
        request.setRequestId(null);
        AlgorithmPlanRespDTO result = adapter.plan(request);

        assertEquals(AlgorithmPlanRespDTO.STATUS_FEASIBLE, result.getStatus());
        // 落库：先插入记录，再更新为 FEASIBLE 并保存响应（insert 与 update 为同一对象引用，状态以最终更新为准）
        ArgumentCaptor<AlgorithmRequestDO> insertCaptor = ArgumentCaptor.forClass(AlgorithmRequestDO.class);
        verify(algorithmRequestMapper).insert(insertCaptor.capture());
        assertNotNull(insertCaptor.getValue().getRequestId());
        assertNotNull(insertCaptor.getValue().getSnapshotHash());
        ArgumentCaptor<AlgorithmRequestDO> updateCaptor = ArgumentCaptor.forClass(AlgorithmRequestDO.class);
        verify(algorithmRequestMapper).updateById(updateCaptor.capture());
        assertEquals(AlgorithmRequestStatusEnum.FEASIBLE.getStatus(), updateCaptor.getValue().getStatus());
        assertNotNull(updateCaptor.getValue().getResponseJson());
        assertEquals("mock-2.0.0", updateCaptor.getValue().getAlgorithmVersion());
    }

    @Test
    void plan_same_snapshot_within_window_reuses_result() throws Exception {
        AlgorithmPlanRespDTO stored = AlgorithmResultValidatorTest.feasibleResult(AlgorithmClientTest.request());
        stored.setRequestId("req-stored");
        AlgorithmRequestDO existing = AlgorithmRequestDO.builder()
                .requestId("req-stored")
                .responseJson(objectMapper.writeValueAsString(stored))
                .status(AlgorithmRequestStatusEnum.FEASIBLE.getStatus())
                .build();
        when(algorithmRequestMapper.selectRecentBySnapshotHash(anyString(), any())).thenReturn(existing);

        AlgorithmPlanReqDTO request = AlgorithmClientTest.request();
        request.setRequestId(null);
        AlgorithmPlanRespDTO result = adapter.plan(request);

        assertEquals("req-stored", result.getRequestId());
        assertEquals(Boolean.TRUE, result.getCached());
        verify(algorithmClient, never()).plan(any());
        verify(algorithmRequestMapper, never()).insert(any(AlgorithmRequestDO.class));
    }

    @Test
    void plan_previous_failure_does_not_block_new_request() {
        AlgorithmRequestDO existing = AlgorithmRequestDO.builder()
                .requestId("req-failed").responseJson(null)
                .status(AlgorithmRequestStatusEnum.FAILED.getStatus())
                .build();
        when(algorithmRequestMapper.selectRecentBySnapshotHash(anyString(), any())).thenReturn(existing);
        when(algorithmClient.plan(any())).thenAnswer(invocation -> {
            AlgorithmPlanReqDTO req = invocation.getArgument(0);
            return AlgorithmResultValidatorTest.feasibleResult(req);
        });

        AlgorithmPlanReqDTO request = AlgorithmClientTest.request();
        request.setRequestId(null);
        AlgorithmPlanRespDTO result = adapter.plan(request);

        assertEquals(AlgorithmPlanRespDTO.STATUS_FEASIBLE, result.getStatus());
        verify(algorithmClient).plan(any());
        verify(algorithmRequestMapper).insert(any(AlgorithmRequestDO.class));
    }

    @Test
    void plan_client_failure_marks_record_failed() {
        when(algorithmRequestMapper.selectRecentBySnapshotHash(anyString(), any())).thenReturn(null);
        when(algorithmClient.plan(any())).thenThrow(
                cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception(ALGORITHM_SERVICE_UNAVAILABLE));

        AlgorithmPlanReqDTO request = AlgorithmClientTest.request();
        request.setRequestId(null);
        ServiceException ex = assertThrows(ServiceException.class, () -> adapter.plan(request));

        assertEquals(ALGORITHM_SERVICE_UNAVAILABLE.getCode(), ex.getCode());
        ArgumentCaptor<AlgorithmRequestDO> updateCaptor = ArgumentCaptor.forClass(AlgorithmRequestDO.class);
        verify(algorithmRequestMapper).updateById(updateCaptor.capture());
        assertEquals(AlgorithmRequestStatusEnum.FAILED.getStatus(), updateCaptor.getValue().getStatus());
        assertEquals(String.valueOf(ALGORITHM_SERVICE_UNAVAILABLE.getCode()), updateCaptor.getValue().getErrorCode());
        assertNull(updateCaptor.getValue().getResponseJson());
    }

    @Test
    void plan_rejects_caller_supplied_request_id() {
        AlgorithmPlanReqDTO request = AlgorithmClientTest.request();
        assertThrows(IllegalArgumentException.class, () -> adapter.plan(request));
        verifyNoInteractions(algorithmClient);
    }

}
