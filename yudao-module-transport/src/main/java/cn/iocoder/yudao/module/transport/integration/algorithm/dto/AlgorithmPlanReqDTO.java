package cn.iocoder.yudao.module.transport.integration.algorithm.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

/**
 * 规划任务请求，对齐 docs/api/algorithm-api.yaml v0.2.0 的 PlanRequest。
 * requestId 由适配层生成；调用方不得自行赋值。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AlgorithmPlanReqDTO {

    /** 全局唯一请求标识，幂等键，算法侧保留 24 小时；由适配层生成 */
    private String requestId;

    /** 半小时批次区间开始，带时区 ISO 8601 */
    private OffsetDateTime batchStart;

    /** 半小时批次区间结束 */
    private OffsetDateTime batchEnd;

    /** 场站（车辆起止点） */
    private AlgorithmStationDTO depot;

    /** 全部服务站点（含场站） */
    private List<AlgorithmStationDTO> stations;

    /** 本批次可用车辆 */
    private List<AlgorithmVehicleDTO> vehicles;

    /** 乘客+包裹订单合计不超过 25 */
    private List<AlgorithmOrderDTO> orders;

    /** ACO 超参数，全部可选，不传用算法默认值 */
    private Map<String, Object> algorithmConfig;

    /** 仅供 Mock 联调的场景标记，真实算法忽略 */
    private String scenario;

}
