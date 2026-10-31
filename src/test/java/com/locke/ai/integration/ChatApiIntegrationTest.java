package com.locke.ai.integration;

import com.locke.ai.llm.LlmAttempt;
import com.locke.ai.llm.LlmCompletion;
import com.locke.ai.llm.LlmRouter;
import com.locke.ai.llm.LlmUsage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
// 注意包名：Boot 4 之前是 org.springframework.boot.test.autoconfigure.web.servlet，
// 之后拆到了 spring-boot-starter-webmvc-test 模块的这个包里。
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 端到端接口测试。
 *
 * <h3>为什么不默认跑</h3>
 * 它要真的启动 Spring 容器，也就需要一个可连的 PostgreSQL + pgvector
 *（Flyway 会执行迁移）。所以默认跳过，显式打开才跑：
 * <pre>
 *   docker compose up -d pgvector ollama
 *   ./mvnw test -Dlocke.it=true
 * </pre>
 *
 * <h3>不需要 Ollama / 模型 API 真的在跑</h3>
 * {@code pull-model-strategy} 被改回 {@code never}——否则容器启动时会去连
 * Ollama 拉 bge-m3（1.2GB），测试环境没有它就会失败。
 * 向量检索也只会在真正调用接口时才发生；本测试里 {@link LlmRouter} 是 mock，
 * 不会产生任何外部模型调用。
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "DEEPSEEK_API_KEY=test-key-not-real",
        // 对话不再由 Spring AI 装配，避免测试环境要求真实 ChatModel
        "spring.ai.model.chat=none",
        "spring.ai.model.embedding=ollama",
        // 关键：禁止启动时拉模型，否则没有 Ollama 的环境会直接启动失败
        "spring.ai.ollama.init.pull-model-strategy=never"
})
@EnabledIfSystemProperty(named = "locke.it", matches = "true")
class ChatApiIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private LlmRouter llmRouter;

    @Test
    @DisplayName("POST /api/chat 返回模型答案，走完整 HTTP 链路")
    void chatEndpointShouldReturnAnswer() throws Exception {
        LlmUsage usage = new LlmUsage(120, 18, false);
        LlmAttempt attempt = LlmAttempt.success("deepseek", "deepseek-flash", 11, usage);
        when(llmRouter.complete(any(), nullable(String.class))).thenReturn(
                new LlmCompletion("deepseek", "deepseek-flash", "喵喵是草系精灵。[1]",
                        usage, 11, List.of(attempt)));

        mockMvc.perform(post("/api/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\":\"喵喵是什么属性？\"}"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("喵喵是草系精灵")))
                .andExpect(content().string(containsString("\"provider\":\"deepseek\"")));
    }

    @Test
    @DisplayName("空问题被 @Valid 拦下，返回 400 而不是 500")
    void blankQuestionShouldReturn400() throws Exception {
        mockMvc.perform(post("/api/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\":\"   \"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("未知 provider 返回 400")
    void unknownProviderShouldReturn400() throws Exception {
        mockMvc.perform(post("/api/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\":\"喵喵是什么属性？\",\"provider\":\"not-exist\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("Provider 目录接口返回默认 provider 与 fallback")
    void providerCatalogShouldBeExposed() throws Exception {
        mockMvc.perform(get("/api/llm/providers"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("\"defaultProvider\":\"deepseek\"")))
                .andExpect(content().string(containsString("\"fallback\"")));
    }

    @Test
    @DisplayName("查不到的精灵返回 404，而不是 500 或者空对象")
    void missingSpiritShouldReturn404() throws Exception {
        mockMvc.perform(get("/api/spirits/{name}", "这个精灵不存在"))
                .andExpect(status().isNotFound());
    }
}
