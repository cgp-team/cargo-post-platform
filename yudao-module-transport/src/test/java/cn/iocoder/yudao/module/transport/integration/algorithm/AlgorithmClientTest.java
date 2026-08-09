package cn.iocoder.yudao.module.transport.integration.algorithm;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.module.transport.integration.algorithm.dto.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;

import static cn.iocoder.yudao.module.transport.enums.ErrorCodeConstants.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.springframework.http.HttpMethod.GET;
import static org.springframework.http.HttpMethod.POST;

/**
 * 对照 docs/test-cases.md 的算法 Mock 场景与幂等/重试用例。
 */
class AlgorithmClientTest {

    private static final String BASE_URL = "http://algorithm";
    private static final String PLAN_URL = BASE_URL + "/api/v1/plan";
    private static final String RESULT_URL = BASE_URL + "/api/v1/result/req-test-1";

    private AlgorithmClient client;
    private MockRestServiceServer mockServer;

    @BeforeEach
    void setUp() {
        AlgorithmProperties properties = new AlgorithmProperties();
        properties.setBaseUrl(BASE_URL);
        properties.setPollInterval(Duration.ZERO);
        properties.setRetryBackoff(Duration.ZERO);
        properties.setMaxRetries(2);
        properties.setMaxPollAttempts(3);
        AlgorithmAdapterConfiguration configuration = new AlgorithmAdapterConfiguration();
        RestTemplate restTemplate = configuration.algorithmRestTemplate(properties);
        mockServer = MockRestServiceServer.bindTo(restTemplate).build();
        client = new AlgorithmClient(restTemplate, properties);
    }

    @Test
    void plan_success() {
        mockServer.expect(requestTo(PLAN_URL)).andExpect(method(POST))
                .andRespond(withSuccess(feasibleJson(), MediaType.APPLICATION_JSON));

        AlgorithmPlanRespDTO result = client.plan(request());

        assertEquals(AlgorithmPlanRespDTO.STATUS_FEASIBLE, result.getStatus());
        assertEquals("mock-2.0.0", result.getAlgorithmVersion());
        assertEquals(1, result.getVehiclePlans().size());
        mockServer.verify();
    }

    @Test
    void plan_infeasible_via_200() {
        mockServer.expect(requestTo(PLAN_URL)).andExpect(method(POST))
                .andRespond(withSuccess(infeasibleJson("TIMING_CONFLICT"), MediaType.APPLICATION_JSON));

        AlgorithmPlanRespDTO result = client.plan(request());

        assertEquals(AlgorithmPlanRespDTO.STATUS_INFEASIBLE, result.getStatus());
        assertEquals("TIMING_CONFLICT", result.getReasonCode());
        mockServer.verify();
    }

    @Test
    void plan_timeout_then_poll_until_result() {
        mockServer.expect(requestTo(PLAN_URL)).andExpect(method(POST))
                .andRespond(withStatus(HttpStatus.REQUEST_TIMEOUT).contentType(MediaType.APPLICATION_JSON)
                        .body(errorJson("TIMEOUT", "计算超时")));
        mockServer.expect(requestTo(RESULT_URL)).andExpect(method(GET))
                .andRespond(withStatus(HttpStatus.ACCEPTED).contentType(MediaType.APPLICATION_JSON)
                        .body("{\"requestId\":\"req-test-1\",\"status\":\"computing\"}"));
        mockServer.expect(requestTo(RESULT_URL)).andExpect(method(GET))
                .andRespond(withSuccess(feasibleJson(), MediaType.APPLICATION_JSON));

        AlgorithmPlanRespDTO result = client.plan(request());

        assertEquals(AlgorithmPlanRespDTO.STATUS_FEASIBLE, result.getStatus());
        mockServer.verify();
    }

    @Test
    void plan_infeasible_via_422_compat() {
        mockServer.expect(requestTo(PLAN_URL)).andExpect(method(POST))
                .andRespond(withStatus(HttpStatus.UNPROCESSABLE_ENTITY).contentType(MediaType.APPLICATION_JSON)
                        .body("{\"code\":\"INFEASIBLE\",\"message\":\"业务不可行\",\"requestId\":\"req-test-1\","
                                + "\"details\":{\"reasonCode\":\"OVER_CAPACITY\"}}"));

        AlgorithmPlanRespDTO result = client.plan(request());

        assertEquals(AlgorithmPlanRespDTO.STATUS_INFEASIBLE, result.getStatus());
        assertEquals("OVER_CAPACITY", result.getReasonCode());
        mockServer.verify();
    }

