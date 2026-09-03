package cn.iocoder.yudao.module.transport.integration.algorithm.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 算法可用车辆：空载从场站出发，规划结束后返回场站；算法自动判定启用数量。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AlgorithmVehicleDTO {

    /** 默认实时最大载客人数 */
    public static final int DEFAULT_PASSENGER_CAPACITY = 5;
    /** 默认货仓实时最大包裹件数 */
    public static final int DEFAULT_CARGO_CAPACITY = 4;

    private Long vehicleId;

    /** 实时最大载客人数 */
    private Integer passengerCapacity;

    /** 货仓实时最大包裹件数 */
    private Integer cargoCapacity;

    /** 初始载客人数（车辆从场站出发时已有的乘客数，需 < passengerCapacity） */
    private Integer initialPassengerLoad;

    /** 初始载货件数（车辆从场站出发时已有的货物件数，需 <= cargoCapacity） */
    private Integer initialCargoLoad;

    /** 公交骨架（Mandatory Passenger Service）：该车辆必须按序经停的站点编号（不含场站）。缺省纯 VRP。 */
    private java.util.List<String> skeleton;

}
