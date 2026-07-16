package cn.iocoder.yudao.module.transport.controller.admin;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TransportTestControllerTest {

    private final TransportTestController controller = new TransportTestController();

    @Test
    void shouldReturnModuleRunningMessage() {
        CommonResult<String> result = controller.get();

        assertEquals("transport module is running", result.getData());
    }

}
