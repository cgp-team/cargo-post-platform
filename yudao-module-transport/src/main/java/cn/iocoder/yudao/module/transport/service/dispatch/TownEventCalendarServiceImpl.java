package cn.iocoder.yudao.module.transport.service.dispatch;

import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.framework.mybatis.core.query.LambdaQueryWrapperX;
import cn.iocoder.yudao.module.transport.controller.admin.transport.townevent.vo.TownEventPageReqVO;
import cn.iocoder.yudao.module.transport.controller.admin.transport.townevent.vo.TownEventSaveReqVO;
import cn.iocoder.yudao.module.transport.dal.dataobject.dispatch.TownEventCalendarDO;
import cn.iocoder.yudao.module.transport.dal.mysql.dispatch.TownEventCalendarMapper;
import cn.iocoder.yudao.module.transport.enums.dispatch.TownEventTypeEnum;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;
import org.springframework.validation.annotation.Validated;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.module.transport.enums.ErrorCodeConstants.TOWN_EVENT_DUPLICATE;
import static cn.iocoder.yudao.module.transport.enums.ErrorCodeConstants.TOWN_EVENT_NOT_EXISTS;
import static cn.iocoder.yudao.module.transport.enums.ErrorCodeConstants.TOWN_EVENT_PERIOD_ILLEGAL;
import static cn.iocoder.yudao.module.transport.enums.ErrorCodeConstants.TOWN_EVENT_RULE_ILLEGAL;

@Service
@Validated
public class TownEventCalendarServiceImpl implements TownEventCalendarService {

    /** 只有两种重复规则，写在这里而不是散在页面与 SQL 里：第三轮再加就要先想清楚有没有数据能验证它 */
    private static final Set<String> RECURRENCES = Set.of("NONE", "EVERY_N_DAYS");
    private static final int MIN_INTERVAL_DAYS = 2;
    private static final int MAX_INTERVAL_DAYS = 31;

    @Resource
    private TownEventCalendarMapper mapper;

    @Override
    public Long create(TownEventSaveReqVO reqVO) {
        TownEventCalendarDO o = toDO(reqVO);
        validate(o);
        assertNotDuplicated(o, null);
        mapper.insert(o);
        return o.getId();
    }

    @Override
    public void update(TownEventSaveReqVO reqVO) {
        if (reqVO.getId() == null) {
            throw exception(TOWN_EVENT_NOT_EXISTS);
        }
        get(reqVO.getId());
        TownEventCalendarDO o = toDO(reqVO);
        o.setId(reqVO.getId());
        validate(o);
        assertNotDuplicated(o, reqVO.getId());
        mapper.updateById(o);
    }

    @Override
    public void delete(Long id) {
        get(id);
        mapper.deleteById(id);
    }

    @Override
    public TownEventCalendarDO get(Long id) {
        TownEventCalendarDO o = id == null ? null : mapper.selectById(id);
        if (o == null) {
            throw exception(TOWN_EVENT_NOT_EXISTS);
        }
        return o;
    }

    @Override
    public PageResult<TownEventCalendarDO> getPage(TownEventPageReqVO reqVO) {
        return mapper.selectPage(reqVO);
    }

    @Override
    public List<TownEventCalendarDO> listEffectiveOn(LocalDate day) {
        if (day == null) {
            return List.of();
        }
        // 先按区间捞，再在内存里过周期：这张表一年撑死几百行，为它写一段取模 SQL 反而看不见规则本身
        List<TownEventCalendarDO> covering = mapper.selectList(new LambdaQueryWrapperX<TownEventCalendarDO>()
                .le(TownEventCalendarDO::getStartDate, day)
                .ge(TownEventCalendarDO::getEndDate, day)
                .orderByAsc(TownEventCalendarDO::getTown));
        return covering.stream().filter(o -> "NONE".equals(o.getRecurrence()) || hitsCycle(o, day))
                .collect(Collectors.toList());
    }

    private static boolean hitsCycle(TownEventCalendarDO o, LocalDate day) {
        Integer interval = o.getIntervalDays();
        if (interval == null || interval < 1 || o.getStartDate() == null) {
            return false;
        }
        return ChronoUnit.DAYS.between(o.getStartDate(), day) % interval == 0;
    }

    private static TownEventCalendarDO toDO(TownEventSaveReqVO reqVO) {
        return TownEventCalendarDO.builder()
                .town(reqVO.getTown() == null ? null : reqVO.getTown().trim())
                .eventType(reqVO.getEventType())
                .startDate(reqVO.getStartDate())
                .endDate(reqVO.getEndDate())
                .recurrence(reqVO.getRecurrence() == null || reqVO.getRecurrence().isBlank()
                        ? "NONE" : reqVO.getRecurrence().trim())
                .intervalDays(reqVO.getIntervalDays() == null ? 0 : reqVO.getIntervalDays())
                .note(reqVO.getNote())
                .build();
    }

    /**
     * 规则校验：填错不会报错、只会让情景提示静默漏事件，所以每条都给得出原因。
     */
    private static void validate(TownEventCalendarDO o) {
        if (o.getStartDate() == null || o.getEndDate() == null || o.getEndDate().isBefore(o.getStartDate())) {
            throw exception(TOWN_EVENT_PERIOD_ILLEGAL, "结束日不能早于起始日");
        }
        if (!TownEventTypeEnum.matches(o.getEventType())) {
            throw exception(TOWN_EVENT_RULE_ILLEGAL, "事件类型只能是 " + typeNames());
        }
        if (!RECURRENCES.contains(o.getRecurrence())) {
            throw exception(TOWN_EVENT_RULE_ILLEGAL, "重复规则只能是 " + String.join(" / ", RECURRENCES));
        }
        if ("EVERY_N_DAYS".equals(o.getRecurrence())) {
            int n = o.getIntervalDays() == null ? 0 : o.getIntervalDays();
            if (n < MIN_INTERVAL_DAYS || n > MAX_INTERVAL_DAYS) {
                // 间隔 1 天等于"每天"，那不是场期是噪声；超过一个月也不叫周期
                throw exception(TOWN_EVENT_RULE_ILLEGAL,
                        "按周期重复时间隔天数要在 " + MIN_INTERVAL_DAYS + "–" + MAX_INTERVAL_DAYS + " 之间");
            }
        }
    }

    private static String typeNames() {
        return java.util.Arrays.stream(TownEventTypeEnum.values()).map(Enum::name).collect(Collectors.joining(" / "));
    }

    private void assertNotDuplicated(TownEventCalendarDO o, Long selfId) {
        List<TownEventCalendarDO> same = mapper.selectSameDayList(o.getTown(), o.getEventType(), o.getStartDate());
        for (TownEventCalendarDO s : same) {
            if (!Objects.equals(s.getId(), selfId)) {
                throw exception(TOWN_EVENT_DUPLICATE, o.getTown() + " " + o.getStartDate() + "（已有记录 #" + s.getId() + "）");
            }
        }
    }
}
