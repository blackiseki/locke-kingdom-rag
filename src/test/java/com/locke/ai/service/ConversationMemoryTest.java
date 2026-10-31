package com.locke.ai.service;

import com.locke.ai.domain.dto.CompactResult;
import com.locke.ai.domain.enums.ChatRole;
import com.locke.ai.llm.LlmMessage;
import com.locke.ai.repository.ConversationStore;
import com.locke.ai.support.InMemoryConversationStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link ConversationMemory} 单元测试。
 *
 * <p>用内存版 {@link ConversationStore}，不起数据库；压缩器用 mock，
 * 只验证「什么时候压、压多少条、水位线推到哪」，模型侧行为在
 * {@link ConversationCompressorTest} 里单独测。
 */
class ConversationMemoryTest {

    private static final int BUDGET = 200;

    private InMemoryConversationStore store;
    private ConversationCompressor compressor;
    private ConversationMemory memory;

    @BeforeEach
    void setUp() {
        store = new InMemoryConversationStore();
        compressor = mock(ConversationCompressor.class);
        when(compressor.compress(any(), anyList(), anyInt())).thenReturn("早期摘要");
        memory = newMemory(true, BUDGET, 2, 60, 2, 400);
    }

    @Test
    @DisplayName("不带 sessionId 时生成新 id，且此时不落库（避免失败的请求留下空会话）")
    void shouldGenerateSessionIdWithoutPersisting() {
        ConversationMemory.Snapshot snapshot = memory.prepare(null, true);

        assertThat(snapshot.sessionId()).isNotBlank();
        assertThat(snapshot.hasSession()).isTrue();
        assertThat(snapshot.history()).isEmpty();
        assertThat(store.findSession(snapshot.sessionId())).isEmpty();
    }

    @Test
    @DisplayName("同一个 sessionId 的下一轮能拿到上一轮问答作为历史")
    void shouldFeedPreviousTurnIntoHistory() {
        ConversationMemory.Snapshot first = memory.prepare(null, true);
        memory.recordTurn(first, "火系克制什么？", "克制草系和虫系。[1]", "deepseek", "deepseek-flash");

        ConversationMemory.Snapshot second = memory.prepare(first.sessionId(), true);

        assertThat(second.history()).hasSize(2);
        assertThat(second.history().get(0).role()).isEqualTo("user");
        assertThat(second.history().get(0).content()).isEqualTo("火系克制什么？");
        assertThat(second.history().get(1).role()).isEqualTo("assistant");
        assertThat(second.compressed()).isFalse();
    }

    @Test
    @DisplayName("首轮问题会补成会话标题")
    void shouldUseFirstQuestionAsTitle() {
        ConversationMemory.Snapshot snapshot = memory.prepare(null, true);
        memory.recordTurn(snapshot, "深渊罗隐怎么进化？", "需要达到 60 级。", "deepseek", "m");

        assertThat(store.findSession(snapshot.sessionId()))
                .get()
                .extracting(session -> session.title())
                .isEqualTo("深渊罗隐怎么进化？");
    }

    @Test
    @DisplayName("历史超出预算时折叠较早的消息，并把水位线推到被折叠的最后一条")
    void shouldCompressWhenHistoryExceedsBudget() {
        String sessionId = "s-compress";
        store.createSession(sessionId, null);
        long tenthId = 0;
        for (int i = 0; i < 6; i++) {
            store.appendMessage(sessionId, ChatRole.USER, "问题" + i, 100, null, null);
            long assistantId = store.appendMessage(sessionId, ChatRole.ASSISTANT, "答案" + i, 100, "deepseek", "m");
            if (i == 4) {
                tenthId = assistantId;
            }
        }

        ConversationMemory.Snapshot snapshot = memory.prepare(sessionId, true);

        assertThat(snapshot.compressed()).isTrue();
        assertThat(snapshot.foldedMessages()).isEqualTo(10);          // 12 条里留 2 条原文
        assertThat(snapshot.summary()).isEqualTo("早期摘要");
        assertThat(snapshot.history()).hasSize(2);
        assertThat(snapshot.historyTokens()).isEqualTo(BUDGET);
        assertThat(store.summaryUptoId(sessionId)).isEqualTo(tenthId);
        verify(compressor).compress(any(), anyList(), anyInt());

        // 折叠之后水位线之后的原文不再重复折叠
        ConversationMemory.Snapshot next = memory.prepare(sessionId, true);
        assertThat(next.compressed()).isFalse();
        assertThat(next.history()).hasSize(2);
    }

