package com.locke.ai.service;

import com.locke.ai.domain.dto.CompactResult;
import com.locke.ai.domain.dto.CreateSessionRequest;
import com.locke.ai.domain.dto.SessionDetailView;
import com.locke.ai.domain.dto.SessionView;
import com.locke.ai.domain.entity.ConversationMessage;
import com.locke.ai.domain.entity.ConversationSession;
import com.locke.ai.exception.SessionNotFoundException;
import com.locke.ai.repository.ConversationStore;
import com.locke.ai.util.TokenCounter;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

/**
 * 会话管理（列表 / 新建 / 详情 / 删除 / 手动压缩）。
 *
 * <p>和 {@link ConversationMemory} 的分工：这里只做「读写会话」的编排，
 * 不关心 prompt 和预算；记忆装配全部在 ConversationMemory 里。
 * 两者的唯一交点是 {@link #compact(String)}——手动压缩要复用同一套滚动摘要逻辑，
 * 否则手动压出来的摘要格式和自动压的不一致，模型下次会读到两种风格的历史。
 */
@Service
public class ConversationService {

    /** 详情接口默认返回最近多少条消息。 */
    private static final int DEFAULT_MESSAGE_LIMIT = 100;

    private final ConversationStore store;
    private final ConversationMemory memory;

    public ConversationService(ConversationStore store, ConversationMemory memory) {
        this.store = store;
        this.memory = memory;
    }

    /**
     * 新建会话。id 已存在时返回现有会话，不覆盖历史。
     */
    public SessionView create(CreateSessionRequest request) {
        String id = request == null || request.id() == null || request.id().isBlank()
                ? UUID.randomUUID().toString()
                : request.id().trim();
        String title = request == null ? null : request.title();
        ConversationSession session = store.createSession(id, normalizeTitle(title));
        return SessionView.of(session, TokenCounter.estimate(session.summary()));
    }

    /** 最近活跃的会话。 */
    public List<SessionView> list(int limit) {
        return store.listSessions(limit).stream()
                .map(session -> SessionView.of(session, TokenCounter.estimate(session.summary())))
                .toList();
    }

    /** 会话详情：摘要 + 最近若干条消息原文。 */
    public SessionDetailView detail(String sessionId, int messageLimit) {
        ConversationSession session = require(sessionId);
        List<ConversationMessage> messages = store.findRecentMessages(
                session.id(), messageLimit <= 0 ? DEFAULT_MESSAGE_LIMIT : messageLimit);
        return SessionDetailView.of(session, messages);
    }

    /** 删除会话（消息级联删除）。@return 是否真的删掉了 */
    public boolean delete(String sessionId) {
        return store.deleteSession(sessionId);
    }

    /** 手动触发一次压缩。 */
    public CompactResult compact(String sessionId) {
        return memory.compact(sessionId);
    }

    private ConversationSession require(String sessionId) {
        return store.findSession(sessionId)
                .orElseThrow(() -> new SessionNotFoundException(sessionId));
    }

    private static String normalizeTitle(String title) {
        if (title == null || title.isBlank()) {
            return null;
        }
        String trimmed = title.replaceAll("\\s+", " ").trim();
        return trimmed.length() <= 200 ? trimmed : trimmed.substring(0, 200);
    }
}
