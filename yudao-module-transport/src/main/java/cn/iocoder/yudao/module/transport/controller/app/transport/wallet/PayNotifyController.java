package cn.iocoder.yudao.module.transport.controller.app.transport.wallet;

import cn.iocoder.yudao.module.transport.service.wallet.PayService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import jakarta.annotation.security.PermitAll;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 支付回调 Controller。
 * 支付宝/微信异步通知不携带 Bearer token，必须 @PermitAll 放行。
 * 安全性由 PayService 内的 RSA2 验签保证，不依赖框架认证。
 */
@Slf4j
@Tag(name = "用户 App - 支付回调")
@RestController
@RequestMapping("/transport/pay")
@Validated
public class PayNotifyController {

    @Resource
    private PayService payService;

    @PostMapping("/alipay/notify")
    @Operation(summary = "支付宝异步回调")
    @PermitAll
    public String alipayNotify(HttpServletRequest request) {
        Map<String, String> params = new LinkedHashMap<>();
        Enumeration<String> names = request.getParameterNames();
        while (names.hasMoreElements()) {
            String name = names.nextElement();
            params.put(name, request.getParameter(name));
        }
        log.info("[alipayNotify] 收到回调 out_trade_no={}", params.get("out_trade_no"));
        return payService.handleAlipayNotify(params);
    }

    @PostMapping("/wechat/notify")
    @Operation(summary = "微信支付异步回调")
    @PermitAll
    public String wechatNotify(HttpServletRequest request) {
        // TODO: 微信支付 v3 回调验签（需要读取 body + 验证签名头）
        // 配置商户证书后实现
        log.info("[wechatNotify] 收到微信回调（待实现验签）");
        return "{\"code\":\"SUCCESS\",\"message\":\"成功\"}";
    }
}
