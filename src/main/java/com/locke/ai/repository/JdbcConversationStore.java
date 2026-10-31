package com.locke.ai.repository;

import com.locke.ai.domain.entity.ConversationMessage;
import com.locke.ai.domain.entity.ConversationSession;
import com.locke.ai.domain.enums.ChatRole;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 会话存储的 PostgreSQL 实现（JdbcTemplate）。
 *
 * <p>和 {@link SpiritRepository} 一样用 JdbcTemplate 而不是 JPA：
 * 这里只有「插一条、按 id 读一段、改一版摘要」三种动作，实体关系管理没有收益，
 * 反而要小心 JPA 与 pgvector 的类型映射互相干扰。
 *
 * <h3>为什么每条 SQL 都显式列字段</h3>
 * 不用 {@code SELECT *}：{@code chat_message} 以后加字段（比如存引用出处）时，
 * {@code *} 会让结果集的列位置随 DDL 变化，RowMapper 按名字取值虽然不受影响，
 * 但读代码的人没法一眼看出这条查询到底要哪些列。
 */
@Repository
public class JdbcConversationStore implements ConversationStore {

    /** 会话行的公共列。message_count 用子查询算，避免和消息表 join 后行数膨胀。 */
    private static final String SELECT_SESSION = """
            SELECT s.id, s.title, s.summary, s.summary_upto_id, s.compression_count,
                   s.created_at, s.updated_at,
                   (SELECT count(*) FROM chat_message m WHERE m.session_id = s.id) AS message_count
            FROM chat_session s
            """;

    private static final String SELECT_MESSAGE = """
            SELECT id, session_id, role, content, tokens, provider, model, created_at
            FROM chat_message
            """;

    private final JdbcTemplate jdbc;

    public JdbcConversationStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<ConversationSession> findSession(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return Optional.empty();
        }
        return jdbc.query(SELECT_SESSION + " WHERE s.id = ?", SESSION_MAPPER, sessionId.trim())
                .stream()
                .findFirst();
    }

    @Override
    public ConversationSession createSession(String sessionId, String title) {
        jdbc.update("""
                INSERT INTO chat_session (id, title) VALUES (?, ?)
                ON CONFLICT (id) DO NOTHING
                """, sessionId, title);
        return findSession(sessionId)
                .orElseThrow(() -> new IllegalStateException("会话创建失败: " + sessionId));
    }

    @Override
    public List<ConversationSession> listSessions(int limit) {
        int safeLimit = limit <= 0 ? 20 : Math.min(limit, 200);
        return jdbc.query(SELECT_SESSION + " ORDER BY s.updated_at DESC LIMIT ?",
                SESSION_MAPPER, safeLimit);
    }

    @Override
    public boolean deleteSession(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return false;
        }
        return jdbc.update("DELETE FROM chat_session WHERE id = ?", sessionId.trim()) > 0;
    }

    @Override
    public List<ConversationMessage> findMessagesAfter(String sessionId, long afterId, int limit) {
        int safeLimit = limit <= 0 ? 60 : Math.min(limit, 500);
        // 先按倒序取最近的 limit 条，再翻回正序：
        // 直接 ORDER BY id ASC LIMIT n 会把最老的一批取出来，那是错的。
        return jdbc.query("""
                SELECT * FROM (
                    SELECT id, session_id, role, content, tokens, provider, model, created_at
                    FROM chat_message
                    WHERE session_id = ? AND id > ?
                    ORDER BY id DESC
                    LIMIT ?
                ) recent
                ORDER BY id
                """, MESSAGE_MAPPER, sessionId, afterId, safeLimit);
    }

    @Override
    public List<ConversationMessage> findRecentMessages(String sessionId, int limit) {
        return findMessagesAfter(sessionId, 0L, limit);
    }

    @Override
    public int countMessages(String sessionId) {
        Integer total = jdbc.queryForObject(
                "SELECT count(*) FROM chat_message WHERE session_id = ?", Integer.class, sessionId);
        return total == null ? 0 : total;
    }

    @Override
    public long appendMessage(String sessionId, ChatRole role, String content, int tokens,
                              String provider, String model) {
        Long id = jdbc.queryForObject("""
                INSERT INTO chat_message (session_id, role, content, tokens, provider, model)
                VALUES (?, ?, ?, ?, ?, ?)
                RETURNING id
                """, Long.class, sessionId, role.value(), content, tokens, provider, model);
        // 顺手刷新活跃时间：会话列表按它排序，不能等下一次显式 touch
        jdbc.update("UPDATE chat_session SET updated_at = now() WHERE id = ?", sessionId);
        return id == null ? 0L : id;
    }

    @Override
    public void updateSummary(String sessionId, String summary, long uptoId) {
        // GREATEST 防回退：并发/重试时水位线只许前进，退回去会导致同一段历史被折叠两次
        jdbc.update("""
                UPDATE chat_session
                SET summary = ?, summary_upto_id = GREATEST(summary_upto_id, ?),
                    compression_count = compression_count + 1,
                    summary_updated_at = now(), updated_at = now()
                WHERE id = ?
                """, summary, uptoId, sessionId);
    }

    @Override
    public void updateTitleIfAbsent(String sessionId, String title) {
        if (title == null || title.isBlank()) {
            return;
        }
        jdbc.update("""
                UPDATE chat_session SET title = ?
                WHERE id = ? AND (title IS NULL OR btrim(title) = '')
                """, title, sessionId);
    }

    private static final RowMapper<ConversationSession> SESSION_MAPPER = (rs, rowNum) ->
            new ConversationSession(
                    rs.getString("id"),
                    rs.getString("title"),
                    rs.getString("summary"),
                    rs.getLong("summary_upto_id"),
                    rs.getInt("compression_count"),
                    rs.getInt("message_count"),
                    instant(rs, "created_at"),
                    instant(rs, "updated_at"));

    private static final RowMapper<ConversationMessage> MESSAGE_MAPPER = (rs, rowNum) ->
            new ConversationMessage(
                    rs.getLong("id"),
                    rs.getString("session_id"),
                    ChatRole.from(rs.getString("role")),
                    rs.getString("content"),
                    rs.getInt("tokens"),
                    rs.getString("provider"),
                    rs.getString("model"),
                    instant(rs, "created_at"));

    /** timestamptz 读成 OffsetDateTime 再转 Instant，避免依赖 JVM 默认时区。 */
    private static Instant instant(ResultSet rs, String column) throws SQLException {
        OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }
}
