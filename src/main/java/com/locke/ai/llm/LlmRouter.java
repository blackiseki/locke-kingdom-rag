package com.locke.ai.llm;

import com.locke.ai.exception.LlmException;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Provider 路由与失败降级。
 *
 * <p>候选顺序：请求指定的 Provider → 默认 Provider → 配置的 fallback →
 * 其余已配置 Provider。跳过未配置完整（缺 key/base-url/model）的 Provider，
 * 并记录 skipped 尝试。同步与流式都按这个顺序尝试。
 *
 * <p>流式的降级只允许发生在「还没有向客户端输出任何正文」之前：一旦已经
 * 输出 delta，再切换 Provider 会导致两段内容拼接，因此只把错误向上抛。
 */
@Component
public class LlmRouter {

    private final LlmProviderRegistry registry;
    private final LlmMetrics metrics;

    public LlmRouter(LlmProviderRegistry registry, LlmMetrics metrics) {
        this.registry = registry;
        this.metrics = metrics;
    }

    /**
     * 同步调用，按候选顺序降级。
     *
     * @param requestedProvider 请求显式指定的 Provider，可为 null
     * @throws LlmCallException 所有候选都失败
     */
    public LlmCompletion complete(LlmRequest request, String requestedProvider) {
        List<LlmAttempt> attempts = new ArrayList<>();
        List<LlmProvider> candidates = candidates(requestedProvider);
        RuntimeException lastError = null;

        for (LlmProvider provider : candidates) {
            if (!provider.available()) {
                attempts.add(LlmAttempt.skipped(provider.id(), provider.model(), provider.status()));
                continue;
            }
            long startNanos = System.nanoTime();
            try {
                LlmCompletion completion = provider.complete(request);
                attempts.add(LlmAttempt.success(provider.id(), provider.model(),
                        completion.latencyMs(), completion.usage()));
                metrics.recordSuccess(provider.id(), completion.usage(), completion.latencyMs());
                return completion.withAttempts(attempts);
            } catch (RuntimeException e) {
                long elapsed = elapsedMs(startNanos);
                attempts.add(LlmAttempt.failed(provider.id(), provider.model(), elapsed, errorMessage(e)));
                metrics.recordFailure(provider.id(), elapsed);
                lastError = e;
            }
        }

        throw new LlmCallException(
                lastError == null ? "没有可用的模型 Provider" : "所有模型 Provider 均调用失败: " + errorMessage(lastError),
                attempts,
                lastError);
    }

    /**
     * 流式调用，允许在首个正文 delta 之前降级。
     *
     * @param attempts 调用方提供的可变列表，路由过程中会追加 success/failed/skipped
     */
    public Flux<LlmChunk> stream(LlmRequest request, String requestedProvider, List<LlmAttempt> attempts) {
        return streamAttempt(candidates(requestedProvider), 0, request, attempts);
    }

    /** 候选顺序中的第一个可用 Provider，用于在 meta 事件里先告诉前端预期目标。 */
    public Optional<LlmProvider> primary(String requestedProvider) {
        return candidates(requestedProvider).stream().filter(LlmProvider::available).findFirst();
    }

    /**
     * 候选顺序去重。显式指定但未配置的 id 会被忽略；ChatService 会先做
     * 参数校验，避免用户拼错 provider 时静默换模型。
     */
    public List<LlmProvider> candidates(String requestedProvider) {
        Set<String> ids = new LinkedHashSet<>();
        if (hasText(requestedProvider)) {
            ids.add(requestedProvider.trim());
        }
        if (hasText(registry.defaultProvider())) {
            ids.add(registry.defaultProvider());
        }
        registry.fallbackOrder().forEach(id -> {
            if (hasText(id)) {
                ids.add(id);
            }
        });
        registry.all().forEach(provider -> ids.add(provider.id()));

        List<LlmProvider> result = new ArrayList<>();
        for (String id : ids) {
            registry.find(id).ifPresent(result::add);
        }
        return result;
    }

    private Flux<LlmChunk> streamAttempt(List<LlmProvider> candidates, int index,
                                        LlmRequest request, List<LlmAttempt> attempts) {
        if (index >= candidates.size()) {
            return Flux.error(new LlmCallException(
                    "所有模型 Provider 均调用失败", attempts, lastFailure(attempts)));
        }

        LlmProvider provider = candidates.get(index);
        if (!provider.available()) {
            attempts.add(LlmAttempt.skipped(provider.id(), provider.model(), provider.status()));
            return streamAttempt(candidates, index + 1, request, attempts);
        }

        long startNanos = System.nanoTime();
        AtomicReference<LlmUsage> usage = new AtomicReference<>();
        AtomicBoolean contentSeen = new AtomicBoolean(false);
        AtomicBoolean recorded = new AtomicBoolean(false);

        return Flux.defer(() -> Flux.fromStream(provider.stream(request)))
                .doOnNext(chunk -> {
                    if (chunk.usage() != null) {
                        usage.set(chunk.usage());
                    }
                    if (hasText(chunk.delta())) {
                        contentSeen.set(true);
                    }
                })
                // 上游正常结束：有正文才算成功；没有正文则构造错误，进入降级。
                .concatWith(Flux.defer(() -> {
                    if (contentSeen.get()) {
                        if (recorded.compareAndSet(false, true)) {
                            long elapsed = elapsedMs(startNanos);
                            attempts.add(LlmAttempt.success(provider.id(), provider.model(), elapsed, usage.get()));
                            metrics.recordSuccess(provider.id(), usage.get(), elapsed);
                        }
                        return Flux.empty();
                    }
                    if (recorded.compareAndSet(false, true)) {
                        long elapsed = elapsedMs(startNanos);
                        attempts.add(LlmAttempt.failed(provider.id(), provider.model(), elapsed, "空流式响应"));
                        metrics.recordFailure(provider.id(), elapsed);
                    }
                    return Flux.error(new LlmException(provider.id() + " 返回了空流式内容", null, true));
                }))
                .onErrorResume(error -> {
                    if (recorded.compareAndSet(false, true)) {
                        long elapsed = elapsedMs(startNanos);
                        attempts.add(LlmAttempt.failed(provider.id(), provider.model(), elapsed, errorMessage(error)));
                        metrics.recordFailure(provider.id(), elapsed);
                    }
                    if (!contentSeen.get()) {
                        return streamAttempt(candidates, index + 1, request, attempts);
                    }
                    return Flux.error(error);
                });
    }

    private Throwable lastFailure(List<LlmAttempt> attempts) {
        for (int i = attempts.size() - 1; i >= 0; i--) {
            LlmAttempt attempt = attempts.get(i);
            if ("failed".equals(attempt.status())) {
                return new LlmException(attempt.error(), null, true);
            }
        }
        return null;
    }

    private static String errorMessage(Throwable error) {
        String message = error.getMessage();
        String detail = message == null || message.isBlank() ? error.getClass().getSimpleName() : message;
        return detail.length() <= 300 ? detail : detail.substring(0, 300) + "…";
    }

    private static long elapsedMs(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000L;
    }

    private static boolean hasText(String text) {
        return text != null && !text.isBlank();
    }
}
