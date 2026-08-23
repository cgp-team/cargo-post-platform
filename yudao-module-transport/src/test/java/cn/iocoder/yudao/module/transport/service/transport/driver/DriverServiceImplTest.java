package cn.iocoder.yudao.module.transport.service.transport.driver;

import cn.iocoder.yudao.module.transport.dal.dataobject.driver.DriverDO;
import cn.iocoder.yudao.module.transport.dal.mysql.driver.DriverMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDate;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * 司机驾驶证到期查询纯 Mockito 单测：已过期、今天到期、第 N 天到期、第 N+1 天不返回。
 * mock 的 mapper 按 SQL 语义（到期日不为空且 <= deadline，按到期日升序）过滤夹具数据。
 */
@ExtendWith(MockitoExtension.class)
class DriverServiceImplTest {

    @Mock private DriverMapper driverMapper;

    private DriverServiceImpl driverService;

    /** 夹具：已过期、今天到期、第 30 天到期、第 31 天到期、无到期日 */
    private List<DriverDO> fixture;

    @BeforeEach
    void setUp() {
        driverService = new DriverServiceImpl();
        ReflectionTestUtils.setField(driverService, "mapper", driverMapper);

        LocalDate today = LocalDate.now();
        fixture = Arrays.asList(
                DriverDO.builder().id(1L).name("已过期司机").licenseExpireDate(today.minusDays(1)).build(),
                DriverDO.builder().id(2L).name("今天到期").licenseExpireDate(today).build(),
                DriverDO.builder().id(3L).name("第30天到期").licenseExpireDate(today.plusDays(30)).build(),
                DriverDO.builder().id(4L).name("第31天到期").licenseExpireDate(today.plusDays(31)).build(),
                DriverDO.builder().id(5L).name("无到期日").licenseExpireDate(null).build()
        );
        // 模拟 SQL 语义：license_expire_date IS NOT NULL AND license_expire_date <= deadline，按到期日升序
        when(driverMapper.selectExpiringList(any(LocalDate.class))).thenAnswer(invocation -> {
            LocalDate deadline = invocation.getArgument(0);
            return fixture.stream()
                    .filter(o -> o.getLicenseExpireDate() != null && !o.getLicenseExpireDate().isAfter(deadline))
                    .sorted(Comparator.comparing(DriverDO::getLicenseExpireDate))
                    .collect(Collectors.toList());
        });
    }

    @Test
    void getExpiringList_30天内_含已过期与边界_排除第31天() {
        List<DriverDO> result = driverService.getExpiringList(30);

        // 已过期、今天到期、第 30 天到期均返回；第 31 天与无到期日不返回
        assertEquals(3, result.size());
        assertEquals(Arrays.asList(1L, 2L, 3L), result.stream().map(DriverDO::getId).collect(Collectors.toList()));
        // 按到期日升序
        assertTrue(result.get(0).getLicenseExpireDate().isBefore(result.get(1).getLicenseExpireDate()));
        // 截止时间 = 今天 + 30 天
        ArgumentCaptor<LocalDate> captor = ArgumentCaptor.forClass(LocalDate.class);
        verify(driverMapper).selectExpiringList(captor.capture());
        assertEquals(LocalDate.now().plusDays(30), captor.getValue());
    }

    @Test
    void getExpiringList_7天内_只返回已过期与今天到期() {
        List<DriverDO> result = driverService.getExpiringList(7);

        assertEquals(2, result.size());
        assertEquals(Arrays.asList(1L, 2L), result.stream().map(DriverDO::getId).collect(Collectors.toList()));
        ArgumentCaptor<LocalDate> captor = ArgumentCaptor.forClass(LocalDate.class);
        verify(driverMapper).selectExpiringList(captor.capture());
        assertEquals(LocalDate.now().plusDays(7), captor.getValue());
    }
}
