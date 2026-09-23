package cn.iocoder.yudao.module.transport.integration.algorithm;

import cn.hutool.core.util.IdUtil;
import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.module.transport.integration.algorithm.dto.AlgorithmDistanceReqDTO;
import cn.iocoder.yudao.module.transport.integration.algorithm.dto.AlgorithmDistanceRespDTO;
import cn.iocoder.yudao.module.transport.integration.algorithm.dto.AlgorithmErrorRespDTO;
import cn.iocoder.yudao.module.transport.integration.algorithm.dto.AlgorithmRouteReqDTO;
import cn.iocoder.yudao.module.transport.integration.algorithm.dto.AlgorithmRouteRespDTO;
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

    /**
     * route（轻量单路线查询）失败冷却截止时间：算法服务不可用时避免在循环里串行重试（12 段 × 每次重试）
     * 把接口拖到超时。冷却期内直接快速失败，调用方按"估算直线兜底"降级（需求 §141）。
     */
    private final java.util.concurrent.atomic.AtomicLong routeUnavailableUntil = new java.util.concurrent.atomic.AtomicLong(0);
    /** 冷却时长：30 秒（足够跨过前端 15s 轮询的一次刷新） */
    private static final long ROUTE_COOLDOWN_MS = 30_000;

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
     * 两站点间路网距离/耗时查询（寄货页取货→送达）。轻量即时查询，不落幂等留痕；
     * 失败抛 {@link ServiceException}，调用方捕获后降级直线距离。
     */
    /** 启动/巡检：探测算法服务是否可达（openapi 或 plan 路由）。 */
    public boolean healthCheck() {
        try {
            restTemplate.getForEntity("/openapi.json", String.class);
            return true;
        } catch (Exception ex) {
            try {
                restTemplate.getForEntity("/api/v1/route", String.class);
                return true;
            } catch (Exception ex2) {
                log.warn("[healthCheck] 算法服务不可达 baseUrl={} : {}", properties.getBaseUrl(), ex2.getMessage());
                return false;
            }
        }
    }

    /** 动态插单调度：POST /api/v1/dispatch/allocate（DISPATCH_CORE_V047）。 */
    @SuppressWarnings("unchecked")
    public java.util.Map<String, Object> allocate(java.util.Map<String, Object> payload) {
        for (int attempt = 0; attempt <= properties.getMaxRetries(); attempt++) {
            try {
                var response = restTemplate.postForEntity("/api/v1/dispatch/allocate", payload, java.util.Map.class);
                return response.getBody();
            } catch (org.springframework.web.client.HttpServerErrorException ex) {
                int status = ex.getStatusCode().value();
                if (status == 502 || status == 503 || status == 504) {
                    log.warn("[allocate][第 {} 次可重试 status={}]", attempt + 1, status);
                } else {
                    log.warn("[allocate][失败 status={} body={}]", status, ex.getResponseBodyAsString());
                    throw ex;
                }
            } catch (Exception ex) {
                log.warn("[allocate][网络错误 第 {} 次: {}]", attempt + 1, ex.getMessage());
            }
            sleep(properties.getRetryBackoff().toMillis());
        }
        throw new ServiceException(500, "算法动态调度服务暂不可用");
    }

    public AlgorithmDistanceRespDTO distance(AlgorithmDistanceReqDTO request) {
        if (request.getRequestId() == null) {
            request.setRequestId("req-" + IdUtil.fastSimpleUUID());
        }
        for (int attempt = 0; attempt <= properties.getMaxRetries(); attempt++) {
            if (attempt > 0) {
                sleep(properties.getRetryBackoff().toMillis());
            }
            try {
                ResponseEntity<AlgorithmDistanceRespDTO> response =
                        restTemplate.postForEntity("/api/v1/distance", request, AlgorithmDistanceRespDTO.class);
                return response.getBody();
            } catch (HttpStatusCodeException ex) {
                int status = ex.getStatusCode().value();
                if (status == 400) {
                    throw exception(ALGORITHM_INVALID_INPUT, errorMessage(ex));
                }
                if (status == 413) {
                    throw exception(ALGORITHM_OVER_LIMIT);
                }
                if (isRetryable(status)) {
                    log.warn("[distance][requestId={} 第 {} 次请求返回可重试状态 {}]", request.getRequestId(), attempt + 1, status);
                    continue;
                }
                throw exception(ALGORITHM_CALL_FAILED, status, errorMessage(ex));
            } catch (ResourceAccessException ex) {
                log.warn("[distance][requestId={} 第 {} 次请求网络错误：{}]", request.getRequestId(), attempt + 1, ex.getMessage());
            }
        }
        throw exception(ALGORITHM_SERVICE_UNAVAILABLE);
    }

    /**
     * 坐标→坐标单路线查询（实时公交 ETA：车辆位置 → 下一站）。轻量即时查询；
     * 失败抛 {@link ServiceException}，调用方按"本次不返回 ETA"降级（不疯狂重试）。
     */
    public AlgorithmRouteRespDTO route(AlgorithmRouteReqDTO request) {
        if (System.currentTimeMillis() < routeUnavailableUntil.get()) {
            // 冷却期：快速失败，不占用请求线程（调用方回退直线，不伪装真实道路）
            throw exception(ALGORITHM_SERVICE_UNAVAILABLE);
        }
        // route 是循环调用的轻量查询：重试次数上限收紧为 1，避免单次失败放大成整页超时
        int maxAttempts = Math.min(properties.getMaxRetries(), 1);
        for (int attempt = 0; attempt <= maxAttempts; attempt++) {
            if (attempt > 0) {
                sleep(properties.getRetryBackoff().toMillis());
            }
            try {
                ResponseEntity<AlgorithmRouteRespDTO> response =
                        restTemplate.postForEntity("/api/v1/route", request, AlgorithmRouteRespDTO.class);
                routeUnavailableUntil.set(0); // 成功：清除冷却
                return response.getBody();
            } catch (HttpStatusCodeException ex) {
                int status = ex.getStatusCode().value();
                if (status == 400) {
                    throw exception(ALGORITHM_INVALID_INPUT, errorMessage(ex));
                }
                if (isRetryable(status)) {
                    log.warn("[route][第 {} 次请求返回可重试状态 {}]", attempt + 1, status);
                    continue;
                }
                // 422 等不可重试错误必须留痕（否则前端只看到"回退直线"，排查不到根因）
                log.warn("[route][第 {} 次请求被拒绝 status={} origin={} destination={} 响应={}]",
                        attempt + 1, status, describe(request.getOrigin()), describe(request.getDestination()),
                        ex.getResponseBodyAsString());
                throw exception(ALGORITHM_CALL_FAILED, status, errorMessage(ex));
            } catch (ResourceAccessException ex) {
                log.warn("[route][第 {} 次请求网络错误：{}]", attempt + 1, ex.getMessage());
            }
        }
        routeUnavailableUntil.set(System.currentTimeMillis() + ROUTE_COOLDOWN_MS);
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

    /** 日志用：坐标点可读化（排查 422 参数错误） */
    private static String describe(AlgorithmRouteReqDTO.RoutePoint point) {
        return point == null ? "null" : "(" + point.getLongitude() + "," + point.getLatitude() + ")";
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
