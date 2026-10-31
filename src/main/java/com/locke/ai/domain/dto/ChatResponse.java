package com.locke.ai.domain.dto;

import com.locke.ai.llm.LlmAttempt;

import java.util.List;

/**
 * 问答响应。
 *
 * <p>保留了改造前的 {@code answer/sources/model/promptTokens/completionTokens/latencyMs}
 * 字段，避免旧前端与脚本立即失效；新增字段用于多 Provider、降级、多轮记忆与可观测性。
 *
 * @param answer           模型答案
 * @param sources          命中的参考资料
 * @param model            实际使用的模型名
 * @param provider         实际成功的 Provider id
 * @param promptTokens     输入 token（服务端 usage 或估算）
 * @param completionTokens 输出 token（服务端 usage 或估算）
 * @param totalTokens      输入 + 输出 token
 * @param estimatedTokens  是否至少有一项为估算值
 * @param latencyMs        端到端耗时（检索 + 模型）
 * @param retrievalMs      检索耗时
 * @param llmLatencyMs     模型调用耗时（含失败降级尝试）
 * @param fallback         是否发生了 Provider 降级
 * @param attempts         按顺序的 Provider 尝试记录
 * @param sessionId        本轮归属的会话 id；下一轮带上它即可续上下文。
 *                         请求里 {@code memory=false} 时为 null
 * @param historyTokens    本轮实际带进 prompt 的历史 token（摘要不计入）
 * @param contextCompressed 本轮是否触发了历史压缩（把更早的问答折叠进摘要）
 */
public record ChatResponse(
        String answer,
        List<Source> sources,
        String model,
        String provider,
        Integer promptTokens,
        Integer completionTokens,
        Integer totalTokens,
        Boolean estimatedTokens,
        long latencyMs,
        Long retrievalMs,
        Long llmLatencyMs,
        boolean fallback,
        List<LlmAttempt> attempts,
        String sessionId,
        Integer historyTokens,
        Boolean contextCompressed) {

    /**
     * 单条参考资料来源。
     *
     * @param spiritName 精灵名（元数据里带的话）
     * @param source     语料来源，如 data/wiki/xxx.md
     * @param snippet    片段摘要
     * @param score      向量相似度得分
     */
    public record Source(String spiritName, String source, String snippet, Double score) {
    }

    public ChatResponse {
        sources = sources == null ? List.of() : List.copyOf(sources);
        attempts = attempts == null ? List.of() : List.copyOf(attempts);
        if (totalTokens == null && promptTokens != null && completionTokens != null) {
            totalTokens = promptTokens + completionTokens;
        }
    }

    /** 兼容改造前只关心 6 个字段的调用方。 */
    public ChatResponse(String answer, List<Source> sources, String model,
                        Integer promptTokens, Integer completionTokens, long latencyMs) {
        this(answer, sources, model, null, promptTokens, completionTokens,
                promptTokens == null || completionTokens == null ? null : promptTokens + completionTokens,
                null, latencyMs, null, null, false, List.of(), null, null, null);
    }

    /** 兜底/降级响应的快捷构造，例如模型不可用时。 */
    public static ChatResponse fallback(String message, long latencyMs) {
        return fallback(message, null, latencyMs, List.of(), null, null);
    }

    /** 带 Provider 与尝试记录的兜底响应。 */
    public static ChatResponse fallback(String message, String provider, long latencyMs,
                                        List<LlmAttempt> attempts) {
        return fallback(message, provider, latencyMs, attempts, null, null);
    }

    /**
     * 完整兜底响应。
     *
     * <p>兜底文案不写入会话历史（它不是模型的答案，写进去会污染下一轮的上下文），
     * 所以这里只回传 sessionId 供前端继续用，{@code contextCompressed} 恒为 false。
     */
    public static ChatResponse fallback(String message, String provider, long latencyMs,
                                        List<LlmAttempt> attempts, String sessionId, Integer historyTokens) {
        return new ChatResponse(message, List.of(), null, provider, null, null, null, null,
                latencyMs, null, null, true, attempts, sessionId, historyTokens, false);
    }
}
