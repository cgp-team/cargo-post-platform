package cn.iocoder.yudao.module.transport.service.order;

import cn.iocoder.yudao.module.transport.enums.order.ReviewStatusEnum;
import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * 承运审核结果（自动审核引擎输出）。
 *
 * 与订单生命周期联动（见 CargoReviewService）：
 * PASSED → READY_FOR_POOL；CONDITIONAL → WAITING_CUSTOMER_ACTION；
 * MANUAL_REVIEW → PENDING_REVIEW；REJECTED → CANCELLED。
 */
@Data
@Builder
public class CargoReviewResult {

    /** 审核结果（ReviewStatusEnum.status） */
    private Integer reviewStatus;

    /** 原因码列表（ReviewReasonCodeEnum.code，可为多个） */
    private List<String> reasonCodes;

    /** 取货服务方式（ServiceModeEnum.code） */
    private String pickupServiceMode;

    /** 送达服务方式（ServiceModeEnum.code） */
    private String deliveryServiceMode;

    /** 建议服务站点编号（替代交接时推荐；未推荐为 null） */
    private Long servicePointStationId;

    /** 给客户的建议文案（前端也可按原因码映射，后端兜底） */
    private String message;

    /** 便捷判断 */
    public boolean isPassed() {
        return reviewStatus != null && reviewStatus.equals(ReviewStatusEnum.PASSED.getStatus());
    }

    public boolean isRejected() {
        return reviewStatus != null && reviewStatus.equals(ReviewStatusEnum.REJECTED.getStatus());
    }

}
