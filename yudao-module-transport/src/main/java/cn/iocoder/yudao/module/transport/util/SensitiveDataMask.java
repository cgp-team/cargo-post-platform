package cn.iocoder.yudao.module.transport.util;

import cn.hutool.core.util.DesensitizedUtil;
import cn.hutool.core.util.StrUtil;

import java.util.regex.Matcher;

/**
 * 敏感字段的唯一掩码出口（队列 #93 / 契约 docs/AI_ASSIST_BOUNDARY.md §4）。
 *
 * <p>为什么单独一个类：后台列表接口今天就在回<b>明文手机号</b>（{@code DriverBaseVO}/{@code DriverRespVO}/
 * {@code FeedbackRespVO} 都有 {@code mobile} 字段），那有业务理由——调度要直接打给司机。
 * 但同一份数据被喂给外部推理服务时不能带明文，所以"要不要掩码"必须由<b>出口</b>决定，
 * 而不是每个调用处自己写一遍正则：自己写的第二份一定会有人漏掉一种形状。
 *
 * <p>判据：{@code scripts/check_admin_ai_data_mask.py} 钉三件事——本类是唯一实现、
 * 空值不许被折叠成看起来像结论的占位串、AI 侧的代码不许引用明文手机号字段。
 */
public final class SensitiveDataMask {

    /** 非手机号形状时的兜底：只保留首字符，其余整段屏蔽（不是"掩码成功"的暗示，是"这条我不敢猜"） */
    private static final int UNKNOWN_SHAPE_KEEP = 1;

    private SensitiveDataMask() {
    }

    /**
     * 手机号 → {@code 138****8001}。
     *
     * <p>⚠ 空值原样返回（null 进 null 出）：把"后端没给手机号"掩成 {@code "***"}，
     * 读的人就会以为"有一个但被屏蔽了"——那是把缺值折叠成一个看起来像结论的值，
     * 与金额缺值念成 {@code 0.00} 同一种病（见 {@code scripts/check_admin_money_absence.py}）。
     */
    public static String mobile(String raw) {
        if (StrUtil.isBlank(raw)) {
            return raw;
        }
        String digits = raw.trim();
        if (digits.matches("1\\d{10}")) {
            return DesensitizedUtil.mobilePhone(digits);
        }
        return unknownShape(digits);
    }

    /**
     * 证件号/长编号一类：只留后 4 位。空值同上，原样返回。
     * 这里不做"是不是身份证"的判断——调用方给什么字段是它的事，本方法只保证不会多给内容。
     */
    public static String tail4(String raw) {
        if (StrUtil.isBlank(raw)) {
            return raw;
        }
        String v = raw.trim();
        if (v.length() <= 4) {
            return unknownShape(v);
        }
        return "****" + v.substring(v.length() - 4);
    }

    /**
     * 整段文本里内嵌的手机号逐个掩掉（AI 侧送出去的就是这一段）。
     *
     * <p>为什么放在本类而不是让调用方自己写正则：§4 的口径是"要不要掩码由<b>出口</b>决定"。
     * 调用方各写一份 `1[3-9]\d{9}`，就会出现"有人写、有人没写"的分布 —— 而那正是这本闸要抓的形状。
     * 掩完仍不自检的也不接受：本方法末尾自己再查一遍，漏了就抛，而不是把号码发给对面。
     */
    public static String maskEmbeddedMobiles(String text) {
        if (StrUtil.isBlank(text)) {
            return text;
        }
        Matcher m = EMBEDDED_MOBILE.matcher(text);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            m.appendReplacement(sb, Matcher.quoteReplacement(mobile(m.group())));
        }
        m.appendTail(sb);
        String out = sb.toString();
        if (EMBEDDED_MOBILE.matcher(out).find()) {
            throw new IllegalStateException("掩码后仍残留疑似手机号，拒绝外发（这是出口自己的自检，不靠调用方）");
        }
        return out;
    }

    private static final java.util.regex.Pattern EMBEDDED_MOBILE =
            java.util.regex.Pattern.compile("1[3-9]\\d{9}");

    private static String unknownShape(String v) {
        int keep = Math.min(UNKNOWN_SHAPE_KEEP, v.length());
        return v.substring(0, keep) + "***";
    }
}
