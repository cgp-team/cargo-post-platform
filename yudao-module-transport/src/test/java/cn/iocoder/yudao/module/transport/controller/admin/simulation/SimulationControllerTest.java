package cn.iocoder.yudao.module.transport.controller.admin.simulation;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.module.transport.service.developer.DeveloperSimulationGuard;
import cn.iocoder.yudao.module.transport.service.simulation.SimulationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * SimulationController 单元测试：验证 Guard 调用链。
 *
 * 注：@PreAuthorize 的 RBAC 校验由 Spring Security 框架保证，此处不重复测试。
 * 本测试聚焦于 Controller 是否正确调用 Guard，以及 Guard 的异常是否正确传播。
 */
class SimulationControllerTest {

    private SimulationController controller;
    private SimulationService simulationService;
    private DeveloperSimulationGuard guard;

    @BeforeEach
    void setUp() {
        simulationService = mock(SimulationService.class);
        guard = mock(DeveloperSimulationGuard.class);
        controller = new SimulationController();
        ReflectionTestUtils.setField(controller, "simulationService", simulationService);
        ReflectionTestUtils.setField(controller, "guard", guard);
    }

    // ========== start ==========

    @Test
    void start_calls_guard_requireSimulationControl() {
        controller.start(100L, 7L, 10.0);
        verify(guard).requireSimulationControl();
        verify(simulationService).start(100L, 7L, 10.0);
    }

    @Test
    void start_propagates_guard_exception() {
        doThrow(new ServiceException(1_005_015_000, "请先开启开发者模式"))
                .when(guard).requireSimulationControl();

        ServiceException ex = assertThrows(ServiceException.class,
                () -> controller.start(100L, 7L, 10.0));
        assertEquals(1_005_015_000, ex.getCode());
        verifyNoInteractions(simulationService);
    }

    // ========== pause ==========

    @Test
    void pause_calls_guard_requireSimulationControl() {
        controller.pause(7L);
        verify(guard).requireSimulationControl();
        verify(simulationService).pause(7L);
    }

    @Test
    void pause_propagates_guard_exception() {
        doThrow(new ServiceException(1_005_015_001, "当前环境未开启模拟能力"))
                .when(guard).requireSimulationControl();

        ServiceException ex = assertThrows(ServiceException.class,
                () -> controller.pause(7L));
        assertEquals(1_005_015_001, ex.getCode());
        verifyNoInteractions(simulationService);
    }

    // ========== resume ==========

    @Test
    void resume_calls_guard_requireSimulationControl() {
        controller.resume(7L);
        verify(guard).requireSimulationControl();
        verify(simulationService).resume(7L);
    }

    @Test
    void resume_propagates_guard_exception() {
        doThrow(new ServiceException(1_005_015_000, "请先开启开发者模式"))
                .when(guard).requireSimulationControl();

        assertThrows(ServiceException.class, () -> controller.resume(7L));
        verifyNoInteractions(simulationService);
    }

    // ========== reset ==========

    @Test
    void reset_calls_guard_requireSimulationControl() {
        controller.reset(7L);
        verify(guard).requireSimulationControl();
        verify(simulationService).reset(7L);
    }

    @Test
    void reset_propagates_guard_exception() {
        doThrow(new ServiceException(1_005_015_001, "当前环境未开启模拟能力"))
                .when(guard).requireSimulationControl();

        assertThrows(ServiceException.class, () -> controller.reset(7L));
        verifyNoInteractions(simulationService);
    }

    // ========== speed ==========

    @Test
    void setSpeed_calls_guard_requireSimulationControl() {
        controller.setSpeed(7L, 60.0);
        verify(guard).requireSimulationControl();
        verify(simulationService).setSpeed(7L, 60.0);
    }

    @Test
    void setSpeed_propagates_guard_exception() {
        doThrow(new ServiceException(1_005_015_000, "请先开启开发者模式"))
                .when(guard).requireSimulationControl();

        assertThrows(ServiceException.class, () -> controller.setSpeed(7L, 60.0));
        verifyNoInteractions(simulationService);
    }

    // ========== status ==========

    @Test
    void status_calls_guard_requireSimulationView() {
        SimulationStatusRespVO mockStatus = new SimulationStatusRespVO();
        mockStatus.setVehicleId(7L);
        mockStatus.setStatus(1);
        when(simulationService.getStatus(7L)).thenReturn(mockStatus);

        var result = controller.status(7L);
        verify(guard).requireSimulationView();
        verify(simulationService).getStatus(7L);
        assertNotNull(result.getData());
        assertEquals(7L, result.getData().getVehicleId());
    }

    @Test
    void status_propagates_guard_exception_when_developerMode_false() {
        doThrow(new ServiceException(1_005_015_000, "请先开启开发者模式"))
                .when(guard).requireSimulationView();

        ServiceException ex = assertThrows(ServiceException.class,
                () -> controller.status(7L));
        assertEquals(1_005_015_000, ex.getCode());
        verifyNoInteractions(simulationService);
    }

    @Test
    void status_does_NOT_throw_when_environment_disabled() {
        // 关键：status 在 environment=false 时不应报错
        // requireSimulationView 只检查 developerMode，不检查 environment
        // 所以 guard 不抛异常，controller 正常返回
        SimulationStatusRespVO mockStatus = new SimulationStatusRespVO();
        mockStatus.setVehicleId(7L);
        when(simulationService.getStatus(7L)).thenReturn(mockStatus);

        // guard.requireSimulationView() 不抛异常（只有 developerMode=false 才抛）
        var result = controller.status(7L);
        verify(guard).requireSimulationView();
        assertNotNull(result.getData());
    }

}
