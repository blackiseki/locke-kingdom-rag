package com.locke.ai.repository;

import com.locke.ai.domain.entity.ConversationMessage;
import com.locke.ai.domain.entity.ConversationSession;
import com.locke.ai.domain.enums.ChatRole;

import java.util.List;
import java.util.Optional;

/**
 * 会话存储的抽象。
 *
 * <p>抽成接口的目的很实际：{@code ConversationMemory} 里的「按 budget 裁剪 + 触发压缩 +
 * 推进水位线」这套逻辑是全项目最容易写错的地方，而它本身不需要数据库就能测。
 * 单测里塞一个内存实现即可覆盖压缩边界，不必起 PostgreSQL。
 *
 * <p>所有实现都必须满足两条语义：
 * <ul>
 *   <li>{@link #appendMessage} 返回的自增 id 严格单调，历史顺序以它为准；</li>
 *   <li>{@link #updateSummary} 必须在同一条语句里同时写 {@code summary} 与水位线，
 *       否则会出「摘要换了、水位没动」的错位。</li>
 * </ul>
 */
public interface ConversationStore {

    /** 查会话；不存在返回 empty。 */
    Optional<ConversationSession> findSession(String sessionId);

    /**
     * 创建会话；id 已存在时不覆盖已有内容，直接返回现有会话。
     *
     * <p>这样「客户端带 id 首次提问」和「并发两个请求同时创建同一个 id」都不会互相踩。
     */
    ConversationSession createSession(String sessionId, String title);

    /** 最近活跃的会话列表。 */
    List<ConversationSession> listSessions(int limit);

    /** 删除会话（消息级联删除）。@return 是否真的删掉了一条 */
    boolean deleteSession(String sessionId);

    /**
     * 取水位线之后的消息，按 id 升序。
     *
     * @param afterId 水位线，id 大于它的才返回
     * @param limit   最多返回多少条；实现应返回<b>最近的</b> limit 条，
     *                避免历史很长时把整表读进内存
     */
    List<ConversationMessage> findMessagesAfter(String sessionId, long afterId, int limit);

    /** 最近 limit 条消息，按 id 升序。 */
    List<ConversationMessage> findRecentMessages(String sessionId, int limit);

    /** 消息总数（含已折叠的）。 */
    int countMessages(String sessionId);

    /**
     * 追加一条消息，并刷新会话的 updated_at。
     *
     * @return 新消息的自增 id
     */
    long appendMessage(String sessionId, ChatRole role, String content, int tokens,
                       String provider, String model);

    /**
     * 写入滚动摘要并推进水位线，压缩次数 +1。
     *
     * @param summary 新版摘要（覆盖旧摘要，不是追加）
     * @param uptoId  已折叠的最大消息 id
     */
    void updateSummary(String sessionId, String summary, long uptoId);

    /** 会话还没有标题时补一个；已有标题则不动。 */
    void updateTitleIfAbsent(String sessionId, String title);
}
