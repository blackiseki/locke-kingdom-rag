package com.locke.ai.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 配置契约测试：把「配置必须满足的不变量」写成断言，而不是靠人肉检查。
 *
 * <h3>为什么需要这个测试</h3>
 * YAML 有一类错误是**静默的**：比如缩进错一级、或者某一行被合并进注释，
 * 文件依然是合法 YAML，Spring 也不会报错（未知属性默认被忽略），
 * 但那份配置就**悄悄失效了**。
 * 我自己就在用正则改 {@code application.yml} 时把 {@code init:} 那行的换行吃掉过，
 * 结果 {@code spring.ai.ollama.init.*} 整段变成了注释、下面的键还降级挂到了
 * {@code embedding} 下面——启动日志里看不出任何异常。
 *
 * <p>所以这里用 Spring Boot 自带的 snakeyaml 直接解析配置文件，
 * 按路径断言关键配置存在、且取值正确。
 *
 * <h3>顺带固化的两个跨文件不变量</h3>
 * <ol>
 *   <li><b>维度三处一致</b>：{@code EMBEDDING_DIMENSIONS} 的默认值
 *       必须等于 {@code V1__init_schema.sql} 里 {@code vector(N)} 的 N，
 *       否则写库时会报维度不匹配；</li>
 *   <li><b>密钥不得入库</b>：{@code api-key} 必须是占位符，不能是明文，
 *       这条能防住"不小心把 key 提交进 git"。</li>
 * </ol>
 */
class ApplicationConfigTest {

    private static final Map<String, Object> CONFIG = loadYaml("application.yml");
    private static final String SCHEMA_SQL = readText("db/migration/V1__init_schema.sql");

    /** {@code ${VAR:default}} 里的 default。 */
    private static final Pattern PLACEHOLDER_DEFAULT = Pattern.compile("\\$\\{[^:}]+:([^}]*)}");
    /** SQL 里的 {@code vector(1024)}。 */
    private static final Pattern VECTOR_DIMENSION = Pattern.compile("vector\\((\\d+)\\)");
    /** 疑似明文密钥。 */
    private static final Pattern LOOKS_LIKE_SECRET = Pattern.compile("sk-[A-Za-z0-9_\\-]{8,}");

    // ============================================================
    // 模型选择：多 starter 场景下最容易静默出错的地方
    // ============================================================

    @Test
    @DisplayName("chat 必须显式关闭 Spring AI 装配，embedding 仍由 Ollama 提供")
    void modelSelectionMustBeExplicit() {
        // 对话改走自建 OpenAI-compatible 层；chat=none 才能避免 Ollama
        // 的 ChatModel 自动装配按默认条件生效，和我们的路由层抢 Bean。
        assertThat(path("spring.ai.model.chat")).isEqualTo("none");
        assertThat(path("spring.ai.model.embedding")).isEqualTo("ollama");
    }

    @Test
    @DisplayName("默认 provider 与 fallback 顺序必须在配置里显式给出")
    void providerRoutingMustBeConfigured() {
        assertThat(defaultOf(path("locke.ai.llm.provider"))).isEqualTo("deepseek");
        assertThat(defaultOf(path("locke.ai.llm.fallback"))).isEqualTo("tongyi");
        assertThat(path("locke.ai.llm.providers.deepseek")).isNotNull();
        assertThat(path("locke.ai.llm.providers.tongyi")).isNotNull();
    }

    @Test
    @DisplayName("对话模型名必须是 API 认得的 id（deepseek-flash / deepseek-v4-pro），不能填显示名")
    void deepSeekModelMustBeApiModelId() {
        // 实测：API 拒绝 "DeepSeek-V4.1-Flash"（那是显示名），只认 deepseek-flash / deepseek-v4-pro
        assertThat(defaultOf(path("locke.ai.llm.providers.deepseek.model")))
                .as("llm.providers.deepseek.model 的默认值")
                .isIn("deepseek-flash", "deepseek-v4-pro");
    }

    @Test
    @DisplayName("必须关掉 DeepSeek 思考模式（默认开启，实测延迟 4 倍、输出九成是推理 token）")
    void deepSeekThinkingMustBeDisabled() {
        assertThat(path("locke.ai.llm.providers.deepseek.extra-body.thinking.type"))
                .as("thinking.type 应为 disabled；改回 enabled 会让 temperature 静默失效，且延迟与成本显著上升")
                .isEqualTo("disabled");
        // temperature 只在非思考模式下生效，所以上一条是这条的前提
        assertThat(path("locke.ai.llm.providers.deepseek.temperature")).isNotNull();
    }

    @Test
    @DisplayName("两个 OpenAI-compatible Provider 都必须有 base-url 与超时")
    void providerTransportsMustBeComplete() {
        for (String provider : new String[]{"deepseek", "tongyi"}) {
            assertThat(path("locke.ai.llm.providers." + provider + ".base-url"))
                    .as("%s 的 base-url", provider)
                    .isNotNull();
            assertThat(path("locke.ai.llm.providers." + provider + ".timeout"))
                    .as("%s 的 timeout", provider)
                    .isNotNull();
        }
    }

    // ============================================================
    // Ollama 向量模型
    // ============================================================

