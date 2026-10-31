package com.locke.ai.domain.dto;

import com.locke.ai.domain.entity.ConversationSession;

import java.time.Instant;

/**
 * 会话列表项。
 *
 * <p>刻意不带消息正文：列表接口可能一次返回几十个会话，
 * 把每条消息都塞进来会让响应体轻松上兆。
 *
 * @param id               会话 id
 * @param title            标题（首个问题的前 30 字）
 * @param messageCount     消息总数（含已压缩折叠的）
 * @param compressionCount 压缩次数
 * @param summaryTokens    摘要 token 估算，0 表示还没压缩过
 * @param createdAt        创建时间
 * @param updatedAt        最后活跃时间
 */
public record SessionView(
        String id,
        String title,
        int messageCount,
        int compressionCount,
        int summaryTokens,
        Instant createdAt,
        Instant updatedAt) {

    public static SessionView of(ConversationSession session, int summaryTokens) {
        return new SessionView(session.id(), session.title(), session.messageCount(),
                session.compressionCount(), summaryTokens, session.createdAt(), session.updatedAt());
    }
}
