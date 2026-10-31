package com.locke.ai.llm;

import tools.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Provider 目录：把配置里的每个条目实例化为一个 {@link LlmProvider}。
 *
 * <p>目录在启动时构建一次；之后只读，因此可以安全地被多个请求并发使用。
 */
@Component
public class LlmProviderRegistry {

    private final LlmProperties properties;
    private final Map<String, LlmProvider> providers;

    public LlmProviderRegistry(LlmProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        Map<String, LlmProvider> built = new LinkedHashMap<>();
        properties.getProviders().forEach((id, config) -> {
            if (id == null || id.isBlank() || config == null) {
                return;
            }
            built.put(id, new OpenAiCompatibleProvider(id, config, objectMapper));
        });
        this.providers = Collections.unmodifiableMap(new LinkedHashMap<>(built));
    }

    public Optional<LlmProvider> find(String id) {
        return id == null ? Optional.empty() : Optional.ofNullable(providers.get(id));
    }

    public Collection<LlmProvider> all() {
        return providers.values();
    }

    public String defaultProvider() {
        return properties.getProvider();
    }

    public List<String> fallbackOrder() {
        return properties.getFallback();
    }

    public boolean isDefault(String id) {
        return properties.getProvider() != null && properties.getProvider().equals(id);
    }
}
