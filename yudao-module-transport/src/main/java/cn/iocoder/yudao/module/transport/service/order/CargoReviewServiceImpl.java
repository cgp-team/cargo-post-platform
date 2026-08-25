package cn.iocoder.yudao.module.transport.service.order;

import cn.iocoder.yudao.module.transport.enums.order.ReviewReasonCodeEnum;
import cn.iocoder.yudao.module.transport.enums.order.ReviewStatusEnum;
import cn.iocoder.yudao.module.transport.enums.order.ServiceModeEnum;
import org.springframework.stereotype.Service;
import org.springframework.validation.annotation.Validated;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import static cn.iocoder.yudao.module.transport.enums.order.ReviewReasonCodeEnum.*;

/**
 * 承运审核引擎默认实现（规则式）。规则与阈值集中在此，便于评审与扩展。
 */
@Service
@Validated
public class CargoReviewServiceImpl implements CargoReviewService {

    /** 单件重量上限(kg)，超过即拒运（OVER_WEIGHT） */
    private static final BigDecimal MAX_WEIGHT_KG = new BigDecimal("30");

    /** 危险品关键词（命中即拒运） */
    private static final String[] DANGEROUS_KEYWORDS = {"烟花爆竹", "烟花", "鞭炮", "爆炸", "炸药", "雷管", "汽油",
            "柴油", "酒精", "油漆", "打火机", "液化气", "甲烷"};
    /** 禁运品关键词（命中即拒运） */
    private static final String[] PROHIBITED_KEYWORDS = {"毒品", "枪支", "弹药", "管制刀具", "违禁"};
    /** 生鲜/需冷链关键词（命中转人工审核） */
    private static final String[] FRESH_KEYWORDS = {"生鲜", "冻品", "冷冻", "冷藏", "活体", "海鲜", "冰淇淋"};
    /** 大件/超规关键词（命中需客户送站或特殊安排 → CONDITIONAL） */
    private static final String[] OVERSIZE_KEYWORDS = {"大件", "家具", "家电", "冰箱", "洗衣机", "床垫", "超长", "超宽"};

    @Override
    public CargoReviewResult review(String goodsName, BigDecimal weightKg, Boolean freshFlag,
                                    String goodsNote, Long pickupStationId, Long deliveryStationId) {
        String name = goodsName != null ? goodsName : "";
        String note = goodsNote != null ? goodsNote : "";
        List<String> reasons = new ArrayList<>();

        // 1. 危险品/禁运品 → 拒运（最高优先级，直接返回）
        if (containsAny(name, DANGEROUS_KEYWORDS)) {
            reasons.add(DANGEROUS_GOODS.getCode());
        }
        if (containsAny(name, PROHIBITED_KEYWORDS)) {
            reasons.add(PROHIBITED_GOODS.getCode());
        }
        if (!reasons.isEmpty()) {
            return rejected(reasons);
        }

        // 2. 超重 → 拒运
        if (weightKg != null && weightKg.compareTo(MAX_WEIGHT_KG) > 0) {
            reasons.add(OVER_WEIGHT.getCode());
        }
        if (!reasons.isEmpty()) {
            return rejected(reasons);
        }

        // 3. 大件/超规 → 需客户送站（CONDITIONAL）：送达转 CUSTOMER_TO_STATION，推荐送达站点
        if (containsAny(name, OVERSIZE_KEYWORDS) || containsAny(note, OVERSIZE_KEYWORDS)) {
            return CargoReviewResult.builder()
                    .reviewStatus(ReviewStatusEnum.CONDITIONAL.getStatus())
                    .reasonCodes(List.of(CUSTOMER_ACTION_REQUIRED.getCode()))
                    .pickupServiceMode(ServiceModeEnum.STATION_TO_STATION.getCode())
                    .deliveryServiceMode(ServiceModeEnum.CUSTOMER_TO_STATION.getCode())
                    .servicePointStationId(deliveryStationId)
                    .message("大件/超规货物需到指定站点交接，请将货物送到最近服务站点")
                    .build();
        }

        // 4. 生鲜/需冷链 → 需人工审核（确认车辆冷链条件）
        if (Boolean.TRUE.equals(freshFlag) || containsAny(name, FRESH_KEYWORDS)
                || containsAny(note, FRESH_KEYWORDS)) {
            return CargoReviewResult.builder()
                    .reviewStatus(ReviewStatusEnum.MANUAL_REVIEW.getStatus())
                    .reasonCodes(List.of(MANUAL_REVIEW_REQUIRED.getCode()))
                    .pickupServiceMode(ServiceModeEnum.STATION_TO_STATION.getCode())
                    .deliveryServiceMode(ServiceModeEnum.STATION_TO_STATION.getCode())
                    .message("生鲜/冷链货物需人工确认承运条件，请等待工作人员联系")
                    .build();
        }

        // 5. 默认通过：两端站到站
        return CargoReviewResult.builder()
                .reviewStatus(ReviewStatusEnum.PASSED.getStatus())
                .reasonCodes(List.of())
                .pickupServiceMode(ServiceModeEnum.STATION_TO_STATION.getCode())
                .deliveryServiceMode(ServiceModeEnum.STATION_TO_STATION.getCode())
                .message("审核通过，可进入待入池")
                .build();
    }

    private static CargoReviewResult rejected(List<String> reasons) {
        return CargoReviewResult.builder()
                .reviewStatus(ReviewStatusEnum.REJECTED.getStatus())
                .reasonCodes(reasons)
                .pickupServiceMode(ServiceModeEnum.STATION_TO_STATION.getCode())
                .deliveryServiceMode(ServiceModeEnum.STATION_TO_STATION.getCode())
                .message("货物不符合运输条件，不予承运")
                .build();
    }

    private static boolean containsAny(String text, String[] keywords) {
        if (text == null || text.isEmpty()) {
            return false;
        }
        for (String keyword : keywords) {
            if (text.contains(keyword)) {
                return true;
            }
        }
        return false;
    }

    /** 原因码列表 → 逗号分隔字符串（落库） */
    public static String joinReasonCodes(List<String> reasonCodes) {
        return reasonCodes == null || reasonCodes.isEmpty() ? "" : String.join(",", reasonCodes);
    }

    /** 原因码列表 → 中文文案（前端可映射；后端兜底展示） */
    public static String reasonText(List<String> reasonCodes) {
        if (reasonCodes == null || reasonCodes.isEmpty()) {
            return "";
        }
        List<String> names = new ArrayList<>();
        for (String code : reasonCodes) {
            String name = ReviewReasonCodeEnum.nameOf(code);
            if (name.isEmpty()) {
                name = code;
            }
            names.add(name);
        }
        return String.join("、", names);
    }

}
