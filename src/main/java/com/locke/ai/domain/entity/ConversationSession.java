package com.locke.ai.domain.entity;

import java.time.Instant;

/**
 * 一个会话（多轮对话的一条线程）。
 *
 * @param id               会话 id，客户端可自带（便于前端把本地会话对上服务端记录），留空则服务端生成 UUID
 * @param title            标题，默认取首个问题前若干个字
 * @param summary          滚动摘要：把更早的历史压成一段文字，代替原文进入 prompt
 * @param summaryUptoId    已折叠进 {@code summary} 的最大消息 id；id 不大于它的消息不再进入 prompt
 * @param compressionCount 压缩了几次，纯观测用
 * @param messageCount     消息总数（含已折叠的），列表页展示用
 * @param createdAt        创建时间
 * @param updatedAt        最后活跃时间（每次追加消息都会更新）
 */
public record ConversationSession(
        String id,
        String title,
        String summary,
        long summaryUptoId,
        int compressionCount,
        int messageCount,
        Instant createdAt,
        Instant updatedAt) {

    public ConversationSession {
        messageCount = Math.max(messageCount, 0);
        compressionCount = Math.max(compressionCount, 0);
    }

    /** 摘要是否非空。 */
    public boolean hasSummary() {
        return summary != null && !summary.isBlank();
    }
}
