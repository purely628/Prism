package com.prism.chat.util;

/**
 * Token 近似估算：ceil(中文字符数 × 1 + 非中文字符数 ÷ 4)。
 *
 * <p>依据：中文按一字约等于 1 token；英文/数字/符号约 4 个字符折 1 token
 * （主流 tokenizer 的粗略经验值）。这是<b>演示级近似</b>——
 * 生产应对接真实 tokenizer（tiktoken / GLM tokenizer 等），
 * 高中英混排文本上本估算的误差可达 ±20%；
 * 真实用量以上游返回的 usage 为准（见 ChatOrchestrator.resolveUsage）。</p>
 */
public final class TokenEstimator {

    private TokenEstimator() {
    }

    public static int estimate(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        int cjk = 0;
        int other = 0;
        for (int i = 0; i < text.length(); ) {
            int cp = text.codePointAt(i);
            if (isCjk(cp)) {
                cjk++;
            } else {
                other++;
            }
            i += Character.charCount(cp);
        }
        return (int) Math.ceil(cjk + other / 4.0);
    }

    /** 是否汉字（Unicode HAN 文字系，覆盖常用简繁体与扩展区） */
    public static boolean isCjk(int codePoint) {
        return Character.UnicodeScript.of(codePoint) == Character.UnicodeScript.HAN;
    }
}
