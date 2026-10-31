package com.locke.ai;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 洛克王国 AI 助手启动类。
 *
 * <p>启动前需保证：
 * <ul>
 *   <li>PostgreSQL + pgvector 已就绪（{@code docker compose up -d pgvector}）；</li>
 *   <li>本地 Ollama 与 bge-m3 已就绪（向量嵌入）；</li>
 *   <li>至少默认对话 Provider 的密钥已设置：{@code DEEPSEEK_API_KEY}；
 *       通义降级可选 {@code DASHSCOPE_API_KEY}；</li>
 *   <li>Flyway 已执行 {@code db/migration/V1__init_schema.sql}。</li>
 * </ul>
 */
@SpringBootApplication
public class LockeAiApplication {

    public static void main(String[] args) {
        SpringApplication.run(LockeAiApplication.class, args);
    }
}
