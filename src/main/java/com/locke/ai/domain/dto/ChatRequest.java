package com.locke.ai.domain.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 问答请求。
 *
 * <p>没有 {@code stream} 字段：流式与否由<b>不同的接口</b>区分
 * （{@code POST /api/chat} 返回 JSON，{@code POST /api/chat/stream} 返回 SSE）。
 * 一个方法没法同时声明返回 {@code ChatResponse} 和 {@code SseEmitter}
 * ——Spring MVC 的返回类型解析器是按声明类型选的，
 * 把返回类型写成 {@code Object} 会让 SseEmitter 被当普通对象序列化成 JSON。
 *
 * @param question  用户问题，必填
 * @param topK      召回条数，null 时用 {@code locke.ai.retriever.top-k} 的默认值
 * @param sessionId 会话标识。带上它就能接上同一会话的历史（服务端会按 token 预算
 *                  保留最近若干轮原文，更早的压缩成摘要）；不传则每轮都是独立的单轮问答。
 *                  首次提问可以留空，响应的 {@code sessionId} 会带回来一个新建的 id
 * @param provider  可选的 Provider id；留空用服务端默认 Provider
 * @param memory    是否使用会话记忆，null 视为 true。设为 false 可以强制单轮，
 *                  便于做「同一问题多轮对比」这类评测，不让历史影响结果
 */
public record ChatRequest(
        @NotBlank(message = "question 不能为空")
        @Size(max = 500, message = "question 最长 500 字")
        String question,

        Integer topK,

        @Size(max = 64, message = "sessionId 最长 64 位")
        String sessionId,

        @Size(max = 40, message = "provider 最长 40 字")
        String provider,

        Boolean memory) {

    public ChatRequest(String question, Integer topK, String sessionId) {
        this(question, topK, sessionId, null, null);
    }

    public ChatRequest(String question, Integer topK, String sessionId, String provider) {
        this(question, topK, sessionId, provider, null);
    }

    /** topK 兜底：null 或非正数都回落到配置默认值。 */
    public int topKOrDefault(int fallback) {
        return (topK == null || topK <= 0) ? fallback : topK;
    }

    /** 记忆开关：只有显式传 false 才关。 */
    public boolean memoryEnabled() {
        return memory == null || memory;
    }
}
