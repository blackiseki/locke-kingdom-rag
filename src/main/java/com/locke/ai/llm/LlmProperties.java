package com.locke.ai.llm;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 多 Provider 配置。
 *
 * <p>这里刻意不复用 Spring AI 的 model starter：starter 的装配条件是
 * 「配置里写死一个 provider」，启动后无法按请求切换，也无法在运行期做
 * 「主 Provider 失败 → 自动换下一个」。本项目的对话链路统一走
 * OpenAI-compatible HTTP 协议，因此一个配置 map 就能覆盖 DeepSeek、
 * DashScope（通义兼容模式）、OpenAI 以及任何兼容服务。
 *
 * <p>配置示例见 {@code application.yml} 的 {@code locke.ai.llm} 段。
 */
@Component
@ConfigurationProperties(prefix = "locke.ai.llm")
public class LlmProperties {

    /** 默认 Provider id；请求里没有显式传 provider 时使用。 */
    private String provider = "deepseek";

    /** 默认 Provider 失败后的降级顺序。 */
    private List<String> fallback = new ArrayList<>();

    /**
     * Provider 目录。
     *
     * <p>key 就是对外暴露的 provider id，允许字母、数字、下划线和短横线。
     */
    private Map<String, Provider> providers = new LinkedHashMap<>();

    public String getProvider() {
        return provider;
    }

    public void setProvider(String provider) {
        this.provider = provider;
    }

    public List<String> getFallback() {
        return fallback;
    }

    public void setFallback(List<String> fallback) {
        this.fallback = fallback == null ? new ArrayList<>() : fallback;
    }

    public Map<String, Provider> getProviders() {
        return providers;
    }

    public void setProviders(Map<String, Provider> providers) {
        this.providers = providers == null ? new LinkedHashMap<>() : providers;
    }

    /** 单个 OpenAI-compatible Provider 的配置。 */
    public static class Provider {

        private boolean enabled = true;

        /**
         * 是否必须有 api-key 才算可用。
         *
         * <p>本地 Ollama / vLLM 之类通常不需要鉴权，可以设为 false。
         */
        private boolean requiresApiKey = true;

        /** 例如 https://api.deepseek.com 或 https://dashscope.aliyuncs.com/compatible-mode/v1。 */
        private String baseUrl;

        private String apiKey = "";

        private String model;

        private Double temperature = 0.3;

        private Integer maxTokens = 2048;

        private Duration timeout = Duration.ofSeconds(60);

        /** 流式请求是否发送 stream_options.include_usage=true。 */
        private boolean includeUsage = true;

        /** 透传给 Provider 的额外 JSON 字段，例如 DeepSeek 的 thinking。 */
        private Map<String, Object> extraBody = new LinkedHashMap<>();

        /** 额外 HTTP 请求头。 */
        private Map<String, String> headers = new LinkedHashMap<>();

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public boolean isRequiresApiKey() {
            return requiresApiKey;
        }

        public void setRequiresApiKey(boolean requiresApiKey) {
            this.requiresApiKey = requiresApiKey;
        }

        public String getBaseUrl() {
            return baseUrl;
        }

        public void setBaseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
        }

        public String getApiKey() {
            return apiKey;
        }

        public void setApiKey(String apiKey) {
            this.apiKey = apiKey;
        }

        public String getModel() {
            return model;
        }

        public void setModel(String model) {
            this.model = model;
        }

        public Double getTemperature() {
            return temperature;
        }

        public void setTemperature(Double temperature) {
            this.temperature = temperature;
        }

        public Integer getMaxTokens() {
            return maxTokens;
        }

        public void setMaxTokens(Integer maxTokens) {
            this.maxTokens = maxTokens;
        }

        public Duration getTimeout() {
            return timeout;
        }

        public void setTimeout(Duration timeout) {
            this.timeout = timeout;
        }

        public boolean isIncludeUsage() {
            return includeUsage;
        }

        public void setIncludeUsage(boolean includeUsage) {
            this.includeUsage = includeUsage;
        }

        public Map<String, Object> getExtraBody() {
            return extraBody;
        }

        public void setExtraBody(Map<String, Object> extraBody) {
            this.extraBody = extraBody == null ? new LinkedHashMap<>() : extraBody;
        }

        public Map<String, String> getHeaders() {
            return headers;
        }

        public void setHeaders(Map<String, String> headers) {
            this.headers = headers == null ? new LinkedHashMap<>() : headers;
        }
    }
}
