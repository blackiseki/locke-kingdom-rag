package com.locke.ai.llm;

import com.locke.ai.util.TokenCounter;

import java.util.ArrayList;
import java.util.List;

/**
 * 一次对话请求的输入。
 *
 * <p>RAG 场景下 userPrompt 已经包含检索到的参考资料；Provider 只负责
 * 把 system / history / user 按顺序发给模型，不感知检索与记忆细节。
 *
 * <h3>为什么 history 是独立字段而不是拼进 userPrompt</h3>
 * 历史问答必须作为真正的 {@code user/assistant} 消息发出去。
 * 拼成一段文本塞进 userPrompt 也能跑，但模型会把历史答案当成「资料的一部分」，
 * 于是把上一轮的内容当成本轮参考资料来引用，RAG 的出处在多轮下立刻失真。
 *
 * @param systemPrompt 系统提示词（已含会话摘要块）
 * @param history      历史消息，按时间正序，可为空；不含本轮问题
 * @param userPrompt   本轮用户消息（参考资料 + 问题）
 */
public record LlmRequest(String systemPrompt, List<LlmMessage> history, String userPrompt) {

    public LlmRequest {
        history = history == null ? List.of() : List.copyOf(history);
    }

    /** 单轮问答（无历史）的快捷构造。 */
    public LlmRequest(String systemPrompt, String userPrompt) {
        this(systemPrompt, List.of(), userPrompt);
    }

    /**
     * 按发送顺序展开成消息列表：system → history → user。
     *
     * <p>Provider 直接用这个列表构造请求体，避免每个实现各写一遍拼接逻辑
     * （拼错顺序的 bug 在单 Provider 时很难被发现）。
     */
    public List<LlmMessage> messages() {
        List<LlmMessage> messages = new ArrayList<>(history.size() + 2);
        if (systemPrompt != null && !systemPrompt.isBlank()) {
            messages.add(new LlmMessage("system", systemPrompt));
        }
        messages.addAll(history);
        if (userPrompt != null && !userPrompt.isBlank()) {
            messages.add(LlmMessage.user(userPrompt));
        }
        return messages;
    }

    /** 整个请求的 token 估算，用于日志与成本观测。 */
    public int estimatedTokens() {
        int total = TokenCounter.estimate(systemPrompt) + TokenCounter.estimate(userPrompt);
        for (LlmMessage message : history) {
            total += TokenCounter.estimate(message.content());
        }
        return total;
    }
}
