package cn.iocoder.yudao.module.transport.framework.pay;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "yudao.pay")
public class PayProperties {
    // ========== 支付宝 ==========
    private String alipayAppId = "";
    /** RSA2 应用私钥 */
    private String alipayPrivateKey = "";
    /** 支付宝公钥（在开放平台获取） */
    private String alipayPublicKey = "";
    private String alipayGateway = "https://openapi.alipay.com/gateway.do";
    private String alipayNotifyUrl = "";

    // ========== 微信支付 ==========
    private String wechatAppId = "";
    private String wechatMchId = "";
    /** API v3 密钥 */
    private String wechatApiV3Key = "";
    /** 商户证书序列号 */
    private String wechatSerialNumber = "";
    /** 商户私钥（apiclient_key.pem 内容） */
    private String wechatPrivateKey = "";
    private String wechatNotifyUrl = "";

    public boolean isAlipayEnabled() {
        return !alipayAppId.isEmpty() && !alipayPrivateKey.isEmpty();
    }

    public boolean isWechatEnabled() {
        return !wechatMchId.isEmpty() && !wechatPrivateKey.isEmpty();
    }
}
