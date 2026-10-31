package com.locke.ai.service;

import com.locke.ai.domain.dto.ChatRequest;
import com.locke.ai.domain.dto.ChatResponse;
import com.locke.ai.domain.dto.ChatStreamEvent;
import com.locke.ai.exception.LlmException;
import com.locke.ai.ingestion.transformer.DocumentEnricher;
import com.locke.ai.llm.LlmAttempt;
import com.locke.ai.llm.LlmCallException;
import com.locke.ai.llm.LlmCompletion;
import com.locke.ai.llm.LlmProvider;
import com.locke.ai.llm.LlmProviderRegistry;
import com.locke.ai.llm.LlmRequest;
import com.locke.ai.llm.LlmRouter;
import com.locke.ai.llm.LlmUsage;
import com.locke.ai.util.TokenCounter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * RAG 问答编排。
 *
 * <p>一次问答的固定流程：
 * <pre>
 *   会话记忆装配 (ConversationMemory)  ->  检索 (RetrieverService)  ->  拼上下文
 *        ->  渲染 prompt (PromptBuilder)  ->  Provider 路由/降级 (LlmRouter)
 *        ->  记录本轮问答  ->  组装响应（出处、token、耗时、尝试记录、会话）
 * </pre>
 *
 * <p>几个刻意的设计选择：
 * <ul>
 *   <li><b>上下文为空时不硬答</b>：系统提示词要求模型如实说不知道，比后端编一句更可控；</li>
 *   <li><b>模型失败要降级</b>：LlmRouter 会依次尝试候选 Provider；全部失败才返回兜底文案，
 *       并把每次尝试的记录带回去，便于定位是超时、限流还是 Key 失效；</li>
 *   <li><b>token 优先用服务端 usage</b>：缺失时才用 {@link TokenCounter} 估算，并标记 estimated；</li>
 *   <li><b>流式只发结构化事件</b>：meta/delta/done/error，前端不再猜裸文本格式。</li>
 * </ul>
 *
 * <h3>多轮记忆的落点</h3>
 * 记忆只在两个地方出现：请求前取快照（{@link ConversationMemory#prepare}），
 * 成功拿到答案后写回（{@link ConversationMemory#recordTurn}）。
 * 兜底文案、流式中途失败的部分答案都不写历史——把它们记进去，
 * 下一轮模型会看到「抱歉，服务暂时不可用」并照着学。
 */
@Service
public class ChatService {

    private static final Logger log = LoggerFactory.getLogger(ChatService.class);

    /** 检索不到资料时给模型的占位上下文，避免 prompt 里出现空的「参考资料」段落。 */
    private static final String EMPTY_CONTEXT = "（本轮没有检索到任何参考资料）";

    /** 模型侧故障时的兜底回复。 */
    private static final String FALLBACK_ANSWER =
            "抱歉，模型服务暂时不可用，请稍后重试。如果反复出现，请查看服务端日志确认 API Key 与网络。";

    /** 单条出处的摘要长度上限，避免响应体被正文撑大。 */
    private static final int SNIPPET_MAX_CHARS = 120;

    private final RetrieverService retrieverService;
    private final PromptBuilder promptBuilder;
    private final LlmRouter llmRouter;
    private final LlmProviderRegistry providerRegistry;
    private final ConversationMemory conversationMemory;
    private final int defaultTopK;
    private final double similarityThreshold;

    public ChatService(RetrieverService retrieverService,
                       PromptBuilder promptBuilder,
                       LlmRouter llmRouter,
                       LlmProviderRegistry providerRegistry,
                       ConversationMemory conversationMemory,
                       @Value("${locke.ai.retriever.top-k:5}") int defaultTopK,
                       @Value("${locke.ai.retriever.similarity-threshold:0.5}") double similarityThreshold) {
        this.retrieverService = retrieverService;
        this.promptBuilder = promptBuilder;
        this.llmRouter = llmRouter;
        this.providerRegistry = providerRegistry;
        this.conversationMemory = conversationMemory;
        this.defaultTopK = defaultTopK;
        this.similarityThreshold = similarityThreshold;
    }

    /** 同步问答。 */
    public ChatResponse ask(ChatRequest request) {
        String requestedProvider = validatedProvider(request.provider());
        long startNanos = System.nanoTime();
        int topK = request.topKOrDefault(defaultTopK);

        // 会话记忆必须在检索之前装配：压缩本身可能调一次模型，
        // 放在检索后面会让「检索耗时」把压缩耗时也算进去，指标就不可信了
        ConversationMemory.Snapshot memory = conversationMemory.prepare(
                request.sessionId(), request.memoryEnabled());

        long retrievalStart = System.nanoTime();
        List<Document> documents = retrieverService.retrieve(
                conversationMemory.retrievalQuery(request.question(), memory), topK, similarityThreshold);
        long retrievalMs = elapsedMs(retrievalStart);

        LlmRequest llmRequest = buildLlmRequest(request, documents, memory);
        int estimatedPromptTokens = llmRequest.estimatedTokens();
        List<ChatResponse.Source> sources = toSources(documents);

        LlmCompletion completion;
        try {
            completion = llmRouter.complete(llmRequest, requestedProvider);
        } catch (RuntimeException e) {
            List<LlmAttempt> attempts = attemptsOf(e);
            long totalMs = elapsedMs(startNanos);
            log.error("模型调用失败，返回兜底回复。requested={} attempts={} retryable={} sessionId={}",
                    effectiveProvider(requestedProvider), attempts.size(),
                    e instanceof LlmException llmException && llmException.isRetryable(),
                    memory.sessionId(), e);
            return ChatResponse.fallback(FALLBACK_ANSWER, effectiveProvider(requestedProvider),
                    totalMs, attempts, memory.sessionId(), memory.historyTokens());
        }

        LlmUsage usage = completion.usage() == null
                ? new LlmUsage(estimatedPromptTokens, TokenCounter.estimate(completion.content()), true)
                : completion.usage();
        long totalMs = elapsedMs(startNanos);
        long llmLatencyMs = sumLatency(completion.attempts(), completion.latencyMs());
        boolean fallback = !effectiveProvider(requestedProvider).equals(completion.provider());

        conversationMemory.recordTurn(memory, request.question(), completion.content(),
                completion.provider(), completion.model());

        log.info("问答完成 provider={} model={} topK={} 命中={} promptTokens={} completionTokens={} estimated={} fallback={} 耗时={}ms retrieval={}ms llm={}ms session={} 历史tokens={} 压缩={}",
                completion.provider(), completion.model(), topK, documents.size(),
                usage.promptTokens(), usage.completionTokens(), usage.estimated(),
                fallback, totalMs, retrievalMs, llmLatencyMs,
                memory.sessionId(), memory.historyTokens(), memory.compressed());

        return new ChatResponse(
                completion.content(),
                sources,
                completion.model(),
                completion.provider(),
                usage.promptTokens(),
                usage.completionTokens(),
                usage.totalTokens(),
                usage.estimated(),
                totalMs,
                retrievalMs,
                llmLatencyMs,
                fallback,
                completion.attempts(),
                memory.sessionId(),
                memory.historyTokens(),
                memory.compressed());
    }

    /**
     * 流式问答，返回结构化 SSE 事件。
     *
     * <p>只有在还没有输出任何 delta 时才允许整段降级；已经输出过半截答案后
     * 再换 Provider 会把两段内容拼接，因此只发 error + done 收尾。
     */
    public Flux<ChatStreamEvent> stream(ChatRequest request) {
        // 在进入 SSE 之前完成参数校验：未知 provider 应该得到 400，而不是 200 + error 事件。
        String requestedProvider = validatedProvider(request.provider());
        return Flux.defer(() -> streamInternal(request, requestedProvider))
                .onErrorResume(error -> {
                    log.error("流式问答在检索/编排阶段失败", error);
                    return Flux.just(ChatStreamEvent.error(
                            effectiveProvider(requestedProvider), null,
                            "检索或服务异常: " + errorMessage(error), false, List.of()));
                });
    }

    private Flux<ChatStreamEvent> streamInternal(ChatRequest request, String requestedProvider) {
        long totalStart = System.nanoTime();
        int topK = request.topKOrDefault(defaultTopK);

        ConversationMemory.Snapshot memory = conversationMemory.prepare(
                request.sessionId(), request.memoryEnabled());

        long retrievalStart = System.nanoTime();
        List<Document> documents = retrieverService.retrieve(
                conversationMemory.retrievalQuery(request.question(), memory), topK, similarityThreshold);
        long retrievalMs = elapsedMs(retrievalStart);

        LlmRequest llmRequest = buildLlmRequest(request, documents, memory);
        int estimatedPromptTokens = llmRequest.estimatedTokens();
        List<ChatResponse.Source> sources = toSources(documents);

        Optional<LlmProvider> primary = llmRouter.primary(requestedProvider);
        String metaProvider = primary.map(LlmProvider::id).orElseGet(() -> effectiveProvider(requestedProvider));
        String metaModel = primary.map(LlmProvider::model).orElse(null);

        List<LlmAttempt> attempts = new CopyOnWriteArrayList<>();
        StringBuilder answer = new StringBuilder();
        AtomicReference<LlmUsage> usageRef = new AtomicReference<>();
        AtomicBoolean recorded = new AtomicBoolean();
        long llmStart = System.nanoTime();

        log.info("流式问答开始 requested={} primary={} topK={} 命中={} session={} 历史tokens={} 压缩={}",
                effectiveProvider(requestedProvider), metaProvider, topK, documents.size(),
                memory.sessionId(), memory.historyTokens(), memory.compressed());

        Flux<ChatStreamEvent> body = llmRouter
                .stream(llmRequest, requestedProvider, attempts)
                .doOnNext(chunk -> {
                    if (chunk.usage() != null) {
                        usageRef.set(chunk.usage());
                    }
                })
                .filter(chunk -> chunk.delta() != null && !chunk.delta().isEmpty())
                .map(chunk -> {
                    answer.append(chunk.delta());
                    return ChatStreamEvent.delta(metaProvider, metaModel, chunk.delta());
                })
                .concatWith(Flux.defer(() -> {
                    LlmUsage usage = usageRef.get();
                    if (usage == null) {
                        usage = new LlmUsage(estimatedPromptTokens, TokenCounter.estimate(answer.toString()), true);
                    }
                    String provider = lastSuccessProvider(attempts, effectiveProvider(requestedProvider));
                    String model = lastSuccessModel(attempts, metaModel);
                    boolean fallback = !effectiveProvider(requestedProvider).equals(provider);
                    long llmLatencyMs = sumLatency(attempts, elapsedMs(llmStart));
                    long totalMs = elapsedMs(totalStart);

                    // 只在正常输出完成时记录历史：中途 abort / 连接断开不会走到这里
                    if (recorded.compareAndSet(false, true)) {
                        conversationMemory.recordTurn(memory, request.question(), answer.toString(), provider, model);
                    }

                    log.info("流式问答完成 provider={} model={} promptTokens={} completionTokens={} estimated={} fallback={} 耗时={}ms retrieval={}ms llm={}ms session={} 压缩={}",
                            provider, model, usage.promptTokens(), usage.completionTokens(), usage.estimated(),
                            fallback, totalMs, retrievalMs, llmLatencyMs, memory.sessionId(), memory.compressed());
                    return Flux.just(ChatStreamEvent.done(provider, model, answer.toString(), usage,
                            llmLatencyMs, retrievalMs, totalMs, fallback, attempts,
                            memory.sessionId(), memory.compressed()));
                }))
                .onErrorResume(error -> {
                    long totalMs = elapsedMs(totalStart);
                    long llmLatencyMs = sumLatency(attempts, elapsedMs(llmStart));
                    String provider = lastSuccessProvider(attempts, effectiveProvider(requestedProvider));
                    String model = lastSuccessModel(attempts, metaModel);
                    log.error("流式模型调用失败 provider={} 已输出={} 字 attempts={} session={}",
                            provider, answer.length(), attempts.size(), memory.sessionId(), error);

                    if (answer.length() == 0) {
                        LlmUsage usage = new LlmUsage(estimatedPromptTokens,
                                TokenCounter.estimate(FALLBACK_ANSWER), true);
                        return Flux.just(
                                ChatStreamEvent.delta(provider, model, FALLBACK_ANSWER),
                                ChatStreamEvent.done(provider, model, FALLBACK_ANSWER, usage,
                                        llmLatencyMs, retrievalMs, totalMs, true, false, attempts,
                                        memory.sessionId(), memory.compressed()));
                    }

                    LlmUsage usage = usageRef.get();
                    if (usage == null) {
                        usage = new LlmUsage(estimatedPromptTokens,
                                TokenCounter.estimate(answer.toString()), true);
                    }
                    return Flux.just(
                            ChatStreamEvent.error(provider, model, errorMessage(error), false, attempts),
                            ChatStreamEvent.done(provider, model, answer.toString(), usage,
                                    llmLatencyMs, retrievalMs, totalMs, false, false, attempts,
                                    memory.sessionId(), memory.compressed()));
                });

        return Flux.just(ChatStreamEvent.meta(metaProvider, metaModel, retrievalMs, sources,
                        memory.sessionId(), memory.historyTokens()))
                .concatWith(body);
    }

    /**
     * 组装本轮发给模型的请求：系统提示词（含摘要）+ 历史消息 + 本轮用户消息。
     *
     * <p>历史放在 {@code history} 而不是拼进 userPrompt，见 {@link LlmRequest} 的说明。
     */
    private LlmRequest buildLlmRequest(ChatRequest request, List<Document> documents,
                                       ConversationMemory.Snapshot memory) {
        String systemPrompt = promptBuilder.systemPrompt(memory.summary());
        String userPrompt = promptBuilder.buildUserPrompt(renderContext(documents), request.question());
        return new LlmRequest(systemPrompt, memory.history(), userPrompt);
    }

    /**
     * 把命中的文档拼成参考资料文本。
     *
     * <p>编号（{@code [1]} {@code [2]}）必须和 {@link #toSources} 的顺序一致，
     * 否则模型标了 [2] 而前端把出处显示成另一条，等于给出错误引用。
     */
    String renderContext(List<Document> documents) {
        if (documents == null || documents.isEmpty()) {
            return EMPTY_CONTEXT;
        }
        StringBuilder builder = new StringBuilder();
        for (int index = 0; index < documents.size(); index++) {
            Document document = documents.get(index);
            builder.append('[').append(index + 1).append("] 来源：")
                    .append(sourceOf(document)).append('\n');
            String text = document.getText();
            builder.append(text == null ? "" : text.trim()).append("\n\n");
        }
        return builder.toString().trim();
    }

    /** 组装出处列表，顺序与 {@link #renderContext} 的编号严格对应。 */
    List<ChatResponse.Source> toSources(List<Document> documents) {
        if (documents == null || documents.isEmpty()) {
            return List.of();
        }
        List<ChatResponse.Source> sources = new ArrayList<>(documents.size());
        for (Document document : documents) {
            String text = document.getText() == null ? "" : document.getText().replaceAll("\\s+", " ").trim();
            String snippet = text.length() <= SNIPPET_MAX_CHARS
                    ? text
                    : text.substring(0, SNIPPET_MAX_CHARS) + "…";
            sources.add(new ChatResponse.Source(
                    stringMetadata(document, DocumentEnricher.META_SPIRIT_NAME),
                    sourceOf(document),
                    snippet,
                    document.getScore()));
        }
        return sources;
    }

    private String validatedProvider(String rawProvider) {
        if (rawProvider == null || rawProvider.isBlank()) {
            return null;
        }
        String provider = rawProvider.trim();
        if (providerRegistry.find(provider).isEmpty()) {
            throw new IllegalArgumentException("未知 provider: " + provider);
        }
        return provider;
    }

    private String effectiveProvider(String requestedProvider) {
        return requestedProvider == null || requestedProvider.isBlank()
                ? providerRegistry.defaultProvider()
                : requestedProvider;
    }

    private static String lastSuccessProvider(List<LlmAttempt> attempts, String fallback) {
        for (int i = attempts.size() - 1; i >= 0; i--) {
            LlmAttempt attempt = attempts.get(i);
            if (attempt.isSuccess() && attempt.provider() != null) {
                return attempt.provider();
            }
        }
        return fallback;
    }

    private static String lastSuccessModel(List<LlmAttempt> attempts, String fallback) {
        for (int i = attempts.size() - 1; i >= 0; i--) {
            LlmAttempt attempt = attempts.get(i);
            if (attempt.isSuccess() && attempt.model() != null) {
                return attempt.model();
            }
        }
        return fallback;
    }

    private static long sumLatency(List<LlmAttempt> attempts, long fallback) {
        long sum = attempts.stream().mapToLong(LlmAttempt::latencyMs).sum();
        return sum > 0 ? sum : fallback;
    }

    private static List<LlmAttempt> attemptsOf(RuntimeException error) {
        if (error instanceof LlmCallException callException) {
            return callException.attempts();
        }
        return List.of();
    }

    private static String errorMessage(Throwable error) {
        String message = error.getMessage();
        if (message == null || message.isBlank()) {
            message = error.getClass().getSimpleName();
        }
        return message.length() <= 300 ? message : message.substring(0, 300) + "…";
    }

    private static String sourceOf(Document document) {
        String source = stringMetadata(document, DocumentEnricher.META_SOURCE);
        return source == null ? "unknown" : source;
    }

    private static String stringMetadata(Document document, String key) {
        // 元数据来自 JSON 反序列化，类型不保证是 String，用 valueOf 兜住
        Object value = document.getMetadata().get(key);
        return value == null ? null : String.valueOf(value);
    }

    private static long elapsedMs(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000L;
    }
}
