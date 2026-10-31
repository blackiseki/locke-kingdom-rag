package com.locke.ai.domain.entity;

import com.locke.ai.domain.enums.ChatRole;

import java.time.Instant;

/**
 * 一条会话消息。
 *
 * @param id        自增主键，同时也是历史顺序的依据（bigserial 单调递增）
 * @param sessionId 所属会话
 * @param role      角色
 * @param content   正文（用户问题或模型答案原文）
 * @param tokens    写入时估算的 token 数，读历史做预算裁剪时直接复用
 * @param provider  产生这条答案的 Provider（仅 assistant 消息有）
 * @param model     产生这条答案的模型名（仅 assistant 消息有）
 * @param createdAt 写入时间
 */
public record ConversationMessage(
        long id,
        String sessionId,
        ChatRole role,
        String content,
        int tokens,
        String provider,
        String model,
        Instant createdAt) {
}
