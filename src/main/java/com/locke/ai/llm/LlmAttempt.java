package com.locke.ai.llm;

/**
 * 单次 Provider 尝试记录。
 *
 * <p>成功、失败、跳过都会记录：前端/排障需要知道为什么最终用了某个
 * Provider，而不是只看到一个结果。
 *
 * @param status success / failed / skipped
 */
public record LlmAttempt(
        String provider,
        String model,
        String status,
        long latencyMs,
        Integer promptTokens,
        Integer completionTokens,
        String error) {

    public static LlmAttempt success(String provider, String model, long latencyMs, LlmUsage usage) {
        return new LlmAttempt(provider, model, "success", latencyMs,
                usage == null ? null : usage.promptTokens(),
                usage == null ? null : usage.completionTokens(),
                null);
    }

    public static LlmAttempt failed(String provider, String model, long latencyMs, String error) {
        return new LlmAttempt(provider, model, "failed", latencyMs, null, null, error);
    }

    public static LlmAttempt skipped(String provider, String model, String reason) {
        return new LlmAttempt(provider, model, "skipped", 0L, null, null, reason);
    }

    public boolean isSuccess() {
        return "success".equals(status);
    }
}
