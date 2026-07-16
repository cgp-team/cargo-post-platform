package cn.iocoder.yudao.module.transport.controller.admin;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import static cn.iocoder.yudao.framework.common.pojo.CommonResult.success;

@Tag(name = "管理后台 - 客货邮模块接入验证")
@RestController
@RequestMapping("/transport/test")
public class TransportTestController {

    @GetMapping("/get")
    @Operation(summary = "验证客货邮业务模块已加载")
    public CommonResult<String> get() {
        return success("transport module is running");
    }

}
