package cn.iocoder.yudao.module.transport.integration.algorithm;

import cn.hutool.core.util.IdUtil;
import cn.hutool.crypto.digest.DigestUtil;
import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.module.transport.dal.dataobject.algorithm.AlgorithmRequestDO;
import cn.iocoder.yudao.module.transport.dal.mysql.algorithm.AlgorithmRequestMapper;
import cn.iocoder.yudao.module.transport.enums.algorithm.AlgorithmRequestStatusEnum;
import cn.iocoder.yudao.module.transport.integration.algorithm.dto.AlgorithmPlanReqDTO;
import cn.iocoder.yudao.module.transport.integration.algorithm.dto.AlgorithmPlanRespDTO;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * 算法适配层门面：幂等、快照留痕、结果校验（适配层责任第 1、5、7 条）。
 *
 * 幂等策略：对请求快照（不含 requestId）计算哈希，24 小时内相同快照复用已落库的结果，
 * 不重复创建业务任务、不覆盖已有结果。
 */
@Component
@Slf4j
public class AlgorithmAdapter {

    private final AlgorithmClient algorithmClient;
    private final AlgorithmRequestMapper algorithmRequestMapper;
    private final ObjectMapper objectMapper;
    private final AlgorithmProperties properties;

    public AlgorithmAdapter(AlgorithmClient algorithmClient,
                            AlgorithmRequestMapper algorithmRequestMapper,
                            @Qualifier(AlgorithmAdapterConfiguration.ALGORITHM_OBJECT_MAPPER) ObjectMapper objectMapper,
                            AlgorithmProperties properties) {
        this.algorithmClient = algorithmClient;
        this.algorithmRequestMapper = algorithmRequestMapper;
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    /**
     * 提交规划快照并返回校验通过的规划结果。
     * requestId 由适配层生成；入参中的 requestId 必须为 null。
     */
    public AlgorithmPlanRespDTO plan(AlgorithmPlanReqDTO request) {
        if (request.getRequestId() != null) {
            throw new IllegalArgumentException("requestId 由适配层生成，调用方不得赋值");
        }
        String snapshotHash = sha256Hex(request);

        // 24 小时内相同快照直接复用已落库结果，不重复创建业务任务
        AlgorithmRequestDO existing = algorithmRequestMapper.selectRecentBySnapshotHash(snapshotHash,
                LocalDateTime.now().minus(properties.getIdempotencyWindow()));
        if (existing != null && existing.getResponseJson() != null) {
            log.info("[plan][快照 {} 命中幂等记录 requestId={}]", snapshotHash, existing.getRequestId());
            AlgorithmPlanRespDTO cached = readJson(existing.getResponseJson(), AlgorithmPlanRespDTO.class);
            cached.setCached(true);
            return cached;
        }

        request.setRequestId("req-" + IdUtil.fastSimpleUUID());
        AlgorithmRequestDO record = AlgorithmRequestDO.builder()
                .requestId(request.getRequestId())
                .snapshotHash(snapshotHash)
                .requestJson(writeJson(request))
                .status(AlgorithmRequestStatusEnum.PROCESSING.getStatus())
                .build();
        algorithmRequestMapper.insert(record);

        try {
            AlgorithmPlanRespDTO result = algorithmClient.plan(request);
            AlgorithmResultValidator.validate(request, result);
            record.setResponseJson(writeJson(result));
            record.setStatus(AlgorithmPlanRespDTO.STATUS_FEASIBLE.equals(result.getStatus())
                    ? AlgorithmRequestStatusEnum.FEASIBLE.getStatus() : AlgorithmRequestStatusEnum.INFEASIBLE.getStatus());
            record.setAlgorithmVersion(result.getAlgorithmVersion());
            record.setParameterVersion(result.getParameterVersion());
            algorithmRequestMapper.updateById(record);
            return result;
        } catch (ServiceException ex) {
            record.setStatus(AlgorithmRequestStatusEnum.FAILED.getStatus());
            record.setErrorCode(String.valueOf(ex.getCode()));
            record.setErrorMessage(ex.getMessage());
            algorithmRequestMapper.updateById(record);
            throw ex;
        }
    }

    /** 快照哈希覆盖除 requestId 外的全部字段，保证同一规划输入得到同一哈希 */
    @SneakyThrows
    private String sha256Hex(AlgorithmPlanReqDTO request) {
        AlgorithmPlanReqDTO snapshot = objectMapper.readValue(
                objectMapper.writeValueAsString(request), AlgorithmPlanReqDTO.class);
        snapshot.setRequestId(null);
        return DigestUtil.sha256Hex(objectMapper.writeValueAsString(snapshot));
    }

    @SneakyThrows
    private String writeJson(Object value) {
        return objectMapper.writeValueAsString(value);
    }

    @SneakyThrows
    private <T> T readJson(String json, Class<T> clazz) {
        return objectMapper.readValue(json, clazz);
    }

}
