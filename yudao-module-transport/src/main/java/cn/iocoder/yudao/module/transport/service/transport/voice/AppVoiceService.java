package cn.iocoder.yudao.module.transport.service.transport.voice;

/**
 * 语音服务（小程序寄货语音输入 + 面对面翻译）：语音识别 / 文本翻译
 */
public interface AppVoiceService {

    /**
     * 语音识别：音频 → 文本
     *
     * @param audio  音频字节
     * @param format 音频格式（pcm/wav/m4a）
     * @param lang   识别语言（zh/en）
     */
    String recognize(byte[] audio, String format, String lang);

    /**
     * 文本翻译：中英文互译
     *
     * @param text 原文
     * @param from 源语言（zh/en）
     * @param to   目标语言（en/zh）
     */
    String translate(String text, String from, String to);

}
