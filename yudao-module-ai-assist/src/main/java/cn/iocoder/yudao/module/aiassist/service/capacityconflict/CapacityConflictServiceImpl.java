package cn.iocoder.yudao.module.aiassist.service.capacityconflict;

import cn.iocoder.yudao.module.aiassist.controller.admin.capacityconflict.vo.CapacityConflictCheckReqVO;
import cn.iocoder.yudao.module.aiassist.controller.admin.capacityconflict.vo.CapacityConflictCheckRespVO;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;
import org.springframework.validation.annotation.Validated;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.module.aiassist.enums.ErrorCodeConstants.CAPACITY_CONFLICT_INPUT_EMPTY;
import static cn.iocoder.yudao.module.aiassist.enums.ErrorCodeConstants.CAPACITY_CONFLICT_NEGATIVE;

/**
 * 客货空间冲突检视实现（契约 §6 第 3 项）。
 *
 * <p>纯函数式判定：把"核定"与"预报"两组数放到一起比大小，逐维度给出冲突点与缓冲建议。
 * 三条纪律落在代码里：
 * <ol>
 *   <li><b>未录 ≠ 0</b>：某辆车某维度容量为 null 时，该维度整条判"不可判"，并显式列出提示，
 *       不把 null 当 0 座去和预报量比（否则会把"档案没维护"谎报成"超载"）。</li>
 *   <li><b>接近上限也算一处</b>：预报量达到核定的 90% 即给 WARN 与缓冲建议，措辞保守，
 *       不夸大成"必然冲突"。</li>
 *   <li><b>只报数出来的</b>：summary 只说事实（超限 N 处 / 接近上限 N 处 / 不可判 N 维），
 *       不给"应该没问题"这类无依据的安慰话。</li>
 * </ol>
 */
@Service
@Validated
public class CapacityConflictServiceImpl implements CapacityConflictService {

    /** 接近上限的阈值：预报量 / 核定 >= 90% 视为需要预留缓冲。 */
    private static final BigDecimal WARN_RATIO = new BigDecimal("0.90");

    @Override
    public CapacityConflictCheckRespVO check(CapacityConflictCheckReqVO reqVO) {
        if (CollectionUtils.isEmpty(reqVO.getVehicles()) || CollectionUtils.isEmpty(reqVO.getSegments())) {
            throw exception(CAPACITY_CONFLICT_INPUT_EMPTY);
        }
        validateNonNegative(reqVO);

        // ---- 合计"核定"：逐维记录是否全部车辆都录了容量 ----
        int passengerCap = 0, cargoItemCap = 0;
        BigDecimal cargoKgCap = BigDecimal.ZERO;
        boolean passengerRated = true, cargoItemRated = true, cargoKgRated = true;
        List<String> unratedNotes = new ArrayList<>();
        for (CapacityConflictCheckReqVO.Vehicle v : reqVO.getVehicles()) {
            String label = StringUtils.hasText(v.getLabel()) ? v.getLabel() : "未命名车辆";
            if (v.getPassengerCapacity() == null) {
                passengerRated = false;
                unratedNotes.add("车辆「" + label + "」未录核定载客：客位维度不可判（不把「未录」当成 0 座）");
            } else {
                passengerCap += v.getPassengerCapacity();
            }
            if (v.getCargoCapacityItems() == null) {
                cargoItemRated = false;
                unratedNotes.add("车辆「" + label + "」未录核定货仓件数：货位维度不可判");
            } else {
                cargoItemCap += v.getCargoCapacityItems();
            }
            if (v.getCargoCapacityKg() == null) {
                cargoKgRated = false;
                unratedNotes.add("车辆「" + label + "」未录核定载重：重量维度不可判");
            } else {
                cargoKgCap = cargoKgCap.add(v.getCargoCapacityKg());
            }
        }

        // ---- 合计"预报" ----
        int passengerForecast = 0, cargoItemForecast = 0;
        BigDecimal cargoKgForecast = BigDecimal.ZERO;
        for (CapacityConflictCheckReqVO.Segment s : reqVO.getSegments()) {
            passengerForecast += nz(s.getForecastPassenger());
            cargoItemForecast += nz(s.getForecastCargoItems());
            cargoKgForecast = cargoKgForecast.add(s.getForecastCargoKg() == null ? BigDecimal.ZERO : s.getForecastCargoKg());
        }

        // ---- 逐维判定 ----
        List<CapacityConflictCheckRespVO.Conflict> conflicts = new ArrayList<>();
        LinkedHashSet<String> suggestions = new LinkedHashSet<>();
        if (passengerRated) {
            inspect(conflicts, suggestions, "PASSENGER", "客位",
                    BigDecimal.valueOf(passengerCap), BigDecimal.valueOf(passengerForecast),
                    "客位不够：建议拆班、改派更大车型，或把部分客流转到下一班次。");
        }
        if (cargoItemRated) {
            inspect(conflicts, suggestions, "CARGO_ITEM", "货位",
                    BigDecimal.valueOf(cargoItemCap), BigDecimal.valueOf(cargoItemForecast),
                    "货位不够：建议拆件分趟、加开货班，或改用货仓更大的车辆。");
        }
        if (cargoKgRated) {
            inspect(conflicts, suggestions, "CARGO_WEIGHT", "载重",
                    cargoKgCap, cargoKgForecast,
                    "载重不够：建议分流超出部分，或改用核载更高的车辆。");
        }

        boolean overCapacity = conflicts.stream().anyMatch(c -> "BLOCK".equals(c.getSeverity()));
        long warnCount = conflicts.stream().filter(c -> "WARN".equals(c.getSeverity())).count();

        CapacityConflictCheckRespVO resp = new CapacityConflictCheckRespVO();
        resp.setSceneLabel(reqVO.getSceneLabel());
        resp.setOverCapacity(overCapacity);
        resp.setTotalPassengerCapacity(passengerCap);
        resp.setTotalPassengerForecast(passengerForecast);
        resp.setTotalCargoItemCapacity(cargoItemCap);
        resp.setTotalCargoItemForecast(cargoItemForecast);
        resp.setTotalCargoKgCapacity(cargoKgCap);
        resp.setTotalCargoKgForecast(cargoKgForecast);
        resp.setConflicts(conflicts);
        resp.setBufferSuggestions(new ArrayList<>(suggestions));
        resp.setUnratedNotes(unratedNotes);
        resp.setSummary(buildSummary(overCapacity, conflicts.size(), warnCount, unratedNotes.size()));
        return resp;
    }

