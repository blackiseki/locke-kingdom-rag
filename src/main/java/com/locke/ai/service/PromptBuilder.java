package com.locke.ai.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;
import org.springframework.util.StreamUtils;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Prompt 模板管理。
 *
 * <p>模板放在 {@code resources/prompts/*.st}，改提示词不用重新编译 Java，
 * 调 prompt 的迭代成本能降一大截。
 *
 * <p><b>为什么不用 Spring AI 的 {@code PromptTemplate}</b>：
 * 它底层是 StringTemplate，会把模板里所有 {@code {}} 都当占位符，
 * 而 RAG 的参考资料里经常出现 JSON（{@code {"name":"火花"}}），
 * 一旦混进去就得逐处转义，很容易在改提示词时踩坑。
 * 这里只做 {@code {key}} 字面替换，占位符由调用方显式传入；
 * 如果你确实需要条件/循环等模板能力，把 {@link #render} 换成
 * {@code new PromptTemplate(template).render(vars)} 即可。
 *
 * <p>约定：模板语言与 Prompt 内容都用 UTF-8 读取，
 * 不要用 {@code FileReader} 那套依赖平台默认编码的写法（Windows 上默认 GBK 会乱码）。
 */
@Component
public class PromptBuilder {

    private static final Logger log = LoggerFactory.getLogger(PromptBuilder.class);

    /** RAG 参考资料在用户模板里的占位符。 */
    public static final String VAR_CONTEXT = "context";

    /** 用户问题在用户模板里的占位符。 */
    public static final String VAR_QUESTION = "question";

    /** 已有摘要在压缩模板里的占位符。 */
    public static final String VAR_PREVIOUS = "previous";

    /** 待折叠对话在压缩模板里的占位符。 */
    public static final String VAR_DIALOG = "dialog";

    /**
     * 会话摘要注入系统提示词时的块。
     *
     * <p>摘要放在 system 而不是 user：它描述的是「这个会话此前聊了什么」，
     * 属于角色背景而不是本轮输入。塞进 user 会和【参考资料】抢位置，
     * 模型容易把摘要里的旧结论当成新资料引用。
     *
     * <p>末尾那句关于编号的提醒不是客套：历史答案里带着上一轮的 {@code [1] [2]}，
     * 而本轮参考资料编号已经重排，不明确说一句，模型会照抄旧编号，出处直接指错。
     */
    private static final String SUMMARY_BLOCK = """


            【本会话此前的对话摘要】（可能已过时；与【参考资料】冲突时以参考资料为准）
            %s
            注意：历史对话里出现过的 [编号] 只对当时的资料有效，本轮回答只能引用本轮【参考资料】的编号。
            """;

    /**
     * 压缩器自己的系统提示词。
     *
     * <p>刻意写成常量而不是模板文件：它不随业务调参，也不需要占位符，
     * 单独开一个 .st 只会让 PromptBuilder 的构造器多一个 Resource 参数。
     */
    private static final String SUMMARY_SYSTEM_PROMPT = """
            你是对话记录压缩器。你只做信息压缩，不回答问题、不补充对话里没有的知识。

            压缩要求：
            1. 保留对后续追问有用的信息：问过的对象（精灵 / 技能 / 属性 / 道具名）、已经确认的结论、
               用户明确表达的偏好或限定条件（例如「只要火系」「不要提进化」）。
            2. 删除寒暄、重复表述，以及已被后续对话修正或推翻的说法，只保留最新结论。
            3. 不要编造、不要推测、不要引入对话之外的游戏资料。
            4. 用简体中文的要点列表输出，总长度不超过用户消息里给出的字数上限。
            5. 只输出摘要正文：不要标题、不要前言、不要解释你在做什么。
            """;

    private final String systemTemplate;
    private final String userTemplate;
    private final String summaryTemplate;

    public PromptBuilder(@Value("classpath:prompts/rag-system.st") Resource systemResource,
                         @Value("classpath:prompts/rag-user.st") Resource userResource,
                         @Value("classpath:prompts/memory-summary.st") Resource summaryResource) {
        this.systemTemplate = read(systemResource);
        this.userTemplate = read(userResource);
        this.summaryTemplate = read(summaryResource);
    }

    /** 系统提示词，内容固定，直接返回。 */
    public String systemPrompt() {
        return systemTemplate;
    }

    /**
     * 带上会话摘要的系统提示词。
     *
     * @param summary 滚动摘要；为空时等同于 {@link #systemPrompt()}
     */
    public String systemPrompt(String summary) {
        if (summary == null || summary.isBlank()) {
            return systemTemplate;
        }
        return systemTemplate + SUMMARY_BLOCK.formatted(summary.trim());
    }

    /**
     * 渲染用户提示词。
     *
     * @param context  已拼好的参考资料文本，格式为 "[1] 来源：xxx\n正文…"
     * @param question 用户原始问题
     */
    public String buildUserPrompt(String context, String question) {
        return render(userTemplate, Map.of(
                VAR_CONTEXT, context == null ? "" : context,
                VAR_QUESTION, question == null ? "" : question));
    }

    /** 压缩器的系统提示词。 */
    public String summarySystemPrompt() {
        return SUMMARY_SYSTEM_PROMPT;
    }

    /**
     * 渲染「把这段对话压成摘要」的用户提示词。
     *
     * @param previous 上一版摘要，可为空（第一次压缩）
     * @param dialog   待折叠的多轮对话文本
     * @param maxChars 摘要字数上限（中文近似 1 字 1 token）
     */
    public String buildSummaryPrompt(String previous, String dialog, int maxChars) {
        Map<String, String> variables = new LinkedHashMap<>();
        variables.put(VAR_PREVIOUS, previous == null || previous.isBlank() ? "（无，这是第一次压缩）" : previous.trim());
        variables.put(VAR_DIALOG, dialog == null ? "" : dialog);
        String prompt = substitute(summaryTemplate, variables);
        // 字数上限用普通文本拼接，不占模板占位符：模板里多一个变量就多一处可能拼错的坑
        return prompt + "\n\n本次摘要不超过 " + Math.max(120, maxChars) + " 字。";
    }

    /**
     * 极简模板渲染：只替换 {@code {key}}，不做表达式求值。
     *
     * <p>未提供的占位符会原样保留并打 warn——静默替换成空串会让
     * 「提示词少传了变量」这种问题直到线上答错才被发现。
     */
    public String render(String template, Map<String, String> variables) {
        if (template == null) {
            return "";
        }
        String result = substitute(template, variables);
        if (result.indexOf('{') >= 0 && result.indexOf('}') > result.indexOf('{')) {
            log.warn("Prompt 模板里仍存在未替换的占位符，请检查变量名是否与模板一致：" +
                    "模板变量={}", variables.keySet());
        }
        return result;
    }

    /**
     * 只做替换、不做占位符残留检查的版本。
     *
     * <p>压缩模板的变量值是<b>正文</b>（摘要、对话原文），里面出现 {@code {}} 完全正常
     * （用户真的可能问「这个 JSON 的 {"name":"火花"} 是什么」），
     * 走 {@link #render} 会每压缩一次就打一条误导性的 warn。
     */
    private String substitute(String template, Map<String, String> variables) {
        if (template == null) {
            return "";
        }
        String result = template;
        for (Map.Entry<String, String> entry : variables.entrySet()) {
            result = result.replace("{" + entry.getKey() + "}", entry.getValue());
        }
        return result;
    }

    private static String read(Resource resource) {
        try (InputStream in = resource.getInputStream()) {
            String text = StreamUtils.copyToString(in, StandardCharsets.UTF_8);
            if (text.isBlank()) {
                throw new IllegalStateException("Prompt 模板内容为空: " + resource.getDescription());
            }
            return text;
        } catch (IOException e) {
            // 启动期就失败，比等到第一次问答才发现模板没打包进去好
            throw new IllegalStateException("读取 Prompt 模板失败: " + resource.getDescription(), e);
        }
    }
}
