package com.locke.ai.util;

/**
 * Token 估算（成本控制用）。
 *
 * <p>这里是**启发式估算**，不是真实分词：中文语料下误差大约 ±15%，
 * 目的是快速看到「检索块是不是把 context 撑爆了」，不用于计费对账。
 * 需要精确值时应引入 jtokkit 这类真正的分词库，而不是继续调这里的系数。
 *
 * <p>实测经验（DeepSeek / OpenAI 系 tokenizer）：
 * <ul>
 *   <li>CJK 字符 ≈ 1 token / 字（略偏保守）</li>
 *   <li>ASCII 文本 ≈ 1 token / 4 字符</li>
 * </ul>
 */
public final class TokenCounter {

    /** ASCII 文本的平均字符数 / token。 */
    private static final double ASCII_CHARS_PER_TOKEN = 4.0;

    private TokenCounter() {
    }

    /** 估算一段文本的 token 数。 */
    public static int estimate(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        int cjk = 0;
        int other = 0;
        for (int i = 0; i < text.length(); i++) {
            if (isCjk(text.charAt(i))) {
                cjk++;
            } else {
                other++;
            }
        }
        return cjk + (int) Math.ceil(other / ASCII_CHARS_PER_TOKEN);
    }

    /** 估算多段文本之和，方便算 system + context + question 的总量。 */
    public static int estimateAll(String... texts) {
        if (texts == null) {
            return 0;
        }
        int total = 0;
        for (String text : texts) {
            total += estimate(text);
        }
        return total;
    }

    /**
     * 按 token 预算截断文本（从尾部裁，保留前面的内容）。
     *
     * <p>检索块过多时用它把 context 压回预算内——但更好的做法是在
     * {@code RetrieverService} 里控制 topK 和块大小，不要靠这里兜底。
     */
    public static String truncateToTokens(String text, int maxTokens) {
        if (text == null || maxTokens <= 0 || estimate(text) <= maxTokens) {
            return text;
        }
        int cjk = 0;
        int other = 0;
        for (int i = 0; i < text.length(); i++) {
            if (isCjk(text.charAt(i))) {
                cjk++;
            } else {
                other++;
            }
            if (cjk + (int) Math.ceil(other / ASCII_CHARS_PER_TOKEN) > maxTokens) {
                return text.substring(0, i);
            }
        }
        return text;
    }

    private static boolean isCjk(char c) {
        return (c >= 0x4E00 && c <= 0x9FFF)      // 基本汉字
                || (c >= 0x3400 && c <= 0x4DBF)   // 扩展 A
                || (c >= 0x3000 && c <= 0x303F)   // CJK 标点
                || (c >= 0xFF00 && c <= 0xFFEF);  // 全角字符
    }
}
