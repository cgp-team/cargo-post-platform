package cn.iocoder.yudao.module.transport.service.dispatch;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.module.transport.controller.admin.transport.townevent.vo.TownEventSaveReqVO;
import cn.iocoder.yudao.module.transport.dal.dataobject.dispatch.TownEventCalendarDO;
import cn.iocoder.yudao.module.transport.dal.mysql.dispatch.TownEventCalendarMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 乡镇事件日历的规则与周期展开（队列 #93）。
 *
 * <p>钉的都是**静默失效**那一类：这张表填错了不会报错，只会让"赶集日提前留舱"的提示
 * 那天不出来、或者把不是集日的日子当成集日。所以每条断言都指着一种具体的漏法。
 */
@ExtendWith(MockitoExtension.class)
class TownEventCalendarServiceImplTest {

    @Mock private TownEventCalendarMapper mapper;
    @InjectMocks private TownEventCalendarServiceImpl service;

    private static final LocalDate MON = LocalDate.of(2026, 10, 12);

    private static TownEventSaveReqVO req(String type, LocalDate start, LocalDate end, String recurrence, Integer interval) {
        TownEventSaveReqVO v = new TownEventSaveReqVO();
        v.setTown("双龙镇");
        v.setEventType(type);
        v.setStartDate(start);
        v.setEndDate(end);
        v.setRecurrence(recurrence);
        v.setIntervalDays(interval);
        return v;
    }

    @Test
    void endBeforeStartIsRejected() {
        ServiceException ex = assertThrows(ServiceException.class,
                () -> service.create(req("MARKET", MON, MON.minusDays(1), "NONE", 0)));
        assertTrue(ex.getMessage().contains("结束日"), ex.getMessage());
        verify(mapper, never()).insert(any(TownEventCalendarDO.class));
    }

    /** 类型写错（market / 集市）⇒ 情景规则匹配不上，事件等于不存在 */
    @Test
    void unknownTypeIsRejected() {
        assertThrows(ServiceException.class,
                () -> service.create(req("market", MON, MON, "NONE", 0)));
        verify(mapper, never()).insert(any(TownEventCalendarDO.class));
    }

    /** 间隔 1 天=每天，那不是场期是噪声；超过一个月也不叫周期 —— 两者都会把提示变成噪音或永不命中 */
    @Test
    void cycleIntervalOutOfRangeIsRejected() {
        assertThrows(ServiceException.class,
                () -> service.create(req("MARKET", MON, MON.plusMonths(2), "EVERY_N_DAYS", 1)));
        assertThrows(ServiceException.class,
                () -> service.create(req("MARKET", MON, MON.plusMonths(2), "EVERY_N_DAYS", 40)));
        verify(mapper, never()).insert(any(TownEventCalendarDO.class));
    }

    @Test
    void sameTownSameDaySameTypeIsRefusedAsDuplicate() {
        when(mapper.selectSameDayList(eq("双龙镇"), eq("MARKET"), eq(MON))).thenReturn(
                List.of(TownEventCalendarDO.builder().id(7L).town("双龙镇").eventType("MARKET").startDate(MON).build()));

        ServiceException ex = assertThrows(ServiceException.class,
                () -> service.create(req("MARKET", MON, MON, "NONE", 0)));

        assertTrue(ex.getMessage().contains("#7"), ex.getMessage());
        verify(mapper, never()).insert(any(TownEventCalendarDO.class));
    }

    /** 周期规则：锚点后第 3 天命中（间隔 3），第 4 天不命中；单次事件在区间内每天都命中 */
    @Test
    void effectiveOnExpandsCyclesOnlyOnTheirDays() {
        TownEventCalendarDO cycle = TownEventCalendarDO.builder().id(1L).town("双龙镇").eventType("MARKET")
                .startDate(MON).endDate(MON.plusMonths(1)).recurrence("EVERY_N_DAYS").intervalDays(3).build();
        TownEventCalendarDO once = TownEventCalendarDO.builder().id(2L).town("龙潭镇").eventType("FARMING")
                .startDate(MON).endDate(MON.plusDays(10)).recurrence("NONE").intervalDays(0).build();
        when(mapper.selectList(any())).thenReturn(List.of(cycle, once));

        List<String> onDayPlus3 = service.listEffectiveOn(MON.plusDays(3)).stream()
                .map(TownEventCalendarDO::getTown).collect(Collectors.toList());
        assertEquals(List.of("双龙镇", "龙潭镇"), onDayPlus3);

        List<String> onDayPlus4 = service.listEffectiveOn(MON.plusDays(4)).stream()
                .map(TownEventCalendarDO::getTown).collect(Collectors.toList());
        assertEquals(List.of("龙潭镇"), onDayPlus4, "间隔 3 的场期不该在第 4 天也被数出来");
    }

    /** 主路径：留空 recurrence 要落成 NONE（不是 null 进库）—— 读的人无法区分"单次"和"没填"的那种空值 */
    @Test
    void blankRecurrenceIsStoredAsNoneNotAsNull() {
        TownEventSaveReqVO v = req("MARKET", MON, MON, null, null);

        service.create(v);

        org.mockito.ArgumentCaptor<TownEventCalendarDO> cap =
                org.mockito.ArgumentCaptor.forClass(TownEventCalendarDO.class);
        verify(mapper).insert(cap.capture());
        assertEquals("NONE", cap.getValue().getRecurrence());
        assertEquals(0, cap.getValue().getIntervalDays());
    }

    @Test
    void nullDayYieldsEmptyInsteadOfMatchingEverything() {
        assertEquals(List.of(), service.listEffectiveOn(null));
        verify(mapper, never()).selectSameDayList(anyString(), anyString(), any());
    }
}
