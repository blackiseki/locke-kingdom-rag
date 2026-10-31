package com.locke.ai.llm;

import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 进程内 Provider 指标聚合。
 *
 * <p>不引入 Prometheus/Micrometer 依赖，先提供够用的运行期观测：
 * 每个 Provider 的请求数、成功/失败、token、平均/最大耗时。
 * 指标是进程级的，重启归零；对外只读。
 */
@Component
public class LlmMetrics {

    private final Map<String, ProviderMetric> metrics = new ConcurrentHashMap<>();

    public void recordSuccess(String provider, LlmUsage usage, long latencyMs) {
        ProviderMetric metric = metric(provider);
        metric.requests.incrementAndGet();
        metric.successes.incrementAndGet();
        metric.addLatency(latencyMs);
        if (usage != null) {
            metric.promptTokens.addAndGet(Math.max(0, usage.promptTokens()));
            metric.completionTokens.addAndGet(Math.max(0, usage.completionTokens()));
            if (usage.estimated()) {
                metric.estimatedRequests.incrementAndGet();
            }
        }
    }

    public void recordFailure(String provider, long latencyMs) {
        ProviderMetric metric = metric(provider);
        metric.requests.incrementAndGet();
        metric.failures.incrementAndGet();
        metric.addLatency(latencyMs);
    }

    public Map<String, Snapshot> snapshot() {
        Map<String, Snapshot> result = new TreeMap<>();
        metrics.forEach((provider, metric) -> result.put(provider, metric.snapshot(provider)));
        return result;
    }

    private ProviderMetric metric(String provider) {
        String key = provider == null || provider.isBlank() ? "unknown" : provider;
        return metrics.computeIfAbsent(key, ignored -> new ProviderMetric());
    }

    /** 单个 Provider 的累计值。 */
    private static final class ProviderMetric {

        private final AtomicLong requests = new AtomicLong();
        private final AtomicLong successes = new AtomicLong();
        private final AtomicLong failures = new AtomicLong();
        private final AtomicLong promptTokens = new AtomicLong();
        private final AtomicLong completionTokens = new AtomicLong();
        private final AtomicLong totalLatencyMs = new AtomicLong();
        private final AtomicLong maxLatencyMs = new AtomicLong();
        private final AtomicLong estimatedRequests = new AtomicLong();

        private void addLatency(long latencyMs) {
            long safe = Math.max(0, latencyMs);
            totalLatencyMs.addAndGet(safe);
            maxLatencyMs.accumulateAndGet(safe, Math::max);
        }

        private Snapshot snapshot(String provider) {
            long requestCount = requests.get();
            long average = requestCount == 0 ? 0 : totalLatencyMs.get() / requestCount;
            long prompt = promptTokens.get();
            long completion = completionTokens.get();
            return new Snapshot(
                    provider,
                    requestCount,
                    successes.get(),
                    failures.get(),
                    prompt,
                    completion,
                    prompt + completion,
                    estimatedRequests.get(),
                    average,
                    maxLatencyMs.get());
        }
    }

    /**
     * 指标快照。
     *
     * @param requests          总请求数（成功 + 失败）
     * @param estimatedRequests 至少部分 token 为估算的请求数
     * @param avgLatencyMs      平均调用耗时（含失败）
     */
    public record Snapshot(
            String provider,
            long requests,
            long successes,
            long failures,
            long promptTokens,
            long completionTokens,
            long totalTokens,
            long estimatedRequests,
            long avgLatencyMs,
            long maxLatencyMs) {
    }
}
