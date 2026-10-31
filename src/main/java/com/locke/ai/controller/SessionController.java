package com.locke.ai.controller;

import com.locke.ai.domain.dto.CompactResult;
import com.locke.ai.domain.dto.CreateSessionRequest;
import com.locke.ai.domain.dto.SessionDetailView;
import com.locke.ai.domain.dto.SessionView;
import com.locke.ai.service.ConversationService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 会话管理接口。
 *
 * <p>问答接口本身不要求先调这里：{@code POST /api/chat} 不带 sessionId 时会新建会话
 * 并把 id 放进响应，前端存下来下一轮带上即可。这些接口是给「会话列表 / 历史查看 /
 * 手动压缩 / 删除」用的。
 */
@RestController
@RequestMapping("/api/sessions")
public class SessionController {

    private final ConversationService conversationService;

    public SessionController(ConversationService conversationService) {
        this.conversationService = conversationService;
    }

    /** 新建会话；请求体可省略（{@code POST} 空 body 也能用）。 */
    @PostMapping
    public SessionView create(@Valid @RequestBody(required = false) CreateSessionRequest request) {
        return conversationService.create(request);
    }

    /** 最近活跃的会话列表。 */
    @GetMapping
    public List<SessionView> list(@RequestParam(defaultValue = "20") int limit) {
        return conversationService.list(limit);
    }

    /** 会话详情：摘要 + 最近消息原文。 */
    @GetMapping("/{id}")
    public SessionDetailView detail(@PathVariable String id,
                                    @RequestParam(defaultValue = "100") int limit) {
        return conversationService.detail(id, limit);
    }

    /** 删除会话。 */
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable String id) {
        return conversationService.delete(id)
                ? ResponseEntity.noContent().build()
                : ResponseEntity.notFound().build();
    }

    /**
     * 手动压缩：把较早的历史折叠进摘要。
     *
     * <p>自动压缩按 token 预算触发，这个接口用于「提问前主动腾出空间」，
     * 也方便排查压缩效果（返回折叠条数和摘要 token）。
     */
    @PostMapping("/{id}/compact")
    public CompactResult compact(@PathVariable String id) {
        return conversationService.compact(id);
    }
}
