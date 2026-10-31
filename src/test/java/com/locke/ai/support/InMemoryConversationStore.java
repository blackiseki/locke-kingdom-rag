package com.locke.ai.support;

import com.locke.ai.domain.entity.ConversationMessage;
import com.locke.ai.domain.entity.ConversationSession;
import com.locke.ai.domain.enums.ChatRole;
import com.locke.ai.repository.ConversationStore;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 内存版 {@link ConversationStore}，单测用。
 *
 * <p>为什么值得单独写一个：会话记忆里最容易错的是「按预算裁剪 + 推进水位线」这套
 * 纯逻辑，它不需要 PostgreSQL 就能验证。用真库测会把单测变成集成测试
 * （要起容器、跑 Flyway），跑一次几秒变几十秒。
 *
 * <p>语义必须和 {@link com.locke.ai.repository.JdbcConversationStore} 保持一致：
 * id 单调递增、updateSummary 同时写摘要与水位线、appendMessage 刷新活跃时间。
 */
public class InMemoryConversationStore implements ConversationStore {

    private final Map<String, State> sessions = new LinkedHashMap<>();
    private long sequence = 0;

    private static final class State {
        private String title;
        private String summary;
        private long summaryUptoId;
        private int compressionCount;
        private Instant createdAt = Instant.now();
        private Instant updatedAt = Instant.now();
        private final List<ConversationMessage> messages = new ArrayList<>();
    }

    @Override
    public Optional<ConversationSession> findSession(String sessionId) {
        State state = sessions.get(sessionId);
        return state == null ? Optional.empty() : Optional.of(toSession(sessionId, state));
    }

    @Override
    public ConversationSession createSession(String sessionId, String title) {
        State state = sessions.computeIfAbsent(sessionId, key -> new State());
        if (isBlank(state.title) && !isBlank(title)) {
            state.title = title;
        }
        return toSession(sessionId, state);
    }

    @Override
    public List<ConversationSession> listSessions(int limit) {
        return sessions.entrySet().stream()
                .sorted(Comparator.comparing((Map.Entry<String, State> e) -> e.getValue().updatedAt).reversed())
                .limit(limit <= 0 ? 20 : limit)
                .map(entry -> toSession(entry.getKey(), entry.getValue()))
                .toList();
    }

    @Override
    public boolean deleteSession(String sessionId) {
        return sessions.remove(sessionId) != null;
    }

    @Override
    public List<ConversationMessage> findMessagesAfter(String sessionId, long afterId, int limit) {
        State state = sessions.get(sessionId);
        if (state == null) {
            return List.of();
        }
        List<ConversationMessage> matched = state.messages.stream()
                .filter(message -> message.id() > afterId)
                .toList();
        int from = Math.max(matched.size() - (limit <= 0 ? 60 : limit), 0);
        return List.copyOf(matched.subList(from, matched.size()));
    }

    @Override
    public List<ConversationMessage> findRecentMessages(String sessionId, int limit) {
        return findMessagesAfter(sessionId, 0L, limit);
    }

    @Override
    public int countMessages(String sessionId) {
        State state = sessions.get(sessionId);
        return state == null ? 0 : state.messages.size();
    }

    @Override
    public long appendMessage(String sessionId, ChatRole role, String content, int tokens,
                              String provider, String model) {
        State state = sessions.get(sessionId);
        if (state == null) {
            throw new IllegalStateException("会话不存在: " + sessionId);
        }
        long id = ++sequence;
        state.messages.add(new ConversationMessage(id, sessionId, role, content, tokens,
                provider, model, Instant.now()));
        state.updatedAt = Instant.now();
        return id;
    }

    @Override
    public void updateSummary(String sessionId, String summary, long uptoId) {
        State state = require(sessionId);
        state.summary = summary;
        state.summaryUptoId = Math.max(state.summaryUptoId, uptoId);
        state.compressionCount++;
        state.updatedAt = Instant.now();
    }

    @Override
    public void updateTitleIfAbsent(String sessionId, String title) {
        State state = require(sessionId);
        if (isBlank(state.title) && !isBlank(title)) {
            state.title = title;
        }
    }

    /** 测试断言用：拿到当前水位线。 */
    public long summaryUptoId(String sessionId) {
        return require(sessionId).summaryUptoId;
    }

    /** 测试断言用：拿到当前摘要。 */
    public String summary(String sessionId) {
        return require(sessionId).summary;
    }

    private State require(String sessionId) {
        State state = sessions.get(sessionId);
        if (state == null) {
            throw new IllegalStateException("会话不存在: " + sessionId);
        }
        return state;
    }

    private static ConversationSession toSession(String sessionId, State state) {
        return new ConversationSession(sessionId, state.title, state.summary, state.summaryUptoId,
                state.compressionCount, state.messages.size(), state.createdAt, state.updatedAt);
    }

    private static boolean isBlank(String text) {
        return text == null || text.isBlank();
    }
}
