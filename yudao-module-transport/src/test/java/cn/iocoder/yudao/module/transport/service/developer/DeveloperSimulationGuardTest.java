package cn.iocoder.yudao.module.transport.service.developer;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.framework.security.core.util.SecurityFrameworkUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * DeveloperSimulationGuard 单元测试
 */
class DeveloperSimulationGuardTest {

    private DeveloperSimulationGuard guard;
    private DeveloperModeService developerModeService;

    @BeforeEach
    void setUp() {
        developerModeService = mock(DeveloperModeService.class);
        guard = new DeveloperSimulationGuard();
        ReflectionTestUtils.setField(guard, "developerModeService", developerModeService);
    }

    // ========== requireSimulationView（status 用） ==========

    @Test
    void requireSimulationView_throws_when_developerMode_false() {
        try (MockedStatic<SecurityFrameworkUtils> mocked = mockStatic(SecurityFrameworkUtils.class)) {
            mocked.when(SecurityFrameworkUtils::getLoginUserId).thenReturn(100L);
            when(developerModeService.isDeveloperMode(100L)).thenReturn(false);
            ReflectionTestUtils.setField(guard, "environmentSimulationEnabled", true);

            ServiceException ex = assertThrows(ServiceException.class, () -> guard.requireSimulationView());
            assertEquals(1_005_015_000, ex.getCode()); // DEVELOPER_MODE_REQUIRED
        }
    }

    @Test
    void requireSimulationView_passes_when_developerMode_true_environment_false() {
        // 关键：status 在 environment=false 时不应报错
        try (MockedStatic<SecurityFrameworkUtils> mocked = mockStatic(SecurityFrameworkUtils.class)) {
            mocked.when(SecurityFrameworkUtils::getLoginUserId).thenReturn(100L);
            when(developerModeService.isDeveloperMode(100L)).thenReturn(true);
            ReflectionTestUtils.setField(guard, "environmentSimulationEnabled", false);

            assertDoesNotThrow(() -> guard.requireSimulationView());
        }
    }

    @Test
    void requireSimulationView_passes_when_all_conditions_met() {
        try (MockedStatic<SecurityFrameworkUtils> mocked = mockStatic(SecurityFrameworkUtils.class)) {
            mocked.when(SecurityFrameworkUtils::getLoginUserId).thenReturn(100L);
            when(developerModeService.isDeveloperMode(100L)).thenReturn(true);
            ReflectionTestUtils.setField(guard, "environmentSimulationEnabled", true);

            assertDoesNotThrow(() -> guard.requireSimulationView());
        }
    }

    // ========== requireSimulationControl（start/pause/resume/reset/speed 用） ==========

    @Test
    void requireSimulationControl_throws_when_developerMode_false() {
        try (MockedStatic<SecurityFrameworkUtils> mocked = mockStatic(SecurityFrameworkUtils.class)) {
            mocked.when(SecurityFrameworkUtils::getLoginUserId).thenReturn(100L);
            when(developerModeService.isDeveloperMode(100L)).thenReturn(false);
            ReflectionTestUtils.setField(guard, "environmentSimulationEnabled", true);

            ServiceException ex = assertThrows(ServiceException.class, () -> guard.requireSimulationControl());
            assertEquals(1_005_015_000, ex.getCode()); // DEVELOPER_MODE_REQUIRED
        }
    }

    @Test
    void requireSimulationControl_throws_when_environment_disabled() {
        try (MockedStatic<SecurityFrameworkUtils> mocked = mockStatic(SecurityFrameworkUtils.class)) {
            mocked.when(SecurityFrameworkUtils::getLoginUserId).thenReturn(100L);
            when(developerModeService.isDeveloperMode(100L)).thenReturn(true);
            ReflectionTestUtils.setField(guard, "environmentSimulationEnabled", false);

            ServiceException ex = assertThrows(ServiceException.class, () -> guard.requireSimulationControl());
            assertEquals(1_005_015_001, ex.getCode()); // SIMULATION_DISABLED
        }
    }

    @Test
    void requireSimulationControl_passes_when_all_conditions_met() {
        try (MockedStatic<SecurityFrameworkUtils> mocked = mockStatic(SecurityFrameworkUtils.class)) {
            mocked.when(SecurityFrameworkUtils::getLoginUserId).thenReturn(100L);
            when(developerModeService.isDeveloperMode(100L)).thenReturn(true);
            ReflectionTestUtils.setField(guard, "environmentSimulationEnabled", true);

            assertDoesNotThrow(() -> guard.requireSimulationControl());
        }
    }

    // ========== isEnvironmentSimulationEnabled ==========

    @Test
    void isEnvironmentSimulationEnabled_returns_configured_value() {
        ReflectionTestUtils.setField(guard, "environmentSimulationEnabled", true);
        assertTrue(guard.isEnvironmentSimulationEnabled());

        ReflectionTestUtils.setField(guard, "environmentSimulationEnabled", false);
        assertFalse(guard.isEnvironmentSimulationEnabled());
    }

}