    @Test
    void plan_422_without_details_falls_back_to_error_code() {
        mockServer.expect(requestTo(PLAN_URL)).andExpect(method(POST))
                .andRespond(withStatus(HttpStatus.UNPROCESSABLE_ENTITY).contentType(MediaType.APPLICATION_JSON)
                        .body(errorJson("INFEASIBLE", "业务不可行")));

        AlgorithmPlanRespDTO result = client.plan(request());

        // 无 details 时回退标准错误码，保证归一结果必带 reasonCode
        assertEquals(AlgorithmPlanRespDTO.STATUS_INFEASIBLE, result.getStatus());
        assertEquals("INFEASIBLE", result.getReasonCode());
        mockServer.verify();
    }

    @Test
    void plan_400_not_retryable() {
        mockServer.expect(requestTo(PLAN_URL)).andExpect(method(POST))
                .andRespond(withStatus(HttpStatus.BAD_REQUEST).contentType(MediaType.APPLICATION_JSON)
                        .body(errorJson("INVALID_INPUT", "至少需要一台可用车辆")));

        ServiceException ex = assertThrows(ServiceException.class, () -> client.plan(request()));
        assertEquals(ALGORITHM_INVALID_INPUT.getCode(), ex.getCode());
        mockServer.verify();
    }

    @Test
    void plan_413_over_limit_not_retryable() {
        mockServer.expect(requestTo(PLAN_URL)).andExpect(method(POST))
                .andRespond(withStatus(HttpStatus.PAYLOAD_TOO_LARGE).contentType(MediaType.APPLICATION_JSON)
                        .body(errorJson("OVER_LIMIT", "超出规模上限")));

        ServiceException ex = assertThrows(ServiceException.class, () -> client.plan(request()));
        assertEquals(ALGORITHM_OVER_LIMIT.getCode(), ex.getCode());
        mockServer.verify();
    }

