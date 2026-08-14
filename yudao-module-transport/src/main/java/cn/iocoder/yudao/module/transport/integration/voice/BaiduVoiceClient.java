package cn.iocoder.yudao.module.transport.integration.voice;

import cn.hutool.core.util.StrUtil;
import cn.hutool.http.HttpRequest;
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import cn.iocoder.yudao.framework.common.exception.ServiceException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.module.transport.enums.ErrorCodeConstants.*;

/**
 * 百度智能云语音服务客户端（短语音识别 / 机器翻译）。
 * 鉴权用 API Key + Secret Key 换取 access_token（30 天有效，本地缓存提前刷新）。
 */
@Component
@Slf4j
public class BaiduVoiceClient {

    private static final String TOKEN_URL = "https://aip.baidubce.com/oauth/2.0/token";
    private static final String SHORT_SPEECH_URL = "https://aip.baidubce.com/rest/2.0/speech/v1/short_speech";
    private static final String TRANSLATE_URL = "https://aip.baidubce.com/rest/2.0/mt/potrans/v1/translate";
    private static final String CUID = "cargo-post-miniprogram";

    private final BaiduVoiceProperties properties;

    /** access_token 缓存 */
    private volatile String accessToken;
    private volatile long tokenExpireAtMillis;

    public BaiduVoiceClient(BaiduVoiceProperties properties) {
        this.properties = properties;
    }

    // ==================== 鉴权 ====================

    private String getAccessToken() {
        if (!properties.isConfigured()) {
            throw exception(VOICE_NOT_CONFIGURED);
        }
        long now = System.currentTimeMillis();
        // 提前 1 天刷新
        long refreshAhead = properties.getTokenRefreshAhead().toMillis();
        if (accessToken != null && now < tokenExpireAtMillis - refreshAhead) {
            return accessToken;
        }
        synchronized (this) {
            if (accessToken != null && now < tokenExpireAtMillis - refreshAhead) {
                return accessToken;
            }
            String url = TOKEN_URL + "?grant_type=client_credentials"
                    + "&client_id=" + properties.getApiKey()
                    + "&client_secret=" + properties.getSecretKey();
            try {
                String resp = HttpRequest.post(url).execute().body();
                JSONObject json = JSONUtil.parseObj(resp);
                String token = json.getStr("access_token");
                if (StrUtil.isBlank(token)) {
                    log.error("[getAccessToken] 获取失败: {}", resp);
                    throw exception(VOICE_NOT_CONFIGURED);
                }
                accessToken = token;
                // expires_in 默认 2592000（30 天）
                tokenExpireAtMillis = now + (json.getLong("expires_in", 2592000L) * 1000);
                return accessToken;
            } catch (ServiceException e) {
                throw e;
            } catch (Exception e) {
                log.error("[getAccessToken] 调用异常", e);
                throw exception(VOICE_NOT_CONFIGURED);
            }
        }
    }

    // ==================== 短语音识别 ====================

    /**
     * 短语音识别：音频 → 文本
     *
     * @param audio  音频字节（微信 recorder 输出）
     * @param format 音频格式：pcm / wav / m4a（微信 aac 对应 m4a）
     * @param rate   采样率：16000 推荐
     * @param lang   识别语言：zh（普通话）/ en（英语）
     */
    public String recognize(byte[] audio, String format, int rate, String lang) {
        if (audio == null || audio.length == 0) {
            throw exception(VOICE_RECOGNIZE_FAIL, "音频为空");
        }
        String token = getAccessToken();
        int devPid = "en".equalsIgnoreCase(lang) ? 1737 : 1537; // 1537 普通话 / 1737 英语
        JSONObject body = new JSONObject();
        body.set("format", format);
        body.set("rate", rate);
        body.set("channel", 1);
        body.set("cuid", CUID);
        body.set("token", token);
        body.set("dev_pid", devPid);
        body.set("speech", Base64.getEncoder().encodeToString(audio));
        body.set("len", audio.length);
        String url = SHORT_SPEECH_URL + "?access_token=" + token;
        try {
            String resp = HttpRequest.post(url).body(body.toString()).execute().body();
            JSONObject json = JSONUtil.parseObj(resp);
            int errNo = json.getInt("err_no", -1);
            if (errNo != 0) {
                log.warn("[recognize] 识别失败 err_no={} msg={}", errNo, json.getStr("err_msg"));
                throw exception(VOICE_RECOGNIZE_FAIL, json.getStr("err_msg"));
            }
            JSONArray result = json.getJSONArray("result");
            if (result == null || result.isEmpty()) {
                throw exception(VOICE_RECOGNIZE_FAIL, "未识别到内容");
            }
            return result.getStr(0);
        } catch (ServiceException e) {
            throw e;
        } catch (Exception e) {
            log.error("[recognize] 调用异常", e);
            throw exception(VOICE_RECOGNIZE_FAIL, e.getMessage());
        }
    }

    // ==================== 文本翻译 ====================

    /**
     * 文本翻译：中英文互译
     *
     * @param text 原文
     * @param from 源语言：zh / en
     * @param to   目标语言：en / zh
     */
    public String translate(String text, String from, String to) {
        if (StrUtil.isBlank(text)) {
            throw exception(VOICE_TRANSLATE_FAIL, "文本为空");
        }
        String token = getAccessToken();
        JSONObject body = new JSONObject();
        body.set("q", text);
        body.set("from", StrUtil.blankToDefault(from, "zh"));
        body.set("to", StrUtil.blankToDefault(to, "en"));
        String url = TRANSLATE_URL + "?access_token=" + token;
        try {
            String resp = HttpRequest.post(url)
                    .header("Content-Type", "application/json")
                    .body(body.toString().getBytes(StandardCharsets.UTF_8))
                    .execute().body();
            JSONObject json = JSONUtil.parseObj(resp);
            if (json.containsKey("error_code")) {
                log.warn("[translate] 翻译失败 code={} msg={}", json.getInt("error_code"), json.getStr("error_msg"));
                throw exception(VOICE_TRANSLATE_FAIL, json.getStr("error_msg"));
            }
            JSONArray transResult = json.getJSONArray("trans_result");
            if (transResult == null || transResult.isEmpty()) {
                throw exception(VOICE_TRANSLATE_FAIL, "无翻译结果");
            }
            return transResult.getJSONObject(0).getStr("dst");
        } catch (ServiceException e) {
            throw e;
        } catch (Exception e) {
            log.error("[translate] 调用异常", e);
            throw exception(VOICE_TRANSLATE_FAIL, e.getMessage());
        }
    }

}