    @Test
    @DisplayName("溢出不足压缩阈值时不压缩，宁可轻微超预算也不丢细节")
    void shouldNotCompressWhenOverflowBelowThreshold() {
        memory = newMemory(true, BUDGET, 1, 60, 4, 400);
        String sessionId = "s-small-overflow";
        store.createSession(sessionId, null);
        for (int i = 0; i < 3; i++) {
            store.appendMessage(sessionId, ChatRole.USER, "问题" + i, 100, null, null);
        }

        ConversationMemory.Snapshot snapshot = memory.prepare(sessionId, true);

        assertThat(snapshot.compressed()).isFalse();
        assertThat(snapshot.history()).hasSize(3);
        assertThat(snapshot.historyTokens()).isEqualTo(300);
    }

    @Test
    @DisplayName("预算再紧也要保住最近 N 条原文（否则追问「那它呢」会失去指代对象）")
    void shouldAlwaysKeepRecentMessages() {
        memory = newMemory(true, BUDGET, 6, 60, 2, 400);
        String sessionId = "s-keep-recent";
        store.createSession(sessionId, null);
        for (int i = 0; i < 10; i++) {
            store.appendMessage(sessionId, ChatRole.USER, "问题" + i, 100, null, null);
        }

        ConversationMemory.Snapshot snapshot = memory.prepare(sessionId, true);

        assertThat(snapshot.history()).hasSize(6);
        assertThat(snapshot.foldedMessages()).isEqualTo(4);
    }

    @Test
    @DisplayName("memory=false 时完全不走会话：不读历史也不写库")
    void shouldSkipMemoryWhenDisabledPerRequest() {
        ConversationMemory.Snapshot snapshot = memory.prepare("s1", false);
        memory.recordTurn(snapshot, "问题", "答案", "deepseek", "m");

        assertThat(snapshot.hasSession()).isFalse();
        assertThat(snapshot.history()).isEmpty();
        assertThat(store.findSession("s1")).isEmpty();
    }

    @Test
    @DisplayName("全局关闭记忆时同样退化成单轮")
    void shouldSkipMemoryWhenGloballyDisabled() {
        memory = newMemory(false, BUDGET, 2, 60, 2, 400);

        ConversationMemory.Snapshot snapshot = memory.prepare("s1", true);

        assertThat(snapshot.hasSession()).isFalse();
    }

    @Test
    @DisplayName("存储故障不让问答挂掉：降级为单轮，异常只进日志")
    void shouldDegradeWhenStoreFails() {
        ConversationStore broken = mock(ConversationStore.class);
        when(broken.findSession(anyString())).thenThrow(new IllegalStateException("数据库断了"));
        memory = new ConversationMemory(broken, compressor, true, true, BUDGET, 2, 60, 2, 400);

        ConversationMemory.Snapshot snapshot = memory.prepare("s1", true);

        assertThat(snapshot.hasSession()).isFalse();
        assertThat(snapshot.history()).isEmpty();
    }

    @Test
    @DisplayName("sessionId 超长属于参数错误，必须往上抛而不是静默降级")
    void shouldRejectTooLongSessionId() {
        assertThatThrownBy(() -> memory.prepare("x".repeat(65), true))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("sessionId");
    }

