package cn.iocoder.yudao.module.transport.service.order;

import cn.iocoder.yudao.module.transport.enums.order.ReviewReasonCodeEnum;
import cn.iocoder.yudao.module.transport.enums.order.ReviewStatusEnum;
import cn.iocoder.yudao.module.transport.enums.order.ServiceModeEnum;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import cn.iocoder.yudao.module.transport.dal.dataobject.station.StationDO;
import static org.junit.jupiter.api.Assertions.assertEquals;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 承运审核引擎规则单测（纯逻辑，无依赖）。
 */
class CargoReviewServiceImplTest {

    private final CargoReviewServiceImpl service = new CargoReviewServiceImpl();

    // ==================== 就近交接站点匹配（客户送站）====================

    private static StationDO station(long id, String name, int level, double lng, double lat) {
        return StationDO.builder().id(id).stationName(name).stationLevel(level).status(0)
                .longitude(BigDecimal.valueOf(lng)).latitude(BigDecimal.valueOf(lat)).build();
    }

    @Test
    void servicePoint_prefersDepotLevelPickupStation() {
        // 取货站本身就是场站级（县城客运中心）→ 直接在该站交接，不再另找
        List<StationDO> stations = List.of(
                station(1L, "县城客运中心", 1, 104.0657, 30.5723),
                station(2L, "红花村站", 2, 104.1234, 30.6012));
        assertEquals(1L, CargoReviewServiceImpl.selectServicePointStation(1L, 4L, stations));
    }

    @Test
    void servicePoint_picksNearestEnabledStationToPickup() {
        // 取货站是村级（绿水村站）→ 取距它最近的启用站点（红花村站），而不是远端的送达站点
        List<StationDO> stations = List.of(
                station(3L, "绿水村站", 2, 104.0890, 30.5890),
                station(2L, "红花村站", 2, 104.1234, 30.6012), // 最近
                station(4L, "青山镇站", 1, 104.2345, 30.6234));
        assertEquals(2L, CargoReviewServiceImpl.selectServicePointStation(3L, 4L, stations));
    }

    @Test
    void servicePoint_tieBreaksByStationId() {
        // 取货站(1) 与两个候选等距（东西各 0.02°）→ 取站点 ID 升序（5 而非 9）
        List<StationDO> stations = List.of(
                station(1L, "取货站", 2, 104.10, 30.60),
                station(9L, "站B", 2, 104.12, 30.60),
                station(5L, "站A", 2, 104.08, 30.60));
        assertEquals(5L, CargoReviewServiceImpl.selectServicePointStation(1L, 4L, stations));
    }

    @Test
    void servicePoint_fallsBackWhenNoStationData() {
        // 站点数据缺失 → 退回送达站点，流程不断
        assertEquals(4L, CargoReviewServiceImpl.selectServicePointStation(1L, 4L, List.of()));
        assertEquals(4L, CargoReviewServiceImpl.selectServicePointStation(1L, 4L, null));
    }

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
    void overweight_conditional_split_suggestion() {
        // 业务规则：有解决方案就告诉用户做什么 —— 超重可拆分 → 需客户操作（不是直接拒运）
        CargoReviewResult r = service.review("土鸡蛋", new BigDecimal("50"), false, "", 1L, 2L);
        assertEquals(ReviewStatusEnum.CONDITIONAL.getStatus(), r.getReviewStatus());
        assertTrue(r.getReasonCodes().contains(ReviewReasonCodeEnum.OVER_WEIGHT.getCode()));
        assertTrue(r.getMessage() != null && r.getMessage().contains("拆分"));
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
