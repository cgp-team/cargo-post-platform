package cn.iocoder.yudao.module.transport.integration.voice;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * 语音服务装配：注册百度智能云配置
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(BaiduVoiceProperties.class)
public class VoiceConfiguration {
}
