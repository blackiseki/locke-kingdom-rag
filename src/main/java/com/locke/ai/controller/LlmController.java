package com.locke.ai.controller;

import com.locke.ai.llm.LlmMetrics;
import com.locke.ai.llm.LlmProvider;
import com.locke.ai.llm.LlmProviderRegistry;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Provider 目录与运行期指标。
 *
 * <p>只读接口，不触发模型调用；用于前端下拉框和排障。
 */
@RestController
@RequestMapping("/api/llm")
public class LlmController {

    private final LlmProviderRegistry registry;
    private final LlmMetrics metrics;

    public LlmController(LlmProviderRegistry registry, LlmMetrics metrics) {
        this.registry = registry;
        this.metrics = metrics;
    }

    /** 当前配置了哪些 Provider、哪些可用、默认与 fallback 顺序。 */
    @GetMapping("/providers")
    public ProviderCatalog providers() {
        List<ProviderInfo> providers = new ArrayList<>();
        for (LlmProvider provider : registry.all()) {
            String status = provider.status();
            providers.add(new ProviderInfo(
                    provider.id(),
                    provider.model(),
                    status,
                    "ready".equals(status),
                    registry.isDefault(provider.id()),
                    registry.fallbackOrder().contains(provider.id())));
        }
        return new ProviderCatalog(registry.defaultProvider(), registry.fallbackOrder(), providers);
    }

    /** 进程内聚合指标；重启归零。 */
    @GetMapping("/metrics")
    public MetricsResponse metrics() {
        return new MetricsResponse(Instant.now(), metrics.snapshot());
    }

    public record ProviderInfo(
            String id,
            String model,
            String status,
            boolean available,
            boolean isDefault,
            boolean fallback) {
    }

    public record ProviderCatalog(
            String defaultProvider,
            List<String> fallback,
            List<ProviderInfo> providers) {
    }

    public record MetricsResponse(
            Instant collectedAt,
            Map<String, LlmMetrics.Snapshot> providers) {
    }
}