    @Test
    @DisplayName("兜底答案不写历史：只记用户问题会误导下一轮")
    void shouldNotRecordWhenAnswerIsBlank() {
        ConversationMemory.Snapshot snapshot = memory.prepare(null, true);
        memory.recordTurn(snapshot, "问题", null, "deepseek", "m");

        assertThat(store.countMessages(snapshot.sessionId())).isEqualTo(1);
    }

    @Test
    @DisplayName("手动压缩保留最近 N 条，返回折叠条数")
    void shouldCompactManuallyKeepingRecentMessages() {
        String sessionId = "s-manual";
        store.createSession(sessionId, null);
        for (int i = 0; i < 10; i++) {
            store.appendMessage(sessionId, ChatRole.USER, "问题" + i, 100, null, null);
        }

        CompactResult result = memory.compact(sessionId);

        assertThat(result.compressed()).isTrue();
        assertThat(result.foldedMessages()).isEqualTo(8);   // 10 条里留 keepRecentMessages=2 条
        assertThat(result.compressionCount()).isEqualTo(1);
        assertThat(store.countMessages(sessionId)).isEqualTo(10);
    }

    @Test
    @DisplayName("消息太少时手动压缩是空操作")
    void shouldDoNothingWhenNothingToCompact() {
        String sessionId = "s-manual-empty";
        store.createSession(sessionId, null);
        store.appendMessage(sessionId, ChatRole.USER, "问题", 10, null, null);

        CompactResult result = memory.compact(sessionId);

        assertThat(result.compressed()).isFalse();
        assertThat(result.foldedMessages()).isZero();
    }

    private ConversationMemory newMemory(boolean enabled, int maxHistoryTokens, int keepRecent,
                                         int maxHistoryMessages, int compressMin, int summaryMaxTokens) {
        return new ConversationMemory(store, compressor, enabled, true, maxHistoryTokens, keepRecent,
                maxHistoryMessages, compressMin, summaryMaxTokens);
    }

    /** 确认历史消息不会被压缩器改写。 */
    @Test
    @DisplayName("折叠进摘要的消息不再出现在下一轮历史里")
    void shouldDropFoldedMessagesFromNextTurnHistory() {
        String sessionId = "s-drop";
        store.createSession(sessionId, null);
        for (int i = 0; i < 4; i++) {
            store.appendMessage(sessionId, ChatRole.USER, "问题" + i, 100, null, null);
        }

        memory.prepare(sessionId, true);
        List<String> contents = memory.prepare(sessionId, true).history().stream()
                .map(message -> message.content())
                .toList();

        assertThat(contents).containsExactly("问题2", "问题3");
    }

    @Test
    @DisplayName("指代不明的追问要把上一轮问题补进检索语句，否则向量检索召回不到")
    void shouldRewriteReferentialQuestion() {
        ConversationMemory.Snapshot snapshot = new ConversationMemory.Snapshot("s1", null,
                List.of(LlmMessage.user("火系克制哪些属性？"), LlmMessage.assistant("克制草、冰、虫。[1]")),
                30, false, 0);

        assertThat(memory.retrievalQuery("那它被哪些属性克制？", snapshot))
                .isEqualTo("火系克制哪些属性？ 那它被哪些属性克制？");
    }

    @Test
    @DisplayName("完整的新问题不做改写，避免被上一轮话题带偏")
    void shouldNotRewriteSelfContainedQuestion() {
        ConversationMemory.Snapshot snapshot = new ConversationMemory.Snapshot("s1", null,
                List.of(LlmMessage.user("火系克制哪些属性？")), 10, false, 0);

        assertThat(memory.retrievalQuery("普通咕噜球怎么获得？", snapshot))
                .isEqualTo("普通咕噜球怎么获得？");
    }

    @Test
    @DisplayName("没有历史时检索语句保持不变")
    void shouldKeepQueryWithoutHistory() {
        ConversationMemory.Snapshot snapshot = new ConversationMemory.Snapshot("s1", null,
                List.of(), 0, false, 0);

        assertThat(memory.retrievalQuery("那它呢？", snapshot)).isEqualTo("那它呢？");
    }
}
