package com.locke.ai.service;

import com.locke.ai.domain.entity.ConversationMessage;
import com.locke.ai.domain.enums.ChatRole;
import com.locke.ai.llm.LlmAttempt;
import com.locke.ai.llm.LlmCallException;
import com.locke.ai.llm.LlmCompletion;
import com.locke.ai.llm.LlmRequest;
import com.locke.ai.llm.LlmRouter;
import com.locke.ai.llm.LlmUsage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.core.io.ClassPathResource;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link ConversationCompressor} 单元测试。
 *
 * <p>重点测两件事：压缩 prompt 里必须同时带旧摘要和新增对话（滚动摘要的语义），
 * 以及模型挂掉时必须退化成抽取式摘要而不是把异常抛到问答主链路。
 */
class ConversationCompressorTest {

    private LlmRouter llmRouter;
    private ConversationCompressor compressor;

    @BeforeEach
    void setUp() {
        llmRouter = mock(LlmRouter.class);
        PromptBuilder promptBuilder = new PromptBuilder(
                new ClassPathResource("prompts/rag-system.st"),
                new ClassPathResource("prompts/rag-user.st"),
                new ClassPathResource("prompts/memory-summary.st"));
        compressor = new ConversationCompressor(llmRouter, promptBuilder);
    }

    @Test
    @DisplayName("把旧摘要和新增对话一起送进压缩请求，返回模型给的摘要")
    void shouldMergePreviousSummaryWithNewDialog() {
        when(llmRouter.complete(any(), nullable(String.class)))
                .thenReturn(completion("· 用户问过火系克制关系\n· 结论：克制草系、虫系"));

        String summary = compressor.compress("· 用户问过喵喵的属性", messages(), 400);

        assertThat(summary).contains("火系克制关系");

        ArgumentCaptor<LlmRequest> request = ArgumentCaptor.forClass(LlmRequest.class);
        verify(llmRouter).complete(request.capture(), nullable(String.class));
        assertThat(request.getValue().userPrompt())
                .contains("用户问过喵喵的属性")
                .contains("用户：火系克制什么？")
                .contains("助手：克制草系和虫系");
        assertThat(request.getValue().systemPrompt()).contains("压缩");
    }

    @Test
    @DisplayName("模型不可用时退化成抽取式摘要，绝不向上抛异常")
    void shouldFallBackToExtractiveWhenModelFails() {
        when(llmRouter.complete(any(), nullable(String.class)))
                .thenThrow(new LlmCallException("全部 Provider 失败",
                        List.of(LlmAttempt.failed("deepseek", "deepseek-flash", 50, "timeout"))));

        String summary = compressor.compress("· 用户问过喵喵的属性", messages(), 400);

        assertThat(summary)
                .contains("用户问过喵喵的属性")
                .contains("用户问：火系克制什么？")
                .contains("已答：克制草系和虫系");
    }

    @Test
    @DisplayName("模型返回空内容时也走兜底")
    void shouldFallBackWhenModelReturnsBlank() {
        when(llmRouter.complete(any(), nullable(String.class))).thenReturn(completion("   "));

        String summary = compressor.compress(null, messages(), 400);

        assertThat(summary).contains("火系克制什么？");
    }

    @Test
    @DisplayName("没有待折叠消息时原样返回旧摘要，不浪费一次模型调用")
    void shouldReturnPreviousSummaryWhenNothingToFold() {
        String summary = compressor.compress("旧摘要", List.of(), 400);

        assertThat(summary).isEqualTo("旧摘要");
        verify(llmRouter, org.mockito.Mockito.never()).complete(any(), nullable(String.class));
    }

    @Test
    @DisplayName("摘要超字数时按 token 预算硬裁")
    void shouldTruncateOverlongSummary() {
        when(llmRouter.complete(any(), nullable(String.class)))
                .thenReturn(completion("火".repeat(2000)));

        String summary = compressor.compress(null, messages(), 120);

        assertThat(summary.length()).isLessThanOrEqualTo(120);
    }

    private static LlmCompletion completion(String content) {
        LlmUsage usage = new LlmUsage(300, 80, false);
        return new LlmCompletion("deepseek", "deepseek-flash", content, usage, 40,
                List.of(LlmAttempt.success("deepseek", "deepseek-flash", 40, usage)));
    }

    private static List<ConversationMessage> messages() {
        return List.of(
                new ConversationMessage(1, "s1", ChatRole.USER, "火系克制什么？", 8, null, null, Instant.now()),
                new ConversationMessage(2, "s1", ChatRole.ASSISTANT, "克制草系和虫系。[1]", 10,
                        "deepseek", "deepseek-flash", Instant.now()));
    }
}
