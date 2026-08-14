package cn.iocoder.yudao.module.transport.integration.voice;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/**
 * 百度智能云语音服务接入配置（短语音识别 / 机器翻译 / 语音合成）。
 * 密钥走服务器环境变量注入（.env），不提交仓库；未配置时语音接口返回 VOICE_NOT_CONFIGURED。
 */
@ConfigurationProperties(prefix = "yudao.transport.voice.baidu")
@Validated
@Data
public class BaiduVoiceProperties {

    /** 百度智能云 API Key（控制台-安全认证） */
    private String apiKey;

    /** 百度智能云 Secret Key */
    private String secretKey;

    /** 连接超时 */
    private Duration connectTimeout = Duration.ofSeconds(3);

    /** 读取超时 */
    private Duration readTimeout = Duration.ofSeconds(15);

    /** access_token 提前刷新窗口（百度 token 有效期 30 天，提前 1 天刷新） */
    private Duration tokenRefreshAhead = Duration.ofDays(1);

    /** 是否已配置密钥 */
    public boolean isConfigured() {
        return apiKey != null && !apiKey.isBlank() && secretKey != null && !secretKey.isBlank();
    }

}
