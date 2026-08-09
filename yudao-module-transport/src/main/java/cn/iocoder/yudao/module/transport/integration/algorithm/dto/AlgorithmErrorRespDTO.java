package cn.iocoder.yudao.module.transport.integration.algorithm.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

/**
 * 算法服务标准错误响应。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AlgorithmErrorRespDTO {

    /** INVALID_INPUT / OVER_LIMIT / INFEASIBLE / TIMEOUT / REQUEST_NOT_FOUND / NOT_READY / SERVICE_BUSY / ALGORITHM_INTERNAL_ERROR */
    private String code;

    private String message;

    private String requestId;

    private Map<String, Object> details;

}
