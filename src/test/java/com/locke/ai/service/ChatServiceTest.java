package com.locke.ai.service;

import com.locke.ai.domain.dto.ChatRequest;
import com.locke.ai.domain.dto.ChatResponse;
import com.locke.ai.domain.dto.ChatStreamEvent;
import com.locke.ai.llm.LlmAttempt;
import com.locke.ai.llm.LlmCallException;
import com.locke.ai.llm.LlmChunk;
import com.locke.ai.llm.LlmCompletion;
import com.locke.ai.llm.LlmMessage;
import com.locke.ai.llm.LlmProvider;
import com.locke.ai.llm.LlmProviderRegistry;
import com.locke.ai.llm.LlmRequest;
import com.locke.ai.llm.LlmRouter;
import com.locke.ai.llm.LlmUsage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.document.Document;
import org.springframework.core.io.ClassPathResource;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link ChatService} 单元测试。
 *
 * <p>不起 Spring 容器：依赖全是构造器注入的，手工 new 比 {@code @SpringBootTest}
 * 快两个数量级，而且不需要数据库、Ollama 和 API Key——
 * 这正是把它设计成构造器注入的原因。
 */
class ChatServiceTest {

    private static final String QUESTION = "喵喵是什么属性？";

    private RetrieverService retrieverService;
    private LlmRouter llmRouter;
    private LlmProviderRegistry providerRegistry;
    private ConversationMemory conversationMemory;
    private ChatService chatService;

    @BeforeEach
    void setUp() {
        retrieverService = mock(RetrieverService.class);
        llmRouter = mock(LlmRouter.class);
        providerRegistry = mock(LlmProviderRegistry.class);
        when(providerRegistry.defaultProvider()).thenReturn("deepseek");

        // 用真实的 PromptBuilder：模板就在 test classpath 上，
        // 顺带验证 prompts/*.st 确实被打进了产物（漏打包时这里会直接抛异常）
        PromptBuilder promptBuilder = new PromptBuilder(
                new ClassPathResource("prompts/rag-system.st"),
                new ClassPathResource("prompts/rag-user.st"),
                new ClassPathResource("prompts/memory-summary.st"));

        // 记忆用 mock：本类只关心「记忆给出的摘要 / 历史有没有被正确塞进 prompt」，
        // 预算裁剪与压缩策略由 ConversationMemoryTest 覆盖
        conversationMemory = mock(ConversationMemory.class);
        when(conversationMemory.prepare(nullable(String.class), anyBoolean()))
                .thenReturn(new ConversationMemory.Snapshot("s-test", null, List.of(), 0, false, 0));
        // 默认不改写检索语句（改写规则本身在 ConversationMemoryTest 里测）
        when(conversationMemory.retrievalQuery(anyString(), any()))
                .thenAnswer(invocation -> invocation.getArgument(0));

        chatService = new ChatService(retrieverService, promptBuilder, llmRouter, providerRegistry,
                conversationMemory, 3, 0.5);
    }

    @Test
    @DisplayName("正常问答：返回模型答案、出处、provider 与 usage")
    void shouldReturnAnswerWithSources() {
        Document document = spiritDocument("喵喵", "pets/3001.json", 0.91);
        when(retrieverService.retrieve(eq(QUESTION), anyInt(), anyDouble())).thenReturn(List.of(document));
        when(llmRouter.complete(any(), nullable(String.class))).thenReturn(completion("喵喵是草系精灵。[1]"));

        ChatResponse response = chatService.ask(new ChatRequest(QUESTION, 3, null));

        assertThat(response.answer()).isEqualTo("喵喵是草系精灵。[1]");
        assertThat(response.model()).isEqualTo("deepseek-flash");
        assertThat(response.provider()).isEqualTo("deepseek");
        assertThat(response.sources()).hasSize(1);
        assertThat(response.sources().get(0).spiritName()).isEqualTo("喵喵");
        assertThat(response.sources().get(0).score()).isEqualTo(0.91);
        assertThat(response.promptTokens()).isEqualTo(120);
        assertThat(response.completionTokens()).isEqualTo(18);
        assertThat(response.totalTokens()).isEqualTo(138);
        assertThat(response.estimatedTokens()).isFalse();
        assertThat(response.fallback()).isFalse();
        assertThat(response.latencyMs()).isNotNegative();
        assertThat(response.retrievalMs()).isNotNegative();
        assertThat(response.llmLatencyMs()).isEqualTo(11);
        assertThat(response.attempts()).hasSize(1);
    }

