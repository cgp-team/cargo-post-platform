package cn.iocoder.yudao.module.transport.service.transport.vehicle;

import cn.iocoder.yudao.module.transport.dal.dataobject.vehicle.VehicleDO;
import cn.iocoder.yudao.module.transport.dal.mysql.vehicle.VehicleMapper;
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
 * 车辆保险到期查询纯 Mockito 单测：已过期、今天到期、第 N 天到期、第 N+1 天不返回。
 * mock 的 mapper 按 SQL 语义（到期日不为空且 <= deadline，按到期日升序）过滤夹具数据。
 */
@ExtendWith(MockitoExtension.class)
class VehicleServiceImplTest {

    @Mock private VehicleMapper vehicleMapper;

    private VehicleServiceImpl vehicleService;

    /** 夹具：已过期、今天到期、第 30 天到期、第 31 天到期、无到期日 */
    private List<VehicleDO> fixture;

    @BeforeEach
    void setUp() {
        vehicleService = new VehicleServiceImpl();
        ReflectionTestUtils.setField(vehicleService, "mapper", vehicleMapper);

        LocalDate today = LocalDate.now();
        fixture = Arrays.asList(
                VehicleDO.builder().id(1L).plateNo("渝A00001").insuranceExpireDate(today.minusDays(1)).build(),
                VehicleDO.builder().id(2L).plateNo("渝A00002").insuranceExpireDate(today).build(),
                VehicleDO.builder().id(3L).plateNo("渝A00003").insuranceExpireDate(today.plusDays(30)).build(),
                VehicleDO.builder().id(4L).plateNo("渝A00004").insuranceExpireDate(today.plusDays(31)).build(),
                VehicleDO.builder().id(5L).plateNo("渝A00005").insuranceExpireDate(null).build()
        );
        // 模拟 SQL 语义：insurance_expire_date IS NOT NULL AND insurance_expire_date <= deadline，按到期日升序
        when(vehicleMapper.selectExpiringList(any(LocalDate.class))).thenAnswer(invocation -> {
            LocalDate deadline = invocation.getArgument(0);
            return fixture.stream()
                    .filter(o -> o.getInsuranceExpireDate() != null && !o.getInsuranceExpireDate().isAfter(deadline))
                    .sorted(Comparator.comparing(VehicleDO::getInsuranceExpireDate))
                    .collect(Collectors.toList());
        });
    }

    @Test
    void getExpiringList_30天内_含已过期与边界_排除第31天() {
        List<VehicleDO> result = vehicleService.getExpiringList(30);

        // 已过期、今天到期、第 30 天到期均返回；第 31 天与无到期日不返回
        assertEquals(3, result.size());
        assertEquals(Arrays.asList(1L, 2L, 3L), result.stream().map(VehicleDO::getId).collect(Collectors.toList()));
        // 按到期日升序
        assertTrue(result.get(0).getInsuranceExpireDate().isBefore(result.get(1).getInsuranceExpireDate()));
        // 截止时间 = 今天 + 30 天
        ArgumentCaptor<LocalDate> captor = ArgumentCaptor.forClass(LocalDate.class);
        verify(vehicleMapper).selectExpiringList(captor.capture());
        assertEquals(LocalDate.now().plusDays(30), captor.getValue());
    }

    @Test
    void getExpiringList_7天内_只返回已过期与今天到期() {
        List<VehicleDO> result = vehicleService.getExpiringList(7);

        assertEquals(2, result.size());
        assertEquals(Arrays.asList(1L, 2L), result.stream().map(VehicleDO::getId).collect(Collectors.toList()));
        ArgumentCaptor<LocalDate> captor = ArgumentCaptor.forClass(LocalDate.class);
        verify(vehicleMapper).selectExpiringList(captor.capture());
        assertEquals(LocalDate.now().plusDays(7), captor.getValue());
    }
}
