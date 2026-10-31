package com.locke.ai.llm;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;
import com.locke.ai.exception.LlmException;
import com.locke.ai.util.TokenCounter;

import java.io.IOException;
import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Iterator;
import java.util.NoSuchElementException;
import java.util.Spliterator;
import java.util.Spliterators;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

/**
 * 一个使用 OpenAI-compatible Chat Completions 协议的 Provider。
 *
 * <p>DeepSeek、DashScope 兼容模式、OpenAI、vLLM、Ollama 的
 * {@code /v1/chat/completions} 都可以用同一份实现。请求/响应只依赖
 * Jackson + JDK HttpClient，不再让业务层依赖某一个 Spring AI model starter。
 */
public class OpenAiCompatibleProvider implements LlmProvider {

    private static final String CHAT_COMPLETIONS_PATH = "/chat/completions";
    private static final int MAX_ERROR_DETAIL_CHARS = 500;

    private final String id;
    private final LlmProperties.Provider config;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    public OpenAiCompatibleProvider(String id, LlmProperties.Provider config, ObjectMapper objectMapper) {
        this.id = id;
        this.config = config;
        this.objectMapper = objectMapper;
        Duration timeout = config.getTimeout() == null ? Duration.ofSeconds(60) : config.getTimeout();
        // 连接超时不必等于整个请求超时：连不上时应该尽快失败，把时间留给降级。
        Duration connectTimeout = timeout.compareTo(Duration.ofSeconds(10)) > 0
                ? Duration.ofSeconds(10)
                : timeout;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(connectTimeout)
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    @Override
    public String id() {
        return id;
    }

    @Override
    public String model() {
        return config.getModel();
    }

    @Override
    public String status() {
        if (!config.isEnabled()) {
            return "disabled";
        }
        if (!hasText(config.getBaseUrl())) {
            return "missing base-url";
        }
        if (!hasText(config.getModel())) {
            return "missing model";
        }
        if (config.isRequiresApiKey() && !hasText(config.getApiKey())) {
            return "missing api-key";
        }
        return "ready";
    }

    @Override
    public LlmCompletion complete(LlmRequest request) {
        long startNanos = System.nanoTime();
        String body = requestBody(request, false);
        HttpResponse<String> response = send(body, false);
        String responseBody = response.body() == null ? "" : response.body();
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw httpError(response.statusCode(), responseBody);
        }

        JsonNode root;
        try {
            root = objectMapper.readTree(responseBody);
        } catch (JacksonException e) {
            throw new LlmException(id + " 返回了无法解析的 JSON", e, false);
        }
        String content = extractContent(root);
        if (!hasText(content)) {
            throw new LlmException(id + " 返回了空内容，请检查 max-tokens 与内容合规设置", null, false);
        }

        int estimatedPrompt = request.estimatedTokens();
        int estimatedCompletion = TokenCounter.estimate(content);
        LlmUsage usage = parseUsage(root.get("usage"), estimatedPrompt, estimatedCompletion);
        if (usage == null) {
            usage = new LlmUsage(estimatedPrompt, estimatedCompletion, true);
        }
        return new LlmCompletion(id, model(), content, usage, elapsedMs(startNanos));
    }