    @Test
    @DisplayName("参考资料必须带编号和来源，且问题原文要进 prompt")
    void shouldBuildPromptWithNumberedContext() {
        when(retrieverService.retrieve(anyString(), anyInt(), anyDouble())).thenReturn(List.of(
                spiritDocument("喵喵", "pets/3001.json", 0.9),
                spiritDocument("火苗", "pets/3002.json", 0.8)));
        when(llmRouter.complete(any(), nullable(String.class))).thenReturn(completion("答案"));

        chatService.ask(new ChatRequest(QUESTION, null, null));

        ArgumentCaptor<LlmRequest> request = ArgumentCaptor.forClass(LlmRequest.class);
        verify(llmRouter).complete(request.capture(), nullable(String.class));

        assertThat(request.getValue().userPrompt())
                .contains("[1] 来源：pets/3001.json")
                .contains("[2] 来源：pets/3002.json")
                .contains("精灵：喵喵")
                .contains("精灵：火苗")
                .contains(QUESTION);
    }

    @Test
    @DisplayName("模型故障时降级成兜底回复，而不是把异常抛给接口层")
    void shouldFallBackWhenModelFails() {
        when(retrieverService.retrieve(anyString(), anyInt(), anyDouble())).thenReturn(List.of());
        when(llmRouter.complete(any(), nullable(String.class)))
                .thenThrow(new LlmCallException("全部 Provider 失败",
                        List.of(LlmAttempt.failed("deepseek", "deepseek-flash", 50, "timeout"))));

        ChatResponse response = chatService.ask(new ChatRequest(QUESTION, null, null));

        assertThat(response.answer()).contains("暂时不可用");
        assertThat(response.fallback()).isTrue();
        assertThat(response.attempts()).hasSize(1);
    }

