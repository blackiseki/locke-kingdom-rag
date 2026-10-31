package com.locke.ai.llm;

import com.locke.ai.exception.LlmException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 路由顺序、同步降级与流式降级边界的单元测试。 */
class LlmRouterTest {

    private LlmProviderRegistry registry;
    private LlmRouter router;
    private LlmProvider first;
    private LlmProvider second;

    @BeforeEach
    void setUp() {
        registry = mock(LlmProviderRegistry.class);
        first = mock(LlmProvider.class);
        second = mock(LlmProvider.class);
        when(first.id()).thenReturn("first");
        when(first.model()).thenReturn("m1");
        when(first.status()).thenReturn("ready");
        when(first.available()).thenReturn(true);
        when(second.id()).thenReturn("second");
        when(second.model()).thenReturn("m2");
        when(second.status()).thenReturn("ready");
        when(second.available()).thenReturn(true);
        when(registry.defaultProvider()).thenReturn("first");
        when(registry.fallbackOrder()).thenReturn(List.of("second"));
        when(registry.find("first")).thenReturn(Optional.of(first));
        when(registry.find("second")).thenReturn(Optional.of(second));
        when(registry.all()).thenReturn(List.of(first, second));
        router = new LlmRouter(registry, new LlmMetrics());
    }

    @Test
    void shouldFallBackToNextProviderOnComplete() {
        when(first.complete(any())).thenThrow(new LlmException("first down", null, true));
        when(second.complete(any())).thenReturn(completion("second", "m2"));

        LlmCompletion result = router.complete(new LlmRequest("s", "u"), null);

        assertThat(result.provider()).isEqualTo("second");
        assertThat(result.attempts()).extracting(LlmAttempt::status)
                .containsExactly("failed", "success");
        assertThat(result.attempts()).extracting(LlmAttempt::provider)
                .containsExactly("first", "second");
    }

    @Test
    void shouldThrowWithAttemptsWhenAllProvidersFail() {
        when(first.complete(any())).thenThrow(new LlmException("first down", null, true));
        when(second.complete(any())).thenThrow(new LlmException("second down", null, true));

        assertThatThrownBy(() -> router.complete(new LlmRequest("s", "u"), null))
                .isInstanceOfSatisfying(LlmCallException.class, error -> {
                    assertThat(error.attempts()).hasSize(2);
                    assertThat(error.attempts()).allMatch(a -> "failed".equals(a.status()));
                });
    }

    @Test
    void shouldFallBackBeforeAnyStreamContent() {
        when(first.stream(any())).thenThrow(new LlmException("first stream down", null, true));
        when(second.stream(any())).thenReturn(Stream.of(LlmChunk.text("来自第二个")));

        List<LlmChunk> chunks = router.stream(new LlmRequest("s", "u"), null, new java.util.ArrayList<>())
                .collectList().block();

        assertThat(chunks).extracting(LlmChunk::delta).containsExactly("来自第二个");
    }

    @Test
    void shouldNotFallBackAfterStreamContentWasEmitted() {
        when(first.stream(any())).thenReturn(Stream.concat(
                Stream.of(LlmChunk.text("半截")),
                Stream.<LlmChunk>of(LlmChunk.text("x")).map(chunk -> {
                    throw new LlmException("stream broke", null, true);
                })));
        when(second.stream(any())).thenReturn(Stream.of(LlmChunk.text("不应该出现")));

        Flux<LlmChunk> flux = router.stream(new LlmRequest("s", "u"), null, new java.util.ArrayList<>());

        assertThatThrownBy(() -> flux.collectList().block())
                .hasMessageContaining("stream broke");
    }

    private static LlmCompletion completion(String provider, String model) {
        return new LlmCompletion(provider, model, "ok", new LlmUsage(1, 1, false), 5, List.of());
    }
}
