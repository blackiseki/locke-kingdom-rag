package com.locke.ai.domain.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.locke.ai.llm.LlmAttempt;
import com.locke.ai.llm.LlmUsage;

import java.util.List;

/**
 * SSE 流式事件。
 *
 * <p>事件名与 {@code type} 一致：{@code meta} / {@code delta} / {@code done} / {@code error}。
 * 前端用 {@code addEventListener} 或解析 {@code event:} 字段即可区分，
 * 不再依赖「裸文本 data」的隐式约定。
 *
 * <p>{@code sessionId} 在 meta 与 done 都会带：meta 让前端尽早把会话 id 存下来
 * （请求里没传 sessionId 时就是从这里拿新 id），done 再兜一次，
 * 避免前端只挂了 done 回调时拿不到 id 而把上下文接断。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ChatStreamEvent(
        String type,
        String provider,
        String model,
        String delta,
        String answer,
        Integer promptTokens,
        Integer completionTokens,
        Integer totalTokens,
        Boolean estimated,
        Long latencyMs,
        Long retrievalMs,
        Long totalLatencyMs,
        Boolean fallback,
        Boolean success,
        List<ChatResponse.Source> sources,
        List<LlmAttempt> attempts,
        String message,
        String sessionId,
        Integer historyTokens,
        Boolean contextCompressed) {

    public static ChatStreamEvent meta(String provider, String model, Long retrievalMs,
                                       List<ChatResponse.Source> sources, String sessionId,
                                       Integer historyTokens) {
        return new ChatStreamEvent("meta", provider, model, null, null,
                null, null, null, null, null, retrievalMs, null,
                null, null, sources, null, null, sessionId, historyTokens, null);
    }

    public static ChatStreamEvent delta(String provider, String model, String text) {
        return new ChatStreamEvent("delta", provider, model, text, null,
                null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null);
    }

    public static ChatStreamEvent done(String provider, String model, String answer, LlmUsage usage,
                                       long latencyMs, long retrievalMs, long totalLatencyMs,
                                       boolean fallback, List<LlmAttempt> attempts,
                                       String sessionId, boolean compressed) {
        return done(provider, model, answer, usage, latencyMs, retrievalMs, totalLatencyMs,
                fallback, true, attempts, sessionId, compressed);
    }

    public static ChatStreamEvent done(String provider, String model, String answer, LlmUsage usage,
                                       long latencyMs, long retrievalMs, long totalLatencyMs,
                                       boolean fallback, boolean success, List<LlmAttempt> attempts,
                                       String sessionId, boolean compressed) {
        return new ChatStreamEvent("done", provider, model, null, answer,
                usage == null ? null : usage.promptTokens(),
                usage == null ? null : usage.completionTokens(),
                usage == null ? null : usage.totalTokens(),
                usage == null ? null : usage.estimated(),
                latencyMs, retrievalMs, totalLatencyMs,
                fallback, success, null, attempts, null, sessionId, null, compressed);
    }

    public static ChatStreamEvent error(String provider, String model, String message,
                                        boolean fallback, List<LlmAttempt> attempts) {
        return new ChatStreamEvent("error", provider, model, null, null,
                null, null, null, null, null, null, null,
                fallback, false, null, attempts, message, null, null, null);
    }
}