    /** 单维判定：缺口 > 0 记 BLOCK；预报 >= 核定 * 90% 记 WARN。rated 为 0 时不判 WARN（避免 0 的假象）。 */
    private void inspect(List<CapacityConflictCheckRespVO.Conflict> conflicts, LinkedHashSet<String> suggestions,
                         String dimension, String label, BigDecimal rated, BigDecimal forecast, String suggest) {
        BigDecimal exceed = forecast.subtract(rated);
        if (exceed.signum() > 0) {
            conflicts.add(CapacityConflictCheckRespVO.Conflict.builder()
                    .dimension(dimension).severity("BLOCK").rated(rated).forecast(forecast).exceed(exceed)
                    .message(String.format("%s超限：核定 %s，预报 %s，缺口 %s。%s",
                            label, fmt(rated), fmt(forecast), fmt(exceed), suggest))
                    .build());
            suggestions.add(suggest);
        } else if (rated.signum() > 0 && forecast.compareTo(rated.multiply(WARN_RATIO)) >= 0) {
            conflicts.add(CapacityConflictCheckRespVO.Conflict.builder()
                    .dimension(dimension).severity("WARN").rated(rated).forecast(forecast).exceed(BigDecimal.ZERO)
                    .message(String.format("%s接近上限：核定 %s，预报 %s，余量 %s，建议预留缓冲。",
                            label, fmt(rated), fmt(forecast), fmt(rated.subtract(forecast))))
                    .build());
            suggestions.add(label + "接近上限，建议预留缓冲或准备备班。");
        }
    }

    private String buildSummary(boolean overCapacity, int conflictCount, long warnCount, int unratedCount) {
        StringBuilder sb = new StringBuilder();
        if (overCapacity) {
            sb.append("存在空间冲突：共 ").append(conflictCount).append(" 处（含接近上限 ").append(warnCount).append(" 处）。");
        } else if (warnCount > 0) {
            sb.append("未发现超限，但有 ").append(warnCount).append(" 处接近上限，建议预留缓冲。");
        } else {
            sb.append("按给定预报量未发现空间冲突。");
        }
        if (unratedCount > 0) {
            sb.append("另有 ").append(unratedCount).append(" 处容量未录，相关维度不可判，请先补全车辆档案。");
        }
        return sb.toString();
    }

    private void validateNonNegative(CapacityConflictCheckReqVO reqVO) {
        for (CapacityConflictCheckReqVO.Vehicle v : reqVO.getVehicles()) {
            checkNonNegative(v.getPassengerCapacity(), "核定载客");
            checkNonNegative(v.getCargoCapacityItems(), "核定货仓件数");
            checkNonNegative(v.getCargoCapacityKg(), "核定载重");
        }
        for (CapacityConflictCheckReqVO.Segment s : reqVO.getSegments()) {
            checkNonNegative(s.getForecastPassenger(), "预报客位");
            checkNonNegative(s.getForecastCargoItems(), "预报货件");
            checkNonNegative(s.getForecastCargoKg(), "预报货重");
        }
    }

    private void checkNonNegative(Number value, String field) {
        if (value == null) {
            return;
        }
        if (value instanceof BigDecimal bd) {
            if (bd.signum() < 0) {
                throw exception(CAPACITY_CONFLICT_NEGATIVE, field + " 不能为负数：" + bd);
            }
            return;
        }
        if (value.longValue() < 0) {
            throw exception(CAPACITY_CONFLICT_NEGATIVE, field + " 不能为负数：" + value);
        }
    }

    private static int nz(Integer v) {
        return v == null ? 0 : v;
    }

    private static String fmt(BigDecimal v) {
        BigDecimal s = v.stripTrailingZeros();
        return s.scale() < 0 ? s.setScale(0).toPlainString() : s.toPlainString();
    }

}