    @Test
    void plan_503_retry_then_success() {
        mockServer.expect(requestTo(PLAN_URL)).andExpect(method(POST))
                .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE).contentType(MediaType.APPLICATION_JSON)
                        .body(errorJson("SERVICE_BUSY", "服务繁忙")));
        mockServer.expect(requestTo(PLAN_URL)).andExpect(method(POST))
                .andRespond(withSuccess(feasibleJson(), MediaType.APPLICATION_JSON));

        AlgorithmPlanRespDTO result = client.plan(request());

        assertEquals(AlgorithmPlanRespDTO.STATUS_FEASIBLE, result.getStatus());
        mockServer.verify();
    }

    @Test
    void plan_503_exhausted_then_unavailable() {
        for (int i = 0; i < 3; i++) { // 首次 + 2 次重试
            mockServer.expect(requestTo(PLAN_URL)).andExpect(method(POST))
                    .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE).contentType(MediaType.APPLICATION_JSON)
                            .body(errorJson("SERVICE_BUSY", "服务繁忙")));
        }

        ServiceException ex = assertThrows(ServiceException.class, () -> client.plan(request()));
        assertEquals(ALGORITHM_SERVICE_UNAVAILABLE.getCode(), ex.getCode());
        mockServer.verify();
    }

    @Test
    void plan_500_internal_error_not_retryable() {
        mockServer.expect(requestTo(PLAN_URL)).andExpect(method(POST))
                .andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR).contentType(MediaType.APPLICATION_JSON)
                        .body(errorJson("ALGORITHM_INTERNAL_ERROR", "Mock internal error")));

        ServiceException ex = assertThrows(ServiceException.class, () -> client.plan(request()));
        assertEquals(ALGORITHM_CALL_FAILED.getCode(), ex.getCode());
        mockServer.verify();
    }

    @Test
    void plan_poll_exhausted_then_timeout() {
        mockServer.expect(requestTo(PLAN_URL)).andExpect(method(POST))
                .andRespond(withStatus(HttpStatus.REQUEST_TIMEOUT).contentType(MediaType.APPLICATION_JSON)
                        .body(errorJson("TIMEOUT", "计算超时")));
        for (int i = 0; i < 3; i++) { // maxPollAttempts = 3
            mockServer.expect(requestTo(RESULT_URL)).andExpect(method(GET))
                    .andRespond(withStatus(HttpStatus.ACCEPTED).contentType(MediaType.APPLICATION_JSON)
                            .body("{\"requestId\":\"req-test-1\",\"status\":\"computing\"}"));
        }

        ServiceException ex = assertThrows(ServiceException.class, () -> client.plan(request()));
        assertEquals(ALGORITHM_TASK_TIMEOUT.getCode(), ex.getCode());
        mockServer.verify();
    }

    @Test
    void plan_poll_404_result_not_found() {
        mockServer.expect(requestTo(PLAN_URL)).andExpect(method(POST))
                .andRespond(withStatus(HttpStatus.REQUEST_TIMEOUT).contentType(MediaType.APPLICATION_JSON)
                        .body(errorJson("TIMEOUT", "计算超时")));
        mockServer.expect(requestTo(RESULT_URL)).andExpect(method(GET))
                .andRespond(withStatus(HttpStatus.NOT_FOUND).contentType(MediaType.APPLICATION_JSON)
                        .body(errorJson("REQUEST_NOT_FOUND", "requestId 不存在")));

        ServiceException ex = assertThrows(ServiceException.class, () -> client.plan(request()));
        assertEquals(ALGORITHM_RESULT_NOT_FOUND.getCode(), ex.getCode());
        mockServer.verify();
    }

    // ========== 测试数据 ==========

    static AlgorithmPlanReqDTO request() {
        return AlgorithmPlanReqDTO.builder()
                .requestId("req-test-1")
                .batchStart(OffsetDateTime.parse("2026-08-09T08:00:00+08:00"))
                .batchEnd(OffsetDateTime.parse("2026-08-09T08:30:00+08:00"))
                .depot(station("S0"))
                .stations(List.of(station("S0"), station("S1"), station("S2")))
                .vehicles(List.of(AlgorithmVehicleDTO.builder()
                        .vehicleId(1001L).passengerCapacity(5).cargoCapacity(4).build()))
                .orders(List.of(
                        AlgorithmOrderDTO.builder().orderId("O-P001").orderType(AlgorithmOrderDTO.TYPE_PASSENGER)
                                .boardingStationId("S1").alightingStationId("S2").build(),
                        AlgorithmOrderDTO.builder().orderId("O-D001").orderType(AlgorithmOrderDTO.TYPE_DELIVERY)
                                .stationId("S2").itemCount(1).build()))
                .build();
    }

    static AlgorithmStationDTO station(String stationId) {
        return AlgorithmStationDTO.builder().stationId(stationId).longitude(104.06).latitude(30.57).build();
    }

    static String feasibleJson() {
        return "{\"requestId\":\"req-test-1\",\"status\":\"feasible\",\"cached\":false,"
                + "\"algorithmVersion\":\"mock-2.0.0\",\"parameterVersion\":\"default-v1\",\"totalDistance\":1.0,"
                + "\"vehiclePlans\":[{\"vehicleId\":1001,\"totalDistance\":1.0,\"stops\":["
                + "{\"stationId\":\"S0\",\"action\":\"DEPART\",\"segmentDistance\":0.0},"
                + "{\"stationId\":\"S1\",\"orderId\":\"O-P001\",\"action\":\"BOARD\",\"segmentDistance\":0.5},"
                + "{\"stationId\":\"S2\",\"orderId\":\"O-P001\",\"action\":\"ALIGHT\",\"segmentDistance\":0.3},"
                + "{\"stationId\":\"S2\",\"orderId\":\"O-D001\",\"action\":\"DELIVER\",\"segmentDistance\":0.0},"
                + "{\"stationId\":\"S0\",\"action\":\"RETURN\",\"segmentDistance\":0.2}]}],"
                + "\"computedAt\":\"2026-08-09T08:00:05+08:00\"}";
    }

    static String infeasibleJson(String reasonCode) {
        return "{\"requestId\":\"req-test-1\",\"status\":\"infeasible\",\"reasonCode\":\"" + reasonCode + "\","
                + "\"cached\":false,\"algorithmVersion\":\"mock-2.0.0\",\"parameterVersion\":\"default-v1\","
                + "\"computedAt\":\"2026-08-09T08:00:05+08:00\"}";
    }

    static String errorJson(String code, String message) {
        return "{\"code\":\"" + code + "\",\"message\":\"" + message + "\",\"requestId\":\"req-test-1\"}";
    }

}
