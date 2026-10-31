package com.locke.ai.llm;

import com.locke.ai.exception.LlmException;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 用本地 HttpServer 验证 OpenAI-compatible 协议解析，不依赖外网。 */
class OpenAiCompatibleProviderTest {

    private HttpServer server;
    private OpenAiCompatibleProvider provider;
    private final AtomicReference<String> authorization = new AtomicReference<>();

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.start();

        LlmProperties.Provider config = new LlmProperties.Provider();
        config.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
        config.setApiKey("secret-key");
        config.setModel("test-model");
        config.setTimeout(Duration.ofSeconds(5));
        provider = new OpenAiCompatibleProvider("test", config, new ObjectMapper());
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void shouldParseCompletionAndUsage() {
        server.createContext("/chat/completions", exchange -> {
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            respond(exchange, 200, "application/json", """
                    {"choices":[{"message":{"role":"assistant","content":"喵喵是草系精灵。 [1]"}}],
                     "usage":{"prompt_tokens":12,"completion_tokens":8,"total_tokens":20}}
                    """);
        });

        LlmCompletion completion = provider.complete(new LlmRequest("system", "user"));

        assertThat(completion.content()).isEqualTo("喵喵是草系精灵。 [1]");
        assertThat(completion.provider()).isEqualTo("test");
        assertThat(completion.model()).isEqualTo("test-model");
        assertThat(completion.usage().promptTokens()).isEqualTo(12);
        assertThat(completion.usage().completionTokens()).isEqualTo(8);
        assertThat(completion.usage().estimated()).isFalse();
        assertThat(authorization.get()).isEqualTo("Bearer secret-key");
    }

    @Test
    void shouldParseSseDeltasAndFinalUsage() {
        server.createContext("/chat/completions", exchange -> {
            exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, 0);
            try (var out = exchange.getResponseBody()) {
                write(out, "data: {\"choices\":[{\"delta\":{\"content\":\"你\"},\"finish_reason\":null}]}\n\n");
                write(out, "data: {\"choices\":[{\"delta\":{\"content\":\"好\"},\"finish_reason\":null}]}\n\n");
                write(out, "data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\"}],"
                        + "\"usage\":{\"prompt_tokens\":7,\"completion_tokens\":2,\"total_tokens\":9}}\n\n");
                write(out, "data: [DONE]\n\n");
            } catch (IOException ignored) {
                // 客户端提前关闭时忽略
            }
        });

        List<LlmChunk> chunks = provider.stream(new LlmRequest("system", "user")).toList();

        String text = chunks.stream().map(LlmChunk::delta).collect(Collectors.joining());
        assertThat(text).isEqualTo("你好");
        LlmUsage usage = chunks.stream().map(LlmChunk::usage)
                .filter(u -> u != null).findFirst().orElseThrow();
        assertThat(usage.promptTokens()).isEqualTo(7);
        assertThat(usage.completionTokens()).isEqualTo(2);
        assertThat(usage.estimated()).isFalse();
    }

    @Test
    void shouldMark429RetryableAndRedactApiKey() {
        server.createContext("/chat/completions", exchange ->
                respond(exchange, 429, "application/json",
                        "{\"error\":{\"message\":\"rate limit for secret-key\"}}"));

        assertThatThrownBy(() -> provider.complete(new LlmRequest("system", "user")))
                .isInstanceOfSatisfying(LlmException.class, error -> {
                    assertThat(error.isRetryable()).isTrue();
                    assertThat(error.getMessage()).contains("429");
                    assertThat(error.getMessage()).doesNotContain("secret-key");
                    assertThat(error.getMessage()).contains("***");
                });
    }

    @Test
    void shouldReportMissingApiKeyAsUnavailable() {
        LlmProperties.Provider config = new LlmProperties.Provider();
        config.setBaseUrl("http://127.0.0.1:1");
        config.setModel("test-model");
        config.setApiKey("");
        config.setRequiresApiKey(true);

        OpenAiCompatibleProvider missingKey = new OpenAiCompatibleProvider("test", config, new ObjectMapper());

        assertThat(missingKey.available()).isFalse();
        assertThat(missingKey.status()).isEqualTo("missing api-key");
    }

    private static void respond(HttpExchange exchange, int status, String contentType, String body)
            throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", contentType);
        exchange.sendResponseHeaders(status, bytes.length);
        try (var out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    private static void write(java.io.OutputStream out, String text) throws IOException {
        out.write(text.getBytes(StandardCharsets.UTF_8));
        out.flush();
    }
}
