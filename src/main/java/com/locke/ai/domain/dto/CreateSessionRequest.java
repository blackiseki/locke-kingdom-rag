package com.locke.ai.domain.dto;

import jakarta.validation.constraints.Size;

/**
 * 新建会话请求。
 *
 * <p>{@code title} 可选：不传时后端会在第一轮问答后用问题补上，
 * 前端“新建会话”按钮只需要发一个空对象。
 *
 * @param id    可选的会话 id；不传则服务端生成 UUID。重复使用已有 id 不会覆盖旧会话
 * @param title 可选的标题
 */
public record CreateSessionRequest(
        @Size(max = 64, message = "id 最长 64 位")
        String id,

        @Size(max = 200, message = "title 最长 200 字")
        String title) {
}
