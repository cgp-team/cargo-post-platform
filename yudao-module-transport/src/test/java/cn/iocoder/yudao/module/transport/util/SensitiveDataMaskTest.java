package cn.iocoder.yudao.module.transport.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 掩码出口的语义（队列 #93 / 契约 §4）：钉的是** leaks 什么**，不是 Hutool 今天怎么排版。
 * 所以断言写成"中间四位一定不在结果里"，而不是硬编码某个长度/星号个数 ——
 * 依赖换个版本就红一次的判据，最后只会被人改成"能过"。
 */
class SensitiveDataMaskTest {

    @Test
    void mobileKeepsOnlyHeadAndTail() {
        String masked = SensitiveDataMask.mobile("13800138001");

        assertTrue(masked.startsWith("138"), masked);
        assertTrue(masked.endsWith("8001"), masked);
        assertTrue(masked.contains("*"), masked);
        assertFalse(masked.contains("0013"), "中间那段还是明文：" + masked);
        assertFalse(masked.contains("13800138001"), masked);
    }

    /** 空值不许被折叠成"像被掩过"的串：null/空/全空白都原样返回，读的人才能分辨"没给"和"给了被掩码" */
    @Test
    void blankStaysBlankInsteadOfBecomingAFakeMask() {
        assertNull(SensitiveDataMask.mobile(null));
        assertEquals("", SensitiveDataMask.mobile(""));
        assertEquals("   ", SensitiveDataMask.mobile("   "));
        assertNull(SensitiveDataMask.tail4(null));
        assertEquals("", SensitiveDataMask.tail4(""));
    }

    /** 不是手机号形状的东西不外泄内容（座机、带区号、脏数据）：只留首字符 */
    @Test
    void unexpectedShapeDoesNotLeakTheRest() {
        String masked = SensitiveDataMask.mobile("0351-8886666");

        assertTrue(masked.endsWith("***"), masked);
        assertFalse(masked.contains("8886666"), masked);
    }

    @Test
    void tail4KeepsLastFourOnly() {
        assertEquals("****7X23", SensitiveDataMask.tail4("500221199003077X23"));
        // 比 4 位还短的输入：留不出"后四位"，整段屏蔽而不是原样吐出
        assertTrue(SensitiveDataMask.tail4("123").endsWith("***"), SensitiveDataMask.tail4("123"));
        assertFalse(SensitiveDataMask.tail4("123").contains("12"), SensitiveDataMask.tail4("123"));
    }

    /** 整段文本里内嵌的号码也要掩 —— AI 侧送出去的是这种文本，不是单独的 mobile 字段。 */
    @Test
    void 内嵌手机号逐个掩掉且不留残值() {
        String out = SensitiveDataMask.maskEmbeddedMobiles("司机 13800138000 说路断了，联系 13912345678");
        assertTrue(out.contains("138****8000"), out);
        assertTrue(out.contains("139****5678"), out);
        assertFalse(java.util.regex.Pattern.compile("1[3-9]\\d{9}").matcher(out).find(), "掩完仍残留明文：" + out);
    }

    /** 空白进空白出：把"没给"掩成 "***" 会被读成"有一个但被屏蔽了"（与金额缺值同一种病）。 */
    @Test
    void 空文本不被掩成占位串() {
        assertNull(SensitiveDataMask.maskEmbeddedMobiles(null));
        assertEquals("", SensitiveDataMask.maskEmbeddedMobiles(""));
    }
}