    @Test
    @DisplayName("Ollama 向量配置必须完整：模型、上下文窗口都要在")
    void ollamaEmbeddingConfigMustBeComplete() {
        assertThat(defaultOf(path("spring.ai.ollama.embedding.model"))).isEqualTo("bge-m3");

        // num-ctx 默认只有 2048，而精灵档案最长约 1900 字（中文接近 1 字 1 token），
        // 不调大就会被静默截断。这里守住下限，防止有人把它删掉。
        assertThat(path("spring.ai.ollama.embedding.num-ctx"))
                .as("num-ctx 必须显式设置且不小于 4096，否则长档案会被静默截断")
                .isInstanceOf(Integer.class);
        assertThat((Integer) path("spring.ai.ollama.embedding.num-ctx")).isGreaterThanOrEqualTo(4096);
    }

    @Test
    @DisplayName("不能设置 num-batch：它控制模型内部 prompt 批大小（默认 512），配小了反而拖慢 GPU")
    void numBatchMustNotBeOverridden() {
        assertThat(path("spring.ai.ollama.embedding.num-batch"))
                .as("num-batch 是模型推理参数不是「每次请求几条文本」，不要覆盖它")
                .isNull();
    }

    @Test
    @DisplayName("init 段必须完整存在（这一整段曾被一行合并进注释而静默失效）")
    void ollamaInitBlockMustSurvive() {
        assertThat(path("spring.ai.ollama.init.pull-model-strategy")).isEqualTo("when_missing");
        assertThat(path("spring.ai.ollama.init.timeout")).isNotNull();
    }

    // ============================================================
    // 跨文件不变量
    // ============================================================

    @Test
    @DisplayName("维度必须三处一致：EMBEDDING_DIMENSIONS 默认值 == SQL 里的 vector(N)")
    void embeddingDimensionsMustMatchSchema() {
        String configured = defaultOf(path("spring.ai.vectorstore.pgvector.dimensions"));
        Matcher sql = VECTOR_DIMENSION.matcher(SCHEMA_SQL);
        assertThat(sql.find()).as("V1__init_schema.sql 里应当有 vector(N)").isTrue();
        String ddl = sql.group(1);

        assertThat(configured)
                .as("配置里的向量维度(%s)必须等于 V1__init_schema.sql 里的 vector(%s)", configured, ddl)
                .isEqualTo(ddl);
        // bge-m3 是 1024 维；换模型时这三处要一起改
        assertThat(ddl).isEqualTo("1024");
    }

    @Test
    @DisplayName("Flyway 拥有 DDL，所以 pgvector 不能自己去建表")
    void flywayOwnsTheSchema() {
        assertThat(path("spring.flyway.enabled")).isEqualTo(Boolean.TRUE);
        assertThat(path("spring.ai.vectorstore.pgvector.initialize-schema"))
                .as("两套建表逻辑打架时表结构会不可预期")
                .isEqualTo(Boolean.FALSE);
    }

    @Test
    @DisplayName("api-key 必须是占位符，不能出现明文密钥（防误提交进 git）")
    void apiKeysMustBePlaceholders() {
        for (String keyPath : new String[]{
                "locke.ai.llm.providers.deepseek.api-key",
                "locke.ai.llm.providers.tongyi.api-key",
                "spring.ai.ollama.api-key"}) {
            Object value = path(keyPath);
            if (value == null) {
                continue;   // 本地 Ollama 不需要 key；没有 DashScope key 时也允许缺省
            }
            String text = String.valueOf(value);
            assertThat(text)
                    .as("%s 应当写成 ${ENV_VAR} 形式", keyPath)
                    .startsWith("${");
            assertThat(LOOKS_LIKE_SECRET.matcher(text).find())
                    .as("%s 里出现了疑似明文密钥，请改成环境变量占位符", keyPath)
                    .isFalse();
        }
    }

    // ============================================================
    // 工具
    // ============================================================

    /** 按 {@code a.b.c} 路径取嵌套配置；不存在返回 null。 */
    private static Object path(String dotted) {
        Object current = CONFIG;
        for (String segment : dotted.split("\\.")) {
            if (!(current instanceof Map<?, ?> map)) {
                return null;
            }
            current = map.get(segment);
        }
        return current;
    }

    /** 取出 {@code ${VAR:default}} 里的 default；不是占位符就原样返回。 */
    private static String defaultOf(Object value) {
        String text = String.valueOf(value);
        Matcher matcher = PLACEHOLDER_DEFAULT.matcher(text);
        return matcher.matches() ? matcher.group(1) : text;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> loadYaml(String resource) {
        try (InputStream in = ApplicationConfigTest.class.getClassLoader().getResourceAsStream(resource)) {
            assertThat(in).as("找不到 %s（应该在 src/main/resources 下）", resource).isNotNull();
            return new Yaml().load(in);
        } catch (Exception e) {
            throw new IllegalStateException("解析 " + resource + " 失败: " + e.getMessage(), e);
        }
    }

    private static String readText(String resource) {
        try (InputStream in = ApplicationConfigTest.class.getClassLoader().getResourceAsStream(resource)) {
            assertThat(in).as("找不到 %s", resource).isNotNull();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("读取 " + resource + " 失败: " + e.getMessage(), e);
        }
    }
}
