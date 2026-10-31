package com.locke.ai.controller;

import com.locke.ai.domain.dto.ChatRequest;
import com.locke.ai.domain.dto.ChatResponse;
import com.locke.ai.domain.dto.ChatStreamEvent;
import com.locke.ai.service.ChatService;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

/**
 * 问答接口。
 *
 * <p>控制器只做参数校验和转发，编排逻辑全在 {@link ChatService}。
 */
@RestController
@RequestMapping("/api/chat")
public class ChatController {

    private final ChatService chatService;

    public ChatController(ChatService chatService) {
        this.chatService = chatService;
    }

    /**
     * 同步问答。
     *
     * <p>{@code @Valid} 触发的校验失败由
     * {@link com.locke.ai.exception.GlobalExceptionHandler} 统一转成 400。
     */
    @PostMapping
    public ChatResponse chat(@Valid @RequestBody ChatRequest request) {
        return chatService.ask(request);
    }

    /**
     * 流式问答（SSE）。
     *
     * <p>{@code produces} 必须显式声明成 {@code text/event-stream}：
     * Spring MVC 只有在响应内容类型是 SSE 时，才会把 {@code Flux} 按
     * {@code data: ...} 逐条推给客户端，否则会试图当成 JSON 数组一次性序列化。
     *
     * <p>前端用 {@code EventSource} 订阅时注意：{@code EventSource} 只支持 GET，
     * 要 POST 得用 {@code fetch} + {@code ReadableStream}，或者干脆用 GET + 查询参数。
     */
    @PostMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<ChatStreamEvent>> stream(@Valid @RequestBody ChatRequest request) {
        return chatService.stream(request)
                .map(event -> ServerSentEvent.<ChatStreamEvent>builder(event)
                        .event(event.type())
                        .build());
    }
}