    @Test
    @DisplayName("请求未知 provider 时在检索前就拒绝，返回参数错误")
    void shouldRejectUnknownProvider() {
        when(providerRegistry.find("not-exist")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> chatService.ask(new ChatRequest(QUESTION, null, null, "not-exist")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("未知 provider");
    }

    @Test
    @DisplayName("检索为空时上下文要显式说明，不能让 prompt 出现空的参考资料段")
    void shouldExplainEmptyContext() {
        assertThat(chatService.renderContext(List.of())).contains("没有检索到");
    }

    @Test
    @DisplayName("上下文编号顺序必须与 sources 顺序一致，否则模型引用的 [1] 会指错")
    void shouldKeepContextAndSourcesInSameOrder() {
        Document first = spiritDocument("喵喵", "pets/3001.json", 0.9);
        Document second = spiritDocument("火苗", "pets/3002.json", 0.8);

        String context = chatService.renderContext(List.of(first, second));
        List<ChatResponse.Source> sources = chatService.toSources(List.of(first, second));

        assertThat(context.indexOf("[1]")).isLessThan(context.indexOf("[2]"));
        assertThat(context.indexOf("pets/3001.json")).isLessThan(context.indexOf("pets/3002.json"));
        assertThat(sources.get(0).source()).isEqualTo("pets/3001.json");
        assertThat(sources.get(1).source()).isEqualTo("pets/3002.json");
    }

    @Test
    @DisplayName("流式问答依次发出 meta、delta、done，并带上 usage")
    void shouldEmitStructuredStreamEvents() {
        when(retrieverService.retrieve(anyString(), anyInt(), anyDouble())).thenReturn(List.of());
        LlmProvider provider = mock(LlmProvider.class);
        when(provider.id()).thenReturn("deepseek");
        when(provider.model()).thenReturn("deepseek-flash");
        when(llmRouter.primary(nullable(String.class))).thenReturn(Optional.of(provider));

        LlmUsage usage = new LlmUsage(10, 3, false);
        when(llmRouter.stream(any(), nullable(String.class), anyList())).thenReturn(Flux.just(
                new LlmChunk("喵喵", null, null),
                new LlmChunk("", usage, "stop")));

        List<ChatStreamEvent> events = chatService.stream(new ChatRequest(QUESTION, null, null))
                .collectList()
                .block();

        assertThat(events).extracting(ChatStreamEvent::type)
                .containsExactly("meta", "delta", "done");
        assertThat(events.get(1).delta()).isEqualTo("喵喵");
        assertThat(events.get(2).answer()).isEqualTo("喵喵");
        assertThat(events.get(2).promptTokens()).isEqualTo(10);
        assertThat(events.get(2).completionTokens()).isEqualTo(3);
        assertThat(events.get(2).success()).isTrue();
    }

    @Test
    @DisplayName("流式全部失败时先发兜底 delta，再发 success=false 的 done")
    void shouldEmitFallbackDeltaWhenStreamFailsBeforeAnyOutput() {
        when(retrieverService.retrieve(anyString(), anyInt(), anyDouble())).thenReturn(List.of());
        when(llmRouter.primary(nullable(String.class))).thenReturn(Optional.empty());
        when(llmRouter.stream(any(), nullable(String.class), anyList())).thenReturn(Flux.error(
                new LlmCallException("全部 Provider 失败",
                        List.of(LlmAttempt.failed("deepseek", "deepseek-flash", 50, "timeout")))));

        List<ChatStreamEvent> events = chatService.stream(new ChatRequest(QUESTION, null, null))
                .collectList()
                .block();

        assertThat(events).extracting(ChatStreamEvent::type)
                .containsExactly("meta", "delta", "done");
        assertThat(events.get(1).delta()).contains("暂时不可用");
        assertThat(events.get(2).success()).isFalse();
        assertThat(events.get(2).fallback()).isTrue();
    }

    @Test
    @DisplayName("多轮：摘要进 system、历史作为独立消息、成功后才写回会话")
    void shouldFeedMemoryIntoPromptAndRecordTurn() {
        when(conversationMemory.prepare(eq("s-1"), anyBoolean())).thenReturn(
                new ConversationMemory.Snapshot("s-1", "用户问过喵喵的属性",
                        List.of(LlmMessage.user("喵喵是什么属性？"),
                                LlmMessage.assistant("喵喵是草系精灵。[1]")),
                        20, true, 2));
        when(retrieverService.retrieve(anyString(), anyInt(), anyDouble())).thenReturn(List.of());
        when(llmRouter.complete(any(), nullable(String.class))).thenReturn(completion("补充答案"));

        ChatResponse response = chatService.ask(new ChatRequest(QUESTION, null, "s-1"));

        ArgumentCaptor<LlmRequest> request = ArgumentCaptor.forClass(LlmRequest.class);
        verify(llmRouter).complete(request.capture(), nullable(String.class));
        // 摘要必须在 system 里，历史必须是真正的 user/assistant 消息
        assertThat(request.getValue().systemPrompt()).contains("用户问过喵喵的属性");
        assertThat(request.getValue().history()).hasSize(2);
        assertThat(request.getValue().messages()).extracting(LlmMessage::role)
                .containsExactly("system", "user", "assistant", "user");
        // 响应要把会话信息带回去，前端才知道下一轮带哪个 id
        assertThat(response.sessionId()).isEqualTo("s-1");
        assertThat(response.historyTokens()).isEqualTo(20);
        assertThat(response.contextCompressed()).isTrue();
        verify(conversationMemory).recordTurn(any(), eq(QUESTION), eq("补充答案"), eq("deepseek"), eq("deepseek-flash"));
    }

    @Test
    @DisplayName("兜底回复不写入会话历史")
    void shouldNotRecordFallbackAnswer() {
        when(retrieverService.retrieve(anyString(), anyInt(), anyDouble())).thenReturn(List.of());
        when(llmRouter.complete(any(), nullable(String.class)))
                .thenThrow(new LlmCallException("全部 Provider 失败",
                        List.of(LlmAttempt.failed("deepseek", "deepseek-flash", 50, "timeout"))));

        ChatResponse response = chatService.ask(new ChatRequest(QUESTION, null, "s-2"));

        assertThat(response.fallback()).isTrue();
        assertThat(response.sessionId()).isEqualTo("s-test");
        verify(conversationMemory, org.mockito.Mockito.never())
                .recordTurn(any(), anyString(), anyString(), nullable(String.class), nullable(String.class));
    }

    @Test
    @DisplayName("流式 meta 事件带上会话 id，方便前端在第一帧就把上下文接上")
    void shouldExposeSessionIdInStreamMeta() {
        when(retrieverService.retrieve(anyString(), anyInt(), anyDouble())).thenReturn(List.of());
        LlmProvider provider = mock(LlmProvider.class);
        when(provider.id()).thenReturn("deepseek");
        when(provider.model()).thenReturn("deepseek-flash");
        when(llmRouter.primary(nullable(String.class))).thenReturn(Optional.of(provider));
        when(llmRouter.stream(any(), nullable(String.class), anyList())).thenReturn(Flux.just(
                new LlmChunk("喵喵", null, null),
                new LlmChunk("", new LlmUsage(10, 3, false), "stop")));

        List<ChatStreamEvent> events = chatService.stream(new ChatRequest(QUESTION, null, null))
                .collectList()
                .block();

        assertThat(events).isNotNull();
        assertThat(events.get(0).sessionId()).isEqualTo("s-test");
        assertThat(events.get(events.size() - 1).sessionId()).isEqualTo("s-test");
        verify(conversationMemory).recordTurn(any(), eq(QUESTION), eq("喵喵"), anyString(), anyString());
    }

    private static LlmCompletion completion(String answer) {
        LlmUsage usage = new LlmUsage(120, 18, false);
        LlmAttempt attempt = LlmAttempt.success("deepseek", "deepseek-flash", 11, usage);
        return new LlmCompletion("deepseek", "deepseek-flash", answer, usage, 11, List.of(attempt));
    }

    private static Document spiritDocument(String spiritName, String source, double score) {
        return Document.builder()
                .text("精灵：" + spiritName + "\n属性：草")
                .metadata(Map.of("spiritName", spiritName, "source", source))
                .score(score)
                .build();
    }
}