    @Override
    public Stream<LlmChunk> stream(LlmRequest request) {
        HttpResponse<Stream<String>> response = sendLines(requestBody(request, true));
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            String errorBody;
            try (Stream<String> lines = response.body()) {
                errorBody = lines.collect(Collectors.joining("\n"));
            }
            throw httpError(response.statusCode(), errorBody);
        }
        SseChunkIterator iterator = new SseChunkIterator(response.body());
        return StreamSupport.stream(
                        Spliterators.spliteratorUnknownSize(iterator, Spliterator.ORDERED | Spliterator.NONNULL),
                        false)
                .onClose(iterator::close);
    }

    // ============================================================
    // HTTP
    // ============================================================

    private HttpResponse<String> send(String body, boolean stream) {
        HttpRequest httpRequest = buildRequest(body, stream);
        try {
            return httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (HttpTimeoutException e) {
            throw new LlmException(id + " 请求超时", e, true);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new LlmException(id + " 请求被中断", e, true);
        } catch (IOException e) {
            throw new LlmException(id + " 网络请求失败: " + safeMessage(e), e, isRetryableNetworkError(e));
        }
    }

    private HttpResponse<Stream<String>> sendLines(String body) {
        HttpRequest httpRequest = buildRequest(body, true);
        try {
            return httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofLines());
        } catch (HttpTimeoutException e) {
            throw new LlmException(id + " 流式请求超时", e, true);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new LlmException(id + " 流式请求被中断", e, true);
        } catch (IOException e) {
            throw new LlmException(id + " 流式网络请求失败: " + safeMessage(e), e, isRetryableNetworkError(e));
        }
    }

    private HttpRequest buildRequest(String body, boolean stream) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(endpoint())
                .timeout(config.getTimeout() == null ? Duration.ofSeconds(60) : config.getTimeout())
                .header("Content-Type", "application/json")
                .header("Accept", stream ? "text/event-stream" : "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
        if (hasText(config.getApiKey())) {
            builder.header("Authorization", "Bearer " + config.getApiKey());
        }
        if (config.getHeaders() != null) {
            config.getHeaders().forEach((name, value) -> {
                if (hasText(name) && value != null) {
                    builder.header(name, value);
                }
            });
        }
        return builder.build();
    }

    private URI endpoint() {
        return URI.create(config.getBaseUrl().replaceAll("/+$", "") + CHAT_COMPLETIONS_PATH);
    }

    private String requestBody(LlmRequest request, boolean stream) {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("model", config.getModel());
        ArrayNode messages = root.putArray("messages");
        // 顺序由 LlmRequest.messages() 统一决定（system → history → user），
        // 这里只做序列化，不在每个 Provider 里各写一遍拼接逻辑
        for (LlmMessage message : request.messages()) {
            messages.addObject().put("role", message.role()).put("content", message.content());
        }

        if (config.getTemperature() != null) {
            root.put("temperature", config.getTemperature());
        }
        if (config.getMaxTokens() != null && config.getMaxTokens() > 0) {
            root.put("max_tokens", config.getMaxTokens());
        }
        root.put("stream", stream);
        if (stream && config.isIncludeUsage()) {
            root.putObject("stream_options").put("include_usage", true);
        }
        // extra-body 最后写入，允许 provider 覆盖上面的默认字段（例如 thinking）。
        if (config.getExtraBody() != null) {
            config.getExtraBody().forEach((key, value) -> {
                if (hasText(key) && value != null) {
                    root.set(key, objectMapper.valueToTree(value));
                }
            });
        }
        try {
            return objectMapper.writeValueAsString(root);
        } catch (JacksonException e) {
            throw new LlmException(id + " 请求体序列化失败", e, false);
        }
    }

    // ============================================================
    // 响应解析
    // ============================================================

    private String extractContent(JsonNode root) {
        JsonNode content = root.path("choices").path(0).path("message").path("content");
        if (content.isTextual()) {
            return content.asText();
        }
        if (content.isArray()) {
            StringBuilder builder = new StringBuilder();
            content.forEach(part -> {
                if (part.isTextual()) {
                    builder.append(part.asText());
                } else if (part.has("text")) {
                    builder.append(part.path("text").asText(""));
                }
            });
            return builder.toString();
        }
        return "";
    }

    /**
     * 解析 usage；缺失的字段用估算值补齐，并标记 estimated。
     *
     * @return usage 节点完全缺失时返回 null，由调用方决定是否整体估算
     */
    private LlmUsage parseUsage(JsonNode usageNode, int estimatedPrompt, int estimatedCompletion) {
        if (usageNode == null || usageNode.isMissingNode() || usageNode.isNull()) {
            return null;
        }
        int prompt = usageNode.path("prompt_tokens").asInt(-1);
        int completion = usageNode.path("completion_tokens").asInt(-1);
        if (prompt < 0 && completion < 0) {
            return null;
        }
        boolean estimated = false;
        if (prompt < 0) {
            prompt = estimatedPrompt;
            estimated = true;
        }
        if (completion < 0) {
            completion = estimatedCompletion;
            estimated = true;
        }
        return new LlmUsage(prompt, completion, estimated);
    }

    private LlmException httpError(int statusCode, String body) {
        String detail = errorDetail(body);
        boolean retryable = statusCode == 408 || statusCode == 409 || statusCode == 429 || statusCode >= 500;
        return new LlmException(id + " HTTP " + statusCode + ": " + detail, null, retryable);
    }

    private String errorDetail(String body) {
        if (!hasText(body)) {
            return "空响应体";
        }
        String detail = body;
        try {
            JsonNode root = objectMapper.readTree(body);
            JsonNode error = root.path("error");
            if (!error.isMissingNode() && !error.isNull()) {
                detail = error.path("message").asText(error.toString());
            } else if (root.has("message")) {
                detail = root.path("message").asText(body);
            }
        } catch (JacksonException ignored) {
            // 非 JSON 错误体（网关 HTML 等）直接截断展示
        }
        detail = sanitize(detail);
        return detail.length() <= MAX_ERROR_DETAIL_CHARS ? detail : detail.substring(0, MAX_ERROR_DETAIL_CHARS) + "…";
    }

    private String sanitize(String text) {
        if (!hasText(text) || !hasText(config.getApiKey())) {
            return text;
        }
        return text.replace(config.getApiKey(), "***");
    }

    private static boolean isRetryableNetworkError(IOException e) {
        return e instanceof ConnectException || e instanceof HttpTimeoutException;
    }

    private static String safeMessage(Throwable throwable) {
        String message = throwable.getMessage();
        return message == null ? throwable.getClass().getSimpleName() : message;
    }

    private static long elapsedMs(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000L;
    }

    private static boolean hasText(String text) {
        return text != null && !text.isBlank();
    }

    // ============================================================
    // SSE 解析
    // ============================================================

    /**
     * 把 OpenAI-compatible SSE 行迭代器转成 {@link LlmChunk}。
     *
     * <p>SSE 允许多行 data，按空行分隔一个事件；这里严格按规范累积，
     * 而不是假设「一行就是一个 JSON」。
     */
    private final class SseChunkIterator implements Iterator<LlmChunk>, AutoCloseable {

        private final Stream<String> source;
        private final Iterator<String> lines;
        private final Deque<LlmChunk> pending = new ArrayDeque<>();
        private final StringBuilder data = new StringBuilder();
        private final AtomicBoolean closed = new AtomicBoolean();
        private boolean finished;

        private SseChunkIterator(Stream<String> source) {
            this.source = source;
            this.lines = source.iterator();
        }

        @Override
        public boolean hasNext() {
            while (pending.isEmpty() && !finished && !closed.get()) {
                if (!lines.hasNext()) {
                    processData();
                    finished = true;
                    break;
                }
                String line = lines.next();
                if (line == null || line.isEmpty()) {
                    processData();
                } else if (line.startsWith(":")) {
                    // SSE 注释/心跳
                } else {
                    int colon = line.indexOf(':');
                    String field = colon < 0 ? line : line.substring(0, colon);
                    String value = colon < 0 ? "" : line.substring(colon + 1);
                    if (value.startsWith(" ")) {
                        value = value.substring(1);
                    }
                    if ("data".equals(field)) {
                        if (data.length() > 0) {
                            data.append('\n');
                        }
                        data.append(value);
                    }
                }
            }
            return !pending.isEmpty();
        }

        @Override
        public LlmChunk next() {
            if (!hasNext()) {
                throw new NoSuchElementException();
            }
            return pending.removeFirst();
        }

        private void processData() {
            if (data.length() == 0) {
                return;
            }
            String payload = data.toString().trim();
            data.setLength(0);
            if (payload.isEmpty()) {
                return;
            }
            if ("[DONE]".equals(payload)) {
                finished = true;
                close();
                return;
            }
            try {
                JsonNode root = objectMapper.readTree(payload);
                JsonNode error = root.get("error");
                if (error != null && !error.isNull()) {
                    String message = error.path("message").asText(error.toString());
                    throw new LlmException(id + " 流式返回错误: " + sanitize(message), null,
                            isRetryableStatusMessage(message));
                }
                JsonNode choice = root.path("choices").path(0);
                String delta = choice.path("delta").path("content").asText("");
                String finishReason = choice.path("finish_reason").asText(null);
                LlmUsage usage = parseUsage(root.get("usage"), 0, 0);
                if (!delta.isEmpty()) {
                    pending.add(new LlmChunk(delta, usage, finishReason));
                } else if (usage != null) {
                    pending.add(new LlmChunk("", usage, finishReason));
                }
            } catch (JacksonException e) {
                throw new LlmException(id + " 流式数据块 JSON 解析失败", e, true);
            }
        }

        @Override
        public void close() {
            if (closed.compareAndSet(false, true)) {
                try {
                    source.close();
                } catch (RuntimeException ignored) {
                    // 关闭失败不影响已经收到的内容
                }
            }
        }
    }

    private static boolean isRetryableStatusMessage(String message) {
        String lower = message == null ? "" : message.toLowerCase();
        return lower.contains("timeout") || lower.contains("timed out")
                || lower.contains("429") || lower.contains("rate limit")
                || lower.contains("503") || lower.contains("502") || lower.contains("overloaded");
    }
}
