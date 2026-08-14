package cn.iocoder.yudao.module.transport.service.transport.voice;

import cn.iocoder.yudao.module.transport.integration.voice.BaiduVoiceClient;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.validation.annotation.Validated;

/**
 * 语音服务实现：委托百度智能云客户端
 */
@Service
@Validated
@Slf4j
public class AppVoiceServiceImpl implements AppVoiceService {

    @Resource
    private BaiduVoiceClient baiduVoiceClient;

    @Override
    public String recognize(byte[] audio, String format, String lang) {
        // 微信 recorder aac → 百度 m4a；wav 直接用；缺省按 wav
        String bdFormat = format;
        if (format == null) {
            bdFormat = "wav";
        } else if ("aac".equalsIgnoreCase(format)) {
            bdFormat = "m4a";
        }
        return baiduVoiceClient.recognize(audio, bdFormat, 16000, lang);
    }

    @Override
    public String translate(String text, String from, String to) {
        return baiduVoiceClient.translate(text, from, to);
    }

}
