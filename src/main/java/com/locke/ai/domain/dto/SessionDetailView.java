package com.locke.ai.domain.dto;

import com.locke.ai.domain.entity.ConversationMessage;
import com.locke.ai.domain.entity.ConversationSession;
import com.locke.ai.util.TokenCounter;

import java.time.Instant;
import java.util.List;

/**
 * 会话详情：摘要 + 最近的消息原文。
 *
 * <p>{@code summarized=true} 的消息表示它已经被折叠进 {@link #summary}，
 * 不再进入 prompt。排查「模型怎么忘了上文」这类问题时，这条标记能直接告诉你是
 * 压缩丢的，还是检索/上下文装配的问题。
 *
 * @param id               会话 id
 * @param title            标题
 * @param summary          滚动摘要，可为空
 * @param summaryTokens    摘要 token 估算
 * @param summaryUptoId    已折叠的最大消息 id
 * @param compressionCount 压缩次数
 * @param messageCount     消息总数
 * @param createdAt        创建时间
 * @param updatedAt        最后活跃时间
 * @param messages         消息原文（按时间正序，最多取最近的若干条）
 */
public record SessionDetailView(
        String id,
        String title,
        String summary,
        int summaryTokens,
        long summaryUptoId,
        int compressionCount,
        int messageCount,
        Instant createdAt,
        Instant updatedAt,
        List<MessageView> messages) {

    public SessionDetailView {
        messages = messages == null ? List.of() : List.copyOf(messages);
    }

    /**
     * 单条消息。
     *
     * @param summarized 是否已被折叠进摘要（折叠后不再进 prompt）
     */
    public record MessageView(
            long id,
            String role,
            String content,
            int tokens,
            String provider,
            String model,
            boolean summarized,
            Instant createdAt) {

        public static MessageView of(ConversationMessage message, long summaryUptoId) {
            return new MessageView(
                    message.id(),
                    message.role() == null ? null : message.role().value(),
                    message.content(),
                    message.tokens(),
                    message.provider(),
                    message.model(),
                    message.id() <= summaryUptoId,
                    message.createdAt());
        }
    }

    public static SessionDetailView of(ConversationSession session, List<ConversationMessage> messages) {
        List<MessageView> views = messages.stream()
                .map(message -> MessageView.of(message, session.summaryUptoId()))
                .toList();
        return new SessionDetailView(
                session.id(),
                session.title(),
                session.summary(),
                TokenCounter.estimate(session.summary()),
                session.summaryUptoId(),
                session.compressionCount(),
                session.messageCount(),
                session.createdAt(),
                session.updatedAt(),
                views);
    }
}
