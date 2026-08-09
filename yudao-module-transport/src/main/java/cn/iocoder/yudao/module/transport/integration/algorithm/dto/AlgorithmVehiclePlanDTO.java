package cn.iocoder.yudao.module.transport.integration.algorithm.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 单台车辆的闭环方案：首末均为场站。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AlgorithmVehiclePlanDTO {

    private Long vehicleId;

    /** 闭环站点访问序列 */
    private List<AlgorithmRouteStopDTO> stops;

    /** 本车单次往返总里程 */
    private Double totalDistance;

}
