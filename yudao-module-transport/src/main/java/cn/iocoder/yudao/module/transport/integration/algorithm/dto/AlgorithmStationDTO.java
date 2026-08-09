package cn.iocoder.yudao.module.transport.integration.algorithm.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 算法站点，坐标统一 GCJ-02；WGS-84 转换由业务后端在调用前完成。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AlgorithmStationDTO {

    private String stationId;

    /** GCJ-02 经度 */
    private Double longitude;

    /** GCJ-02 纬度 */
    private Double latitude;

}
