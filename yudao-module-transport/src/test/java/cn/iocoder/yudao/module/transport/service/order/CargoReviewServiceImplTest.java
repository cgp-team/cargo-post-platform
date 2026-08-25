package cn.iocoder.yudao.module.transport.service.order;

import cn.iocoder.yudao.module.transport.enums.order.ReviewReasonCodeEnum;
import cn.iocoder.yudao.module.transport.enums.order.ReviewStatusEnum;
import cn.iocoder.yudao.module.transport.enums.order.ServiceModeEnum;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 承运审核引擎规则单测（纯逻辑，无依赖）。
 */
class CargoReviewServiceImplTest {

    private final CargoReviewServiceImpl service = new CargoReviewServiceImpl();

    @Test
    void dangerous_goods_rejected() {
        CargoReviewResult r = service.review("烟花", new BigDecimal("1"), false, "", 1L, 2L);
        assertTrue(r.isRejected());
        assertEquals(ReviewStatusEnum.REJECTED.getStatus(), r.getReviewStatus());
        assertTrue(r.getReasonCodes().contains(ReviewReasonCodeEnum.DANGEROUS_GOODS.getCode()));
    }

    @Test
    void prohibited_goods_rejected() {
        CargoReviewResult r = service.review("管制刀具", new BigDecimal("1"), false, "", 1L, 2L);
        assertTrue(r.isRejected());
        assertTrue(r.getReasonCodes().contains(ReviewReasonCodeEnum.PROHIBITED_GOODS.getCode()));
    }

    @Test
    void overweight_rejected() {
        CargoReviewResult r = service.review("土鸡蛋", new BigDecimal("50"), false, "", 1L, 2L);
        assertTrue(r.isRejected());
        assertTrue(r.getReasonCodes().contains(ReviewReasonCodeEnum.OVER_WEIGHT.getCode()));
    }

    @Test
    void normal_weight_pass() {
        CargoReviewResult r = service.review("土鸡蛋", new BigDecimal("2.5"), false, "", 1L, 2L);
        assertEquals(ReviewStatusEnum.PASSED.getStatus(), r.getReviewStatus());
        assertTrue(r.getReasonCodes().isEmpty());
        assertEquals(ServiceModeEnum.STATION_TO_STATION.getCode(), r.getPickupServiceMode());
        assertEquals(ServiceModeEnum.STATION_TO_STATION.getCode(), r.getDeliveryServiceMode());
    }

    @Test
    void oversize_conditional_customer_action() {
        // 大件/超规 → 需客户送站（CONDITIONAL），送达转 CUSTOMER_TO_STATION，推荐送达站点
        CargoReviewResult r = service.review("双人床垫", new BigDecimal("15"), false, "", 1L, 42L);
        assertEquals(ReviewStatusEnum.CONDITIONAL.getStatus(), r.getReviewStatus());
        assertTrue(r.getReasonCodes().contains(ReviewReasonCodeEnum.CUSTOMER_ACTION_REQUIRED.getCode()));
        assertEquals(ServiceModeEnum.CUSTOMER_TO_STATION.getCode(), r.getDeliveryServiceMode());
        assertEquals(42L, r.getServicePointStationId());
    }

    @Test
    void fresh_manual_review() {
        // 生鲜 → 需人工审核
        CargoReviewResult r = service.review("冷冻海鲜", new BigDecimal("3"), false, "", 1L, 2L);
        assertEquals(ReviewStatusEnum.MANUAL_REVIEW.getStatus(), r.getReviewStatus());
        assertTrue(r.getReasonCodes().contains(ReviewReasonCodeEnum.MANUAL_REVIEW_REQUIRED.getCode()));
    }

    @Test
    void join_reason_codes_and_text() {
        assertEquals("", CargoReviewServiceImpl.joinReasonCodes(List.of()));
        assertEquals("DANGEROUS_GOODS,OVER_WEIGHT",
                CargoReviewServiceImpl.joinReasonCodes(List.of("DANGEROUS_GOODS", "OVER_WEIGHT")));
        assertEquals("危险品、超重",
                CargoReviewServiceImpl.reasonText(List.of("DANGEROUS_GOODS", "OVER_WEIGHT")));
    }

}
