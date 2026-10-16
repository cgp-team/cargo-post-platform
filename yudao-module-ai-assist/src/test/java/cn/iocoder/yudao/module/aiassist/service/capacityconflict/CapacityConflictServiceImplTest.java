package cn.iocoder.yudao.module.aiassist.service.capacityconflict;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.module.aiassist.controller.admin.capacityconflict.vo.CapacityConflictCheckReqVO;
import cn.iocoder.yudao.module.aiassist.controller.admin.capacityconflict.vo.CapacityConflictCheckRespVO;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 客货空间冲突检视的判定口径（契约 §6 第 3 项）。
 *
 * <p>钉的都是**静默失效**那一类：这类检视算错了不会抛异常，只会给出一个看着像结论的错结论，
 * 运营据此排班就真的少装/超载了。所以每条断言都指着一种具体的错法。
 */
class CapacityConflictServiceImplTest {

    private final CapacityConflictServiceImpl service = new CapacityConflictServiceImpl();

    private static CapacityConflictCheckReqVO.Vehicle vehicle(String label, Integer p, Integer items, String kg) {
        CapacityConflictCheckReqVO.Vehicle v = new CapacityConflictCheckReqVO.Vehicle();
        v.setLabel(label);
        v.setPassengerCapacity(p);
        v.setCargoCapacityItems(items);
        v.setCargoCapacityKg(kg == null ? null : new BigDecimal(kg));
        return v;
    }

    private static CapacityConflictCheckReqVO.Segment segment(String name, Integer p, Integer items, String kg) {
        CapacityConflictCheckReqVO.Segment s = new CapacityConflictCheckReqVO.Segment();
        s.setName(name);
        s.setForecastPassenger(p);
        s.setForecastCargoItems(items);
        s.setForecastCargoKg(kg == null ? null : new BigDecimal(kg));
        return s;
    }

    private static CapacityConflictCheckReqVO req(List<CapacityConflictCheckReqVO.Vehicle> vehicles,
                                                  List<CapacityConflictCheckReqVO.Segment> segments) {
        CapacityConflictCheckReqVO r = new CapacityConflictCheckReqVO();
        r.setSceneLabel("早班 06:30 石桥线");
        r.setVehicles(vehicles);
        r.setSegments(segments);
        return r;
    }

    /** 主路径：货位缺口必须被算成 BLOCK，且缺口数值 = 预报 - 核定。 */
    @Test
    void cargoItemShortfallIsBlockAndReportsGap() {
        CapacityConflictCheckRespVO resp = service.check(req(
                List.of(vehicle("渝A1", 19, 20, "500")),
                List.of(segment("石桥→县城", 10, 34, "300"))));

        assertTrue(resp.getOverCapacity());
        CapacityConflictCheckRespVO.Conflict c = resp.getConflicts().stream()
                .filter(x -> "CARGO_ITEM".equals(x.getDimension()) && "BLOCK".equals(x.getSeverity()))
                .findFirst().orElseThrow();
        assertEquals(0, new BigDecimal("14").compareTo(c.getExceed()), "缺口应为 34-20=14");
        assertTrue(resp.getBufferSuggestions().stream().anyMatch(s -> s.contains("拆件")), resp.getBufferSuggestions().toString());
    }

    /** 未录容量：该维度必须"不可判"，绝不能把 null 当 0 座而与预报量比出一个假的超限。 */
    @Test
    void nullCapacityMakesDimensionUnjudgeableNotZero() {
        CapacityConflictCheckRespVO resp = service.check(req(
                List.of(vehicle("渝A1", null, 20, "500")),
                List.of(segment("石桥→县城", 10, 8, "100"))));

        assertFalse(resp.getOverCapacity(), "未录载客不该被念成超载");
        assertTrue(resp.getConflicts().stream().noneMatch(c -> "PASSENGER".equals(c.getDimension())),
                "客位维度不可判，不该产出任何冲突点");
        assertTrue(resp.getUnratedNotes().stream().anyMatch(s -> s.contains("核定载客")), resp.getUnratedNotes().toString());
        assertTrue(resp.getSummary().contains("不可判"), resp.getSummary());
    }

    /** 0 与"未录"必须分开：真实 0 座车厢装上预报客流，就是超限，不是"不可判"。 */
    @Test
    void zeroRatedIsARealZeroAndCanExceed() {
        CapacityConflictCheckRespVO resp = service.check(req(
                List.of(vehicle("纯货班车", 0, 20, "500")),
                List.of(segment("石桥→县城", 3, 5, "10"))));

        assertTrue(resp.getOverCapacity());
        assertTrue(resp.getConflicts().stream().anyMatch(c -> "PASSENGER".equals(c.getDimension()) && "BLOCK".equals(c.getSeverity())));
        assertTrue(resp.getUnratedNotes().isEmpty(), "0 是已知值，不该进「未录」清单");
    }

    /** 接近上限（>=90%）给 WARN 而不是 BLOCK，措辞保守。 */
    @Test
    void nearCapacityIsWarnNotBlock() {
        CapacityConflictCheckRespVO resp = service.check(req(
                List.of(vehicle("渝A1", 10, 100, "500")),
                List.of(segment("石桥→县城", 9, 5, "10"))));

        assertFalse(resp.getOverCapacity(), "9/10 是接近上限，不是超限");
        assertTrue(resp.getConflicts().stream().anyMatch(c -> "PASSENGER".equals(c.getDimension()) && "WARN".equals(c.getSeverity())));
    }

    /** 载重维度用 BigDecimal 比：小数缺口也要算准，不能被整数化吃掉。 */
    @Test
    void cargoWeightUsesDecimalGap() {
        CapacityConflictCheckRespVO resp = service.check(req(
                List.of(vehicle("渝A1", 19, 40, "100.5")),
                List.of(segment("石桥→县城", 5, 5, "120.8"))));

        CapacityConflictCheckRespVO.Conflict c = resp.getConflicts().stream()
                .filter(x -> "CARGO_WEIGHT".equals(x.getDimension()))
                .findFirst().orElseThrow();
        assertEquals(0, new BigDecimal("20.3").compareTo(c.getExceed()), "缺口应为 120.8-100.5=20.3");
    }

    /** 空输入必须被挡：没有车或没有预报量就无从判定，不能返回一个空的"没问题"。 */
    @Test
    void emptyInputIsRejected() {
        assertThrows(ServiceException.class, () -> service.check(req(new ArrayList<>(), List.of(segment("x", 1, 1, "1")))));
        assertThrows(ServiceException.class, () -> service.check(req(List.of(vehicle("渝A1", 1, 1, "1")), new ArrayList<>())));
    }

    /** 负数输入必须被挡（负数会让缺口算式产出无意义的结论）。 */
    @Test
    void negativeInputIsRejected() {
        ServiceException ex = assertThrows(ServiceException.class, () -> service.check(req(
                List.of(vehicle("渝A1", -1, 20, "500")),
                List.of(segment("石桥→县城", 3, 5, "10")))));
        assertTrue(ex.getMessage().contains("负数"), ex.getMessage());
    }

}
