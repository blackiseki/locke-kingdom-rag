package com.locke.ai.llm;

/**
 * token 用量。
 *
 * <p>{@code estimated=true} 表示至少有一项不是服务端返回的 usage，而是
 * 用 {@link com.locke.ai.util.TokenCounter} 估算的。前端应把这两种来源
 * 区分展示，避免把估算值当成账单。
 *
 * @param promptTokens     输入 token
 * @param completionTokens 输出 token
 * @param estimated        是否包含估算值
 */
public record LlmUsage(int promptTokens, int completionTokens, boolean estimated) {

    public int totalTokens() {
        return promptTokens + completionTokens;
    }
}
