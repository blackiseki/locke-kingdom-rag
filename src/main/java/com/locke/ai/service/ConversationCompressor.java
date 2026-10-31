package com.locke.ai.service;

import com.locke.ai.domain.entity.ConversationMessage;
import com.locke.ai.domain.enums.ChatRole;
import com.locke.ai.llm.LlmCompletion;
import com.locke.ai.llm.LlmRequest;
import com.locke.ai.llm.LlmRouter;
import com.locke.ai.util.TokenCounter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 把多轮对话压成一段摘要。
 *
 * <p>两条路径：
 * <ol>
 *   <li><b>首选</b>：让模型把「上一版摘要 + 本次要折叠的对话」重写成新版摘要（滚动摘要）；</li>
 *   <li><b>兜底</b>：模型不可用 / 返回空内容时，退化成抽取式摘要——保留原摘要加每条消息的首句。</li>
 * </ol>
 *
 * <p>兜底不是可有可无的装饰：压缩发生在问答请求的<b>前置链路</b>里，
 * 如果这里抛出异常，用户会连问题都问不出去。宁可摘要质量差一点，
 * 也不能因为「压缩模型挂了」导致整个服务不可用。
 *
 * <p>压缩失败时仍然会推进水位线（在 {@code ConversationMemory} 里做），
 * 否则下一轮会再次尝试压缩同一段历史，陷入每次都失败、每次都重试的死循环。
 */
@Component
public class ConversationCompressor {

    private static final Logger log = LoggerFactory.getLogger(ConversationCompressor.class);

    /** 单条消息进入压缩 prompt 时的截断长度：摘要不需要答案全文。 */
    private static final int MAX_MESSAGE_CHARS = 800;

    /** 抽取式兜底里每条消息保留的首句长度。 */
    private static final int FALLBACK_LINE_CHARS = 60;

    private final LlmRouter llmRouter;
    private final PromptBuilder promptBuilder;

    public ConversationCompressor(LlmRouter llmRouter, PromptBuilder promptBuilder) {
        this.llmRouter = llmRouter;
        this.promptBuilder = promptBuilder;
    }

    /**
     * 生成新版摘要。永不抛异常。
     *
     * @param previousSummary 上一版摘要，可为空
     * @param messages        本次要折叠进摘要的消息（按时间正序）
     * @param maxSummaryTokens 摘要 token 上限
     * @return 新版摘要；messages 为空时原样返回 previousSummary
     */
    public String compress(String previousSummary, List<ConversationMessage> messages, int maxSummaryTokens) {
        if (messages == null || messages.isEmpty()) {
            return previousSummary == null ? "" : previousSummary;
        }
        String dialog = renderDialog(messages);
        try {
            LlmCompletion completion = llmRouter.complete(new LlmRequest(
                    promptBuilder.summarySystemPrompt(),
                    promptBuilder.buildSummaryPrompt(previousSummary, dialog, maxSummaryTokens)), null);
            String summary = completion.content() == null ? "" : completion.content().trim();
            if (!summary.isEmpty()) {
                // 模型偶尔会超字数，这里硬裁一次：摘要失控等于每轮都在为它付 token
                String bounded = TokenCounter.truncateToTokens(summary, maxSummaryTokens);
                log.info("会话摘要压缩成功 provider={} 折叠消息={} 条 摘要tokens={}",
                        completion.provider(), messages.size(), TokenCounter.estimate(bounded));
                return bounded;
            }
            log.warn("会话摘要压缩返回空内容，改用抽取式摘要：折叠消息={} 条", messages.size());
        } catch (RuntimeException e) {
            log.warn("会话摘要压缩调用失败，改用抽取式摘要：{}", e.getMessage());
        }
        return extractive(previousSummary, messages, maxSummaryTokens);
    }

    /** 把多轮消息渲染成带角色前缀的对话文本。 */
    String renderDialog(List<ConversationMessage> messages) {
        StringBuilder builder = new StringBuilder();
        for (ConversationMessage message : messages) {
            String content = message.content() == null ? "" : message.content().trim();
            if (content.isEmpty()) {
                continue;
            }
            if (content.length() > MAX_MESSAGE_CHARS) {
                content = content.substring(0, MAX_MESSAGE_CHARS) + "…";
            }
            builder.append(message.role() == ChatRole.USER ? "用户：" : "助手：")
                    .append(content)
                    .append("\n\n");
        }
        return builder.toString().trim();
    }

    /**
     * 抽取式兜底摘要：保留旧摘要 + 每条消息的首句。
     *
     * <p>信息密度不如模型摘要，但至少不会丢掉「用户问过哪些对象」这条线索，
     * 而这条线索正是多轮追问（「那它的进化型呢？」）能不能接上的关键。
     */
    String extractive(String previousSummary, List<ConversationMessage> messages, int maxSummaryTokens) {
        StringBuilder builder = new StringBuilder();
        if (previousSummary != null && !previousSummary.isBlank()) {
            builder.append(previousSummary.trim()).append('\n');
        }
        builder.append("（以下为按轮次摘录的要点）\n");
        for (ConversationMessage message : messages) {
            String line = firstLine(message.content());
            if (line.isEmpty()) {
                continue;
            }
            builder.append(message.role() == ChatRole.USER ? "· 用户问：" : "· 已答：")
                    .append(line)
                    .append('\n');
        }
        return TokenCounter.truncateToTokens(builder.toString().trim(), maxSummaryTokens);
    }

    private static String firstLine(String content) {
        if (content == null || content.isBlank()) {
            return "";
        }
        String line = content.trim();
        int newline = line.indexOf('\n');
        if (newline > 0) {
            line = line.substring(0, newline);
        }
        line = line.replaceAll("\\s+", " ").trim();
        return line.length() <= FALLBACK_LINE_CHARS ? line : line.substring(0, FALLBACK_LINE_CHARS) + "…";
    }
}
