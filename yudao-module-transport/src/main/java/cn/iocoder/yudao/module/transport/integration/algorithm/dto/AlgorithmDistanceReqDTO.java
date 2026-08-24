package cn.iocoder.yudao.module.transport.integration.algorithm.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 两站点距离/耗时查询请求，对齐 docs/api/algorithm-api.yaml 的 DistanceRequest。
 * stations 恰好 2 个，顺序为 [取货站, 送达站]。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AlgorithmDistanceReqDTO {

    /** 全局唯一请求标识，幂等键；由适配层生成 */
    private String requestId;

    /** 恰好两个站点：[取货站, 送达站]，GCJ-02 坐标 */
    private List<AlgorithmStationDTO> stations;

}
