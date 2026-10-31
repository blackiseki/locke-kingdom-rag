package com.locke.ai.llm;

import java.util.List;

/**
 * 同步调用的完整结果。
 *
 * @param provider  实际成功的 provider id
 * @param model     实际使用的模型名
 * @param content   模型正文
 * @param usage     token 用量（可能部分为估算）
 * @param latencyMs 该 Provider 的调用耗时
 * @param attempts  按顺序发生的尝试记录，包含失败/跳过
 */
public record LlmCompletion(
        String provider,
        String model,
        String content,
        LlmUsage usage,
        long latencyMs,
        List<LlmAttempt> attempts) {

    public LlmCompletion {
        attempts = attempts == null ? List.of() : List.copyOf(attempts);
    }

    public LlmCompletion(String provider, String model, String content, LlmUsage usage, long latencyMs) {
        this(provider, model, content, usage, latencyMs, List.of());
    }

    public LlmCompletion withAttempts(List<LlmAttempt> newAttempts) {
        return new LlmCompletion(provider, model, content, usage, latencyMs, newAttempts);
    }
}
