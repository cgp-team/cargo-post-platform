package cn.iocoder.yudao.module.transport.integration.algorithm;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.module.transport.integration.algorithm.dto.AlgorithmErrorRespDTO;
import cn.iocoder.yudao.module.transport.integration.algorithm.dto.AlgorithmPlanReqDTO;
import cn.iocoder.yudao.module.transport.integration.algorithm.dto.AlgorithmPlanRespDTO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

import java.time.OffsetDateTime;

import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.module.transport.enums.ErrorCodeConstants.*;

/**
 * 算法服务 HTTP 客户端：封装 POST /api/v1/plan 与 GET /api/v1/result/{requestId}。
 *
 * 语义（契约 v0.2.0）：
 * - 408：计算超时，不重复提交，改为凭 requestId 轮询结果；
 * - 502/503/504 与网络错误：有上限退避重试（POST 因 requestId 幂等而安全）；
 * - 400/413：不可重试，直接抛业务异常；
 * - 422：契约存在"200 + status=infeasible"与 422 并存的未决矛盾，兼容处理为无解结果。
 */
@Component
@Slf4j
public class AlgorithmClient {

    private final RestTemplate restTemplate;
    private final AlgorithmProperties properties;

    public AlgorithmClient(@Qualifier(AlgorithmAdapterConfiguration.ALGORITHM_REST_TEMPLATE) RestTemplate restTemplate,
                           AlgorithmProperties properties) {
        this.restTemplate = restTemplate;
        this.properties = properties;
    }

    /**
     * 提交规划任务并等待最终结果（含 408 后的轮询）。
     *
     * @return feasible 或 infeasible 的规划结果；服务不可用、参数错误等抛出 {@link ServiceException}
     */
    public AlgorithmPlanRespDTO plan(AlgorithmPlanReqDTO request) {
        for (int attempt = 0; attempt <= properties.getMaxRetries(); attempt++) {
            if (attempt > 0) {
                sleep(properties.getRetryBackoff().toMillis());
            }
            try {
                ResponseEntity<AlgorithmPlanRespDTO> response =
                        restTemplate.postForEntity("/api/v1/plan", request, AlgorithmPlanRespDTO.class);
                return response.getBody();
            } catch (HttpStatusCodeException ex) {
                int status = ex.getStatusCode().value();
                if (status == 408) {
                    // 算法侧已在计算，凭 requestId 轮询；重复提交只会命中幂等缓存
                    return pollResult(request.getRequestId());
                }
                if (status == 422) {
                    return toInfeasible(request, parseError(ex));
                }
                if (status == 400) {
                    throw exception(ALGORITHM_INVALID_INPUT, errorMessage(ex));
                }
                if (status == 413) {
                    throw exception(ALGORITHM_OVER_LIMIT);
                }
                if (isRetryable(status)) {
                    log.warn("[plan][requestId={} 第 {} 次请求返回可重试状态 {}]", request.getRequestId(), attempt + 1, status);
                    continue;
                }
                throw exception(ALGORITHM_CALL_FAILED, status, errorMessage(ex));
            } catch (ResourceAccessException ex) {
                log.warn("[plan][requestId={} 第 {} 次请求网络错误：{}]", request.getRequestId(), attempt + 1, ex.getMessage());
            }
        }
        throw exception(ALGORITHM_SERVICE_UNAVAILABLE);
    }

    /**
     * 轮询规划结果：202 继续等待，200 返回，404 说明 requestId 不存在或已过幂等保留期。
     * 轮询期间的 502/503/504 与网络错误并入轮询次数上限，不单独重试。
     */
    private AlgorithmPlanRespDTO pollResult(String requestId) {
        for (int attempt = 0; attempt < properties.getMaxPollAttempts(); attempt++) {
            sleep(properties.getPollInterval().toMillis());
            try {
                ResponseEntity<AlgorithmPlanRespDTO> response =
                        restTemplate.getForEntity("/api/v1/result/" + requestId, AlgorithmPlanRespDTO.class);
                if (response.getStatusCode().value() == 202) {
                    continue;
                }
                return response.getBody();
            } catch (HttpStatusCodeException ex) {
                int status = ex.getStatusCode().value();
                if (status == 404) {
                    throw exception(ALGORITHM_RESULT_NOT_FOUND, requestId);
                }
                if (!isRetryable(status)) {
                    throw exception(ALGORITHM_CALL_FAILED, status, errorMessage(ex));
                }
                log.warn("[pollResult][requestId={} 第 {} 次轮询返回可重试状态 {}]", requestId, attempt + 1, status);
            } catch (ResourceAccessException ex) {
                log.warn("[pollResult][requestId={} 第 {} 次轮询网络错误：{}]", requestId, attempt + 1, ex.getMessage());
            }
        }
        throw exception(ALGORITHM_TASK_TIMEOUT, requestId);
    }

    /**
     * 422 兼容路径：算法组 Q10 回复为 200 + status=infeasible，Q12 将 422 标注为业务不可行。
     * 统一归一为无解结果，reasonCode 优先取 details.reasonCode，缺省回退为标准错误码，
     * 保证归一后的结果总能通过结果校验（无解必须携带 reasonCode）。
     */
    private AlgorithmPlanRespDTO toInfeasible(AlgorithmPlanReqDTO request, AlgorithmErrorRespDTO error) {
        String reasonCode = null;
        if (error != null) {
            if (error.getDetails() != null && error.getDetails().get("reasonCode") != null) {
                reasonCode = String.valueOf(error.getDetails().get("reasonCode"));
            } else {
                reasonCode = error.getCode();
            }
        }
        return AlgorithmPlanRespDTO.builder()
                .requestId(request.getRequestId())
                .status(AlgorithmPlanRespDTO.STATUS_INFEASIBLE)
                .reasonCode(reasonCode)
                .cached(false)
                .computedAt(OffsetDateTime.now())
                .build();
    }

    private AlgorithmErrorRespDTO parseError(HttpStatusCodeException ex) {
        try {
            return ex.getResponseBodyAs(AlgorithmErrorRespDTO.class);
        } catch (Exception ignored) {
            return null;
        }
    }

    private String errorMessage(HttpStatusCodeException ex) {
        AlgorithmErrorRespDTO error = parseError(ex);
        return error != null && error.getMessage() != null ? error.getMessage() : ex.getStatusText();
    }

    private boolean isRetryable(int status) {
        return status == 502 || status == 503 || status == 504;
    }

    private void sleep(long millis) {
        if (millis <= 0) {
            return;
        }
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw exception(ALGORITHM_SERVICE_UNAVAILABLE);
        }
    }

}
