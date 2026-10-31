package com.locke.ai.service;

import com.locke.ai.domain.dto.CompactResult;
import com.locke.ai.domain.entity.ConversationMessage;
import com.locke.ai.domain.entity.ConversationSession;
import com.locke.ai.domain.enums.ChatRole;
import com.locke.ai.exception.SessionNotFoundException;
import com.locke.ai.llm.LlmMessage;
import com.locke.ai.repository.ConversationStore;
import com.locke.ai.util.TokenCounter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 会话记忆：让同一 {@code sessionId} 的追问能接上上文，并在历史变长时自动压缩。
 *
 * <h3>为什么不是「把历史全塞进 prompt」</h3>
 * 无上限的上下文有三个代价：token 费用随轮数线性增长、模型对中段内容注意力衰减
 * （lost in the middle），以及迟早撞上模型窗口上限直接被 400 拒绝。
 * 所以这里用「<b>最近若干轮原文 + 更早历史的滚动摘要</b>」：
 * <pre>
 *   [ 会话摘要（压缩产物，覆盖 id &lt;= summary_upto_id 的消息） ]
 *   [ user  ... ]  [ assistant ... ]   ← 水位线之后的原文，按 token 预算从新到旧保留
 *   [ 本轮 user（参考资料 + 问题） ]
 * </pre>
 *
 * <h3>压缩什么时候发生</h3>
 * 每次请求前先按 {@code max-history-tokens} 从<b>最新往最老</b>挑历史，
 * 挑剩下的（溢出的那部分）就是待折叠的候选。候选数达到
 * {@code compress-min-messages} 才真的压缩——只溢出 1 条就压，等于把刚说完的话
 * 立刻换成摘要，白花一次模型调用还丢细节。
 *
 * <h3>失败策略</h3>
 * 记忆是增强能力，不是主链路：加载失败就退化成单轮（{@link Snapshot#disabled()}），
 * 写库失败只打日志。绝不能因为记忆出问题让用户问不了问题。
 */
@Component
public class ConversationMemory {

    private static final Logger log = LoggerFactory.getLogger(ConversationMemory.class);

    /** 与 {@code chat_session.id varchar(64)} 对齐。 */
    private static final int SESSION_ID_MAX_LENGTH = 64;

    /** 标题取首个问题的前若干个字。 */
    private static final int TITLE_MAX_CHARS = 30;

    /** 手动压缩时最多翻多少条历史。 */
    private static final int MANUAL_COMPACT_LIMIT = 200;

    /**
     * 指代词。命中就认为这轮问题「自己说不清楚在问什么」，需要用上一轮问题补全检索语句。
     *
     * <p>这份词表刻意短且保守：误判的代价是把上一轮的话题混进检索，
     * 漏判的代价只是召回变差（模型仍能从历史里理解指代）。
     */
    private static final List<String> ANAPHORA_MARKERS =
            List.of("它", "他", "她", "这个", "那个", "这些", "那些", "刚才", "上面", "继续", "还有");

    /**
     * 短问题阈值：不到这个长度的问题（如「怎么进化？」）单独去检索基本召回不到东西。
     *
     * <p>定成 8 而不是 10：中文短句的信息密度高，10 个字（「普通咕噜球怎么获得？」）
     * 已经能自洽，再往上补上一轮问题反而容易把检索带偏。
     */
    private static final int SHORT_QUESTION_CHARS = 8;

    /** 改写后的检索语句 token 上限，太长会把原问题的语义稀释掉。 */
    private static final int RETRIEVAL_QUERY_MAX_TOKENS = 100;

    private final ConversationStore store;
    private final ConversationCompressor compressor;

    private final boolean enabled;
    private final boolean rewriteRetrievalQuery;
    private final int maxHistoryTokens;
    private final int keepRecentMessages;
    private final int maxHistoryMessages;
    private final int compressMinMessages;
    private final int summaryMaxTokens;

    public ConversationMemory(ConversationStore store,
                              ConversationCompressor compressor,
                              @Value("${locke.ai.memory.enabled:true}") boolean enabled,
                              @Value("${locke.ai.memory.rewrite-retrieval-query:true}") boolean rewriteRetrievalQuery,
                              @Value("${locke.ai.memory.max-history-tokens:1200}") int maxHistoryTokens,
                              @Value("${locke.ai.memory.keep-recent-messages:6}") int keepRecentMessages,
                              @Value("${locke.ai.memory.max-history-messages:60}") int maxHistoryMessages,
                              @Value("${locke.ai.memory.compress-min-messages:2}") int compressMinMessages,
                              @Value("${locke.ai.memory.summary-max-tokens:400}") int summaryMaxTokens) {
        this.store = store;
        this.compressor = compressor;
        this.enabled = enabled;
        this.rewriteRetrievalQuery = rewriteRetrievalQuery;
        this.maxHistoryTokens = Math.max(maxHistoryTokens, 200);
        this.keepRecentMessages = Math.max(keepRecentMessages, 0);
        this.maxHistoryMessages = Math.min(Math.max(maxHistoryMessages, 2), 500);
        this.compressMinMessages = Math.max(compressMinMessages, 2);
        this.summaryMaxTokens = Math.max(summaryMaxTokens, 100);
    }

    /**
     * 一次问答要用的记忆快照。
     *
     * @param sessionId      本轮归属的会话 id；{@link #disabled()} 时为 null
     * @param summary        滚动摘要，可为空
     * @param history        要作为对话消息发出去的历史（已转成 {@link LlmMessage}）
     * @param historyTokens  历史部分的 token 估算
     * @param compressed     本轮是否触发了压缩（本轮之前溢出的历史被折叠进摘要）
     * @param foldedMessages 本轮折叠了几条消息
     */
    public record Snapshot(String sessionId,
                           String summary,
                           List<LlmMessage> history,
                           int historyTokens,
                           boolean compressed,
                           int foldedMessages) {

        public Snapshot {
            history = history == null ? List.of() : List.copyOf(history);
        }

        /** 关闭记忆时的空快照。 */
        public static Snapshot disabled() {
            return new Snapshot(null, null, List.of(), 0, false, 0);
        }

        public boolean hasSession() {
            return sessionId != null && !sessionId.isBlank();
        }

        /** 历史里最近一条用户消息；没有历史时为 null。 */
        public String lastUserQuestion() {
            for (int i = history.size() - 1; i >= 0; i--) {
                LlmMessage message = history.get(i);
                if (LlmMessage.ROLE_USER.equals(message.role())) {
                    return message.content();
                }
            }
            return null;
        }
    }

    /**
     * 生成本轮<b>检索</b>用的查询语句（拿历史补全指代）。
     *
     * <p>为什么需要这一步：多轮里用户不会每次都把主语说全。
     * 上一轮问「火系克制哪些属性」，这轮只问「那它被哪些属性克制？」，
     * 用原句去做向量检索会召回一堆无关内容——实测就是这样，
     * 模型明明从历史里知道「它」指火系，却因为检索没给到资料而答「资料里没有」。
     * 记忆只解决了「模型看得懂」，还得解决「检索找得到」。
     *
     * <p>只在两种情况下补全：问题里出现指代词，或者问题特别短。
     * 其余情况原样返回——每轮都拼上一轮问题会让检索被旧话题带偏。
     */
    public String retrievalQuery(String question, Snapshot snapshot) {
        if (!rewriteRetrievalQuery || question == null || question.isBlank() || snapshot == null) {
            return question;
        }
        String hint = snapshot.lastUserQuestion();
        if (hint == null || hint.isBlank() || hint.trim().equals(question.trim())) {
            return question;
        }
        boolean referential = ANAPHORA_MARKERS.stream().anyMatch(question::contains);
        if (!referential && question.trim().length() > SHORT_QUESTION_CHARS) {
            return question;
        }
        String merged = hint.trim() + " " + question.trim();
        String rewritten = TokenCounter.truncateToTokens(merged, RETRIEVAL_QUERY_MAX_TOKENS);
        log.debug("检索语句按历史补全: {}", rewritten);
        return rewritten;
    }

    /**
     * 准备本轮的记忆快照：必要时先压缩，再返回可用的摘要 + 历史。
     *
     * @param rawSessionId 请求里的 sessionId，可为空（空表示新会话）
     * @param memoryAllowed 请求是否允许使用记忆（{@code ChatRequest.memory=false} 时纯单轮）
     */
    public Snapshot prepare(String rawSessionId, boolean memoryAllowed) {
        if (!enabled || !memoryAllowed) {
            return Snapshot.disabled();
        }
        String trimmed = rawSessionId == null ? "" : rawSessionId.trim();
        if (trimmed.length() > SESSION_ID_MAX_LENGTH) {
            // 这条要在 try 外面抛：它是参数错误，应该得到 400 而不是静默降级成单轮
            throw new IllegalArgumentException("sessionId 最长 " + SESSION_ID_MAX_LENGTH + " 位");
        }
        boolean newSession = trimmed.isEmpty();

        try {
            String sessionId = newSession ? UUID.randomUUID().toString() : trimmed;
            if (newSession) {
                // 新会话不落库：等真的产生了问答再写，避免失败的请求在列表里留下空会话
                return new Snapshot(sessionId, null, List.of(), 0, false, 0);
            }

            ConversationSession session = store.findSession(sessionId).orElse(null);
            if (session == null) {
                // 客户端自带的新 id：当作空历史处理，等 recordTurn 时再建行
                return new Snapshot(sessionId, null, List.of(), 0, false, 0);
            }

            List<ConversationMessage> live = store.findMessagesAfter(
                    sessionId, session.summaryUptoId(), maxHistoryMessages);
            Selection selection = selectTail(live);

            String summary = session.summary();
            boolean compressed = false;
            int folded = 0;

            if (selection.overflow().size() >= compressMinMessages) {
                String merged = compressor.compress(summary, selection.overflow(), summaryMaxTokens);
                long uptoId = selection.overflow().get(selection.overflow().size() - 1).id();
                try {
                    store.updateSummary(sessionId, merged, uptoId);
                } catch (RuntimeException e) {
                    // 摘要写不进去也不影响本轮：本轮先用着新摘要，下一轮重新算一遍而已
                    log.error("写入会话摘要失败 sessionId={} uptoId={}", sessionId, uptoId, e);
                }
                summary = merged;
                compressed = true;
                folded = selection.overflow().size();
                log.info("会话记忆压缩 sessionId={} 折叠={} 条 水位线={} 保留原文={} 条 摘要tokens={}",
                        sessionId, folded, uptoId, selection.kept().size(), TokenCounter.estimate(merged));
            } else if (!selection.overflow().isEmpty()) {
                // 溢出不足阈值：不压缩，宁可这轮轻微超预算也别丢细节
                selection = new Selection(List.of(), live, tokensOf(live));
            }

            List<LlmMessage> history = toLlmMessages(selection.kept());
            if (log.isDebugEnabled()) {
                log.debug("会话记忆加载 sessionId={} 历史={} 条 tokens={} 摘要={}",
                        sessionId, history.size(), selection.tokens(), summary != null);
            }
            return new Snapshot(sessionId, summary, history, selection.tokens(), compressed, folded);
        } catch (RuntimeException e) {
            log.error("加载会话记忆失败，本轮按单轮问答处理 sessionId={}", rawSessionId, e);
            return Snapshot.disabled();
        }
    }

    /**
     * 记录一轮问答（用户问题 + 模型答案）。
     *
     * <p>只在这一轮真的产生了答案之后调用：兜底文案、失败重试都不该进历史，
     * 否则下一轮模型会看到一堆「抱歉，服务暂时不可用」，还可能照着学。
     */
    public void recordTurn(Snapshot snapshot, String question, String answer,
                          String provider, String model) {
        if (snapshot == null || !snapshot.hasSession() || question == null || question.isBlank()) {
            return;
        }
        try {
            // 会话行可能还不存在（客户端自带的新 id），先 upsert 一次
            store.createSession(snapshot.sessionId(), null);
            store.appendMessage(snapshot.sessionId(), ChatRole.USER, question,
                    TokenCounter.estimate(question), null, null);
            if (answer != null && !answer.isBlank()) {
                store.appendMessage(snapshot.sessionId(), ChatRole.ASSISTANT, answer,
                        TokenCounter.estimate(answer), provider, model);
            }
            store.updateTitleIfAbsent(snapshot.sessionId(), titleOf(question));
        } catch (RuntimeException e) {
            // 记忆落库失败不能影响已经返回给用户的答案
            log.error("写入会话消息失败 sessionId={}", snapshot.sessionId(), e);
        }
    }

    /**
     * 手动触发一次压缩（{@code POST /api/sessions/{id}/compact}）。
     *
     * <p>保留最近 {@code keep-recent-messages} 条原文——手动压缩也不该把
     * 用户刚问的那句话变成摘要，否则他刷新页面会发现上下文「跳」了一截。
     */
    public CompactResult compact(String sessionId) {
        ConversationSession session = store.findSession(sessionId)
                .orElseThrow(() -> new SessionNotFoundException(sessionId));
        List<ConversationMessage> live = store.findMessagesAfter(
                sessionId, session.summaryUptoId(), MANUAL_COMPACT_LIMIT);

        int foldCount = Math.max(live.size() - keepRecentMessages, 0);
        if (foldCount <= 0) {
            return new CompactResult(0, TokenCounter.estimate(session.summary()),
                    session.compressionCount(), false);
        }
        List<ConversationMessage> toFold = new ArrayList<>(live.subList(0, foldCount));
        String merged = compressor.compress(session.summary(), toFold, summaryMaxTokens);
        long uptoId = toFold.get(toFold.size() - 1).id();
        store.updateSummary(sessionId, merged, uptoId);
        log.info("手动压缩会话 sessionId={} 折叠={} 条 摘要tokens={}",
                sessionId, toFold.size(), TokenCounter.estimate(merged));
        return new CompactResult(toFold.size(), TokenCounter.estimate(merged),
                session.compressionCount() + 1, true);
    }

    // ============================================================
    // 内部
    // ============================================================

    /**
     * 历史裁剪结果。
     *
     * @param overflow 被挤出预算、准备折叠进摘要的消息（按时间正序）
     * @param kept     留在 prompt 里的原文（按时间正序）
     * @param tokens   kept 的 token 合计
     */
    private record Selection(List<ConversationMessage> overflow,
                            List<ConversationMessage> kept,
                            int tokens) {
    }

    /**
     * 从最新一条往前按预算挑，挑不动为止。
     *
     * <p>两条边界：至少保留 {@code keep-recent-messages} 条（保证最近几轮原文完整），
     * 且最新一条永远保留——否则「本轮之前的最后一问」会被压缩掉，
     * 用户接着追问「那它呢？」时模型完全不知道「它」指什么。
     */
    private Selection selectTail(List<ConversationMessage> live) {
        int start = live.size();
        int tokens = 0;
        int kept = 0;
        while (start > 0) {
            int messageTokens = tokensOf(live.get(start - 1));
            boolean budgetFull = tokens + messageTokens > maxHistoryTokens;
            if (budgetFull && kept >= keepRecentMessages) {
                break;
            }
            start--;
            tokens += messageTokens;
            kept++;
        }
        return new Selection(List.copyOf(live.subList(0, start)),
                List.copyOf(live.subList(start, live.size())), tokens);
    }

    private static List<LlmMessage> toLlmMessages(List<ConversationMessage> messages) {
        List<LlmMessage> result = new ArrayList<>(messages.size());
        for (ConversationMessage message : messages) {
            if (message.role() == null || message.content() == null || message.content().isBlank()) {
                continue;
            }
            result.add(new LlmMessage(message.role().value(), message.content()));
        }
        return result;
    }

    private static int tokensOf(ConversationMessage message) {
        return message.tokens() > 0 ? message.tokens() : TokenCounter.estimate(message.content());
    }

    private static int tokensOf(List<ConversationMessage> messages) {
        int total = 0;
        for (ConversationMessage message : messages) {
            total += tokensOf(message);
        }
        return total;
    }

    private static String titleOf(String question) {
        String title = question.replaceAll("\\s+", " ").trim();
        return title.length() <= TITLE_MAX_CHARS ? title : title.substring(0, TITLE_MAX_CHARS) + "…";
    }
}
